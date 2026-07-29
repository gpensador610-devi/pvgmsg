package com.privmsg.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import uniffi.privmsg_core.pinHash
import java.security.SecureRandom

/** Cuánto tarda la app en bloquearse tras salir de ella. */
enum class LockTimeout(val millis: Long, val label: String) {
    IMMEDIATE(0, "Al salir"),
    M1(60_000, "1 minuto"),
    M5(300_000, "5 minutos"),
    M15(900_000, "15 minutos"),
    H1(3_600_000, "1 hora");

    companion object {
        fun fromMillis(millis: Long): LockTimeout =
            entries.firstOrNull { it.millis == millis } ?: IMMEDIATE
    }
}

/**
 * Bloqueo de la app con PIN propio y huella.
 *
 * El PIN es **distinto al del teléfono** a propósito: si alguien conoce tu
 * desbloqueo (pareja, compañero de trabajo) o te quitan el móvil ya abierto,
 * PrivMsg sigue cerrada.
 *
 * No se guarda el PIN, sino un verificador Argon2id. De él no se puede volver
 * al PIN. Y como un PIN corto tiene pocas combinaciones, hay espera creciente
 * tras cada fallo: la defensa real no es el hash, es que no te dejen probar.
 */
class AppLock(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "privmsg_lock",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val isEnabled: Boolean get() = prefs.getString(KEY_HASH, null) != null

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, true)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    var timeout: LockTimeout
        get() = LockTimeout.fromMillis(prefs.getLong(KEY_TIMEOUT, 0L))
        set(value) = prefs.edit().putLong(KEY_TIMEOUT, value.millis).apply()

    /** Define o cambia el PIN. Devuelve false si no cumple el mínimo. */
    fun setPin(pin: String): Boolean {
        if (!validFormat(pin)) return false
        // No puede coincidir con el de coacción, o uno anularía al otro.
        if (hasDuressPin && matchesDuress(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = runCatching { pinHash(pin, salt) }.getOrNull() ?: return false
        prefs.edit()
            .putString(KEY_SALT, b64(salt))
            .putString(KEY_HASH, b64(hash))
            .putInt(KEY_FAILURES, 0)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
        return true
    }

    // ---------- PIN de coacción ----------

    val hasDuressPin: Boolean get() = prefs.getString(KEY_DURESS_HASH, null) != null

    /**
     * Define el PIN de coacción: al introducirlo, la app borra todo en
     * silencio y arranca como recién instalada.
     *
     * Debe ser distinto al normal y tener el mismo formato, para que no se
     * distinga uno de otro mirando por encima del hombro.
     */
    fun setDuressPin(pin: String): Boolean {
        if (!validFormat(pin)) return false
        if (verifyPinQuietly(pin)) return false // igual que el normal: inválido
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = runCatching { pinHash(pin, salt) }.getOrNull() ?: return false
        prefs.edit()
            .putString(KEY_DURESS_SALT, b64(salt))
            .putString(KEY_DURESS_HASH, b64(hash))
            .apply()
        return true
    }

    fun clearDuressPin() {
        prefs.edit().remove(KEY_DURESS_SALT).remove(KEY_DURESS_HASH).apply()
    }

    /** ¿Es este el PIN de coacción? Se comprueba antes que el normal. */
    fun matchesDuress(pin: String): Boolean {
        val salt = prefs.getString(KEY_DURESS_SALT, null)?.let { unb64(it) } ?: return false
        val expected = prefs.getString(KEY_DURESS_HASH, null) ?: return false
        val actual = runCatching { b64(pinHash(pin, salt)) }.getOrNull() ?: return false
        return constantTimeEquals(actual, expected)
    }

    /** Comprueba el PIN normal sin tocar contadores (uso interno). */
    private fun verifyPinQuietly(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let { unb64(it) } ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        val actual = runCatching { b64(pinHash(pin, salt)) }.getOrNull() ?: return false
        return constantTimeEquals(actual, expected)
    }

    private fun validFormat(pin: String) =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

    fun disable() {
        prefs.edit()
            .remove(KEY_SALT)
            .remove(KEY_HASH)
            .remove(KEY_FAILURES)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    /**
     * Comprueba el PIN. Cuenta los fallos y aplica la espera creciente.
     * Devuelve true solo si es correcto y no estamos en penalización.
     */
    fun verifyPin(pin: String): Boolean {
        if (remainingLockMillis() > 0) return false
        val salt = prefs.getString(KEY_SALT, null)?.let { unb64(it) } ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false

        val actual = runCatching { b64(pinHash(pin, salt)) }.getOrNull()
        // Comparación en tiempo constante: no filtra cuántos bytes acertaste.
        val ok = actual != null && constantTimeEquals(actual, expected)

        if (ok) {
            prefs.edit().putInt(KEY_FAILURES, 0).remove(KEY_LOCKED_UNTIL).apply()
        } else {
            registerFailure()
        }
        return ok
    }

    /** Éxito por huella: limpia el contador igual que un PIN correcto. */
    fun registerBiometricSuccess() {
        prefs.edit().putInt(KEY_FAILURES, 0).remove(KEY_LOCKED_UNTIL).apply()
    }

    val failedAttempts: Int get() = prefs.getInt(KEY_FAILURES, 0)

    /** Milisegundos que faltan para poder volver a intentarlo. */
    fun remainingLockMillis(): Long {
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun registerFailure() {
        val failures = failedAttempts + 1
        val editor = prefs.edit().putInt(KEY_FAILURES, failures)
        penaltyFor(failures)?.let {
            editor.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + it)
        }
        editor.apply()
    }

    /**
     * Espera tras N fallos. Crece rápido: probar 10.000 PIN de 4 dígitos
     * pasaría de minutos a siglos.
     */
    private fun penaltyFor(failures: Int): Long? = when {
        failures < 5 -> null
        failures < 8 -> 30_000L
        failures < 12 -> 5 * 60_000L
        failures < 20 -> 30 * 60_000L
        else -> 3 * 3_600_000L
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 10

        private const val KEY_SALT = "pin_salt_v1"
        private const val KEY_HASH = "pin_hash_v1"
        private const val KEY_BIOMETRIC = "biometric_v1"
        private const val KEY_TIMEOUT = "lock_timeout_v1"
        private const val KEY_FAILURES = "pin_failures_v1"
        private const val KEY_LOCKED_UNTIL = "locked_until_v1"
        private const val KEY_DURESS_SALT = "duress_salt_v1"
        private const val KEY_DURESS_HASH = "duress_hash_v1"
    }
}
