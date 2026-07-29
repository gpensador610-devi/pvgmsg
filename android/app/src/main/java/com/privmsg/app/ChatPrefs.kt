package com.privmsg.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Opciones de autodestrucción disponibles. */
enum class Ttl(val seconds: Long, val label: String) {
    /** Usa el valor general de la app (solo válido a nivel de chat). */
    INHERIT(-1, "Por defecto"),
    OFF(0, "Desactivada"),
    H1(3_600, "1 hora"),
    H8(28_800, "8 horas"),
    D1(86_400, "1 día"),
    W1(604_800, "1 semana"),
    M1(2_592_000, "30 días");

    companion object {
        fun fromSeconds(seconds: Long): Ttl =
            entries.firstOrNull { it.seconds == seconds } ?: OFF

        /** Opciones que puede elegir un chat concreto. */
        val chatOptions: List<Ttl> get() = entries.toList()

        /** Opciones del ajuste general (sin "por defecto", que sería circular). */
        val globalOptions: List<Ttl> get() = entries.filter { it != INHERIT }
    }
}

/** Ajustes de una conversación concreta. */
data class ChatSettings(
    val muted: Boolean = false,
    /** -1 = heredar del ajuste general; 0 = desactivada; >0 = segundos. */
    val ttlSeconds: Long = -1L,
    /** URI del tono; vacío = usar el sonido general de la app. */
    val soundUri: String = "",
    /** Segundos efectivos ya resueltos (nunca -1). */
    val effectiveTtlSeconds: Long = 0L,
) {
    val ttl: Ttl get() = Ttl.fromSeconds(ttlSeconds)
    val effectiveTtl: Ttl get() = Ttl.fromSeconds(effectiveTtlSeconds)
}

/**
 * Ajustes por chat (silenciar, autodestrucción, tono) y lista de bloqueados.
 * Todo local y cifrado en reposo.
 */
class ChatPrefs(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "privmsg_chat_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ---- ajustes por chat ----

    fun settings(chatId: String): ChatSettings {
        val own = prefs.getLong(ttlKey(chatId), INHERIT)
        return ChatSettings(
            muted = prefs.getBoolean(mutedKey(chatId), false),
            ttlSeconds = own,
            soundUri = prefs.getString(soundKey(chatId), "") ?: "",
            effectiveTtlSeconds = if (own >= 0) own else defaultTtlSeconds(),
        )
    }

    /** Segundos de autodestrucción que se aplican realmente a este chat. */
    fun effectiveTtl(chatId: String): Long = settings(chatId).effectiveTtlSeconds

    /** Autodestrucción general: se aplica a todos los chats que no la fijen. */
    fun defaultTtlSeconds(): Long = prefs.getLong(KEY_DEFAULT_TTL, 0L)

    fun setDefaultTtl(seconds: Long) {
        prefs.edit().putLong(KEY_DEFAULT_TTL, seconds.coerceAtLeast(0L)).apply()
    }

    fun setMuted(chatId: String, muted: Boolean) {
        prefs.edit().putBoolean(mutedKey(chatId), muted).apply()
    }

    fun setTtl(chatId: String, seconds: Long) {
        prefs.edit().putLong(ttlKey(chatId), seconds).apply()
    }

    fun setSound(chatId: String, uri: String) {
        prefs.edit().putString(soundKey(chatId), uri).apply()
    }

    // ---- ajustes generales ----

    /** Tono por defecto para los chats que no tienen uno propio. */
    fun defaultSound(): String = prefs.getString(KEY_DEFAULT_SOUND, "") ?: ""

    fun setDefaultSound(uri: String) {
        prefs.edit().putString(KEY_DEFAULT_SOUND, uri).apply()
    }

    fun vibrate(): Boolean = prefs.getBoolean(KEY_VIBRATE, true)

    fun setVibrate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATE, enabled).apply()
    }

    /**
     * Bloqueo de capturas de pantalla (FLAG_SECURE). Activado por defecto:
     * en una app de privacidad, lo seguro debe ser lo que viene de fábrica.
     */
    fun screenSecurity(): Boolean = prefs.getBoolean(KEY_SCREEN_SECURITY, true)

    /** ¿Ya se mostró el asistente de permisos de segundo plano? */
    fun backgroundSetupDone(): Boolean = prefs.getBoolean(KEY_BG_SETUP_DONE, false)

    fun markBackgroundSetupDone() {
        prefs.edit().putBoolean(KEY_BG_SETUP_DONE, true).apply()
    }

    fun setScreenSecurity(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_SECURITY, enabled).apply()
    }

    /** Sonido efectivo de un chat: el suyo propio o el general. */
    fun effectiveSound(chatId: String): String =
        settings(chatId).soundUri.ifBlank { defaultSound() }

    // ---- bloqueo de contactos ----

    fun blocked(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    fun isBlocked(fingerprint: String): Boolean = fingerprint in blocked()

    fun block(fingerprint: String) {
        prefs.edit().putStringSet(KEY_BLOCKED, blocked() + fingerprint).apply()
    }

    fun unblock(fingerprint: String) {
        prefs.edit().putStringSet(KEY_BLOCKED, blocked() - fingerprint).apply()
    }

    /** Borra los ajustes de un chat eliminado. */
    fun forget(chatId: String) {
        prefs.edit()
            .remove(mutedKey(chatId))
            .remove(ttlKey(chatId))
            .remove(soundKey(chatId))
            .apply()
    }

    private fun key(chatId: String) = chatId.replace(" ", "")
    private fun mutedKey(chatId: String) = "muted_${key(chatId)}"
    private fun ttlKey(chatId: String) = "ttl_${key(chatId)}"
    private fun soundKey(chatId: String) = "sound_${key(chatId)}"

    private companion object {
        const val KEY_BLOCKED = "blocked_v1"
        const val KEY_DEFAULT_SOUND = "default_sound_v1"
        const val KEY_DEFAULT_TTL = "default_ttl_v1"
        const val KEY_VIBRATE = "vibrate_v1"
        const val KEY_SCREEN_SECURITY = "screen_security_v1"
        const val KEY_BG_SETUP_DONE = "bg_setup_done_v1"
        const val INHERIT = -1L
    }
}
