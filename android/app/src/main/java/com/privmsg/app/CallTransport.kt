package com.privmsg.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Una dirección donde se puede intentar alcanzar a un extremo. */
data class Candidate(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        fun parse(s: String): Candidate? {
            val idx = s.lastIndexOf(':')
            if (idx <= 0) return null
            val port = s.substring(idx + 1).toIntOrNull() ?: return null
            return Candidate(s.substring(0, idx), port)
        }
    }
}

/**
 * Canal UDP de la voz. Los relays no sirven aquí: guardan y reenvían, con
 * segundos de latencia. La voz necesita ir **directa**, teléfono a teléfono.
 *
 * Estrategia:
 * 1. Reunir candidatos: la IP local (misma WiFi) y la IP pública que nos
 *    devuelve un servidor STUN gratuito (para atravesar el router).
 * 2. Intercambiarlos por el canal cifrado (van dentro del sobre sellado).
 * 3. Ambos extremos disparan paquetes a todos los candidatos del otro a la vez:
 *    eso abre los agujeros en ambos NAT (*hole punching*). El primero que
 *    responde gana y por ahí va la llamada.
 *
 * Con NAT simétrico (frecuente en datos móviles) la perforación falla; ahí
 * haría falta un relay TURN, que sería un servidor.
 */
class CallTransport(
    private val onPacket: (ByteArray) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null

    /** Dirección confirmada del otro extremo (la primera que contesta). */
    @Volatile private var peer: InetSocketAddress? = null

    val connected: Boolean get() = peer != null
    val localPort: Int get() = socket?.localPort ?: 0

    fun open(): Boolean = runCatching {
        val sock = DatagramSocket()
        sock.soTimeout = SOCKET_TIMEOUT_MS
        socket = sock
        running.set(true)
        receiveThread = Thread({ receiveLoop(sock) }, "call-udp").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        true
    }.getOrElse {
        Log.e(TAG, "no se pudo abrir el socket: ${it.message}")
        false
    }

    fun close() {
        running.set(false)
        runCatching { socket?.close() }
        runCatching { receiveThread?.join(200) }
        socket = null
        receiveThread = null
        peer = null
    }

    /** Candidatos propios: IP local + IP pública vía STUN. */
    fun gatherCandidates(): List<Candidate> {
        val port = localPort
        if (port == 0) return emptyList()
        val candidates = mutableListOf<Candidate>()
        localIpv4()?.let { candidates.add(Candidate(it, port)) }
        discoverPublicAddress()?.let { if (it !in candidates) candidates.add(it) }
        return candidates
    }

    /**
     * Dispara paquetes de sondeo a todos los candidatos del otro extremo hasta
     * que uno responde. Ambos lados hacen esto a la vez: así se perforan los NAT.
     */
    fun startPunching(remoteCandidates: List<Candidate>) {
        if (remoteCandidates.isEmpty()) return
        Thread({
            val deadline = System.currentTimeMillis() + PUNCH_TIMEOUT_MS
            while (running.get() && peer == null && System.currentTimeMillis() < deadline) {
                remoteCandidates.forEach { candidate ->
                    runCatching {
                        val address = InetSocketAddress(
                            InetAddress.getByName(candidate.host), candidate.port,
                        )
                        socket?.send(DatagramPacket(PUNCH_MAGIC, PUNCH_MAGIC.size, address))
                    }
                }
                Thread.sleep(PUNCH_INTERVAL_MS)
            }
        }, "call-punch").start()
    }

    /** Envía un paquete de voz ya cifrado. */
    fun send(payload: ByteArray) {
        val target = peer ?: return
        runCatching {
            socket?.send(DatagramPacket(payload, payload.size, target))
        }
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { sock.receive(packet); true }.getOrDefault(false)
            if (!received) continue

            val from = InetSocketAddress(packet.address, packet.port)
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)

            // Sondeo de perforación: fija el peer y responde para confirmar.
            if (data.size == PUNCH_MAGIC.size && data.contentEquals(PUNCH_MAGIC)) {
                if (peer == null) {
                    peer = from
                    Log.d(TAG, "peer confirmado en $from")
                }
                runCatching { sock.send(DatagramPacket(PUNCH_MAGIC, PUNCH_MAGIC.size, from)) }
                continue
            }

            if (peer == null) peer = from
            onPacket(data)
        }
    }

    /** IP privada IPv4 de la interfaz activa (para llamadas dentro de la WiFi). */
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * Pregunta a un servidor STUN público cuál es nuestra IP:puerto vista desde
     * fuera. STUN solo revela direcciones — no ve ni toca el audio.
     */
    private fun discoverPublicAddress(): Candidate? {
        val sock = socket ?: return null
        for (server in STUN_SERVERS) {
            val result = runCatching {
                val host = server.substringBefore(':')
                val port = server.substringAfter(':').toInt()
                val request = stunBindingRequest()
                sock.send(
                    DatagramPacket(
                        request, request.size,
                        InetSocketAddress(InetAddress.getByName(host), port),
                    ),
                )
                val buffer = ByteArray(512)
                val response = DatagramPacket(buffer, buffer.size)
                sock.receive(response)
                parseStunMappedAddress(
                    buffer, response.length, request.copyOfRange(8, 20),
                )
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun stunBindingRequest(): ByteArray {
        val request = ByteArray(20)
        request[0] = 0x00; request[1] = 0x01 // Binding Request
        request[2] = 0x00; request[3] = 0x00 // longitud 0
        // Magic cookie 0x2112A442
        request[4] = 0x21; request[5] = 0x12; request[6] = 0xA4.toByte(); request[7] = 0x42
        java.security.SecureRandom().nextBytes(request.copyOfRange(8, 20).also {
            it.copyInto(request, 8)
        })
        return request
    }

    /** Extrae XOR-MAPPED-ADDRESS (0x0020) de la respuesta STUN. */
    private fun parseStunMappedAddress(
        buffer: ByteArray,
        length: Int,
        transactionId: ByteArray,
    ): Candidate? {
        if (length < 20) return null
        // Debe ser Binding Success Response (0x0101).
        if (buffer[0] != 0x01.toByte() || buffer[1] != 0x01.toByte()) return null
        if (!buffer.copyOfRange(8, 20).contentEquals(transactionId)) return null

        var pos = 20
        while (pos + 4 <= length) {
            val type = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            val attrLen = ((buffer[pos + 2].toInt() and 0xFF) shl 8) or (buffer[pos + 3].toInt() and 0xFF)
            val value = pos + 4
            if (value + attrLen > length) return null

            if (type == 0x0020 && attrLen >= 8 && buffer[value + 1] == 0x01.toByte()) {
                // Puerto y dirección van XOR con el magic cookie.
                val port = (((buffer[value + 2].toInt() and 0xFF) shl 8) or
                    (buffer[value + 3].toInt() and 0xFF)) xor 0x2112
                val ip = (0 until 4).joinToString(".") { i ->
                    ((buffer[value + 4 + i].toInt() and 0xFF) xor
                        (MAGIC_COOKIE[i].toInt() and 0xFF)).toString()
                }
                return Candidate(ip, port)
            }
            pos = value + attrLen + ((4 - attrLen % 4) % 4) // padding a 4 bytes
        }
        return null
    }

    private companion object {
        const val TAG = "CallTransport"
        const val MAX_PACKET = 1500
        const val SOCKET_TIMEOUT_MS = 1000
        const val PUNCH_INTERVAL_MS = 250L
        const val PUNCH_TIMEOUT_MS = 20_000L

        val PUNCH_MAGIC = "PRIVMSG-PUNCH-v1".toByteArray(Charsets.US_ASCII)
        val MAGIC_COOKIE = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)

        /** Servidores STUN públicos y gratuitos (solo revelan la IP pública). */
        val STUN_SERVERS = listOf(
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun.cloudflare.com:3478",
        )
    }
}
