package com.privmsg.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sesiones de Double Ratchet, una por contacto, cifradas en reposo.
 *
 * El estado del ratchet **es** el secreto: contiene las claves de la cadena
 * actual. Se guarda con AES-256 respaldado por el Keystore, igual que la
 * identidad.
 *
 * Ojo con la consistencia: cada cifrado y cada descifrado devuelven un estado
 * nuevo que **hay que guardar**. Si se pierde, la cadena se desincroniza y
 * los mensajes dejan de abrirse. Por eso todas las operaciones que tocan una
 * sesión están serializadas sobre el mismo objeto de bloqueo.
 */
class RatchetStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "privmsg_ratchet",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val lock = Any()

    fun has(fingerprint: String): Boolean = load(fingerprint) != null

    fun load(fingerprint: String): ByteArray? = synchronized(lock) {
        prefs.getString(key(fingerprint), null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
    }

    fun save(fingerprint: String, session: ByteArray) = synchronized(lock) {
        prefs.edit()
            .putString(key(fingerprint), Base64.encodeToString(session, Base64.NO_WRAP))
            .apply()
    }

    /** Elimina la sesión: la próxima vez se negociará una nueva. */
    fun clear(fingerprint: String) = synchronized(lock) {
        prefs.edit().remove(key(fingerprint)).apply()
    }

    /**
     * Ejecuta una operación sobre la sesión de forma atómica: carga el estado,
     * aplica el bloque y persiste el estado resultante. Si el bloque falla, el
     * estado anterior se conserva intacto.
     */
    fun <T> withSession(
        fingerprint: String,
        block: (ByteArray) -> Pair<ByteArray, T>,
    ): T? = synchronized(lock) {
        val current = load(fingerprint) ?: return null
        val (updated, result) = block(current)
        save(fingerprint, updated)
        result
    }

    private fun key(fingerprint: String) = "session_" + fingerprint.replace(" ", "")
}
