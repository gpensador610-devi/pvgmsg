package com.privmsg.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.window.Dialog

/**
 * Confirmación antes de borrar, con la opción de pedirlo también al otro lado.
 *
 * El texto es explícito sobre el límite real: el borrado remoto funciona
 * porque la otra app coopera, no porque se pueda imponer.
 */
@Composable
private fun ClearHistoryDialog(
    isGroup: Boolean,
    peerName: String,
    onDismiss: () -> Unit,
    onConfirm: (alsoRemote: Boolean) -> Unit,
) {
    var alsoRemote by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "¿Borrar el historial?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Se borrarán los mensajes, fotos y audios de esta conversación " +
                        "en este teléfono. El chat y el contacto se mantienen.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { alsoRemote = !alsoRemote },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = alsoRemote, onCheckedChange = { alsoRemote = it })
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isGroup) "Borrar también para todo el grupo"
                            else "Borrar también en el teléfono de $peerName",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (alsoRemote) {
                    Text(
                        "Funciona porque su app obedece la petición. No puede hacer nada " +
                            "contra una captura de pantalla ya hecha ni contra una app " +
                            "modificada: eso no lo resuelve ninguna mensajería.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    TextButton(onClick = { onConfirm(alsoRemote) }) {
                        Text("Borrar", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Rejilla de opciones de autodestrucción, en filas de tres. */
@Composable
fun TtlChips(options: List<Ttl>, selected: Ttl, onSelect: (Ttl) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        label = { Text(option.label, maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Ajustes de una conversación: autodestrucción, silenciar, tono, bloquear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    title: String,
    fingerprint: String,
    photo: ByteArray?,
    isGroup: Boolean,
    /** false en la nota personal: no tiene sentido bloquearse a uno mismo. */
    canBlock: Boolean,
    members: List<Member>,
    settings: ChatSettings,
    blocked: Boolean,
    onSetTtl: (Ttl) -> Unit,
    onToggleMute: () -> Unit,
    onPickSound: () -> Unit,
    onToggleBlock: () -> Unit,
    onLeaveGroup: () -> Unit,
    /** El booleano indica si además hay que pedir el borrado al otro extremo. */
    onClearHistory: (alsoRemote: Boolean) -> Unit,
    onResetSession: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmingClear by remember { mutableStateOf(false) }

    if (confirmingClear) {
        ClearHistoryDialog(
            isGroup = isGroup,
            peerName = title,
            onDismiss = { confirmingClear = false },
            onConfirm = { alsoRemote ->
                confirmingClear = false
                onClearHistory(alsoRemote)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isGroup) "Ajustes del grupo" else "Ajustes del chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Cabecera
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(name = title, fingerprint = fingerprint, size = 88, photo = photo)
                Spacer(Modifier.height(10.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (!isGroup && canBlock) {
                    Text(
                        fingerprint,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Autodestrucción
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("⏱ Autodestrucción", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Los mensajes se borran solos pasado este tiempo, en tu teléfono " +
                            "y en el de " + (if (isGroup) "todos los miembros" else "tu contacto") + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    TtlChips(
                        options = Ttl.chatOptions,
                        selected = settings.ttl,
                        onSelect = onSetTtl,
                    )
                    if (settings.ttl == Ttl.INHERIT) {
                        Text(
                            "Ahora mismo: ${settings.effectiveTtl.label.lowercase()} " +
                                "(según el ajuste general de la app).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (settings.effectiveTtl != Ttl.OFF) {
                        Text(
                            "⚠️ El borrado es cooperativo: nadie puede impedir que alguien haga " +
                                "una captura de pantalla. Ninguna app puede.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // Notificaciones
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔔 Notificaciones", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Silenciar", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (settings.muted) "No recibirás avisos de este chat"
                                else "Recibes avisos normalmente",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = settings.muted, onCheckedChange = { onToggleMute() })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onPickSound),
                    ) {
                        Text("Tono de este chat", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.soundUri.isBlank()) "El general de la app"
                            else "Personalizado",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Miembros del grupo
            if (isGroup) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "👥 Miembros (${members.size})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        members.forEach { member ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(
                                    name = member.name.ifBlank { "?" },
                                    fingerprint = member.fingerprint,
                                    size = 36,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        member.name.ifBlank { "Sin nombre" },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        member.fingerprint,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Acciones
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Borrar historial de este chat",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { confirmingClear = true },
                    )
                    HorizontalDivider()
                    if (!isGroup) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onResetSession),
                        ) {
                            Text("Reiniciar el cifrado", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Si los mensajes dejan de llegar o no se pueden leer, esto " +
                                    "negocia claves nuevas. No borra nada de lo que ya tienes.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider()
                    }
                    if (isGroup) {
                        Text(
                            "Salir del grupo",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onLeaveGroup),
                        )
                    } else if (canBlock) {
                        Text(
                            if (blocked) "Desbloquear contacto" else "Bloquear contacto",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (blocked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onToggleBlock),
                        )
                        Text(
                            if (blocked) {
                                "Ahora mismo se descartan sus mensajes y llamadas."
                            } else {
                                "Sus mensajes y llamadas se descartarán sin avisarle."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
