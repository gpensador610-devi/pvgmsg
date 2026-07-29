package com.privmsg.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Arranca el servicio tras reiniciar el teléfono, para que los mensajes sigan
 * llegando sin que el usuario tenga que abrir la app.
 *
 * Solo lo hace si ya existe una identidad: en un dispositivo recién instalado
 * no se arranca nada.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val hasIdentity = runCatching {
            IdentityStore(context.applicationContext).loadIdentity() != null
        }.getOrDefault(false)

        if (hasIdentity) PrivMsgService.start(context.applicationContext)
    }
}
