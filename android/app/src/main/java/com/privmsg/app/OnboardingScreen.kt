package com.privmsg.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Paso del alta inicial. */
sealed interface OnboardStep {
    data object Welcome : OnboardStep
    data class ShowPhrase(val phrase: String) : OnboardStep
    data object Restore : OnboardStep
}

@Composable
fun OnboardingScreen(
    step: OnboardStep,
    onCreateNew: () -> Unit,
    onConfirmPhrase: (String) -> Unit,
    onGoRestore: () -> Unit,
    onRestore: (String) -> Unit,
    onBackToWelcome: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                is OnboardStep.Welcome -> WelcomeStep(onCreateNew, onGoRestore)
                is OnboardStep.ShowPhrase -> ShowPhraseStep(step.phrase, onConfirmPhrase, onBackToWelcome)
                is OnboardStep.Restore -> RestoreStep(onRestore, onBackToWelcome)
            }
        }
    }
}

@Composable
private fun WelcomeStep(onCreateNew: () -> Unit, onGoRestore: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Text("🔐", style = MaterialTheme.typography.displayLarge)
    Text("PrivMsg", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    Text(
        "Mensajería cifrada de extremo a extremo. Sin cuentas, sin teléfono, sin email, " +
            "sin servidores que puedan leerte.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(24.dp))

    Button(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
        Text("Crear identidad nueva")
    }
    OutlinedButton(onClick = onGoRestore, modifier = Modifier.fillMaxWidth()) {
        Text("Ya tengo una frase de recuperación")
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "Tu identidad se genera dentro de este teléfono y nunca sale de él. " +
            "Se protege con 12 palabras, igual que una wallet.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ShowPhraseStep(
    phrase: String,
    onConfirm: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var acknowledged by remember { mutableStateOf(false) }
    val words = remember(phrase) { phrase.trim().split(Regex("\\s+")) }

    Text("Tu frase de recuperación", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠️ Apúntala en papel ahora", fontWeight = FontWeight.Bold)
            Text(
                "Es la única forma de recuperar tu identidad si pierdes o cambias de teléfono. " +
                    "Nadie más la tiene: ni nosotros, ni ningún servidor. Si la pierdes, la cuenta " +
                    "se pierde para siempre.\n\n" +
                    "Quien tenga estas 12 palabras puede hacerse pasar por ti. No la fotografíes " +
                    "ni la guardes en la nube.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    // Rejilla 2 columnas numerada.
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            words.chunked(2).forEachIndexed { rowIndex, pair ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    pair.forEachIndexed { colIndex, word ->
                        val number = rowIndex * 2 + colIndex + 1
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$number.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    word,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
        Text(
            "He apuntado mis 12 palabras en un lugar seguro",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Button(
        onClick = { onConfirm(phrase) },
        enabled = acknowledged,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continuar")
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text("Cancelar")
    }
}

@Composable
private fun RestoreStep(onRestore: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var input by remember { mutableStateOf("") }
    val wordCount = remember(input) {
        input.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }

    Text("Recuperar identidad", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "Escribe tus 12 palabras separadas por espacios, en el mismo orden.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = input,
        onValueChange = { input = it.lowercase() },
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        placeholder = { Text("palabra1 palabra2 palabra3 …") },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    )
    Text(
        "$wordCount / 12 palabras",
        style = MaterialTheme.typography.labelMedium,
        color = if (wordCount == 12) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = { onRestore(input.trim()) },
        enabled = wordCount == 12,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Recuperar")
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text("Volver")
    }

    Text(
        "Se restaurará tu identidad y tu huella. Los contactos y el historial de mensajes " +
            "no viajan en la frase: tendrás que volver a escanear a tus contactos.",
        style = MaterialTheme.typography.bodySmall,
    )
}
