package com.privmsg.app

import android.content.Context
import java.util.Collections

/**
 * Recuerda qué mensajes ya se procesaron, **sobreviviendo a reinicios**.
 *
 * Los relays guardan los eventos y se los reenvían a quien se suscribe. Cada
 * vez que la app arranca vuelve a pedirlos, así que sin esto se verían otra
 * vez mensajes ya recibidos.
 *
 * Y no es solo cosmético: un mensaje repetido llegaba al ratchet, que ya había
 * destruido su clave (justo lo que se le pide), fallaba al descifrarlo y eso
 * se confundía con una sesión rota. Deduplicar **antes** del ratchet evita que
 * un reenvío pueda tumbar una conversación sana.
 *
 * Solo guarda identificadores, nunca contenido.
 */
class SeenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("privmsg_seen", Context.MODE_PRIVATE)

    /** Orden de inserción: permite podar siempre los más antiguos. */
    private val ids: MutableSet<String> = Collections.synchronizedSet(
        LinkedHashSet(
            (prefs.getString(KEY_IDS, "") ?: "")
                .split(SEP)
                .filter { it.isNotBlank() },
        ),
    )

    /** ¿Ya lo habíamos procesado? Si no, lo marca y devuelve false. */
    fun isDuplicate(id: String): Boolean {
        if (id.isBlank()) return false
        synchronized(ids) {
            if (!ids.add(id)) return true
            if (ids.size > MAX_IDS) {
                val iterator = ids.iterator()
                repeat(ids.size - MAX_IDS) {
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            persist()
        }
        return false
    }

    /**
     * Momento del mensaje más reciente que hemos procesado. Sirve para pedirle
     * al relay solo lo posterior en vez de las últimas 48 horas enteras.
     */
    var lastProcessedSeconds: Long
        get() = prefs.getLong(KEY_LAST, 0L)
        set(value) {
            if (value > lastProcessedSeconds) {
                prefs.edit().putLong(KEY_LAST, value).apply()
            }
        }

    private fun persist() {
        prefs.edit().putString(KEY_IDS, ids.joinToString(SEP)).apply()
    }

    private companion object {
        const val KEY_IDS = "seen_ids_v1"
        const val KEY_LAST = "last_processed_v1"
        const val SEP = ","
        const val MAX_IDS = 3_000
    }
}
