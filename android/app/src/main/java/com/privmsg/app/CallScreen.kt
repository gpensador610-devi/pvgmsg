package com.privmsg.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    state: CallState,
    fingerprint: String,
    photo: ByteArray?,
    muted: Boolean,
    speakerOn: Boolean,
    connected: Boolean,
    onAccept: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val name = when (state) {
        is CallState.Outgoing -> state.name
        is CallState.Incoming -> state.name
        is CallState.Active -> state.name
        CallState.Idle -> return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            Avatar(name = name, fingerprint = fingerprint, size = 120, photo = photo)
            Spacer(Modifier.height(20.dp))

            Text(
                name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            when (state) {
                is CallState.Incoming -> Text(
                    "Llamada entrante",
                    style = MaterialTheme.typography.titleMedium,
                )

                is CallState.Outgoing -> Text(
                    "Llamando…",
                    style = MaterialTheme.typography.titleMedium,
                )

                is CallState.Active -> {
                    if (connected) {
                        CallTimer(state.startedAt)
                    } else {
                        Text("Conectando…", style = MaterialTheme.typography.titleMedium)
                    }
                }

                CallState.Idle -> Unit
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "🔒 Cifrada de extremo a extremo",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                fingerprint,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))

            // Controles de llamada activa
            if (state is CallState.Active) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    modifier = Modifier.padding(bottom = 32.dp),
                ) {
                    CallButton(
                        icon = AppIcons.Mic,
                        label = if (muted) "Activar" else "Silenciar",
                        background = if (muted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondaryContainer,
                        tint = if (muted) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onToggleMute,
                    )
                    CallButton(
                        icon = AppIcons.Speaker,
                        label = if (speakerOn) "Altavoz on" else "Altavoz",
                        background = if (speakerOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondaryContainer,
                        tint = if (speakerOn) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onToggleSpeaker,
                    )
                }
            }

            // Botones principales
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                modifier = Modifier.padding(bottom = 40.dp),
            ) {
                if (state is CallState.Incoming) {
                    CallButton(
                        icon = AppIcons.CallEnd,
                        label = "Rechazar",
                        background = Color(0xFFE53935),
                        tint = Color.White,
                        size = 68,
                        onClick = onHangup,
                    )
                    CallButton(
                        icon = AppIcons.Call,
                        label = "Aceptar",
                        background = Color(0xFF43A047),
                        tint = Color.White,
                        size = 68,
                        onClick = onAccept,
                    )
                } else {
                    CallButton(
                        icon = AppIcons.CallEnd,
                        label = "Colgar",
                        background = Color(0xFFE53935),
                        tint = Color.White,
                        size = 68,
                        onClick = onHangup,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallTimer(startedAt: Long) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(500)
        }
    }
    Text(formatDuration(elapsed), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    tint: Color,
    size: Int = 56,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleButton(size = size, background = background, onClick = onClick) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size((size / 2.2).dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
