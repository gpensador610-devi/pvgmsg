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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Pantalla de bloqueo. Aparece encima de todo: sin PIN o huella no se ve
 * ningún chat, aunque el teléfono esté desbloqueado.
 */
@Composable
fun LockScreen(
    /** Modo de creación: pide el PIN dos veces en vez de verificarlo. */
    settingUp: Boolean,
    biometricAvailable: Boolean,
    lockedUntilMillis: Long,
    onSubmit: (String) -> Unit,
    onBiometric: () -> Unit,
    onCancelSetup: (() -> Unit)? = null,
    /** Creando el PIN de emergencia, no el normal. */
    duressSetup: Boolean = false,
) {
    var pin by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var firstEntry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var remaining by remember { mutableLongStateOf(lockedUntilMillis) }

    // Cuenta atrás de la penalización por intentos fallidos.
    LaunchedEffect(lockedUntilMillis) {
        remaining = lockedUntilMillis
        while (remaining > 0) {
            delay(1000)
            remaining = (remaining - 1000).coerceAtLeast(0)
        }
    }

    // Al abrir, si hay huella disponible se ofrece sola.
    LaunchedEffect(Unit) {
        if (!settingUp && biometricAvailable && lockedUntilMillis == 0L) onBiometric()
    }

    fun submit(value: String) {
        when {
            !settingUp -> onSubmit(value)
            !confirming -> {
                firstEntry = value
                confirming = true
                error = null
            }
            value == firstEntry -> onSubmit(value)
            else -> {
                error = "Los PIN no coinciden. Empieza de nuevo."
                confirming = false
                firstEntry = ""
            }
        }
        pin = ""
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Text(if (duressSetup) "🚨" else "🔒", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    duressSetup && confirming -> "Repite el PIN de emergencia"
                    duressSetup -> "PIN de emergencia"
                    settingUp && confirming -> "Repite el PIN"
                    settingUp -> "Elige un PIN"
                    else -> "PrivMsg está bloqueada"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    duressSetup -> "Al introducir este PIN, la app borra todo en silencio y " +
                        "arranca como recién instalada. Nadie verá que se borró algo.\n\n" +
                        "Que tenga la misma longitud que el normal: así no se distinguen."
                    settingUp -> "Entre ${AppLock.MIN_PIN_LENGTH} y ${AppLock.MAX_PIN_LENGTH} " +
                        "dígitos. Distinto al del teléfono: así protege incluso si alguien " +
                        "conoce tu desbloqueo."
                    remaining > 0 -> "Demasiados intentos fallidos."
                    else -> "Introduce tu PIN para continuar."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            if (remaining > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Espera ${formatDuration(remaining)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(28.dp))
            PinDots(count = pin.length)
            Spacer(Modifier.weight(1f))

            if (remaining == 0L) {
                Keypad(
                    onDigit = {
                        if (pin.length < AppLock.MAX_PIN_LENGTH) {
                            pin += it
                            error = null
                            // Con la longitud máxima, valida solo.
                            if (pin.length == AppLock.MAX_PIN_LENGTH) submit(pin)
                        }
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onConfirm = { if (pin.length >= AppLock.MIN_PIN_LENGTH) submit(pin) },
                    confirmEnabled = pin.length >= AppLock.MIN_PIN_LENGTH,
                )

                if (!settingUp && biometricAvailable) {
                    TextButton(onClick = onBiometric) { Text("Usar huella") }
                }
                onCancelSetup?.let {
                    TextButton(onClick = it) { Text("Cancelar") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PinDots(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count.coerceAtMost(AppLock.MAX_PIN_LENGTH)) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        if (count == 0) {
            Text("· · · ·", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "✓"),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { label ->
                    KeypadButton(
                        label = label,
                        enabled = label != "✓" || confirmEnabled,
                        onClick = {
                            when (label) {
                                "⌫" -> onBackspace()
                                "✓" -> onConfirm()
                                else -> onDigit(label)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val background = when (label) {
        "✓" -> if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
        "⌫" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val tint = when (label) {
        "✓" -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    CircleButton(size = 68, background = background, enabled = enabled, onClick = onClick) {
        Text(label, style = MaterialTheme.typography.headlineSmall, color = tint)
    }
}
