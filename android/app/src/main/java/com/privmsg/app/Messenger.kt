package com.privmsg.app

import android.content.Context
import android.util.Log
import uniffi.privmsg_core.Contact
import uniffi.privmsg_core.Identity
import uniffi.privmsg_core.makeTransportEvent
import uniffi.privmsg_core.openMessage
import uniffi.privmsg_core.ratchetDecrypt
import uniffi.privmsg_core.ratchetEncrypt
import uniffi.privmsg_core.ratchetInitInitiator
import uniffi.privmsg_core.ratchetInitResponder
import uniffi.privmsg_core.rendezvousTag
import uniffi.privmsg_core.sealMessage
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Aviso de que algo cambió, para que la UI y las notificaciones reaccionen. */
interface MessengerListener {
    /** Llegó un mensaje visible. */
    fun onMessage(chatId: String, chatName: String, msg: Msg, isGroup: Boolean) {}

    /** Cambió algo del estado (contactos, grupos, avatares). */
    fun onStateChanged() {}

    /** Señal de llamada entrante o de control. */
    fun onCallSignal(kind: Kind, contact: Contact, displayName: String, payload: ByteArray) {}
}

/**
 * Motor de mensajería: sella, despacha, recibe y almacena.
 *
 * Vive fuera de la Activity para que el servicio en primer plano pueda seguir
 * recibiendo con la app cerrada. La UI se suscribe como listener.
 */
class Messenger(
    context: Context,
    val identity: Identity,
) {
    private val appContext = context.applicationContext

    val store = IdentityStore(appContext)
    val messages = MessageStore(appContext)
    val groups = GroupStore(appContext)
    val prefs = ChatPrefs(appContext)
    val vault = MediaVault(appContext)
    val ratchets = RatchetStore(appContext)

    private val assembler = ChunkAssembler()
    private val listeners = CopyOnWriteArrayList<MessengerListener>()

    private var lan: LanTransport? = null
    private var nostr: NostrTransport? = null

    val myFingerprint: String get() = identity.fingerprint

    fun addListener(listener: MessengerListener) = listeners.add(listener)
    fun removeListener(listener: MessengerListener) = listeners.remove(listener)

    // ---------- ciclo de vida ----------

    fun start() {
        if (lan != null) return
        lan = LanTransport(appContext, identity.fingerprint) { handleSealed(it) }
            .also { it.start() }
        nostr = NostrTransport(
            tagsProvider = { rendezvousTags() },
            onSealedReceived = { handleSealed(it) },
        ).also { it.start() }
    }

    fun stop() {
        lan?.stop(); lan = null
        nostr?.stop(); nostr = null
    }

    fun relaysOnline(): Boolean = nostr?.isOnline() == true

    fun peerOnLan(fingerprint: String): Boolean = lan?.peerVisible(fingerprint) == true

    fun refreshSubscriptions() = nostr?.resubscribe()

    /** Etiquetas de encuentro de hoy y ayer, para contactos y miembros de grupos. */
    private fun rendezvousTags(): List<String> {
        val today = System.currentTimeMillis() / 1000 / 86400
        val peers = (store.loadContacts() + groups.all().flatMap { group ->
            group.recipients(myFingerprint).map { it.toContact() }
        }).distinctBy { it.fingerprint }

        return peers
            .filter { it.fingerprint != identity.fingerprint }
            .flatMap { contact ->
                listOf(today, today - 1).mapNotNull { day ->
                    runCatching { rendezvousTag(identity, contact, day.toULong()) }.getOrNull()
                }
            }
    }

    // ---------- envío ----------

    /**
     * Sella y despacha un payload a un destinatario (troceándolo si hace falta).
     * Intenta primero WiFi directa, luego relays de internet.
     *
     * El contenido de conversación pasa antes por el ratchet: cada mensaje
     * lleva su propia clave, que se destruye al usarse.
     */
    fun dispatch(
        recipient: Contact,
        kind: Kind,
        payload: ByteArray,
        groupId: String = "",
        ttlSeconds: Long = 0L,
        msgId: String = Envelope.newId(),
    ): Boolean {
        val body = if (kind.usesRatchet) {
            ensureRatchetSession(recipient) ?: return false
            ratchetSeal(recipient.fingerprint, payload) ?: return false
        } else {
            payload
        }
        return dispatchRaw(recipient, kind, body, groupId, ttlSeconds, msgId)
    }

    /** Envío sin ratchet: solo el sobre híbrido. */
    private fun dispatchRaw(
        recipient: Contact,
        kind: Kind,
        payload: ByteArray,
        groupId: String = "",
        ttlSeconds: Long = 0L,
        msgId: String = Envelope.newId(),
    ): Boolean {
        val parts = Envelope.build(
            senderFp = identity.fingerprint,
            nickname = store.getProfileName(),
            kind = kind,
            payload = payload,
            groupId = groupId,
            ttlSeconds = ttlSeconds,
            msgId = msgId,
        )
        return parts.all { part ->
            val sealed = sealMessage(recipient, part)
            var ok = lan?.send(recipient.fingerprint, sealed) ?: false
            if (!ok) {
                val today = System.currentTimeMillis() / 1000 / 86400
                val tag = rendezvousTag(identity, recipient, today.toULong())
                val event = makeTransportEvent(
                    sealed, tag, (System.currentTimeMillis() / 1000).toULong(),
                )
                ok = nostr?.send(event) ?: false
            }
            ok
        }
    }

    /** Envía a todos los miembros de un grupo. Basta con uno para darlo por enviado. */
    fun dispatchToGroup(
        group: Group,
        kind: Kind,
        payload: ByteArray,
        ttlSeconds: Long = 0L,
    ): Boolean {
        val recipients = group.recipients(myFingerprint)
        if (recipients.isEmpty()) return true
        val msgId = Envelope.newId()
        var anyOk = false
        recipients.forEach { member ->
            runCatching {
                if (dispatch(member.toContact(), kind, payload, group.id, ttlSeconds, msgId)) {
                    anyOk = true
                }
            }.onFailure { Log.w(TAG, "envío a ${member.fingerprint} falló: ${it.message}") }
        }
        return anyOk
    }

    /** Guarda un mensaje propio en el historial local. */
    fun recordOwn(chatId: String, kind: Kind, text: String, mediaId: String, durationMs: Long) {
        val ttl = prefs.effectiveTtl(chatId)
        messages.append(
            chatId,
            Msg(
                mine = true,
                timestamp = System.currentTimeMillis(),
                kind = kind,
                text = text,
                mediaId = mediaId,
                durationMs = durationMs,
                expiresAt = expiryFor(ttl),
                senderFp = myFingerprint,
            ),
        )
        notifyStateChanged()
    }

    private fun expiryFor(ttlSeconds: Long): Long =
        if (ttlSeconds <= 0) 0L else System.currentTimeMillis() + ttlSeconds * 1000

    // ---------- ratchet ----------

    /**
     * Garantiza que existe sesión con este contacto. Si no la hay, negocia una:
     * genera una semilla y se la manda **dentro del sobre híbrido**, que es de
     * donde el ratchet hereda la protección post-cuántica.
     */
    private fun ensureRatchetSession(recipient: Contact): Unit? {
        if (ratchets.has(recipient.fingerprint)) return Unit

        return runCatching {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val init = ratchetInitInitiator(seed)

            // La invitación lleva semilla + nuestra clave de ratchet inicial.
            val payload = seed + init.data
            if (!dispatchRaw(recipient, Kind.RATCHET_INIT, payload)) {
                error("no se pudo entregar el arranque de sesión")
            }
            ratchets.save(recipient.fingerprint, init.session)
            Unit
        }.onFailure {
            Log.w(TAG, "no se pudo iniciar sesión con ${recipient.fingerprint}: ${it.message}")
        }.getOrNull()
    }

    /** Cifra con el ratchet y persiste el estado avanzado. */
    private fun ratchetSeal(fingerprint: String, payload: ByteArray): ByteArray? =
        runCatching {
            ratchets.withSession(fingerprint) { session ->
                val out = ratchetEncrypt(session, payload)
                out.session to out.data
            }
        }.onFailure {
            Log.w(TAG, "fallo al cifrar con ratchet para $fingerprint: ${it.message}")
        }.getOrNull()

    /**
     * Descifra con el ratchet. Si falla, se descarta la sesión: el próximo
     * mensaje que enviemos negociará una nueva y la conversación se recupera
     * sola en vez de quedarse muda para siempre.
     */
    private fun ratchetOpen(fingerprint: String, payload: ByteArray): ByteArray? {
        val result = runCatching {
            ratchets.withSession(fingerprint) { session ->
                val out = ratchetDecrypt(session, payload)
                out.session to out.data
            }
        }.getOrNull()

        if (result == null) {
            Log.w(TAG, "sesión desincronizada con $fingerprint: se reiniciará")
            ratchets.clear(fingerprint)
        }
        return result
    }

    /**
     * Acepta el arranque de sesión del otro extremo.
     *
     * Si ambos inician a la vez, gana el de huella mayor: así los dos
     * convergen en la misma sesión en vez de quedarse cruzados.
     */
    private fun acceptRatchetInit(senderFp: String, payload: ByteArray) {
        if (payload.size != 64) return

        val theirsWins = senderFp.replace(" ", "") > myFingerprint.replace(" ", "")
        if (ratchets.has(senderFp) && !theirsWins) {
            Log.d(TAG, "arranque simultáneo: conservamos nuestra sesión con $senderFp")
            return
        }

        runCatching {
            val seed = payload.copyOfRange(0, 32)
            val theirRatchetPub = payload.copyOfRange(32, 64)
            ratchets.save(senderFp, ratchetInitResponder(seed, theirRatchetPub))
            Log.d(TAG, "sesión de ratchet establecida con $senderFp")
        }.onFailure { Log.w(TAG, "arranque de sesión inválido: ${it.message}") }
    }

    // ---------- recepción ----------

    private fun handleSealed(sealed: ByteArray) {
        try {
            val parsed = Envelope.parse(openMessage(identity, sealed)) ?: return

            // ¿Quién lo manda? Puede ser un contacto directo o un miembro de grupo.
            val entry = store.loadEntries()
                .firstOrNull { it.contact.fingerprint.replace(" ", "") == parsed.senderFp }
            val groupMember = groups.all()
                .flatMap { it.members }
                .firstOrNull { it.fingerprint.replace(" ", "") == parsed.senderFp }

            val senderContact = entry?.contact ?: groupMember?.toContact()

            // Bloqueo: se descarta antes de tocar nada, incluso el reensamblado.
            senderContact?.let { if (prefs.isBlocked(it.fingerprint)) return }

            // Reensamblar primero: una invitación a un grupo grande también se
            // trocea (cada miembro ocupa ~1,6 KB), así que no se puede procesar
            // fragmento a fragmento. Con total = 1 esto devuelve el dato al vuelo.
            val assembled = assembler.offer(parsed.msgId, parsed.index, parsed.total, parsed.payload)
                ?: return

            // Una invitación de grupo puede venir de alguien no guardado como
            // contacto todavía, pero el sobre ya prueba que conoce nuestra clave.
            // Para todo lo demás exigimos conocer al remitente: solo así tenemos
            // su huella canónica (con espacios), que es la que indexa todo.
            if (senderContact == null) {
                if (parsed.kind == Kind.GROUP_INVITE) {
                    handleGroupControl(parsed.kind, "", assembled)
                }
                return
            }

            val senderFp = senderContact.fingerprint
            val senderName = parsed.nickname.ifBlank {
                entry?.name ?: groupMember?.name.orEmpty()
            }

            // El remitente difunde su nickname en cada mensaje.
            if (entry != null && parsed.nickname.isNotBlank() && parsed.nickname != entry.name) {
                store.setContactName(entry.contact.fingerprint, parsed.nickname)
            }

            if (parsed.kind == Kind.RATCHET_INIT) {
                acceptRatchetInit(senderFp, assembled)
                drainPending(senderFp)
                return
            }

            if (!parsed.kind.usesRatchet) {
                route(parsed.kind, senderContact, senderFp, senderName, assembled, parsed)
                return
            }

            // El arranque de sesión y el primer mensaje viajan por separado, y
            // los relays no garantizan orden. Si el mensaje se adelanta, se
            // guarda hasta que llegue el arranque en vez de perderse.
            if (!ratchets.has(senderFp)) {
                bufferUntilSession(senderFp, Pending(parsed, senderContact, senderName, assembled))
                return
            }

            val payload = ratchetOpen(senderFp, assembled) ?: return
            route(parsed.kind, senderContact, senderFp, senderName, payload, parsed)
        } catch (e: Exception) {
            // Blob que no era para nosotros o corrupto: se ignora en silencio.
        }
    }

    /** Mensaje que llegó antes de tener sesión y espera al arranque. */
    private data class Pending(
        val parsed: Envelope.Parsed,
        val sender: Contact,
        val senderName: String,
        val payload: ByteArray,
    )

    private val pending = ConcurrentHashMap<String, MutableList<Pending>>()

    private fun bufferUntilSession(senderFp: String, item: Pending) {
        val queue = pending.getOrPut(senderFp) { mutableListOf() }
        synchronized(queue) {
            queue.add(item)
            // Acotado: un atacante no puede llenarnos la memoria mandando
            // mensajes de una sesión que nunca va a establecer.
            while (queue.size > MAX_PENDING) queue.removeAt(0)
        }
        Log.d(TAG, "mensaje en espera de sesión con $senderFp (${queue.size})")
    }

    /** Reprocesa lo que estaba esperando, ya con la sesión establecida. */
    private fun drainPending(senderFp: String) {
        val queue = pending.remove(senderFp) ?: return
        val items = synchronized(queue) { queue.toList() }
        items.forEach { item ->
            val payload = ratchetOpen(senderFp, item.payload) ?: return@forEach
            route(item.parsed.kind, item.sender, senderFp, item.senderName, payload, item.parsed)
        }
    }

    /** Entrega el payload ya descifrado a quien corresponda. */
    private fun route(
        kind: Kind,
        sender: Contact,
        senderFp: String,
        senderName: String,
        payload: ByteArray,
        parsed: Envelope.Parsed,
    ) {
        when {
            kind.isCallSignal -> listeners.forEach {
                it.onCallSignal(kind, sender, senderName, payload)
            }

            kind.isGroupControl -> handleGroupControl(kind, senderFp, payload)

            kind == Kind.AVATAR -> {
                store.setContactAvatar(senderFp, payload)
                notifyStateChanged()
            }

            kind.isVisibleMessage -> storeIncoming(parsed, senderFp, senderName, payload)
        }
    }

    private fun storeIncoming(
        parsed: Envelope.Parsed,
        senderFp: String,
        senderName: String,
        payload: ByteArray,
    ) {
        val isGroup = parsed.groupId.isNotBlank()
        val group = if (isGroup) groups.get(parsed.groupId) else null
        if (isGroup && group == null) return // grupo desconocido: ignorar

        val chatId = if (isGroup) parsed.groupId else senderFp
        val chatName = group?.name ?: senderName.ifBlank { "Sin nombre" }

        // El TTL viaja con el mensaje: se respeta el del emisor, o el nuestro
        // si es más corto (nunca guardamos algo más tiempo del que pidió nadie).
        val localTtl = prefs.effectiveTtl(chatId)
        val effectiveTtl = listOf(parsed.ttlSeconds, localTtl).filter { it > 0 }.minOrNull() ?: 0L

        val msg = when (parsed.kind) {
            Kind.TEXT -> Msg(
                mine = false,
                timestamp = System.currentTimeMillis(),
                kind = Kind.TEXT,
                text = String(payload, Charsets.UTF_8),
                expiresAt = expiryFor(effectiveTtl),
                senderFp = senderFp,
            )

            Kind.IMAGE -> Msg(
                mine = false,
                timestamp = System.currentTimeMillis(),
                kind = Kind.IMAGE,
                mediaId = vault.save(payload, "jpg"),
                expiresAt = expiryFor(effectiveTtl),
                senderFp = senderFp,
            )

            Kind.AUDIO -> {
                val mediaId = vault.save(payload, "m4a")
                Msg(
                    mine = false,
                    timestamp = System.currentTimeMillis(),
                    kind = Kind.AUDIO,
                    mediaId = mediaId,
                    durationMs = vault.decryptToCache(mediaId)
                        ?.let { AudioRecorder.durationOf(it) } ?: 0L,
                    expiresAt = expiryFor(effectiveTtl),
                    senderFp = senderFp,
                )
            }

            else -> return
        }

        messages.append(chatId, msg)
        listeners.forEach { it.onMessage(chatId, chatName, msg, isGroup) }
        notifyStateChanged()
    }

    // ---------- grupos ----------

    private fun handleGroupControl(kind: Kind, senderFp: String, payload: ByteArray) {
        when (kind) {
            Kind.GROUP_INVITE, Kind.GROUP_UPDATE -> {
                val incoming = groups.decodeFromWire(payload) ?: return
                // Nos tienen que haber incluido para aceptarlo.
                if (incoming.members.none { it.fingerprint == myFingerprint }) return
                groups.save(incoming)
                refreshSubscriptions()
                notifyStateChanged()
            }

            Kind.GROUP_LEAVE -> {
                val groupId = String(payload, Charsets.UTF_8).trim()
                groups.removeMember(groupId, senderFp)
                notifyStateChanged()
            }

            else -> Unit
        }
    }

    /** Crea un grupo y envía la invitación a todos los miembros. */
    fun createGroup(name: String, members: List<Member>): Group {
        val me = Member(
            fingerprint = myFingerprint,
            x25519Public = identity.x25519Public,
            mlkemPublic = identity.mlkemPublic,
            name = store.getProfileName(),
        )
        val group = Group(
            id = GroupStore.newGroupId(),
            name = name,
            members = (listOf(me) + members).distinctBy { it.fingerprint },
            createdBy = myFingerprint,
        )
        groups.save(group)
        broadcastGroup(group, Kind.GROUP_INVITE)
        refreshSubscriptions()
        notifyStateChanged()
        return group
    }

    /** Difunde el estado del grupo (alta o actualización) a todos sus miembros. */
    fun broadcastGroup(group: Group, kind: Kind = Kind.GROUP_UPDATE) {
        val payload = groups.encodeForWire(group)
        group.recipients(myFingerprint).forEach { member ->
            runCatching { dispatch(member.toContact(), kind, payload, group.id) }
        }
    }

    /** Sale de un grupo y avisa al resto. */
    fun leaveGroup(group: Group) {
        val payload = group.id.toByteArray(Charsets.UTF_8)
        group.recipients(myFingerprint).forEach { member ->
            runCatching { dispatch(member.toContact(), Kind.GROUP_LEAVE, payload, group.id) }
        }
        groups.delete(group.id)
        messages.clear(group.id)
        prefs.forget(group.id)
        notifyStateChanged()
    }

    // ---------- mantenimiento ----------

    /** Borra los mensajes caducados y los medios que quedaron huérfanos. */
    fun purgeExpired(): Int {
        val orphans = messages.purgeExpired()
        orphans.forEach { vault.delete(it) }
        if (orphans.isNotEmpty()) notifyStateChanged()
        return orphans.size
    }

    fun notifyStateChanged() = listeners.forEach { it.onStateChanged() }

    private companion object {
        const val TAG = "Messenger"

        /** Mensajes que se guardan por remitente mientras no hay sesión. */
        const val MAX_PENDING = 20
    }
}
