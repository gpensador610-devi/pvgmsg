package com.privmsg.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import uniffi.privmsg_core.Contact
import uniffi.privmsg_core.Identity

/** Contacto guardado + nombre visible y foto (actualizados por sus mensajes). */
data class ContactEntry(
    val contact: Contact,
    val name: String,
    /** JPEG de su foto de perfil, o null. Llega cifrado dentro de sus mensajes. */
    val avatar: ByteArray? = null,
)

/**
 * Persistencia local cifrada (AES-256 vía Android Keystore).
 * Nada de esto sale jamás del dispositivo.
 */
class IdentityStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "privmsg_secure_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Identidad guardada, o null si aún no hay cuenta en este dispositivo. */
    fun loadIdentity(): Identity? =
        prefs.getString(KEY_IDENTITY, null)?.let { decodeIdentity(it) }

    /** Guarda identidad + frase de recuperación (cifradas por el Keystore). */
    fun saveIdentity(identity: Identity, mnemonic: String) {
        prefs.edit()
            .putString(KEY_IDENTITY, encodeIdentity(identity))
            .putString(KEY_MNEMONIC, mnemonic)
            .apply()
    }

    /** Frase de recuperación, o null en identidades creadas antes de esta función. */
    fun getMnemonic(): String? = prefs.getString(KEY_MNEMONIC, null)

    /** Borra toda la cuenta de este dispositivo (identidad, contactos, mensajes). */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    /** Nombre de perfil propio (viaja cifrado dentro de cada mensaje). */
    fun getProfileName(): String = prefs.getString(KEY_PROFILE_NAME, "") ?: ""

    fun setProfileName(name: String) {
        prefs.edit().putString(KEY_PROFILE_NAME, name.replace("\n", " ").trim()).apply()
    }

    /** Foto de perfil propia en JPEG, o null si no hay. */
    fun getProfileAvatar(): ByteArray? =
        prefs.getString(KEY_PROFILE_AVATAR, null)?.let { runCatching { unb64(it) }.getOrNull() }

    fun setProfileAvatar(jpeg: ByteArray) {
        prefs.edit().putString(KEY_PROFILE_AVATAR, b64(jpeg)).apply()
    }

    /** Contactos a los que ya les enviamos nuestra foto de perfil. */
    fun avatarSentTo(): Set<String> = prefs.getStringSet(KEY_AVATAR_SENT, emptySet()) ?: emptySet()

    fun markAvatarSent(fingerprint: String) {
        prefs.edit().putStringSet(KEY_AVATAR_SENT, avatarSentTo() + fingerprint).apply()
    }

    /** Al cambiar la foto hay que volver a difundirla a todos. */
    fun clearAvatarSent() {
        prefs.edit().remove(KEY_AVATAR_SENT).apply()
    }

    /** Contactos guardados con su nombre visible. */
    fun loadEntries(): List<ContactEntry> {
        val raw = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return raw.split(RECORD_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { decodeEntry(it) }
    }

    fun loadContacts(): List<Contact> = loadEntries().map { it.contact }

    /** Añade un contacto si su huella no existe ya. Devuelve true si se añadió. */
    fun addContact(contact: Contact, name: String = ""): Boolean {
        val entries = loadEntries()
        if (entries.any { it.contact.fingerprint == contact.fingerprint }) return false
        saveEntries(entries + ContactEntry(contact, name.replace("\n", " ").trim()))
        return true
    }

    /** Actualiza el nombre visible de un contacto (local o difundido por él). */
    fun setContactName(fingerprint: String, name: String) {
        val clean = name.replace("\n", " ").trim()
        val entries = loadEntries().map {
            if (it.contact.fingerprint == fingerprint) it.copy(name = clean) else it
        }
        saveEntries(entries)
    }

    /** Elimina un contacto de la agenda. */
    fun removeContact(fingerprint: String) {
        saveEntries(loadEntries().filterNot { it.contact.fingerprint == fingerprint })
    }

    /** Guarda la foto de perfil que un contacto nos ha difundido. */
    fun setContactAvatar(fingerprint: String, jpeg: ByteArray) {
        val entries = loadEntries().map {
            if (it.contact.fingerprint == fingerprint) it.copy(avatar = jpeg) else it
        }
        saveEntries(entries)
    }

    private fun saveEntries(entries: List<ContactEntry>) {
        val serialized = entries.joinToString(RECORD_SEP) { encodeEntry(it) }
        prefs.edit().putString(KEY_CONTACTS, serialized).apply()
    }

    // --- serialización simple campo:base64 ---

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun encodeIdentity(id: Identity): String = listOf(
        b64(id.x25519Secret), b64(id.x25519Public),
        b64(id.mlkemSecret), b64(id.mlkemPublic),
        id.fingerprint,
    ).joinToString(FIELD_SEP)

    private fun decodeIdentity(s: String): Identity? = runCatching {
        val f = s.split(FIELD_SEP)
        Identity(
            x25519Secret = unb64(f[0]),
            x25519Public = unb64(f[1]),
            mlkemSecret = unb64(f[2]),
            mlkemPublic = unb64(f[3]),
            fingerprint = f[4],
        )
    }.getOrNull()

    private fun encodeEntry(e: ContactEntry): String = listOf(
        b64(e.contact.x25519Public), b64(e.contact.mlkemPublic), e.contact.fingerprint,
        b64(e.name.toByteArray(Charsets.UTF_8)),
        e.avatar?.let { b64(it) } ?: "",
    ).joinToString(FIELD_SEP)

    private fun decodeEntry(s: String): ContactEntry? = runCatching {
        val f = s.split(FIELD_SEP)
        ContactEntry(
            contact = Contact(
                x25519Public = unb64(f[0]),
                mlkemPublic = unb64(f[1]),
                fingerprint = f[2],
            ),
            // Registros antiguos no llevaban nombre ni foto.
            name = if (f.size > 3) String(unb64(f[3]), Charsets.UTF_8) else "",
            avatar = f.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { unb64(it) },
        )
    }.getOrNull()

    private companion object {
        const val KEY_IDENTITY = "identity_v1"
        const val KEY_MNEMONIC = "mnemonic_v1"
        const val KEY_CONTACTS = "contacts_v1"
        const val KEY_PROFILE_NAME = "profile_name_v1"
        const val KEY_PROFILE_AVATAR = "profile_avatar_v1"
        const val KEY_AVATAR_SENT = "avatar_sent_v1"
        const val FIELD_SEP = "|"
        const val RECORD_SEP = "\n"
    }
}
