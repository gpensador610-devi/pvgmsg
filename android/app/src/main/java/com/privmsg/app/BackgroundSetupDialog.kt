package com.privmsg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Paso del asistente de permisos de segundo plano. */
enum class BackgroundSetupStep { BATTERY, AUTOSTART }

/**
 * Se muestra una sola vez, al empezar a usar la app.
 *
 * Android no deja que una app se autoconceda estos permisos, así que lo mejor
 * posible es pedirlos de golpe, explicando el porqué, en vez de dejarlos
 * escondidos en Ajustes.
 */
@Composable
fun BackgroundSetupDialog(
    step: BackgroundSetupStep,
    guide: OemGuide?,
    onAccept: () -> Unit,
    onSkip: () -> Unit,
) {
    Dialog(onDismissRequest = onSkip) {
        Card {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    BackgroundSetupStep.BATTERY -> {
                        Text("🔋", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Para recibir mensajes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "PrivMsg no usa las notificaciones push de Google: eso le contaría a " +
                                "un tercero cuándo recibes mensajes y de quién. A cambio, el " +
                                "teléfono tiene que dejar la app despierta.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Android no permite que una app se conceda esto sola. Al pulsar " +
                                "«Permitir» verás un aviso del sistema: elige «Permitir» ahí también.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    BackgroundSetupStep.AUTOSTART -> {
                        val name = guide?.name ?: "tu teléfono"
                        Text("⚙️", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Un paso más en $name",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "$name añade su propio gestor que cierra apps aunque Android las " +
                                "haya eximido. Sin esto, los mensajes no llegarán con la " +
                                "pantalla apagada.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        guide?.steps?.forEachIndexed { index, stepText ->
                            Text(
                                "${index + 1}. $stepText",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip) { Text("Ahora no") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAccept) {
                        Text(if (step == BackgroundSetupStep.BATTERY) "Permitir" else "Abrir ajustes")
                    }
                }
            }
        }
    }
}
