package com.privmsg.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Una fila de la lista de chats. */
data class ChatRow(
    val chatId: String,
    val name: String,
    val lastMessage: Msg?,
    val statusLabel: String?,
    val isSelf: Boolean,
    val isGroup: Boolean,
    val muted: Boolean,
    val ttlLabel: String?,
    val photo: ByteArray? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    rows: List<ChatRow>,
    onOpenChat: (String) -> Unit,
    onRenameContact: (String) -> Unit,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onNewGroup: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PrivMsg") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = onScan) {
                        Icon(AppIcons.Scan, contentDescription = "Escanear código QR")
                    }
                    IconButton(onClick = onShowQr) {
                        Icon(AppIcons.QrCode, contentDescription = "Mostrar mi código QR")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "Más opciones")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Nuevo grupo") },
                            onClick = { menuOpen = false; onNewGroup() },
                        )
                        DropdownMenuItem(
                            text = { Text("Ajustes") },
                            onClick = { menuOpen = false; onSettings() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(rows, key = { it.chatId }) { row ->
                ChatRowItem(
                    row = row,
                    onClick = { onOpenChat(row.chatId) },
                    onLongClick = { if (!row.isSelf && !row.isGroup) onRenameContact(row.chatId) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 80.dp))
            }
            if (rows.size <= 1) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Sin contactos todavía", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Toca el icono de escanear arriba para añadir a alguien con su código QR.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRowItem(
    row: ChatRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = when {
                row.isSelf -> "📝"
                row.isGroup -> "👥"
                else -> row.name.ifBlank { "?" }
            },
            fingerprint = row.chatId,
            size = 48,
            photo = row.photo,
        )

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.muted) {
                    Spacer(Modifier.width(6.dp))
                    Text("🔕", style = MaterialTheme.typography.labelSmall)
                }
                row.ttlLabel?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("⏱", style = MaterialTheme.typography.labelSmall)
                }
            }
            val preview = row.lastMessage
            Text(
                when {
                    preview == null && row.isSelf -> "Prueba el cifrado contigo mismo"
                    preview == null -> "Sin mensajes todavía"
                    preview.mine -> "Tú: ${preview.preview()}"
                    else -> preview.preview()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            row.lastMessage?.let {
                Text(
                    formatChatTime(it.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.statusLabel?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
