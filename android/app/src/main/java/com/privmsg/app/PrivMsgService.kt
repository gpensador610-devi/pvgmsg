package com.privmsg.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import uniffi.privmsg_core.Contact
import java.util.Timer
import java.util.TimerTask

/**
 * Servicio en primer plano: mantiene los transportes conectados y purga los
 * mensajes caducados aunque la app esté cerrada.
 *
 * Sin esto, Android mata el proceso y no llegarían ni mensajes ni llamadas.
 * La notificación permanente es el precio que exige el sistema — y de paso
 * es honesta: el usuario ve que la app está escuchando.
 */
class PrivMsgService : Service() {

    private var listener: MessengerListener? = null
    private var purgeTimer: Timer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Si Android mató el proceso (o el teléfono se reinició), reconstruimos
        // el motor desde la identidad guardada sin necesitar la Activity.
        val messenger = AppState.messenger
            ?: IdentityStore(applicationContext).loadIdentity()?.let { identity ->
                Messenger(applicationContext, identity).also { AppState.messenger = it }
            }
        if (messenger == null) {
            stopSelf()
            return
        }
        if (AppState.calls == null) {
            AppState.calls = CallManager(applicationContext) { contact, kind, payload ->
                runCatching { messenger.dispatch(contact, kind, payload) }.getOrDefault(false)
            }
        }

        NotificationHelper.ensureChannels(this, messenger.prefs)
        startInForeground()

        listener = object : MessengerListener {
            override fun onMessage(chatId: String, chatName: String, msg: Msg, isGroup: Boolean) {
                // Silenciado o chat abierto en pantalla: sin notificación.
                if (messenger.prefs.settings(chatId).muted) return
                if (AppState.visibleChatId == chatId && AppState.appInForeground) return
                NotificationHelper.showMessage(
                    this@PrivMsgService,
                    chatId,
                    chatName,
                    isGroup,
                    messenger.prefs.effectiveSound(chatId),
                )
            }

            /**
             * El enrutado de llamadas vive aquí y no en la Activity: si la app
             * está cerrada cuando entra una llamada, el CallManager tiene que
             * recibir la invitación igual, para que al abrir la notificación ya
             * esté sonando y se pueda aceptar.
             */
            override fun onCallSignal(
                kind: Kind,
                contact: Contact,
                displayName: String,
                payload: ByteArray,
            ) {
                mainHandler.post {
                    val calls = AppState.calls ?: return@post
                    routeCallSignal(calls, kind, contact, displayName, payload)
                }
            }

            private fun routeCallSignal(
                calls: CallManager,
                kind: Kind,
                contact: Contact,
                displayName: String,
                payload: ByteArray,
            ) {
                when (kind) {
                    Kind.CALL_OFFER -> {
                        calls.onIncomingOffer(contact, displayName, payload)
                        if (!AppState.appInForeground) {
                            NotificationHelper.showIncomingCall(this@PrivMsgService, displayName)
                        }
                    }
                    Kind.CALL_ANSWER -> calls.onAnswer(payload)
                    Kind.CALL_END -> {
                        NotificationHelper.cancelCall(this@PrivMsgService)
                        calls.onRemoteEnd()
                    }
                    else -> Unit
                }
            }

        }.also { messenger.addListener(it) }

        messenger.start()
        startPurgeTimer(messenger)
        Log.d(TAG, "servicio arrancado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        purgeTimer?.cancel()
        purgeTimer = null
        listener?.let { AppState.messenger?.removeListener(it) }
        listener = null
        Log.d(TAG, "servicio detenido")
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = NotificationHelper.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.ID_SERVICE, notification)
        }
    }

    /** Barre los mensajes caducados periódicamente. */
    private fun startPurgeTimer(messenger: Messenger) {
        purgeTimer = Timer("purge", true).apply {
            scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() {
                        runCatching { messenger.purgeExpired() }
                    }
                },
                PURGE_INITIAL_MS, PURGE_INTERVAL_MS,
            )
        }
    }

    companion object {
        private const val TAG = "PrivMsgService"
        private const val PURGE_INITIAL_MS = 5_000L
        private const val PURGE_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, PrivMsgService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrivMsgService::class.java))
        }
    }
}

/**
 * Estado compartido entre la Activity y el servicio.
 * El Messenger vive aquí para que ambos usen la misma instancia.
 */
object AppState {
    @Volatile var messenger: Messenger? = null

    /**
     * Vive a nivel de proceso, no de Activity: una llamada tiene que sobrevivir
     * a que la pantalla gire o a que la app se cierre y se reabra.
     */
    @Volatile var calls: CallManager? = null

    @Volatile var appInForeground: Boolean = false
    @Volatile var visibleChatId: String? = null

    // ---- estado del bloqueo, a nivel de proceso ----
    //
    // Vive aquí y no en la Activity porque al girar la pantalla Android
    // destruye y recrea la Activity: si el estado viviera allí, cada rotación
    // volvería a pedir el PIN.

    /** ¿Hay que pedir PIN/huella antes de mostrar nada? */
    val locked = androidx.compose.runtime.mutableStateOf(false)

    /** Para no reevaluar el bloqueo en cada recreación de la Activity. */
    @Volatile var lockInitialized: Boolean = false

    /** Instante en que la app pasó realmente a segundo plano. */
    @Volatile var backgroundedAt: Long = 0L

    /**
     * La app abrió otra pantalla del sistema a propósito (cámara, galería,
     * selector de tono, gestor de archivos). Eso pausa la Activity pero **no
     * es salir de la app**, así que no debe disparar el bloqueo.
     */
    @Volatile var expectingExternalResult: Boolean = false
}
