package com.privmsg.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import uniffi.privmsg_core.Contact
import java.security.SecureRandom

/** Un miembro del grupo: sus claves públicas y el nombre que difunde. */
data class Member(
    val fingerprint: String,
    val x25519Public: ByteArray,
    val mlkemPublic: ByteArray,
    val name: String,
) {
    fun toContact() = Contact(
        x25519Public = x25519Public,
        mlkemPublic = mlkemPublic,
        fingerprint = fingerprint,
    )

    companion object {
        fun of(contact: Contact, name: String) = Member(
            fingerprint = contact.fingerprint,
            x25519Public = contact.x25519Public,
            mlkemPublic = contact.mlkemPublic,
            name = name,
        )
    }
}

/**
 * Un grupo de trabajo.
 *
 * No hay servidor que administre el grupo: la lista de miembros se difunde
 * cifrada entre todos. Cada mensaje se sella **por separado para cada miembro**
 * con el KEM híbrido completo, así que un grupo no debilita el cifrado — y
 * ningún relay puede saber que varios blobs pertenecen al mismo grupo.
 */
data class Group(
    val id: String,
    val name: String,
    val members: List<Member>,
    /** Huella de quien lo creó (informativo: no hay jerarquía real). */
    val createdBy: String,
    val avatar: ByteArray? = null,
) {
    /** Miembros a los que hay que enviar, excluyéndome a mí. */
    fun recipients(myFingerprint: String): List<Member> =
        members.filter { it.fingerprint != myFingerprint }

    fun memberName(fingerprint: String): String =
        members.firstOrNull { it.fingerprint == fingerprint }?.name.orEmpty()
}

/** Grupos guardados localmente, cifrados en reposo. */
class GroupStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "privmsg_groups",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun all(): List<Group> {
        val raw = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { decode(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(groupId: String): Group? = all().firstOrNull { it.id == groupId }

    fun save(group: Group) {
        val updated = all().filterNot { it.id == group.id } + group
        persist(updated)
    }

    fun delete(groupId: String) {
        persist(all().filterNot { it.id == groupId })
    }

    /** Quita a un miembro (cuando abandona el grupo). */
    fun removeMember(groupId: String, fingerprint: String) {
        val group = get(groupId) ?: return
        save(group.copy(members = group.members.filterNot { it.fingerprint == fingerprint }))
    }

    private fun persist(groups: List<Group>) {
        val array = JSONArray()
        groups.forEach { array.put(encode(it)) }
        prefs.edit().putString(KEY_GROUPS, array.toString()).apply()
    }

    // ---- serialización JSON ----

    private fun encode(group: Group): JSONObject = JSONObject().apply {
        put("id", group.id)
        put("name", group.name)
        put("createdBy", group.createdBy)
        group.avatar?.let { put("avatar", b64(it)) }
        put(
            "members",
            JSONArray().apply {
                group.members.forEach { member ->
                    put(
                        JSONObject().apply {
                            put("fp", member.fingerprint)
                            put("x", b64(member.x25519Public))
                            put("k", b64(member.mlkemPublic))
                            put("n", member.name)
                        },
                    )
                }
            },
        )
    }

    private fun decode(json: JSONObject): Group? = runCatching {
        val membersJson = json.getJSONArray("members")
        Group(
            id = json.getString("id"),
            name = json.getString("name"),
            createdBy = json.optString("createdBy", ""),
            avatar = json.optString("avatar", "").takeIf { it.isNotBlank() }?.let { unb64(it) },
            members = (0 until membersJson.length()).map { i ->
                val m = membersJson.getJSONObject(i)
                Member(
                    fingerprint = m.getString("fp"),
                    x25519Public = unb64(m.getString("x")),
                    mlkemPublic = unb64(m.getString("k")),
                    name = m.optString("n", ""),
                )
            },
        )
    }.getOrNull()

    /**
     * Serializa un grupo para enviarlo en una invitación (va dentro del sobre
     * cifrado, así que la lista de miembros nunca se expone).
     */
    fun encodeForWire(group: Group): ByteArray = encode(group).toString().toByteArray(Charsets.UTF_8)

    fun decodeFromWire(bytes: ByteArray): Group? =
        runCatching { decode(JSONObject(String(bytes, Charsets.UTF_8))) }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val KEY_GROUPS = "groups_v1"

        /** Identificador de grupo: 128 bits aleatorios, imposible de adivinar. */
        fun newGroupId(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
