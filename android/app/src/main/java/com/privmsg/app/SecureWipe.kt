package com.privmsg.app

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Borrado de emergencia: deja el dispositivo como una instalación recién hecha.
 *
 * Se dispara con el PIN de coacción. El diseño clave es que **sea invisible**:
 * ni aviso, ni barra de progreso, ni "borrando…". Quien te esté mirando debe
 * ver una app vacía, no una app que acaba de destruir algo — si nota el
 * borrado, sabe que había algo y puede insistir.
 *
 * Lo que borra: identidad y frase, sesiones de ratchet, contactos, grupos,
 * historial, fotos, audios y todos los ajustes.
 *
 * Lo que **no** puede hacer:
 * - Recuperar lo que ya se haya copiado del teléfono antes de este momento.
 * - Borrar lo que el otro extremo tenga en su dispositivo.
 * - Garantizar que los bloques físicos se sobrescriban: en almacenamiento
 *   flash con *wear leveling* eso no lo controla ninguna app. Lo que sí vale
 *   es que todo estaba cifrado y su clave del Keystore se destruye con los
 *   datos: sin ella, lo que quede en el chip es ruido.
 */
object SecureWipe {

    private const val TAG = "SecureWipe"

    /** Todos los almacenes cifrados de la app. */
    private val PREF_FILES = listOf(
        "privmsg_secure_store",
        "privmsg_messages",
        "privmsg_groups",
        "privmsg_chat_prefs",
        "privmsg_ratchet",
        "privmsg_lock",
    )

    /**
     * Borra todo y reinicia la app en la pantalla de bienvenida.
     * No muestra nada al usuario: por diseño, parece un primer arranque.
     */
    fun wipeAndRestart(context: Context) {
        runCatching { wipe(context) }
            .onFailure { Log.e(TAG, "fallo durante el borrado: ${it.message}") }

        // Arrancar de cero: sin identidad, la app abre en la bienvenida.
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        context.startActivity(intent)

        // Matar el proceso asegura que no quede nada descifrado en memoria.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** El borrado en sí, sin reiniciar. Separado para poder probarlo. */
    fun wipe(context: Context) {
        val app = context.applicationContext

        // 1. Cortar la red antes de nada: que no llegue ni salga nada más.
        runCatching {
            AppState.calls?.endCall()
            AppState.messenger?.stop()
        }
        runCatching { PrivMsgService.stop(app) }
        AppState.messenger = null
        AppState.calls = null
        AppState.visibleChatId = null

        // 2. Vaciar los almacenes por la API (destruye también las claves
        //    derivadas que EncryptedSharedPreferences mantiene en memoria).
        PREF_FILES.forEach { name ->
            runCatching {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }

        // 3. Borrar los archivos por debajo, por si quedara algo en disco.
        runCatching {
            File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
                if (PREF_FILES.any { file.name.startsWith(it) }) file.delete()
            }
        }

        // 4. Fotos, audios y temporales.
        runCatching { File(app.filesDir, "media").deleteRecursively() }
        runCatching { File(app.cacheDir, "media_play").deleteRecursively() }
        runCatching { app.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }

        // 5. Notificaciones: que no quede rastro de conversaciones en la barra.
        runCatching {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            manager.cancelAll()
        }

        Log.d(TAG, "borrado completado")
    }
}
