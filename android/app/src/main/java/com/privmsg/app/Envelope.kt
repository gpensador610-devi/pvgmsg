package com.privmsg.app

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tipo de contenido que viaja dentro del sobre cifrado. */
enum class Kind {
    TEXT, IMAGE, AUDIO, AVATAR,

    /** Invitación a llamada: secreto de sesión + candidatos de red. */
    CALL_OFFER,

    /** Aceptación: candidatos de red del que responde. */
    CALL_ANSWER,

    /** Colgar / rechazar. */
    CALL_END,

    /** Alta en un grupo: nombre + lista completa de miembros. */
    GROUP_INVITE,

    /** Cambio de nombre o de miembros del grupo. */
    GROUP_UPDATE,

    /** Un miembro abandona el grupo. */
    GROUP_LEAVE,

    /**
     * Arranque de sesión de ratchet: semilla + clave de ratchet inicial.
     * Va protegido por el sobre híbrido post-cuántico, que es de donde el
     * ratchet hereda su resistencia cuántica.
     */
    RATCHET_INIT,

    /**
     * "Te agregué": lleva la invitación propia para que el alta sea mutua.
     *
     * Viaja al **buzón personal** del destinatario, no a la etiqueta de
     * pareja: es el único mensaje que puede llegar a alguien que todavía no
     * tiene nuestra clave y por tanto no sabría dónde escuchar.
     */
    CONTACT_REQUEST;

    val isCallSignal: Boolean
        get() = this == CALL_OFFER || this == CALL_ANSWER || this == CALL_END

    val isGroupControl: Boolean
        get() = this == GROUP_INVITE || this == GROUP_UPDATE || this == GROUP_LEAVE

    /** ¿Se muestra como burbuja en la conversación? */
    val isVisibleMessage: Boolean
        get() = this == TEXT || this == IMAGE || this == AUDIO

    /**
     * ¿Su contenido pasa por el ratchet?
     *
     * Las señales de llamada y el control de grupos van solo con el sobre
     * híbrido: las primeras son urgentes y ya llevan su propio secreto de
     * sesión, y las segundas pueden venir de alguien con quien todavía no hay
     * sesión establecida.
     */
    val usesRatchet: Boolean
        get() = isVisibleMessage || this == AVATAR

    companion object {
        fun parse(s: String): Kind? = entries.firstOrNull { it.name == s }
    }
}

/**
 * Sobre v4 — todo esto va DENTRO del cifrado, invisible para los relays:
 *
 * ```
 * fpRemitente \n nickname \n kind \n msgId \n idx \n total \n groupId \n ttlSeg \n <payload>
 * ```
 *
 * - `groupId` vacío ⇒ chat directo. Si lleva valor, el mensaje pertenece a ese
 *   grupo (los mensajes de grupo se sellan por separado para cada miembro).
 * - `ttlSeg` = 0 ⇒ el mensaje no caduca. Si es > 0, **ambos extremos** lo
 *   borran pasado ese tiempo: la autodestrucción viaja con el mensaje, no
 *   depende de que el otro tenga la misma configuración.
 *
 * Los archivos grandes se trocean: cada fragmento es un mensaje sellado
 * independiente (claves efímeras propias), y el receptor los reensambla.
 * Un observador no puede ni saber que varios fragmentos son el mismo archivo.
 */
object Envelope {

    /** Tamaño máximo de payload por fragmento (los relays limitan el evento). */
    const val CHUNK_BYTES = 28_000

    private const val HEADER_LINES = 8

    data class Parsed(
        val senderFp: String,
        val nickname: String,
        val kind: Kind,
        val msgId: String,
        val index: Int,
        val total: Int,
        val groupId: String,
        val ttlSeconds: Long,
        val payload: ByteArray,
    )

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** Trocea un payload en los sobres listos para sellar. */
    fun build(
        senderFp: String,
        nickname: String,
        kind: Kind,
        payload: ByteArray,
        groupId: String = "",
        ttlSeconds: Long = 0L,
        msgId: String = newId(),
    ): List<ByteArray> {
        // Troceado sin boxing: una foto de 150 KB no debe crear 150.000 objetos.
        val total = if (payload.isEmpty()) 1 else (payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        val chunks = (0 until total).map { i ->
            val from = i * CHUNK_BYTES
            payload.copyOfRange(from, minOf(from + CHUNK_BYTES, payload.size))
        }
        return chunks.mapIndexed { index, chunk ->
            val header = buildString {
                append(senderFp.replace(" ", "")).append('\n')
                append(nickname.replace("\n", " ")).append('\n')
                append(kind.name).append('\n')
                append(msgId).append('\n')
                append(index).append('\n')
                append(total).append('\n')
                append(groupId).append('\n')
                append(ttlSeconds).append('\n')
            }.toByteArray(Charsets.UTF_8)
            header + chunk
        }
    }

    /** Parsea un sobre descifrado. Devuelve null si no tiene el formato v4. */
    fun parse(bytes: ByteArray): Parsed? {
        // Localizar el final de las líneas de cabecera sin decodificar el payload.
        var seen = 0
        var offset = -1
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                seen++
                if (seen == HEADER_LINES) {
                    offset = i + 1
                    break
                }
            }
        }
        if (offset < 0) return null

        val header = String(bytes, 0, offset - 1, Charsets.UTF_8).split('\n')
        if (header.size < HEADER_LINES) return null

        val kind = Kind.parse(header[2]) ?: return null
        val index = header[4].toIntOrNull() ?: return null
        val total = header[5].toIntOrNull() ?: return null
        if (index < 0 || total < 1 || index >= total) return null

        return Parsed(
            senderFp = header[0],
            nickname = header[1],
            kind = kind,
            msgId = header[3],
            index = index,
            total = total,
            groupId = header[6],
            ttlSeconds = header[7].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            payload = bytes.copyOfRange(offset, bytes.size),
        )
    }
}

/**
 * Reensambla fragmentos hasta completar el archivo original.
 * Descarta transferencias incompletas pasado un tiempo.
 */
class ChunkAssembler {

    private class Pending(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        val startedAt = System.currentTimeMillis()
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Devuelve el payload completo cuando llega el último fragmento. */
    fun offer(msgId: String, index: Int, total: Int, data: ByteArray): ByteArray? {
        if (total == 1) return data

        evictStale()
        val entry = pending.getOrPut(msgId) { Pending(total) }
        if (entry.total != total || index >= entry.total) return null

        synchronized(entry) {
            if (entry.parts[index] != null) return null
            entry.parts[index] = data
            entry.received++
            if (entry.received < entry.total) return null
        }

        pending.remove(msgId)
        val size = entry.parts.sumOf { it?.size ?: 0 }
        val out = ByteArray(size)
        var pos = 0
        entry.parts.forEach { part ->
            part ?: return null
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
    }

    private fun evictStale() {
        val cutoff = System.currentTimeMillis() - STALE_MS
        pending.entries.removeAll { it.value.startedAt < cutoff }
    }

    private companion object {
        const val STALE_MS = 10 * 60 * 1000L
    }
}
