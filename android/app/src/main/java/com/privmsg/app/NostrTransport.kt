package com.privmsg.app

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Transporte mundial por internet usando relays públicos Nostr como buzones
 * tontos. Sin servidor propio, sin cuentas, gratis.
 *
 * Los relays solo ven: blobs cifrados, etiquetas hex que rotan a diario y
 * remitentes aleatorios de un solo uso. No pueden leer nada ni vincular nada.
 */
class NostrTransport(
    private val context: android.content.Context,
    /** Devuelve las etiquetas de encuentro a vigilar (hoy + ayer, por contacto). */
    private val tagsProvider: () -> List<String>,
    /**
     * Desde cuándo pedir mensajes, en segundos unix. Acotarlo al último
     * procesado evita que cada reconexión reenvíe dos días enteros.
     */
    private val sinceProvider: () -> Long,
    private val onSealedReceived: (ByteArray) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val connected = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Relays con una conexión en curso, para no abrir dos a la vez. */
    private val connecting = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val seenEventIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val reconnectExec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var stopped = false
    @Volatile private var subCounter = 0

    /** Marca del evento más reciente visto, para acotar futuras suscripciones. */
    @Volatile var lastEventSeconds: Long = 0L
        private set

    private var networkManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** Reintentos por relay, para espaciar los reintentos progresivamente. */
    private val retries = ConcurrentHashMap<String, Int>()

    fun start() {
        RELAYS.forEach { connect(it) }
        watchNetwork()
        startHealthCheck()
    }

    fun stop() {
        stopped = true
        runCatching { networkCallback?.let { networkManager?.unregisterNetworkCallback(it) } }
        sockets.values.forEach { runCatching { it.close(1000, "bye") } }
        sockets.clear()
        connected.clear()
        reconnectExec.shutdownNow()
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    /**
     * Al cambiar de red (WiFi ↔ datos, o recuperar cobertura) los sockets
     * quedan muertos sin avisar: el sistema no siempre corta la conexión TCP,
     * así que la app cree estar conectada y no recibe nada. Reconectar en
     * cuanto aparece una red nueva es lo que evita ese "silencio".
     */
    private fun watchNetwork() {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        networkManager = manager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                log("red disponible: reconectando")
                reconnectAll()
            }

            override fun onLost(network: android.net.Network) {
                connected.clear()
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    /**
     * Red de seguridad: si por lo que sea no queda ningún relay conectado, se
     * reintenta. Sin esto, un fallo simultáneo dejaría la app muda hasta que
     * el usuario la reiniciara.
     */
    private fun startHealthCheck() {
        runCatching {
            reconnectExec.scheduleWithFixedDelay(
                {
                    if (!stopped && connected.isEmpty()) {
                        log("sin relays conectados: reintentando")
                        reconnectAll()
                    }
                },
                HEALTH_CHECK_S, HEALTH_CHECK_S, TimeUnit.SECONDS,
            )
        }
    }

    /** Cierra lo que haya y vuelve a conectar con todos los relays. */
    fun reconnectAll() {
        if (stopped) return
        sockets.values.forEach { runCatching { it.cancel() } }
        sockets.clear()
        connected.clear()
        connecting.clear()
        RELAYS.forEach { connect(it) }
    }

    /** ¿Hay al menos un relay conectado? */
    fun isOnline(): Boolean = connected.isNotEmpty()

    /** Reinstala las suscripciones (llamar al añadir un contacto o cambiar de día). */
    fun resubscribe() {
        sockets.forEach { (url, ws) -> if (url in connected) subscribe(ws) }
    }

    /**
     * Publica un evento (JSON ya firmado por el core) en todos los relays
     * conectados. Devuelve true si salió por al menos uno.
     */
    fun send(eventJson: String): Boolean {
        var count = 0
        sockets.forEach { (url, ws) ->
            if (url in connected && ws.send("[\"EVENT\",$eventJson]")) count++
        }
        log("evento publicado en $count relays")
        return count > 0
    }

    // ---------- interno ----------

    private fun connect(url: String) {
        if (stopped) return
        // Sin este candado se abrían varias conexiones al mismo relay: `start()`
        // conecta, y acto seguido el aviso de "red disponible" dispara otra
        // ronda mientras las primeras aún están abriéndose y no figuran todavía
        // en el mapa de sockets. Cada duplicado es otra suscripción y otra copia
        // de cada mensaje.
        if (!connecting.add(url)) return

        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                connecting.remove(url)
                sockets[url] = ws
                connected.add(url)
                retries.remove(url)
                log("conectado a $url")
                subscribe(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                connecting.remove(url)
                connected.remove(url)
                sockets.remove(url)
                scheduleReconnect(url)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connecting.remove(url)
                connected.remove(url)
                sockets.remove(url)
                scheduleReconnect(url)
            }
        })
    }

    /**
     * Reintento con espera creciente, pero acotada: empieza rápido para que un
     * corte breve no se note, y se espacia si el relay sigue caído, para no
     * gastar batería martilleando un servidor que no responde.
     */
    private fun scheduleReconnect(url: String) {
        if (stopped) return
        val attempt = retries.merge(url, 1, Int::plus) ?: 1
        val delay = minOf(RECONNECT_BASE_S * (1L shl minOf(attempt - 1, 5)), RECONNECT_MAX_S)
        runCatching {
            reconnectExec.schedule({ connect(url) }, delay, TimeUnit.SECONDS)
        }
    }

    private fun subscribe(ws: WebSocket) {
        val tags = tagsProvider()
        if (tags.isEmpty()) return
        val subId = "privmsg-${subCounter++}"
        // Se pide desde bastante antes de lo ya procesado, a propósito.
        //
        // La marca guardada es la del evento más nuevo visto, pero los relays
        // no entregan en orden: uno puede haber traído ya un mensaje reciente
        // mientras otro aún no ha mandado uno anterior. Recortar demasiado la
        // ventana haría perder ese mensaje para siempre.
        //
        // Repetir es barato (se descarta por identificador antes de tocar el
        // ratchet); perder un mensaje, no. Ante la duda, se pide de más.
        val now = System.currentTimeMillis() / 1000
        val floor = now - LOOKBACK_S
        val since = maxOf(floor, sinceProvider() - OVERLAP_S)

        val filter = JSONObject()
            .put("kinds", JSONArray().put(EVENT_KIND))
            .put("#t", JSONArray(tags))
            .put("since", since)
        val req = JSONArray().put("REQ").put(subId).put(filter)
        ws.send(req.toString())
    }

    private fun handleMessage(text: String) {
        runCatching {
            val arr = JSONArray(text)
            if (arr.getString(0) != "EVENT") return
            val event = arr.getJSONObject(2)
            val id = event.getString("id")

            synchronized(seenEventIds) {
                if (!seenEventIds.add(id)) return
                // LRU sencillo: recortar los más antiguos.
                while (seenEventIds.size > MAX_SEEN) {
                    val it = seenEventIds.iterator()
                    it.next(); it.remove()
                }
            }

            lastEventSeconds = maxOf(lastEventSeconds, event.optLong("created_at", 0L))
            val sealed = Base64.decode(event.getString("content"), Base64.DEFAULT)
            onSealedReceived(sealed)
        }.onFailure { log("mensaje de relay ignorado: ${it.message}") }
    }

    private fun log(msg: String) {
        Log.d("NostrTransport", msg)
    }

    companion object {
        const val EVENT_KIND = 4004

        /** Relays públicos, gratuitos y veteranos. */
        val RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://offchain.pub",
        )

        private const val RECONNECT_BASE_S = 2L
        private const val RECONNECT_MAX_S = 60L
        private const val HEALTH_CHECK_S = 30L
        private const val LOOKBACK_S = 2 * 24 * 3600L // 48 h de mensajes pendientes
        /** Solape generoso: mejor recibir de más que perder un mensaje. */
        private const val OVERLAP_S = 6 * 3600L
        private const val MAX_SEEN = 1000
    }
}
