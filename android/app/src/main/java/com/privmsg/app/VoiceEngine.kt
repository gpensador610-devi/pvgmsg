package com.privmsg.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura, codifica, decodifica y reproduce la voz de una llamada.
 *
 * Opus a 24 kbps mono 16 kHz en tramas de 20 ms: ~60 bytes por paquete, unos
 * 3 KB/s por sentido. Con cancelación de eco y supresor de ruido del hardware
 * cuando el dispositivo los ofrece.
 */
class VoiceEngine(
    private val onEncodedFrame: (ByteArray) -> Unit,
) {
    private val running = AtomicBoolean(false)

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private var captureThread: Thread? = null
    private var encodeThread: Thread? = null

    @Volatile var muted: Boolean = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean = runCatching {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufferSize = maxOf(minBuffer, FRAME_BYTES * 4)

        val rec = AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize,
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord no inicializó" }

        // Cancelación de eco y ruido por hardware si existen.
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        }

        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING),
                    FRAME_BYTES * 4,
                ),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val enc = MediaCodec.createEncoderByType(MIME).apply {
            configure(encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        val dec = MediaCodec.createDecoderByType(MIME).apply {
            configure(decoderFormat(), null, null, 0)
            start()
        }

        recorder = rec
        track = out
        encoder = enc
        decoder = dec

        running.set(true)
        rec.startRecording()
        out.play()

        captureThread = Thread({ captureLoop(rec, enc) }, "voice-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        encodeThread = Thread({ drainEncoderLoop(enc) }, "voice-encode").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        true
    }.getOrElse {
        Log.e(TAG, "no se pudo iniciar el audio: ${it.message}")
        stop()
        false
    }

    fun stop() {
        running.set(false)
        runCatching { captureThread?.join(300) }
        runCatching { encodeThread?.join(300) }
        captureThread = null
        encodeThread = null

        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        recorder = null; track = null; encoder = null; decoder = null; aec = null; ns = null
    }

    /** Entrega un paquete recibido de la red para decodificarlo y reproducirlo. */
    fun playEncodedFrame(frame: ByteArray) {
        val dec = decoder ?: return
        if (!running.get()) return
        runCatching {
            val inIndex = dec.dequeueInputBuffer(5_000)
            if (inIndex >= 0) {
                dec.getInputBuffer(inIndex)?.apply {
                    clear()
                    put(frame)
                }
                dec.queueInputBuffer(inIndex, 0, frame.size, System.nanoTime() / 1000, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = dec.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                dec.getOutputBuffer(outIndex)?.let { buf ->
                    val pcm = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(pcm, 0, info.size)
                    track?.write(pcm, 0, pcm.size)
                }
                dec.releaseOutputBuffer(outIndex, false)
                outIndex = dec.dequeueOutputBuffer(info, 0)
            }
        }
    }

    // ---- bucles internos ----

    private fun captureLoop(rec: AudioRecord, enc: MediaCodec) {
        val pcm = ByteArray(FRAME_BYTES)
        val silence = ByteArray(FRAME_BYTES)
        while (running.get()) {
            val read = runCatching { rec.read(pcm, 0, FRAME_BYTES) }.getOrDefault(-1)
            if (read <= 0) continue
            val payload = if (muted) silence else pcm

            runCatching {
                val index = enc.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    enc.getInputBuffer(index)?.apply {
                        clear()
                        put(payload, 0, read)
                    }
                    enc.queueInputBuffer(index, 0, read, System.nanoTime() / 1000, 0)
                }
            }
        }
    }

    private fun drainEncoderLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = runCatching { enc.dequeueOutputBuffer(info, 10_000) }.getOrDefault(-1)
            if (index < 0) continue
            runCatching {
                enc.getOutputBuffer(index)?.let { buf ->
                    // Descarta la cabecera de configuración del códec.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val frame = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(frame, 0, info.size)
                        onEncodedFrame(frame)
                    }
                }
                enc.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun encoderFormat() = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 2)
        setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
    }

    private fun decoderFormat() = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        // Opus exige estos tres campos en el decodificador.
        setByteBuffer("csd-0", ByteBuffer.wrap(opusIdHeader()))
        setByteBuffer("csd-1", ByteBuffer.wrap(ByteArray(8)))
        setByteBuffer("csd-2", ByteBuffer.wrap(ByteArray(8)))
    }

    /** Cabecera OpusHead mínima (19 bytes) que espera el decodificador. */
    private fun opusIdHeader(): ByteArray {
        val header = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[8] = 1 // versión
        header[9] = 1 // canales
        // preskip (LE)
        header[10] = 0x38; header[11] = 0x01
        // sample rate original (LE)
        header[12] = (SAMPLE_RATE and 0xFF).toByte()
        header[13] = ((SAMPLE_RATE shr 8) and 0xFF).toByte()
        header[14] = ((SAMPLE_RATE shr 16) and 0xFF).toByte()
        header[15] = ((SAMPLE_RATE shr 24) and 0xFF).toByte()
        return header
    }

    companion object {
        private const val TAG = "VoiceEngine"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        const val SAMPLE_RATE = 16_000
        const val BITRATE = 24_000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 20 ms a 16 kHz, 16 bits mono = 320 muestras = 640 bytes. */
        const val FRAME_BYTES = 640

        /** ¿Puede este dispositivo codificar Opus? (Android 10+). */
        fun opusEncoderAvailable(): Boolean = runCatching {
            MediaCodec.createEncoderByType(MIME).also { it.release() }
            true
        }.getOrDefault(false)
    }
}

/** Encamina el audio al altavoz o al auricular. */
class SpeakerController(private val audioManager: AudioManager) {

    private var previousMode = AudioManager.MODE_NORMAL

    fun enterCallMode() {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    fun setSpeaker(on: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
    }

    fun exitCallMode() {
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousMode
    }

    companion object {
        fun encoderInfoOrNull(): MediaCodecInfo? = null
    }
}
