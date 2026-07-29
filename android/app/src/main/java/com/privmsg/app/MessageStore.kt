package com.privmsg.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Un mensaje de una conversación. */
data class Msg(
    val mine: Boolean,
    val timestamp: Long,
    val kind: Kind = Kind.TEXT,
    /** Texto del mensaje (vacío en fotos y audios). */
    val text: String = "",
    /** Identificador en el MediaVault (fotos y audios). */
    val mediaId: String = "",
    /** Duración en ms (audios). */
    val durationMs: Long = 0L,
    /** Instante en que se autodestruye; 0 = nunca. */
    val expiresAt: Long = 0L,
    /** En grupos, huella de quien lo envió (para mostrar el nombre). */
    val senderFp: String = "",
) {
    val expired: Boolean
        get() = expiresAt > 0 && System.currentTimeMillis() >= expiresAt

    /** Resumen de una línea para la lista de chats. */
    fun preview(): String = when (kind) {
        Kind.TEXT -> text
        Kind.IMAGE -> "📷 Foto"
        Kind.AUDIO -> "🎤 Nota de voz (${formatDuration(durationMs)})"
        else -> ""
    }
}

/**
 * Historial por conversación, cifrado en reposo (Android Keystore).
 *
 * El `chatId` es la huella del contacto en chats directos, o el id del grupo
 * en grupos. Los mensajes con TTL se purgan de forma perezosa al leerlos y de
 * forma activa desde el barrido periódico del servicio.
 */
class MessageStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "privmsg_messages",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Devuelve los mensajes vivos, descartando (y borrando) los caducados. */
    fun load(chatId: String): List<Msg> {
        val raw = prefs.getString(keyFor(chatId), null) ?: return emptyList()
        val all = raw.split('\n').filter { it.isNotBlank() }.mapNotNull { decode(it) }
        val alive = all.filterNot { it.expired }
        if (alive.size != all.size) persist(chatId, alive)
        return alive
    }

    fun lastMessage(chatId: String): Msg? = load(chatId).lastOrNull()

    fun append(chatId: String, msg: Msg) {
        val key = keyFor(chatId)
        val existing = prefs.getString(key, "") ?: ""
        val updated = (if (existing.isBlank()) "" else existing + "\n") + encode(msg)
        prefs.edit().putString(key, updated).apply()
    }

    fun clear(chatId: String) {
        prefs.edit().remove(keyFor(chatId)).apply()
    }

    /**
     * Barrido de todas las conversaciones: borra lo caducado.
     * Devuelve los ids de media que ya no referencia nadie, para borrarlos.
     */
    fun purgeExpired(): List<String> {
        val orphanedMedia = mutableListOf<String>()
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { key ->
            val raw = prefs.getString(key, null) ?: return@forEach
            val all = raw.split('\n').filter { it.isNotBlank() }.mapNotNull { decode(it) }
            val (dead, alive) = all.partition { it.expired }
            if (dead.isEmpty()) return@forEach

            dead.forEach { if (it.mediaId.isNotEmpty()) orphanedMedia.add(it.mediaId) }
            if (alive.isEmpty()) {
                prefs.edit().remove(key).apply()
            } else {
                prefs.edit().putString(key, alive.joinToString("\n") { encode(it) }).apply()
            }
        }
        return orphanedMedia
    }

    /** Instante en que caducará el próximo mensaje, o null si ninguno caduca. */
    fun nextExpiry(): Long? = prefs.all.keys
        .filter { it.startsWith(PREFIX) }
        .flatMap { key ->
            (prefs.getString(key, null) ?: "").split('\n')
                .filter { it.isNotBlank() }
                .mapNotNull { decode(it) }
        }
        .mapNotNull { it.expiresAt.takeIf { t -> t > 0 } }
        .minOrNull()

    private fun persist(chatId: String, msgs: List<Msg>) {
        val key = keyFor(chatId)
        if (msgs.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, msgs.joinToString("\n") { encode(it) }).apply()
        }
    }

    private fun keyFor(chatId: String) = PREFIX + chatId.replace(" ", "")

    private fun encode(m: Msg): String = listOf(
        if (m.mine) "1" else "0",
        m.timestamp.toString(),
        m.kind.name,
        Base64.encodeToString(m.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
        m.mediaId,
        m.durationMs.toString(),
        m.expiresAt.toString(),
        m.senderFp,
    ).joinToString("|")

    private fun decode(s: String): Msg? = runCatching {
        val f = s.split("|")
        Msg(
            mine = f[0] == "1",
            timestamp = f[1].toLong(),
            kind = Kind.parse(f[2]) ?: Kind.TEXT,
            text = String(Base64.decode(f[3], Base64.NO_WRAP), Charsets.UTF_8),
            mediaId = f.getOrElse(4) { "" },
            durationMs = f.getOrElse(5) { "0" }.toLongOrNull() ?: 0L,
            expiresAt = f.getOrElse(6) { "0" }.toLongOrNull() ?: 0L,
            senderFp = f.getOrElse(7) { "" },
        )
    }.getOrNull()

    private companion object {
        const val PREFIX = "msgs_"
    }
}
