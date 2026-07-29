package com.privmsg.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Transporte P2P por red local (hito 2, "cartero tonto"):
 * cada teléfono anuncia un servicio mDNS `_privmsg._tcp` en la WiFi y escucha
 * en un puerto TCP. Los peers se descubren solos y se envían los mensajes
 * *ya sellados* directamente, sin ningún servidor.
 *
 * El cartero solo ve blobs cifrados; ni siquiera él puede leerlos.
 */
class LanTransport(
    context: Context,
    myFingerprint: String,
    private val onSealedReceived: (ByteArray) -> Unit,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val myFp = myFingerprint.replace(" ", "")

    /** fp (sin espacios) → host:puerto del peer visible en la red. */
    private val peers = ConcurrentHashMap<String, Pair<String, Int>>()

    /** nombre de servicio NSD → fp, para limpiar cuando un peer desaparece. */
    private val serviceNameToFp = ConcurrentHashMap<String, String>()

    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        // 1. Servidor TCP en puerto efímero.
        val server = ServerSocket(0)
        serverSocket = server
        scope.launch { acceptLoop(server) }

        // 2. Lock multicast (necesario para recibir mDNS en muchos dispositivos).
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("privmsg-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }

        // 3. Anunciar nuestro servicio.
        val info = NsdServiceInfo().apply {
            serviceName = "privmsg-${myFp.take(8)}"
            serviceType = SERVICE_TYPE
            port = server.localPort
            setAttribute("fp", myFp)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = log("anunciado como ${i.serviceName}")
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) = log("fallo anuncio: $e")
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        // 4. Descubrir a los demás.
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.startsWith(SERVICE_TYPE.trimEnd('.'))) return
                @Suppress("DEPRECATION")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val fp = resolved.attributes["fp"]?.toString(Charsets.UTF_8) ?: return
                        if (fp == myFp) return
                        val host = resolved.host?.hostAddress ?: return
                        peers[fp] = host to resolved.port
                        serviceNameToFp[resolved.serviceName] = fp
                        log("peer visible: ${fp.take(8)} en $host:${resolved.port}")
                    }

                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) = log("resolve falló: $e")
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                serviceNameToFp.remove(service.serviceName)?.let { peers.remove(it) }
            }

            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) = log("fallo discovery: $e")
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        runCatching { registrationListener?.let { nsd.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsd.stopServiceDiscovery(it) } }
        runCatching { serverSocket?.close() }
        runCatching { multicastLock?.release() }
        scope.cancel()
        peers.clear()
    }

    /** ¿Está este contacto visible ahora mismo en la red local? */
    fun peerVisible(contactFp: String): Boolean =
        peers.containsKey(contactFp.replace(" ", ""))

    /**
     * Envía un blob sellado al contacto. Llamar desde un hilo IO.
     * Devuelve false si el peer no está visible o la conexión falla.
     */
    fun send(contactFp: String, sealed: ByteArray): Boolean {
        val peer = peers[contactFp.replace(" ", "")] ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.first, peer.second), CONNECT_TIMEOUT_MS)
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(sealed.size)
                    write(sealed)
                    flush()
                }
            }
            true
        }.getOrElse {
            log("envío falló: ${it.message}")
            false
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch {
                runCatching {
                    client.use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val len = input.readInt()
                        require(len in 1..MAX_MESSAGE_BYTES) { "tamaño inválido: $len" }
                        val sealed = ByteArray(len)
                        input.readFully(sealed)
                        onSealedReceived(sealed)
                    }
                }.onFailure { log("recepción falló: ${it.message}") }
            }
        }
    }

    private fun log(msg: String) {
        Log.d("LanTransport", msg)
    }

    private companion object {
        const val SERVICE_TYPE = "_privmsg._tcp."
        const val CONNECT_TIMEOUT_MS = 4000
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    }
}
