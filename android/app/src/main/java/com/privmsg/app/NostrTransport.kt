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
    /** Devuelve las etiquetas de encuentro a vigilar (hoy + ayer, por contacto). */
    private val tagsProvider: () -> List<String>,
    private val onSealedReceived: (ByteArray) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val connected = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val seenEventIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val reconnectExec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var stopped = false
    @Volatile private var subCounter = 0

    fun start() {
        RELAYS.forEach { connect(it) }
    }

    fun stop() {
        stopped = true
        sockets.values.forEach { runCatching { it.close(1000, "bye") } }
        sockets.clear()
        connected.clear()
        reconnectExec.shutdownNow()
        runCatching { client.dispatcher.executorService.shutdown() }
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
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                sockets[url] = ws
                connected.add(url)
                log("conectado a $url")
                subscribe(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                connected.remove(url)
                sockets.remove(url)
                scheduleReconnect(url)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected.remove(url)
                sockets.remove(url)
                scheduleReconnect(url)
            }
        })
    }

    private fun scheduleReconnect(url: String) {
        if (stopped) return
        runCatching {
            reconnectExec.schedule({ connect(url) }, RECONNECT_DELAY_S, TimeUnit.SECONDS)
        }
    }

    private fun subscribe(ws: WebSocket) {
        val tags = tagsProvider()
        if (tags.isEmpty()) return
        val subId = "privmsg-${subCounter++}"
        val filter = JSONObject()
            .put("kinds", JSONArray().put(EVENT_KIND))
            .put("#t", JSONArray(tags))
            .put("since", System.currentTimeMillis() / 1000 - LOOKBACK_S)
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

        private const val RECONNECT_DELAY_S = 10L
        private const val LOOKBACK_S = 2 * 24 * 3600L // 48 h de mensajes pendientes
        private const val MAX_SEEN = 1000
    }
}
