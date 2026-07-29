package com.privmsg.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import uniffi.privmsg_core.CallKeys
import uniffi.privmsg_core.Contact
import uniffi.privmsg_core.callCounterAcceptable
import uniffi.privmsg_core.callDeriveKeys
import uniffi.privmsg_core.callNewSecret
import uniffi.privmsg_core.callOpenPacket
import uniffi.privmsg_core.callSealPacket
import java.util.concurrent.atomic.AtomicLong

/** Estado de la llamada en curso. */
sealed interface CallState {
    data object Idle : CallState
    data class Outgoing(val contact: Contact, val name: String) : CallState
    data class Incoming(val contact: Contact, val name: String) : CallState
    data class Active(val contact: Contact, val name: String, val startedAt: Long) : CallState

    /**
     * La llamada no salió adelante. Se muestra el motivo en vez de volver sin
     * más a la lista de chats: una llamada que se corta sin explicación deja
     * al usuario sin saber si el fallo es suyo, del otro o de la red.
     */
    data class Failed(val name: String, val reason: String, val hint: String?) : CallState
}

/**
 * Orquesta una llamada: señalización cifrada, claves de sesión, canal UDP y
 * motor de voz.
 *
 * La invitación (secreto de sesión + candidatos de red) viaja **dentro del
 * sobre cifrado normal**, o sea con el KEM híbrido post-cuántico completo. De
 * ese secreto salen las claves con las que se cifra cada paquete de voz.
 */
class CallManager(
    private val context: Context,
    /** Envía una señal de llamada por el canal cifrado. Devuelve si salió. */
    private val sendSignal: (Contact, Kind, ByteArray) -> Boolean,
) {
    val state = mutableStateOf<CallState>(CallState.Idle)
    val muted = mutableStateOf(false)
    val speakerOn = mutableStateOf(false)
    val peerConnected = mutableStateOf(false)

    private var transport: CallTransport? = null
    private var voice: VoiceEngine? = null
    private var speaker: SpeakerController? = null

    private var keys: CallKeys? = null
    private var sessionSecret: ByteArray? = null
    private var isCaller = false
    private val sendCounter = AtomicLong(0)
    @Volatile private var highestSeen = 0UL
    @Volatile private var pendingContact: Contact? = null

    val inCall: Boolean get() = state.value !is CallState.Idle

    // ---------- iniciar ----------

    /** Llama a un contacto: genera secreto, reúne candidatos y envía la oferta. */
    fun startCall(contact: Contact, displayName: String): Boolean {
        if (inCall) return false
        if (!VoiceEngine.opusEncoderAvailable()) {
            Log.e(TAG, "sin codificador Opus en este dispositivo")
            return false
        }

        isCaller = true
        pendingContact = contact
        val secret = callNewSecret()
        sessionSecret = secret
        keys = callDeriveKeys(secret, true)

        val udp = CallTransport { packet -> onVoicePacket(packet) }
        if (!udp.open()) return false
        transport = udp

        val candidates = udp.gatherCandidates()
        val payload = buildSignal(secret, candidates)
        if (!sendSignal(contact, Kind.CALL_OFFER, payload)) {
            cleanup()
            return false
        }

        state.value = CallState.Outgoing(contact, displayName)

        // Sin esto, una llamada sin respuesta se quedaba sonando para siempre.
        Thread({
            Thread.sleep(RING_TIMEOUT_MS)
            if (state.value is CallState.Outgoing) {
                Log.d(TAG, "nadie contestó")
                runCatching { sendSignal(contact, Kind.CALL_END, ByteArray(0)) }
                failCall(displayName, "No contestó", null)
            }
        }, "call-ring-timeout").start()

        return true
    }

    /** Corta la llamada y deja el motivo a la vista. */
    private fun failCall(name: String, reason: String, hint: String?) {
        cleanup()
        state.value = CallState.Failed(name, reason, hint)
    }

    /** Cierra la pantalla de fallo y vuelve a la normalidad. */
    fun dismissFailure() {
        if (state.value is CallState.Failed) state.value = CallState.Idle
    }

    /** Llega una invitación: guardamos el secreto y hacemos sonar el timbre. */
    fun onIncomingOffer(contact: Contact, displayName: String, payload: ByteArray) {
        if (inCall) {
            // Ocupado: rechaza sin molestar al usuario.
            sendSignal(contact, Kind.CALL_END, ByteArray(0))
            return
        }
        val (secret, remoteCandidates) = parseSignal(payload) ?: return
        if (secret.size != 32) return

        isCaller = false
        pendingContact = contact
        sessionSecret = secret
        keys = callDeriveKeys(secret, false)
        remoteCandidatesPending = remoteCandidates

        state.value = CallState.Incoming(contact, displayName)
    }

    private var remoteCandidatesPending: List<Candidate> = emptyList()

    /** El usuario acepta: abre el canal, responde con sus candidatos y conecta. */
    fun acceptCall() {
        val current = state.value as? CallState.Incoming ?: return
        val secret = sessionSecret ?: return

        val udp = CallTransport { packet -> onVoicePacket(packet) }
        if (!udp.open()) {
            endCall()
            return
        }
        transport = udp

        val candidates = udp.gatherCandidates()
        if (!sendSignal(current.contact, Kind.CALL_ANSWER, buildSignal(secret, candidates))) {
            endCall()
            return
        }

        udp.startPunching(remoteCandidatesPending)
        beginMedia(current.contact, current.name)
    }

    /** Nos responden: perforamos hacia sus candidatos y arrancamos el audio. */
    fun onAnswer(payload: ByteArray) {
        val current = state.value as? CallState.Outgoing ?: return
        val (_, remoteCandidates) = parseSignal(payload) ?: return
        transport?.startPunching(remoteCandidates)
        beginMedia(current.contact, current.name)
    }

    /** El otro extremo colgó o rechazó. */
    fun onRemoteEnd() {
        if (!inCall) return
        cleanup()
        state.value = CallState.Idle
    }

    /** Colgamos nosotros. */
    fun endCall() {
        val contact = when (val s = state.value) {
            is CallState.Outgoing -> s.contact
            is CallState.Incoming -> s.contact
            is CallState.Active -> s.contact
            // Ya se cortó: no hay a quién avisar de nuevo.
            is CallState.Failed, CallState.Idle -> null
        } ?: pendingContact
        contact?.let { sendSignal(it, Kind.CALL_END, ByteArray(0)) }
        cleanup()
        state.value = CallState.Idle
    }

    fun toggleMute() {
        muted.value = !muted.value
        voice?.muted = muted.value
    }

    fun toggleSpeaker() {
        speakerOn.value = !speakerOn.value
        speaker?.setSpeaker(speakerOn.value)
    }

    // ---------- interno ----------

    private fun beginMedia(contact: Contact, name: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speaker = SpeakerController(audioManager).apply {
            enterCallMode()
            setSpeaker(false)
        }

        val engine = VoiceEngine { frame -> onEncodedFrame(frame) }
        if (!engine.start()) {
            endCall()
            return
        }
        voice = engine
        state.value = CallState.Active(contact, name, System.currentTimeMillis())

        // Vigila que la perforación llegue a conectar.
        Thread({
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (transport?.connected == true) {
                    peerConnected.value = true
                    return@Thread
                }
                Thread.sleep(300)
            }
            if (transport?.connected != true) {
                Log.w(TAG, "sin ruta directa para la voz")
                failCall(
                    name,
                    "No se pudo establecer la conexión de voz",
                    "La voz viaja directa entre los dos teléfonos, sin pasar por " +
                        "ningún servidor. Algunas redes lo impiden: es habitual con " +
                        "datos móviles y con emuladores. En la misma WiFi suele funcionar.",
                )
            }
        }, "call-watchdog").start()
    }

    /** Cifra una trama de voz y la manda por UDP. */
    private fun onEncodedFrame(frame: ByteArray) {
        val key = keys?.sendKey ?: return
        val counter = sendCounter.getAndIncrement().toULong()
        runCatching {
            transport?.send(callSealPacket(key, counter, frame))
        }
    }

    /** Descifra un paquete recibido, comprueba replay y lo reproduce. */
    private fun onVoicePacket(packet: ByteArray) {
        val key = keys?.recvKey ?: return
        runCatching {
            val opened = callOpenPacket(key, packet)
            if (!callCounterAcceptable(opened.counter, highestSeen)) return
            if (opened.counter > highestSeen) highestSeen = opened.counter
            voice?.playEncodedFrame(opened.audio)
        }
    }

    private fun cleanup() {
        voice?.stop()
        transport?.close()
        speaker?.exitCallMode()
        voice = null
        transport = null
        speaker = null
        keys = null
        sessionSecret = null
        pendingContact = null
        remoteCandidatesPending = emptyList()
        sendCounter.set(0)
        highestSeen = 0UL
        muted.value = false
        speakerOn.value = false
        peerConnected.value = false
    }

    /** Payload de señalización: `secreto(32 B) ‖ "host:puerto,host:puerto"`. */
    private fun buildSignal(secret: ByteArray, candidates: List<Candidate>): ByteArray =
        secret + candidates.joinToString(",").toByteArray(Charsets.UTF_8)

    private fun parseSignal(payload: ByteArray): Pair<ByteArray, List<Candidate>>? {
        if (payload.size < 32) return null
        val secret = payload.copyOfRange(0, 32)
        val candidates = String(payload, 32, payload.size - 32, Charsets.UTF_8)
            .split(",")
            .mapNotNull { Candidate.parse(it.trim()) }
        return secret to candidates
    }

    private companion object {
        const val TAG = "CallManager"
        const val CONNECT_TIMEOUT_MS = 25_000L

        /** Cuánto suena antes de darla por no contestada. */
        const val RING_TIMEOUT_MS = 45_000L
    }
}
