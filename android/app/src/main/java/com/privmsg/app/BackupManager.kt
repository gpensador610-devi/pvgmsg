package com.privmsg.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import uniffi.privmsg_core.backupDecrypt
import uniffi.privmsg_core.backupEncrypt

/**
 * Copia de seguridad completa: contactos, grupos, historial, medios y ajustes.
 *
 * La frase de 12 palabras recupera la identidad; esto recupera todo lo demás.
 * El archivo va cifrado con Argon2id + XChaCha20-Poly1305, así que se puede
 * guardar en cualquier sitio (incluida la nube) sin exponer nada.
 */
class BackupManager(
    private val context: Context,
    private val messenger: Messenger,
) {
    /** Genera el archivo cifrado y lo escribe en el destino elegido. */
    fun export(destination: Uri, password: String): Result<Int> = runCatching {
        val payload = buildJson()
        val archive = backupEncrypt(payload.toByteArray(Charsets.UTF_8), password)
        context.contentResolver.openOutputStream(destination)?.use { it.write(archive) }
            ?: error("no se pudo escribir el archivo")
        archive.size
    }

    /** Restaura desde un archivo cifrado. Devuelve cuántos chats se recuperaron. */
    fun import(source: Uri, password: String): Result<Int> = runCatching {
        val archive = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: error("no se pudo leer el archivo")
        val json = JSONObject(String(backupDecrypt(archive, password), Charsets.UTF_8))
        restore(json)
    }

    // ---------- serialización ----------

    private fun buildJson(): String {
        val store = messenger.store
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("profileName", store.getProfileName())
        store.getProfileAvatar()?.let { root.put("profileAvatar", b64(it)) }

        // Contactos
        val contacts = JSONArray()
        store.loadEntries().forEach { entry ->
            contacts.put(
                JSONObject().apply {
                    put("fp", entry.contact.fingerprint)
                    put("x", b64(entry.contact.x25519Public))
                    put("k", b64(entry.contact.mlkemPublic))
                    put("name", entry.name)
                    entry.avatar?.let { put("avatar", b64(it)) }
                },
            )
        }
        root.put("contacts", contacts)

        // Grupos
        val groups = JSONArray()
        messenger.groups.all().forEach { group ->
            groups.put(JSONObject(String(messenger.groups.encodeForWire(group), Charsets.UTF_8)))
        }
        root.put("groups", groups)

        // Conversaciones (con sus medios incrustados)
        val chats = JSONArray()
        val chatIds = buildList {
            add(messenger.myFingerprint)
            addAll(store.loadContacts().map { it.fingerprint })
            addAll(messenger.groups.all().map { it.id })
        }.distinct()

        chatIds.forEach { chatId ->
            val msgs = messenger.messages.load(chatId)
            if (msgs.isEmpty()) return@forEach
            val settings = messenger.prefs.settings(chatId)
            val messagesJson = JSONArray()
            msgs.forEach { msg ->
                messagesJson.put(
                    JSONObject().apply {
                        put("mine", msg.mine)
                        put("ts", msg.timestamp)
                        put("kind", msg.kind.name)
                        put("text", msg.text)
                        put("duration", msg.durationMs)
                        put("expiresAt", msg.expiresAt)
                        put("senderFp", msg.senderFp)
                        if (msg.mediaId.isNotEmpty()) {
                            messenger.vault.load(msg.mediaId)?.let { bytes ->
                                put("mediaExt", msg.mediaId.substringAfterLast('.', "bin"))
                                put("media", b64(bytes))
                            }
                        }
                    },
                )
            }
            chats.put(
                JSONObject().apply {
                    put("chatId", chatId)
                    put("muted", settings.muted)
                    put("ttl", settings.ttlSeconds)
                    put("sound", settings.soundUri)
                    put("messages", messagesJson)
                },
            )
        }
        root.put("chats", chats)
        root.put("blocked", JSONArray(messenger.prefs.blocked().toList()))
        root.put("defaultSound", messenger.prefs.defaultSound())
        return root.toString()
    }

    private fun restore(root: JSONObject): Int {
        val store = messenger.store

        root.optString("profileName").takeIf { it.isNotBlank() }?.let { store.setProfileName(it) }
        root.optString("profileAvatar").takeIf { it.isNotBlank() }
            ?.let { store.setProfileAvatar(unb64(it)) }

        // Contactos
        val contacts = root.optJSONArray("contacts") ?: JSONArray()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            val contact = uniffi.privmsg_core.Contact(
                x25519Public = unb64(c.getString("x")),
                mlkemPublic = unb64(c.getString("k")),
                fingerprint = c.getString("fp"),
            )
            store.addContact(contact, c.optString("name", ""))
            store.setContactName(contact.fingerprint, c.optString("name", ""))
            c.optString("avatar").takeIf { it.isNotBlank() }
                ?.let { store.setContactAvatar(contact.fingerprint, unb64(it)) }
        }

        // Grupos
        val groups = root.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groups.length()) {
            messenger.groups.decodeFromWire(
                groups.getJSONObject(i).toString().toByteArray(Charsets.UTF_8),
            )?.let { messenger.groups.save(it) }
        }

        // Conversaciones
        val chats = root.optJSONArray("chats") ?: JSONArray()
        for (i in 0 until chats.length()) {
            val chat = chats.getJSONObject(i)
            val chatId = chat.getString("chatId")
            messenger.prefs.setMuted(chatId, chat.optBoolean("muted", false))
            messenger.prefs.setTtl(chatId, chat.optLong("ttl", 0L))
            messenger.prefs.setSound(chatId, chat.optString("sound", ""))

            val msgs = chat.optJSONArray("messages") ?: continue
            messenger.messages.clear(chatId)
            for (j in 0 until msgs.length()) {
                val m = msgs.getJSONObject(j)
                val mediaId = m.optString("media").takeIf { it.isNotBlank() }?.let { encoded ->
                    messenger.vault.save(unb64(encoded), m.optString("mediaExt", "bin"))
                }.orEmpty()

                messenger.messages.append(
                    chatId,
                    Msg(
                        mine = m.optBoolean("mine", false),
                        timestamp = m.optLong("ts", System.currentTimeMillis()),
                        kind = Kind.parse(m.optString("kind", "TEXT")) ?: Kind.TEXT,
                        text = m.optString("text", ""),
                        mediaId = mediaId,
                        durationMs = m.optLong("duration", 0L),
                        expiresAt = m.optLong("expiresAt", 0L),
                        senderFp = m.optString("senderFp", ""),
                    ),
                )
            }
        }

        val blocked = root.optJSONArray("blocked") ?: JSONArray()
        for (i in 0 until blocked.length()) messenger.prefs.block(blocked.getString(i))
        root.optString("defaultSound").takeIf { it.isNotBlank() }
            ?.let { messenger.prefs.setDefaultSound(it) }

        messenger.refreshSubscriptions()
        messenger.notifyStateChanged()
        return chats.length()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val FORMAT_VERSION = 1
        const val MIME_TYPE = "application/octet-stream"

        fun suggestedFileName(): String = "privmsg-backup.privmsg"
    }
}
