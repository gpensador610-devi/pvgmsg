package com.privmsg.app

import android.graphics.Bitmap
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.privmsg_core.Contact
import uniffi.privmsg_core.CoreException
import uniffi.privmsg_core.Identity
import uniffi.privmsg_core.generateMnemonic
import uniffi.privmsg_core.identityFromMnemonic
import uniffi.privmsg_core.inviteDecode
import uniffi.privmsg_core.inviteEncode
import uniffi.privmsg_core.validateMnemonic

/** Copia de seguridad esperando a que el usuario escriba la contraseña. */
private data class BackupPrompt(val uri: Uri, val exporting: Boolean)

/** Pantalla actualmente visible. */
private sealed interface Screen {
    data object ChatList : Screen
    data object Settings : Screen
    data object NewGroup : Screen
    data class Chat(val chatId: String) : Screen
    data class ChatSettings(val chatId: String) : Screen
}

// FragmentActivity (no ComponentActivity) porque BiometricPrompt lo exige.
class MainActivity : FragmentActivity() {

    private lateinit var messenger: Messenger
    private lateinit var recorder: AudioRecorder
    private lateinit var calls: CallManager
    private lateinit var appLock: AppLock

    /** ¿Hay que pedir PIN/huella antes de mostrar nada? */
    private val locked = mutableStateOf(false)

    /** Instante en que la app pasó a segundo plano, para el bloqueo automático. */
    private var backgroundedAt = 0L
    private val player = AudioPlayer()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val identity: Identity get() = messenger.identity
    private val store: IdentityStore get() = messenger.store
    private val msgStore: MessageStore get() = messenger.messages
    private val prefs: ChatPrefs get() = messenger.prefs
    private val vault: MediaVault get() = messenger.vault

    /** Contador que fuerza recomposición al cambiar el estado. */
    private val refreshTick = mutableLongStateOf(0L)
    private val playingMediaId = mutableStateOf<String?>(null)
    private val recordingSince = mutableStateOf<Long?>(null)

    private var onContactScanned: ((Contact) -> Unit)? = null
    private var pendingAttachTarget: String? = null
    private var pickingProfilePhoto = false
    private var pendingCallAction: (() -> Unit)? = null
    private var soundTargetChatId: String? = null

    private val uiListener = object : MessengerListener {
        override fun onStateChanged() = runOnUiThread { refreshTick.longValue++ }

        override fun onMessage(chatId: String, chatName: String, msg: Msg, isGroup: Boolean) =
            runOnUiThread { refreshTick.longValue++ }

        // El enrutado de las señales de llamada lo hace PrivMsgService, que
        // sigue vivo aunque esta pantalla no exista.
    }

    // ---------- launchers ----------

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        try {
            onContactScanned?.invoke(inviteDecode(text))
        } catch (e: CoreException) {
            toast("QR inválido: no es una invitación PrivMsg")
        }
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val targetChat = pendingAttachTarget
        val isProfile = pickingProfilePhoto
        pendingAttachTarget = null
        pickingProfilePhoto = false
        if (uri == null) return@registerForActivityResult

        ioScope.launch {
            if (isProfile) {
                val jpeg = MediaVault.compressImage(
                    this@MainActivity, uri, MediaVault.AVATAR_MAX_SIDE, MediaVault.AVATAR_QUALITY,
                ) ?: run { toast("No se pudo leer la imagen"); return@launch }
                store.setProfileAvatar(jpeg)
                store.clearAvatarSent()
                broadcastAvatar(jpeg)
                messenger.notifyStateChanged()
            } else {
                val chatId = targetChat ?: return@launch
                val jpeg = MediaVault.compressImage(
                    this@MainActivity, uri, MediaVault.PHOTO_MAX_SIDE, MediaVault.PHOTO_QUALITY,
                ) ?: run { toast("No se pudo leer la imagen"); return@launch }
                sendMedia(chatId, Kind.IMAGE, jpeg, "jpg", 0L)
            }
        }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callAction = pendingCallAction
        pendingCallAction = null
        when {
            !granted -> toast("Permiso de micrófono denegado")
            callAction != null -> callAction()
            else -> startRecording()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* si lo deniega, la app funciona igual pero sin avisos */ }

    /** Contraseña pendiente para la copia de seguridad en curso. */
    private val backupPasswordPrompt = mutableStateOf<BackupPrompt?>(null)

    private val backupExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        backupPasswordPrompt.value = BackupPrompt(uri, exporting = true)
    }

    private val backupImporter = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        backupPasswordPrompt.value = BackupPrompt(uri, exporting = false)
    }

    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val chatId = soundTargetChatId
        soundTargetChatId = null
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val value = uri?.toString().orEmpty()
        if (chatId == null) {
            prefs.setDefaultSound(value)
            NotificationHelper.ensureChannels(this, prefs)
        } else {
            prefs.setSound(chatId, value)
        }
        refreshTick.longValue++
    }

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = AudioRecorder(applicationContext)
        appLock = AppLock(applicationContext)
        locked.value = appLock.isEnabled

        val existing = IdentityStore(applicationContext).loadIdentity()
        if (existing != null) {
            bootWith(existing)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        locked.value -> UnlockGate()
                        ::messenger.isInitialized -> Root()
                        else -> Onboarding()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.appInForeground = true
        applyScreenSecurity()

        // Bloqueo automático: si estuvo fuera más de lo configurado, se cierra.
        if (appLock.isEnabled && !locked.value && backgroundedAt > 0) {
            if (System.currentTimeMillis() - backgroundedAt >= appLock.timeout.millis) {
                locked.value = true
            }
        }
        backgroundedAt = 0L
    }

    /** Pantalla de bloqueo: nada del contenido se ve hasta pasar de aquí. */
    @Composable
    private fun UnlockGate() {
        var attemptTick by remember { mutableStateOf(0) }
        val remaining = remember(attemptTick) { appLock.remainingLockMillis() }

        LockScreen(
            settingUp = false,
            biometricAvailable = appLock.biometricEnabled && Biometrics.available(this),
            lockedUntilMillis = remaining,
            onSubmit = { pin ->
                when {
                    // El PIN de coacción se mira primero y no dice nada: borra
                    // y reinicia como si fuera una instalación nueva.
                    appLock.matchesDuress(pin) -> SecureWipe.wipeAndRestart(this)

                    appLock.verifyPin(pin) -> locked.value = false

                    else -> {
                        attemptTick++
                        toast(
                            if (appLock.remainingLockMillis() > 0) "Demasiados intentos fallidos"
                            else "PIN incorrecto",
                        )
                    }
                }
            },
            onBiometric = {
                Biometrics.prompt(
                    activity = this,
                    onSuccess = {
                        appLock.registerBiometricSuccess()
                        locked.value = false
                    },
                    onFallbackToPin = { /* se queda en el teclado numérico */ },
                )
            },
        )
    }

    /**
     * FLAG_SECURE: las capturas salen en negro y la vista previa de "recientes"
     * queda oculta. También bloquea la grabación de pantalla y que apps de
     * accesibilidad lean el contenido.
     *
     * Ojo: no impide que alguien fotografíe la pantalla con otro móvil.
     * Ninguna app puede evitar eso.
     */
    private fun applyScreenSecurity() {
        if (!::messenger.isInitialized) return
        if (prefs.screenSecurity()) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onPause() {
        AppState.appInForeground = false
        backgroundedAt = System.currentTimeMillis()
        super.onPause()
    }

    override fun onDestroy() {
        // La llamada NO se corta aquí: vive en AppState y sobrevive a la Activity.
        if (::messenger.isInitialized) messenger.removeListener(uiListener)
        player.stop()
        recorder.cancel()
        if (::messenger.isInitialized) vault.clearPlaybackCache()
        AppState.appInForeground = false
        AppState.visibleChatId = null
        ioScope.cancel()
        super.onDestroy()
    }

    /** Arranca el motor con una identidad ya disponible. */
    private fun bootWith(identity: Identity) {
        messenger = AppState.messenger?.takeIf { it.myFingerprint == identity.fingerprint }
            ?: Messenger(applicationContext, identity).also { AppState.messenger = it }
        messenger.addListener(uiListener)
        messenger.start()

        calls = AppState.calls ?: CallManager(applicationContext) { contact, kind, payload ->
            runCatching { messenger.dispatch(contact, kind, payload) }.getOrDefault(false)
        }.also { AppState.calls = it }

        NotificationHelper.ensureChannels(this, prefs)
        applyScreenSecurity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        PrivMsgService.start(this)
        messenger.purgeExpired()
    }

    // ---------- alta inicial ----------

    @Composable
    private fun Onboarding() {
        var step by remember { mutableStateOf<OnboardStep>(OnboardStep.Welcome) }
        OnboardingScreen(
            step = step,
            onCreateNew = { step = OnboardStep.ShowPhrase(generateMnemonic()) },
            onGoRestore = { step = OnboardStep.Restore },
            onBackToWelcome = { step = OnboardStep.Welcome },
            onConfirmPhrase = { adoptIdentity(it) },
            onRestore = {
                if (validateMnemonic(it)) adoptIdentity(it)
                else toast("Frase inválida: revisa las palabras y su orden")
            },
        )
    }

    private fun adoptIdentity(phrase: String) {
        try {
            val derived = identityFromMnemonic(phrase)
            IdentityStore(applicationContext).saveIdentity(derived, phrase)
            bootWith(derived)
            setContent {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) { Root() }
                }
            }
        } catch (e: Exception) {
            toast("No se pudo crear la identidad: ${e.message}")
        }
    }

    // ---------- envío ----------

    /** ¿Es este chatId un grupo? */
    private fun groupOf(chatId: String): Group? = messenger.groups.get(chatId)

    private fun contactOf(chatId: String): Contact? =
        store.loadEntries().firstOrNull { it.contact.fingerprint == chatId }?.contact

    private fun sendText(chatId: String, text: String) {
        ioScope.launch {
            try {
                val ttl = prefs.effectiveTtl(chatId)
                val payload = text.toByteArray(Charsets.UTF_8)
                val group = groupOf(chatId)

                val delivered = when {
                    group != null -> messenger.dispatchToGroup(group, Kind.TEXT, payload, ttl)
                    chatId == identity.fingerprint -> true // Nota para mí
                    else -> {
                        val contact = contactOf(chatId) ?: return@launch
                        ensureAvatarSent(contact)
                        messenger.dispatch(contact, Kind.TEXT, payload, ttlSeconds = ttl)
                    }
                }
                if (delivered) {
                    messenger.recordOwn(chatId, Kind.TEXT, text, "", 0L)
                } else {
                    toast("Sin conexión: mensaje no enviado")
                }
            } catch (e: Exception) {
                toast("Error al cifrar: ${e.message}")
            }
        }
    }

    private fun sendMedia(chatId: String, kind: Kind, bytes: ByteArray, ext: String, durationMs: Long) {
        try {
            val ttl = prefs.effectiveTtl(chatId)
            val mediaId = vault.save(bytes, ext)
            val group = groupOf(chatId)

            val delivered = when {
                group != null -> messenger.dispatchToGroup(group, kind, bytes, ttl)
                chatId == identity.fingerprint -> true
                else -> {
                    val contact = contactOf(chatId) ?: return
                    ensureAvatarSent(contact)
                    messenger.dispatch(contact, kind, bytes, ttlSeconds = ttl)
                }
            }
            if (delivered) {
                messenger.recordOwn(chatId, kind, "", mediaId, durationMs)
            } else {
                vault.delete(mediaId)
                toast("Sin conexión: no se pudo enviar")
            }
        } catch (e: Exception) {
            toast("Error al enviar: ${e.message}")
        }
    }

    /** La primera vez que escribimos a alguien, le mandamos nuestra foto. */
    private fun ensureAvatarSent(contact: Contact) {
        if (contact.fingerprint == identity.fingerprint) return
        if (contact.fingerprint in store.avatarSentTo()) return
        val avatar = store.getProfileAvatar() ?: return
        if (messenger.dispatch(contact, Kind.AVATAR, avatar)) {
            store.markAvatarSent(contact.fingerprint)
        }
    }

    private fun broadcastAvatar(jpeg: ByteArray) {
        store.loadContacts()
            .filter { it.fingerprint != identity.fingerprint }
            .forEach { contact ->
                runCatching {
                    if (messenger.dispatch(contact, Kind.AVATAR, jpeg)) {
                        store.markAvatarSent(contact.fingerprint)
                    }
                }
            }
    }

    // ---------- medios ----------

    private fun pickPhotoFor(chatId: String) {
        pendingAttachTarget = chatId
        pickingProfilePhoto = false
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun pickProfilePhoto() {
        pendingAttachTarget = null
        pickingProfilePhoto = true
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun pickSound(chatId: String?) {
        soundTargetChatId = chatId
        val current = if (chatId == null) prefs.defaultSound() else prefs.settings(chatId).soundUri
        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Tono de notificación")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            if (current.isNotBlank()) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
            }
        }
        runCatching { ringtonePicker.launch(intent) }
            .onFailure { toast("No hay selector de tonos en este dispositivo") }
    }

    private fun toggleRecording(chatId: String) {
        if (recorder.isRecording) {
            val result = recorder.stop()
            recordingSince.value = null
            if (result == null) {
                toast("Grabación demasiado corta")
                return
            }
            val (bytes, duration) = result
            ioScope.launch { sendMedia(chatId, Kind.AUDIO, bytes, "m4a", duration) }
        } else {
            pendingAttachTarget = chatId
            withMicPermission { startRecording() }
        }
    }

    private fun startRecording() {
        if (recorder.start()) recordingSince.value = recorder.startedAt
        else toast("No se pudo iniciar la grabación")
    }

    private fun playAudio(msg: Msg) {
        if (msg.mediaId.isEmpty()) return
        val file = vault.decryptToCache(msg.mediaId) ?: run { toast("Audio no disponible"); return }
        player.toggle(msg.mediaId, file) { runOnUiThread { playingMediaId.value = null } }
        playingMediaId.value = player.currentId
    }

    // ---------- llamadas ----------

    private fun withMicPermission(action: () -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCallAction = action
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCallWithPermission(contact: Contact, name: String) = withMicPermission {
        if (!calls.startCall(contact, name)) toast("No se pudo iniciar la llamada")
    }

    private fun acceptCallWithPermission() = withMicPermission {
        NotificationHelper.cancelCall(this)
        calls.acceptCall()
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }


    // ---------- UI ----------

    @Composable
    private fun Root() {
        var screen by remember { mutableStateOf<Screen>(Screen.ChatList) }
        var showQr by remember { mutableStateOf(false) }
        var showMnemonic by remember { mutableStateOf(false) }
        var editingProfile by remember { mutableStateOf(false) }
        var namingFingerprint by remember { mutableStateOf<String?>(null) }
        var settingUpPin by remember { mutableStateOf(false) }
        var settingUpDuress by remember { mutableStateOf(false) }
        var pastingInvite by remember { mutableStateOf(false) }
        val tick by refreshTick

        // Crear o cambiar PIN: tapa la pantalla igual que el desbloqueo.
        if (settingUpPin) {
            LockScreen(
                settingUp = true,
                biometricAvailable = false,
                lockedUntilMillis = 0L,
                onSubmit = { pin ->
                    if (appLock.setPin(pin)) {
                        settingUpPin = false
                        toast("Bloqueo activado")
                        refreshTick.longValue++
                    } else {
                        toast("PIN inválido o igual al de emergencia")
                    }
                },
                onBiometric = {},
                onCancelSetup = { settingUpPin = false },
            )
            return
        }

        if (settingUpDuress) {
            LockScreen(
                settingUp = true,
                biometricAvailable = false,
                lockedUntilMillis = 0L,
                duressSetup = true,
                onSubmit = { pin ->
                    if (appLock.setDuressPin(pin)) {
                        settingUpDuress = false
                        toast("PIN de emergencia guardado")
                        refreshTick.longValue++
                    } else {
                        toast("PIN inválido o igual al normal")
                    }
                },
                onBiometric = {},
                onCancelSetup = { settingUpDuress = false },
            )
            return
        }

        // Asistente de permisos: solo la primera vez, y solo si hace falta.
        val oemGuide = remember { BackgroundPermissions.guide() }
        var setupStep by remember {
            mutableStateOf(
                when {
                    prefs.backgroundSetupDone() -> null
                    !BackgroundPermissions.isExempt(this@MainActivity) -> BackgroundSetupStep.BATTERY
                    oemGuide != null -> BackgroundSetupStep.AUTOSTART
                    else -> null
                },
            )
        }

        LaunchedEffect(Unit) {
            while (true) {
                delay(2500)
                refreshTick.longValue++
            }
        }

        val entries = remember(tick) { store.loadEntries() }
        val groups = remember(tick) { messenger.groups.all() }
        val profileName = remember(tick) { store.getProfileName() }

        // Una llamada tapa cualquier otra pantalla.
        val callState = calls.state.value
        if (callState !is CallState.Idle) {
            val callFp = when (callState) {
                is CallState.Outgoing -> callState.contact.fingerprint
                is CallState.Incoming -> callState.contact.fingerprint
                is CallState.Active -> callState.contact.fingerprint
                else -> ""
            }
            CallScreen(
                state = callState,
                fingerprint = callFp,
                photo = entries.firstOrNull { it.contact.fingerprint == callFp }?.avatar,
                muted = calls.muted.value,
                speakerOn = calls.speakerOn.value,
                connected = calls.peerConnected.value,
                onAccept = { acceptCallWithPermission() },
                onHangup = { NotificationHelper.cancelCall(this); calls.endCall() },
                onToggleMute = { calls.toggleMute() },
                onToggleSpeaker = { calls.toggleSpeaker() },
            )
            return
        }

        AppState.visibleChatId = (screen as? Screen.Chat)?.chatId

        when (val current = screen) {
            is Screen.ChatList -> ChatListScreen(
                rows = remember(tick) { buildChatRows(entries, groups) },
                onOpenChat = {
                    NotificationHelper.cancelChat(this, it)
                    screen = Screen.Chat(it)
                },
                onRenameContact = { namingFingerprint = it },
                onShowQr = { showQr = true },
                onScan = { launchScanner { fp -> namingFingerprint = fp } },
                onNewGroup = { screen = Screen.NewGroup },
                onPasteInvite = { pastingInvite = true },
                onSettings = { screen = Screen.Settings },
            )

            is Screen.NewGroup -> NewGroupScreen(
                contacts = entries,
                onCreate = { name, selected ->
                    val members = selected.map { Member.of(it.contact, it.name) }
                    val group = messenger.createGroup(name, members)
                    screen = Screen.Chat(group.id)
                },
                onBack = { screen = Screen.ChatList },
            )

            is Screen.Settings -> SettingsScreen(
                profileName = profileName,
                profilePhoto = remember(tick) { store.getProfileAvatar() },
                fingerprint = identity.fingerprint,
                relaysOnline = messenger.relaysOnline(),
                hasMnemonic = store.getMnemonic() != null,
                defaultSoundSet = prefs.defaultSound().isNotBlank(),
                defaultTtl = Ttl.fromSeconds(prefs.defaultTtlSeconds()),
                blockedCount = prefs.blocked().size,
                batteryOptimized = remember(tick) { !BackgroundPermissions.isExempt(this@MainActivity) },
                oemGuide = remember { BackgroundPermissions.guide() },
                onOpenOemAutostart = { BackgroundPermissions.openOemScreen(this) },
                screenSecurity = prefs.screenSecurity(),
                lockEnabled = remember(tick) { appLock.isEnabled },
                lockTimeout = remember(tick) { appLock.timeout },
                biometricEnabled = remember(tick) { appLock.biometricEnabled },
                biometricAvailable = remember { Biometrics.available(this@MainActivity) },
                onToggleScreenSecurity = {
                    prefs.setScreenSecurity(!prefs.screenSecurity())
                    applyScreenSecurity()
                    refreshTick.longValue++
                },
                onToggleLock = {
                    if (appLock.isEnabled) {
                        appLock.disable()
                        toast("Bloqueo desactivado")
                        refreshTick.longValue++
                    } else {
                        settingUpPin = true
                    }
                },
                onChangePin = { settingUpPin = true },
                onSetLockTimeout = {
                    appLock.timeout = it
                    refreshTick.longValue++
                },
                onToggleBiometric = {
                    appLock.biometricEnabled = !appLock.biometricEnabled
                    refreshTick.longValue++
                },
                duressPinSet = remember(tick) { appLock.hasDuressPin },
                onSetDuressPin = { settingUpDuress = true },
                onClearDuressPin = {
                    appLock.clearDuressPin()
                    toast("PIN de emergencia eliminado")
                    refreshTick.longValue++
                },
                onSetDefaultTtl = {
                    prefs.setDefaultTtl(it.seconds)
                    refreshTick.longValue++
                },
                onRequestBatteryExemption = { BackgroundPermissions.requestExemption(this) },
                onEditProfile = { editingProfile = true },
                onChangePhoto = { pickProfilePhoto() },
                onShowQr = { showQr = true },
                onShowMnemonic = { showMnemonic = true },
                onPickDefaultSound = { pickSound(null) },
                onExportBackup = {
                    runCatching { backupExporter.launch(BackupManager.suggestedFileName()) }
                        .onFailure { toast("No hay gestor de archivos disponible") }
                },
                onImportBackup = {
                    runCatching { backupImporter.launch(arrayOf("*/*")) }
                        .onFailure { toast("No hay gestor de archivos disponible") }
                },
                onBack = { screen = Screen.ChatList },
            )

            is Screen.ChatSettings -> {
                val chatId = current.chatId
                val group = groups.firstOrNull { it.id == chatId }
                val entry = entries.firstOrNull { it.contact.fingerprint == chatId }
                val isSelf = chatId == identity.fingerprint
                ChatSettingsScreen(
                    title = when {
                        isSelf -> "Nota para mí"
                        group != null -> group.name
                        else -> entry?.name?.ifBlank { "Sin nombre" } ?: "Chat"
                    },
                    fingerprint = chatId,
                    photo = if (isSelf) store.getProfileAvatar() else group?.avatar ?: entry?.avatar,
                    isGroup = group != null,
                    canBlock = !isSelf && group == null,
                    members = group?.members.orEmpty(),
                    settings = prefs.settings(chatId),
                    blocked = prefs.isBlocked(chatId),
                    onSetTtl = { prefs.setTtl(chatId, it.seconds); refreshTick.longValue++ },
                    onToggleMute = {
                        prefs.setMuted(chatId, !prefs.settings(chatId).muted)
                        refreshTick.longValue++
                    },
                    onPickSound = { pickSound(chatId) },
                    onToggleBlock = {
                        if (prefs.isBlocked(chatId)) prefs.unblock(chatId) else prefs.block(chatId)
                        refreshTick.longValue++
                    },
                    onLeaveGroup = {
                        group?.let { messenger.leaveGroup(it) }
                        screen = Screen.ChatList
                    },
                    onClearHistory = {
                        msgStore.clear(chatId)
                        refreshTick.longValue++
                        toast("Historial borrado")
                    },
                    onBack = { screen = Screen.Chat(chatId) },
                )
            }

            is Screen.Chat -> {
                val chatId = current.chatId
                val group = groups.firstOrNull { it.id == chatId }
                val entry = entries.firstOrNull { it.contact.fingerprint == chatId }
                val isSelf = chatId == identity.fingerprint

                if (group == null && entry == null && !isSelf) {
                    screen = Screen.ChatList
                } else {
                    val title = when {
                        isSelf -> "Nota para mí"
                        group != null -> group.name
                        else -> entry?.name?.ifBlank { "Sin nombre" } ?: "Sin nombre"
                    }
                    ChatScreen(
                        title = title,
                        subtitle = if (group != null) "${group.members.size} miembros" else chatId,
                        avatarPhoto = if (isSelf) store.getProfileAvatar() else group?.avatar ?: entry?.avatar,
                        messages = remember(tick, chatId) { msgStore.load(chatId) },
                        vault = vault,
                        playingMediaId = playingMediaId.value,
                        recordingSince = recordingSince.value,
                        canCall = !isSelf && group == null,
                        isGroup = group != null,
                        senderNameOf = { fp -> group?.memberName(fp)?.ifBlank { "?" } ?: "" },
                        ttlLabel = prefs.settings(chatId).effectiveTtl
                            .takeIf { it != Ttl.OFF }?.label,
                        onSend = { sendText(chatId, it) },
                        onAttachPhoto = { pickPhotoFor(chatId) },
                        onToggleRecord = { toggleRecording(chatId) },
                        onPlayAudio = { playAudio(it) },
                        onCall = {
                            entry?.let { startCallWithPermission(it.contact, title) }
                        },
                        onOpenSettings = { screen = Screen.ChatSettings(chatId) },
                        onBack = { screen = Screen.ChatList },
                    )
                }
            }
        }

        // ---- diálogos globales ----

        if (editingProfile) {
            NameDialog(
                title = "Tu nickname",
                explanation = "Viaja cifrado dentro de cada mensaje. Los relays nunca lo ven.",
                initial = profileName,
                onDismiss = { editingProfile = false },
                onConfirm = {
                    store.setProfileName(it)
                    editingProfile = false
                    refreshTick.longValue++
                },
            )
        }

        namingFingerprint?.let { fp ->
            NameDialog(
                title = "Nombre del contacto",
                explanation = "Solo para ti. Se actualizará cuando esta persona te escriba con su propio nickname.\n\nHuella: $fp",
                initial = entries.firstOrNull { it.contact.fingerprint == fp }?.name.orEmpty(),
                onDismiss = { namingFingerprint = null },
                onConfirm = {
                    store.setContactName(fp, it)
                    namingFingerprint = null
                    refreshTick.longValue++
                },
            )
        }

        if (showMnemonic) {
            MnemonicDialog(store.getMnemonic()) { showMnemonic = false }
        }

        if (showQr) {
            QrDialog { showQr = false }
        }

        if (pastingInvite) {
            PasteInviteDialog(
                onDismiss = { pastingInvite = false },
                onConfirm = { text ->
                    try {
                        val contact = inviteDecode(text.trim())
                        if (store.addContact(contact)) {
                            messenger.refreshSubscriptions()
                            pastingInvite = false
                            namingFingerprint = contact.fingerprint
                        } else {
                            toast("Ese contacto ya existe")
                            pastingInvite = false
                        }
                        refreshTick.longValue++
                    } catch (e: CoreException) {
                        toast("Invitación inválida: revisa que esté completa")
                    }
                },
            )
        }

        setupStep?.let { step ->
            BackgroundSetupDialog(
                step = step,
                guide = oemGuide,
                onAccept = {
                    when (step) {
                        BackgroundSetupStep.BATTERY -> {
                            BackgroundPermissions.requestExemption(this)
                            // Tras la batería, las capas agresivas piden un paso más.
                            setupStep = if (oemGuide != null) {
                                BackgroundSetupStep.AUTOSTART
                            } else {
                                prefs.markBackgroundSetupDone()
                                null
                            }
                        }
                        BackgroundSetupStep.AUTOSTART -> {
                            BackgroundPermissions.openOemScreen(this)
                            prefs.markBackgroundSetupDone()
                            setupStep = null
                        }
                    }
                },
                onSkip = {
                    prefs.markBackgroundSetupDone()
                    setupStep = null
                },
            )
        }

        backupPasswordPrompt.value?.let { prompt ->
            PasswordDialog(
                exporting = prompt.exporting,
                onDismiss = { backupPasswordPrompt.value = null },
                onConfirm = { password ->
                    backupPasswordPrompt.value = null
                    runBackup(prompt, password)
                },
            )
        }
    }

    private fun runBackup(prompt: BackupPrompt, password: String) {
        ioScope.launch {
            val manager = BackupManager(applicationContext, messenger)
            val result = if (prompt.exporting) {
                manager.export(prompt.uri, password).map { bytes ->
                    "Copia guardada (${bytes / 1024} KB)"
                }
            } else {
                manager.import(prompt.uri, password).map { chats ->
                    "Restaurados $chats chats"
                }
            }
            result
                .onSuccess { toast(it); runOnUiThread { refreshTick.longValue++ } }
                .onFailure { toast(it.message ?: "La operación falló") }
        }
    }

    @Composable
    private fun PasswordDialog(
        exporting: Boolean,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit,
    ) {
        var password by remember { mutableStateOf("") }
        Dialog(onDismissRequest = onDismiss) {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (exporting) "Contraseña de la copia" else "Contraseña del archivo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (exporting) {
                            "Mínimo 8 caracteres. Sin ella el archivo es irrecuperable: nadie " +
                                "puede descifrarlo, tampoco nosotros."
                        } else {
                            "Escribe la contraseña con la que creaste la copia."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("Contraseña") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
                        Button(
                            onClick = { onConfirm(password) },
                            enabled = password.length >= 8,
                        ) { Text(if (exporting) "Exportar" else "Restaurar") }
                    }
                }
            }
        }
    }

    /** Construye las filas de la lista: nota personal, grupos y contactos. */
    private fun buildChatRows(entries: List<ContactEntry>, groups: List<Group>): List<ChatRow> =
        buildList {
            add(
                ChatRow(
                    chatId = identity.fingerprint,
                    name = "Nota para mí",
                    lastMessage = msgStore.lastMessage(identity.fingerprint),
                    statusLabel = null,
                    isSelf = true,
                    isGroup = false,
                    muted = false,
                    ttlLabel = null,
                    photo = store.getProfileAvatar(),
                ),
            )
            groups.forEach { group ->
                val settings = prefs.settings(group.id)
                add(
                    ChatRow(
                        chatId = group.id,
                        name = group.name,
                        lastMessage = msgStore.lastMessage(group.id),
                        statusLabel = "${group.members.size} miembros",
                        isSelf = false,
                        isGroup = true,
                        muted = settings.muted,
                        ttlLabel = settings.effectiveTtl.takeIf { it != Ttl.OFF }?.label,
                        photo = group.avatar,
                    ),
                )
            }
            entries.forEach { entry ->
                val fp = entry.contact.fingerprint
                val settings = prefs.settings(fp)
                add(
                    ChatRow(
                        chatId = fp,
                        name = entry.name.ifBlank { "Sin nombre · ${fp.take(9)}" },
                        lastMessage = msgStore.lastMessage(fp),
                        statusLabel = when {
                            prefs.isBlocked(fp) -> "bloqueado"
                            messenger.peerOnLan(fp) -> "WiFi"
                            else -> null
                        },
                        isSelf = false,
                        isGroup = false,
                        muted = settings.muted,
                        ttlLabel = settings.effectiveTtl.takeIf { it != Ttl.OFF }?.label,
                        photo = entry.avatar,
                    ),
                )
            }
        }

    private fun launchScanner(onAdded: (String) -> Unit) {
        onContactScanned = { contact ->
            if (store.addContact(contact)) {
                messenger.refreshSubscriptions()
                onAdded(contact.fingerprint)
            } else {
                toast("Ese contacto ya existe")
            }
            refreshTick.longValue++
        }
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java),
        )
    }

    // ---- diálogos ----

    @Composable
    private fun QrDialog(onDismiss: () -> Unit) {
        val invite = remember { inviteEncode(identity) }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

        Dialog(onDismissRequest = onDismiss) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Tu invitación", style = MaterialTheme.typography.titleMedium)
                    val qr: Bitmap = remember { QrUtil.generate(invite) }
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "QR de invitación",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                    Text(
                        identity.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Si no pueden escanear, comparte el texto y que lo peguen.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(invite))
                            toast("Invitación copiada")
                        }) { Text("Copiar") }
                        OutlinedButton(onClick = { shareInvite(invite) }) { Text("Compartir") }
                    }
                    Button(onClick = onDismiss) { Text("Cerrar") }
                }
            }
        }
    }

    /** Comparte la invitación por cualquier app (correo, otra mensajería, etc.). */
    private fun shareInvite(invite: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Mi invitación de PrivMsg")
            putExtra(
                android.content.Intent.EXTRA_TEXT,
                "Añádeme en PrivMsg pegando esto:\n\n$invite\n\n" +
                    "Mi huella de seguridad: ${identity.fingerprint}",
            )
        }
        runCatching { startActivity(android.content.Intent.createChooser(intent, "Compartir invitación")) }
            .onFailure { toast("No se pudo compartir") }
    }

    /**
     * Añadir contacto pegando el texto de la invitación.
     *
     * El QR es lo cómodo cara a cara, pero no siempre hay cámara (emuladores,
     * tablets sin cámara trasera) ni es práctico. La invitación es solo texto:
     * claves públicas, nada secreto, así que puede viajar por cualquier canal.
     */
    @Composable
    private fun PasteInviteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

        Dialog(onDismissRequest = onDismiss) {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Añadir contacto",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Pega aquí la invitación que te pasaron. Empieza por «privmsg:».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("privmsg:...") },
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.getText()?.text?.let { text = it }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pegar del portapapeles") }

                    Text(
                        "La invitación solo contiene claves públicas: puede viajar por " +
                            "cualquier canal sin riesgo. Verifica la huella con esa persona " +
                            "por otro medio antes de confiar en ella.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
                        Button(
                            onClick = { onConfirm(text) },
                            enabled = text.trim().startsWith("privmsg:"),
                        ) { Text("Añadir") }
                    }
                }
            }
        }
    }

    @Composable
    private fun MnemonicDialog(phrase: String?, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Frase de recuperación",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Quien tenga estas 12 palabras puede hacerse pasar por ti. " +
                            "Apúntalas en papel; no las fotografíes ni las subas a la nube.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        phrase ?: "(esta identidad no tiene frase)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }

    @Composable
    private fun NameDialog(
        title: String,
        explanation: String,
        initial: String,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit,
    ) {
        var value by remember { mutableStateOf(initial) }
        Dialog(onDismissRequest = onDismiss) {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(explanation, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { if (it.length <= 40) value = it },
                        singleLine = true,
                        placeholder = { Text("Nickname") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
                        Button(onClick = { onConfirm(value.trim()) }) { Text("Guardar") }
                    }
                }
            }
        }
    }
}
