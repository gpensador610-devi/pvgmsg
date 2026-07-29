package com.privmsg.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Grabación de notas de voz en AAC de baja tasa (32 kbps mono):
 * ~4 KB/segundo, así un audio de 30 s pesa ~120 KB y viaja bien troceado.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    var startedAt: Long = 0L
        private set

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean = runCatching {
        val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioChannels(1)
        rec.setAudioSamplingRate(22050)
        rec.setAudioEncodingBitRate(32000)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()

        recorder = rec
        outputFile = file
        startedAt = System.currentTimeMillis()
        true
    }.getOrElse {
        cleanup()
        false
    }

    /** Detiene y devuelve (bytes, duraciónMs), o null si falló o fue muy corto. */
    fun stop(): Pair<ByteArray, Long>? {
        val rec = recorder ?: return null
        val file = outputFile
        val elapsed = System.currentTimeMillis() - startedAt

        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        recorder = null
        outputFile = null

        if (!ok || file == null || !file.exists() || elapsed < MIN_DURATION_MS) {
            file?.delete()
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return bytes?.let { it to elapsed }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        cleanup()
    }

    private fun cleanup() {
        runCatching { recorder?.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    companion object {
        const val MIN_DURATION_MS = 700L
        const val MAX_DURATION_MS = 120_000L

        /** Duración real de un audio ya guardado (para mostrarla en la burbuja). */
        fun durationOf(file: File): Long = runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(file.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            }
        }.getOrDefault(0L)
    }
}

/** Reproductor único: solo un audio suena a la vez. */
class AudioPlayer {

    private var player: MediaPlayer? = null
    private var playingId: String? = null

    val currentId: String? get() = playingId

    fun toggle(mediaId: String, file: File, onFinished: () -> Unit) {
        if (playingId == mediaId) {
            stop()
            onFinished()
            return
        }
        stop()
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onFinished()
                }
                prepare()
                start()
                player = this
                playingId = mediaId
            }
        }.onFailure { onFinished() }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }
}

/** Formatea milisegundos como m:ss. */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
