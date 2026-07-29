package com.privmsg.app

import android.content.Context
import android.util.Log
import uniffi.privmsg_core.Contact
import uniffi.privmsg_core.Identity
import uniffi.privmsg_core.inviteDecode
import uniffi.privmsg_core.inviteEncode
import uniffi.privmsg_core.makeTransportEvent
import uniffi.privmsg_core.openMessage
import uniffi.privmsg_core.personalTag
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

    /** Alguien nos agregó y quedó dado de alta automáticamente. */
    fun onContactAdded(contact: Contact) {}

    /** El otro extremo pidió borrar la conversación y se ha borrado. */
    fun onHistoryDeletedByPeer(chatId: String, chatName: String) {}
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
    val seen = SeenStore(appContext)

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
            context = appContext,
            tagsProvider = { rendezvousTags() },
            sinceProvider = { seen.lastProcessedSeconds },
            onSealedReceived = { sealed ->
                handleSealed(sealed)
                // Avanzar la marca para que la próxima reconexión no vuelva a
                // pedir lo mismo.
                nostr?.lastEventSeconds?.let { seen.lastProcessedSeconds = it }
            },
        ).also { it.start() }
    }

    fun stop() {
        lan?.stop(); lan = null
        nostr?.stop(); nostr = null
    }

    fun relaysOnline(): Boolean = nostr?.isOnline() == true

    fun peerOnLan(fingerprint: String): Boolean = lan?.peerVisible(fingerprint) == true

    fun refreshSubscriptions() = nostr?.resubscribe()

    /**
     * Al volver a primer plano se comprueba la conexión y se resuscribe.
     * Android puede haber congelado los sockets mientras la app estaba en
     * segundo plano sin llegar a cerrarlos, y entonces parecen vivos pero no
     * entregan nada.
     */
    fun onAppForegrounded() {
        val transport = nostr ?: return
        if (transport.isOnline()) transport.resubscribe() else transport.reconnectAll()
    }

    /**
     * Etiquetas que vigilamos en los relays.
     *
     * Una por pareja (de hoy y de ayer, porque rotan a medianoche) y, además,
     * **nuestro buzón personal**: sin él, alguien que nos agrega no tendría
     * forma de avisarnos, porque nosotros no sabríamos en qué etiqueta mirar.
     */
    private fun rendezvousTags(): List<String> {
        val today = System.currentTimeMillis() / 1000 / 86400
        val peers = (store.loadContacts() + groups.all().flatMap { group ->
            group.recipients(myFingerprint).map { it.toContact() }
        }).distinctBy { it.fingerprint }

        val pairTags = peers
            .filter { it.fingerprint != identity.fingerprint }
            .flatMap { contact ->
                listOf(today, today - 1).mapNotNull { day ->
                    runCatching { rendezvousTag(identity, contact, day.toULong()) }.getOrNull()
                }
            }

        val inbox = runCatching { personalTag(identity.x25519Public) }.getOrNull()
        return pairTags + listOfNotNull(inbox)
    }

    /**
     * Avisa a alguien de que lo hemos agregado, mandándole nuestra invitación.
     *
     * Va al buzón personal del destinatario porque todavía no nos conoce.
     * Sin esto, agregar a alguien sería unilateral: podríamos escribirle, pero
     * él no sabría dónde escuchar y nunca recibiría nada.
     */
    fun sendContactRequest(contact: Contact): Boolean {
        val inbox = runCatching { personalTag(contact.x25519Public) }.getOrNull() ?: return false
        val payload = inviteEncode(identity).toByteArray(Charsets.UTF_8)

        val parts = Envelope.build(
            senderFp = identity.fingerprint,
            nickname = store.getProfileName(),
            kind = Kind.CONTACT_REQUEST,
            payload = payload,
        )
        var ok = false
        parts.forEach { part ->
            runCatching {
                val sealed = sealMessage(contact, part)
                // Por WiFi local llega igual; por internet, al buzón personal.
                if (lan?.send(contact.fingerprint, sealed) == true) ok = true
                val event = makeTransportEvent(
                    sealed, inbox, (System.currentTimeMillis() / 1000).toULong(),
                )
                if (nostr?.send(event) == true) ok = true
            }
        }
        return ok
    }

    /**
     * Alguien nos agregó y nos manda su invitación: lo damos de alta para que
     * la conversación funcione en los dos sentidos.
     *
     * Solo puede llegar aquí quien tenga nuestra invitación, que es a quien se
     * la dimos a propósito. Aun así se avisa al usuario, que siempre puede
     * bloquear.
     */
    private fun handleContactRequest(payload: ByteArray) {
        val invite = String(payload, Charsets.UTF_8).trim()
        val contact = runCatching { inviteDecode(invite) }.getOrNull() ?: return
        if (contact.fingerprint == myFingerprint) return
        if (prefs.isBlocked(contact.fingerprint)) return

        val nuevo = store.addContact(contact)
        if (nuevo) {
            refreshSubscriptions()
            listeners.forEach { it.onContactAdded(contact) }
            Log.d(TAG, "contacto añadido por solicitud: ${contact.fingerprint}")
        }
        notifyStateChanged()
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
        val delivered = parts.all { part ->
            val sealed = sealMessage(recipient, part)
            var ok = lan?.send(recipient.fingerprint, sealed) ?: false
            val byLan = ok
            if (!ok) {
                val today = System.currentTimeMillis() / 1000 / 86400
                val tag = rendezvousTag(identity, recipient, today.toULong())
                val event = makeTransportEvent(
                    sealed, tag, (System.currentTimeMillis() / 1000).toULong(),
                )
                ok = nostr?.send(event) ?: false
            }
            Log.d(TAG, "envio $kind a ${recipient.fingerprint.take(9)}: " +
                if (ok) (if (byLan) "OK por WiFi" else "OK por relays") else "FALLO")
            ok
        }
        return delivered
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
    private fun ensureRatchetSession(recipient: Contact, force: Boolean = false): Unit? {
        if (!force && ratchets.has(recipient.fingerprint)) return Unit

        return runCatching {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val init = ratchetInitInitiator(seed)

            // semilla + clave de ratchet inicial, y un byte final que marca si
            // es una renegociación (ver acceptRatchetInit).
            val payload = seed + init.data + byteArrayOf(if (force) FORCE_FLAG else 0)
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

    /** Fallos de descifrado seguidos por contacto, para no reiniciar a la ligera. */
    private val decryptFailures = ConcurrentHashMap<String, Int>()

    /**
     * Descifra con el ratchet.
     *
     * Un fallo aislado **no** reinicia la sesión: puede ser un mensaje viejo
     * que se coló, o uno fuera de la ventana de claves guardadas. Reiniciar por
     * eso rompería una conversación sana, que es exactamente lo que pasaba.
     * Solo tras varios fallos seguidos se asume desincronización real.
     *
     * Y al asumirla, hay que **avisar al otro extremo**: si solo borráramos
     * nuestra sesión, él seguiría cifrando con la suya y nosotros esperando un
     * arranque que nunca llegaría. Eso deja la conversación muda para siempre.
     */
    private fun ratchetOpen(sender: Contact, payload: ByteArray): ByteArray? {
        val fingerprint = sender.fingerprint
        val result = runCatching {
            ratchets.withSession(fingerprint) { session ->
                val out = ratchetDecrypt(session, payload)
                out.session to out.data
            }
        }.getOrNull()

        if (result != null) {
            decryptFailures.remove(fingerprint)
            return result
        }

        val failures = decryptFailures.merge(fingerprint, 1, Int::plus) ?: 1
        if (failures >= MAX_DECRYPT_FAILURES) {
            Log.w(TAG, "sesión desincronizada con $fingerprint: se renegocia")
            ratchets.clear(fingerprint)
            decryptFailures.remove(fingerprint)
            renegotiate(sender)
        } else {
            Log.d(TAG, "mensaje ilegible de $fingerprint ($failures/$MAX_DECRYPT_FAILURES)")
        }
        return null
    }

    /** Último intento de renegociación por contacto, para no inundar. */
    private val lastRenegotiation = ConcurrentHashMap<String, Long>()

    /**
     * Arranca una sesión nueva con alguien, aunque ya tuviéramos una.
     *
     * Es lo que rompe el bloqueo mutuo: quien detecta que no puede leer toma
     * la iniciativa en vez de esperar. Va limitado en frecuencia para que dos
     * extremos confundidos no se pasen la vida renegociando.
     */
    private fun renegotiate(contact: Contact) {
        val now = System.currentTimeMillis()
        val last = lastRenegotiation[contact.fingerprint] ?: 0L
        if (now - last < RENEGOTIATE_COOLDOWN_MS) return
        lastRenegotiation[contact.fingerprint] = now

        runCatching {
            ensureRatchetSession(contact, force = true)
        }.onFailure { Log.w(TAG, "no se pudo renegociar: ${it.message}") }
    }

    /**
     * Acepta el arranque de sesión del otro extremo.
     *
     * Si ambos inician a la vez, gana el de huella mayor: así los dos
     * convergen en la misma sesión en vez de quedarse cruzados.
     */
    private fun acceptRatchetInit(senderFp: String, payload: ByteArray) {
        if (payload.size < 64) return

        // Una renegociación se acepta siempre: el otro nos está diciendo que no
        // puede leernos. Si la rechazáramos por la regla anti-empate, los dos
        // nos quedaríamos esperando y la conversación no se recuperaría nunca.
        val forced = payload.size > 64 && payload[64] == FORCE_FLAG
        val theirsWins = senderFp.replace(" ", "") > myFingerprint.replace(" ", "")
        if (!forced && ratchets.has(senderFp) && !theirsWins) {
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

            // Antes que nada: descartar reenvíos. Los relays reentregan lo ya
            // recibido al reconectar, y dejar que un mensaje viejo llegue al
            // ratchet tumbaría una sesión que funciona.
            //
            // Se deduplica por fragmento, no por mensaje: así un archivo
            // troceado también se descarta entero si vuelve a llegar.
            val fragmentId = "${parsed.senderFp}:${parsed.msgId}:${parsed.index}"
            if (seen.isDuplicate(fragmentId)) {
                Log.d(TAG, "descartado repetido ${parsed.kind} de ${parsed.senderFp.take(9)}")
                return
            }
            Log.d(TAG, "recibido ${parsed.kind} de ${parsed.senderFp.take(9)}")

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
                // Estos dos son los unicos que aceptamos de alguien que aun no
                // tenemos guardado: ambos vienen sellados con nuestra clave
                // publica, asi que quien los manda ya tenia nuestra invitacion.
                when (parsed.kind) {
                    Kind.CONTACT_REQUEST -> handleContactRequest(assembled)
                    Kind.GROUP_INVITE -> handleGroupControl(parsed.kind, "", assembled)
                    else -> Unit
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

            // Ya lo teniamos guardado: su solicitud solo confirma el alta mutua.
            if (parsed.kind == Kind.CONTACT_REQUEST) {
                handleContactRequest(assembled)
                return
            }

            if (parsed.kind == Kind.DELETE_HISTORY) {
                val chatId = parsed.groupId.ifBlank { senderFp }
                val chatName = groups.get(parsed.groupId)?.name
                    ?: senderName.ifBlank { "Sin nombre" }
                clearHistory(chatId)
                // Se avisa a propósito: que te borren tu copia sin que lo sepas
                // sería peor que no poder borrarla.
                listeners.forEach { it.onHistoryDeletedByPeer(chatId, chatName) }
                Log.d(TAG, "historial de $chatId borrado a petición del otro extremo")
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
                // Si el arranque no llega (se perdió, o el otro cree tener una
                // sesión que nosotros ya no tenemos), tomamos la iniciativa.
                renegotiate(senderContact)
                return
            }

            val payload = ratchetOpen(senderContact, assembled) ?: return
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
            val payload = ratchetOpen(item.sender, item.payload) ?: return@forEach
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

    /**
     * Borra el historial de una conversación en **este** dispositivo:
     * mensajes, fotos y audios. Conserva el contacto y la sesión de cifrado,
     * así que se puede seguir hablando.
     */
    fun clearHistory(chatId: String) {
        messages.load(chatId)
            .mapNotNull { it.mediaId.takeIf(String::isNotEmpty) }
            .forEach { vault.delete(it) }
        messages.clear(chatId)
        notifyStateChanged()
    }

    /**
     * Pide al otro extremo que borre también su copia de la conversación.
     *
     * Funciona porque ejecuta esta misma app y decide obedecer. Contra un
     * cliente modificado, o contra una captura de pantalla ya hecha, no hay
     * nada que hacer: eso no lo resuelve ninguna mensajería.
     */
    fun requestRemoteDeletion(chatId: String): Boolean {
        val group = groups.get(chatId)
        if (group != null) {
            return dispatchToGroupRaw(group, Kind.DELETE_HISTORY, ByteArray(0))
        }
        val contact = store.loadContacts().firstOrNull { it.fingerprint == chatId }
            ?: return false
        return dispatchRaw(contact, Kind.DELETE_HISTORY, ByteArray(0))
    }

    /** Difunde una orden de control a todos los miembros, sin pasar por el ratchet. */
    private fun dispatchToGroupRaw(group: Group, kind: Kind, payload: ByteArray): Boolean {
        var anyOk = false
        group.recipients(myFingerprint).forEach { member ->
            runCatching {
                if (dispatchRaw(member.toContact(), kind, payload, group.id)) anyOk = true
            }
        }
        return anyOk
    }

    /**
     * Elimina una conversación por completo: mensajes, medios, sesión de
     * cifrado, ajustes y —si es un contacto directo— la propia entrada de la
     * agenda. Un grupo se abandona avisando al resto.
     *
     * Solo afecta a este dispositivo: lo que el otro tenga en el suyo no se
     * puede borrar desde aquí, y la app no finge lo contrario.
     */
    fun deleteChat(chatId: String) {
        val group = groups.get(chatId)
        if (group != null) {
            leaveGroup(group)
            return
        }

        // Borrar también los archivos, no solo las referencias.
        messages.load(chatId)
            .mapNotNull { it.mediaId.takeIf(String::isNotEmpty) }
            .forEach { vault.delete(it) }

        messages.clear(chatId)
        ratchets.clear(chatId)
        prefs.forget(chatId)
        if (chatId != myFingerprint) store.removeContact(chatId)

        refreshSubscriptions()
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

        /** Fallos seguidos antes de dar una sesión por perdida. */
        const val MAX_DECRYPT_FAILURES = 5

        /** Marca de "renegociación forzada" al final del arranque de sesión. */
        const val FORCE_FLAG: Byte = 1

        /** Espera mínima entre renegociaciones con el mismo contacto. */
        const val RENEGOTIATE_COOLDOWN_MS = 30_000L
    }
}
