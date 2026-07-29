package com.privmsg.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Huella o cara como atajo para desbloquear.
 *
 * Es solo eso, un atajo: el PIN sigue siendo la vía principal, porque la
 * biometría puede fallar (dedo mojado, sensor sucio) y porque en muchos sitios
 * te pueden obligar a poner el dedo más fácilmente que a revelar un PIN.
 */
object Biometrics {

    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** ¿Hay sensor y el usuario tiene huellas registradas? */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Muestra el diálogo del sistema. Los fallos se ignoran: queda el PIN. */
    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFallbackToPin: () -> Unit,
    ) {
        if (!available(activity)) {
            onFallbackToPin()
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Cancelar o quedarse sin intentos: se sigue con el PIN.
                    onFallbackToPin()
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear PrivMsg")
                .setSubtitle("Usa tu huella o introduce el PIN")
                .setNegativeButtonText("Usar PIN")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build(),
        )
    }
}
