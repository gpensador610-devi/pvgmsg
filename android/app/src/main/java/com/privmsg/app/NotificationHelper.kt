package com.privmsg.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Notificaciones de la app.
 *
 * Ojo con la privacidad: el contenido de los mensajes **no** se muestra en la
 * notificación por defecto, solo quién escribe. Nada del texto sale del proceso.
 */
object NotificationHelper {

    const val CHANNEL_SERVICE = "privmsg_service"
    const val CHANNEL_MESSAGES = "privmsg_messages"
    const val CHANNEL_CALLS = "privmsg_calls"

    const val ID_SERVICE = 1
    const val ID_CALL = 2
    private const val ID_MESSAGE_BASE = 1000

    fun ensureChannels(context: Context, prefs: ChatPrefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        // Canal del servicio: silencioso, solo mantiene la app viva.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Servicio en segundo plano",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Mantiene la recepción de mensajes activa"
                setShowBadge(false)
            },
        )

        // Canal de mensajes con el tono elegido por el usuario.
        val existing = manager.getNotificationChannel(CHANNEL_MESSAGES)
        val desiredSound = prefs.defaultSound()
        val currentSound = existing?.sound?.toString().orEmpty()
        if (existing == null || currentSound != desiredSound) {
            // Android no deja cambiar el sonido de un canal existente: se
            // recrea cuando el usuario elige otro tono.
            if (existing != null) manager.deleteNotificationChannel(CHANNEL_MESSAGES)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    "Mensajes",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Mensajes nuevos"
                    enableVibration(prefs.vibrate())
                    if (desiredSound.isNotBlank()) {
                        setSound(
                            Uri.parse(desiredSound),
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .build(),
                        )
                    }
                },
            )
        }

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                "Llamadas",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Llamadas entrantes"
                enableVibration(true)
            },
        )
    }

    /** Notificación permanente y discreta del servicio. */
    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("PrivMsg activo")
            .setContentText("Recibiendo mensajes cifrados")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()

    /** Aviso de mensaje nuevo. Sin contenido: solo quién escribe. */
    fun showMessage(
        context: Context,
        chatId: String,
        chatName: String,
        isGroup: Boolean,
        soundUri: String,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setContentTitle(chatName)
            .setContentText(if (isGroup) "Mensaje nuevo en el grupo" else "Mensaje nuevo")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .apply {
                // En Android 7 y anteriores el sonido va en la notificación.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && soundUri.isNotBlank()) {
                    setSound(Uri.parse(soundUri))
                }
            }
            .build()

        notificationManager(context)
            .notify(ID_MESSAGE_BASE + chatId.hashCode().and(0xFFFF), notification)
    }

    /** Aviso a pantalla completa de llamada entrante. */
    fun showIncomingCall(context: Context, callerName: String) {
        val fullScreen = openAppIntent(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setContentTitle(callerName)
            .setContentText("Llamada cifrada entrante")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()
        notificationManager(context).notify(ID_CALL, notification)
    }

    fun cancelCall(context: Context) = notificationManager(context).cancel(ID_CALL)

    fun cancelChat(context: Context, chatId: String) =
        notificationManager(context).cancel(ID_MESSAGE_BASE + chatId.hashCode().and(0xFFFF))

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
