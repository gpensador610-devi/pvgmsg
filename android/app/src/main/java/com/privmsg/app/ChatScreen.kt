@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.privmsg.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    title: String,
    subtitle: String,
    avatarPhoto: ByteArray?,
    messages: List<Msg>,
    vault: MediaVault,
    playingMediaId: String?,
    recordingSince: Long?,
    canCall: Boolean,
    isGroup: Boolean,
    senderNameOf: (String) -> String,
    ttlLabel: String?,
    onSend: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onToggleRecord: () -> Unit,
    onPlayAudio: (Msg) -> Unit,
    onCall: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // rememberSaveable: al girar la pantalla no se pierde lo ya escrito.
    var draft by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val tap = rememberTapFeedback()
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var showAttachOptions by remember { mutableStateOf(false) }

    if (showAttachOptions) {
        AttachOptionsDialog(
            onDismiss = { showAttachOptions = false },
            onCamera = { showAttachOptions = false; onTakePhoto() },
            onGallery = { showAttachOptions = false; onPickPhoto() },
        )
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Al abrirse el teclado, seguir viendo el ultimo mensaje.
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // union, no suma: con el teclado abierto el inset del teclado ya incluye
    // la barra de navegacion. Sumar ambos empujaba la conversacion hacia
    // arriba y dejaba el campo de texto casi en el techo de la pantalla.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
    ) {
        // Cabecera
        Surface(shadowElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Volver")
                }
                Avatar(name = title, fingerprint = subtitle, size = 38, photo = avatarPhoto)
                Column(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f)
                        .clickable(onClick = onOpenSettings),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (ttlLabel != null) "⏱ $ttlLabel · $subtitle" else subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = if (isGroup) FontFamily.Default else FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                if (canCall) {
                    IconButton(onClick = onCall) {
                        Icon(
                            AppIcons.Call,
                            contentDescription = "Llamar",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(AppIcons.MoreVert, contentDescription = "Ajustes del chat")
                }
            }
        }

        // Mensajes. Tocar la conversacion cierra el teclado, como en cualquier
        // mensajeria: se toca para leer, no para escribir.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { msg ->
                MessageBubble(
                    msg = msg,
                    vault = vault,
                    isPlaying = msg.mediaId.isNotEmpty() && msg.mediaId == playingMediaId,
                    senderName = if (isGroup && !msg.mine) senderNameOf(msg.senderFp) else null,
                    onPlayAudio = { onPlayAudio(msg) },
                )
            }
        }

        // Barra de composición
        if (recordingSince != null) {
            RecordingBar(startedAt = recordingSince, onStop = onToggleRecord)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { tap(); showAttachOptions = true }) {
                    Icon(AppIcons.Attach, contentDescription = "Adjuntar")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mensaje cifrado…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                )
                if (draft.isBlank()) {
                    IconButton(onClick = { tap(); onToggleRecord() }) {
                        Icon(AppIcons.Mic, contentDescription = "Grabar nota de voz")
                    }
                } else {
                    IconButton(onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            tap()
                            onSend(text)
                            draft = ""
                        }
                    }) {
                        Icon(
                            AppIcons.Send,
                            contentDescription = "Enviar",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cámara o galería, como en cualquier mensajería.
 * Público para reutilizarlo también al elegir la foto de perfil.
 */
@Composable
fun PhotoSourceDialog(
    title: String,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) = AttachOptionsDialog(title, onDismiss, onCamera, onGallery)

@Composable
private fun AttachOptionsDialog(
    title: String = "Enviar foto",
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Card {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                AttachOption("📷", "Tomar una foto", "Abre la cámara ahora", onCamera)
                AttachOption("🖼", "Elegir de la galería", "Fotos ya guardadas", onGallery)
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 12.dp),
                ) { Text("Cancelar") }
            }
        }
    }
}

@Composable
private fun AttachOption(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RecordingBar(startedAt: Long, onStop: () -> Unit) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            kotlinx.coroutines.delay(200)
        }
    }

    // El punto rojo late, como en cualquier grabadora: comunica "esto está vivo".
    val pulse = rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blink",
    )
    val tap = rememberTapFeedback()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .alpha(alpha)
                .background(Color(0xFFE53935), CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Grabando  ${formatDuration(elapsed)}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        CircleButton(
            size = 44,
            background = MaterialTheme.colorScheme.primary,
            onClick = { tap(); onStop() },
        ) {
            Icon(
                AppIcons.Stop,
                contentDescription = "Detener y enviar",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(
    msg: Msg,
    vault: MediaVault,
    isPlaying: Boolean,
    senderName: String?,
    onPlayAudio: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (msg.mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = if (msg.mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (msg.mine) 14.dp else 4.dp,
                bottomEnd = if (msg.mine) 4.dp else 14.dp,
            ),
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (!senderName.isNullOrBlank()) {
                    Text(
                        senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                when (msg.kind) {
                    Kind.IMAGE -> PhotoContent(msg, vault)
                    Kind.AUDIO -> AudioContent(msg, isPlaying, onPlayAudio)
                    else -> Text(
                        msg.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Text(
                    timeFmt.format(Date(msg.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoContent(msg: Msg, vault: MediaVault) {
    val bitmap = remember(msg.mediaId) { vault.loadBitmap(msg.mediaId) }
    if (bitmap == null) {
        Text("📷 Foto no disponible", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Foto",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .heightIn(max = 340.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun AudioContent(msg: Msg, isPlaying: Boolean, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // El botón es lo pulsable, no toda la burbuja: así la onda queda
        // dentro del círculo en vez de pintar un rectángulo.
        CircleButton(
            size = 38,
            background = MaterialTheme.colorScheme.primary,
            onClick = onPlay,
        ) {
            Icon(
                if (isPlaying) AppIcons.Stop else AppIcons.Play,
                contentDescription = if (isPlaying) "Detener" else "Reproducir",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                if (isPlaying) "Reproduciendo…" else "Nota de voz",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                formatDuration(msg.durationMs),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}
