package com.privmsg.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profileName: String,
    profilePhoto: ByteArray?,
    fingerprint: String,
    relaysOnline: Boolean,
    hasMnemonic: Boolean,
    defaultSoundSet: Boolean,
    defaultTtl: Ttl,
    blockedCount: Int,
    batteryOptimized: Boolean,
    oemGuide: OemGuide?,
    screenSecurity: Boolean,
    lockEnabled: Boolean,
    lockTimeout: LockTimeout,
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onToggleScreenSecurity: () -> Unit,
    onToggleLock: () -> Unit,
    onChangePin: () -> Unit,
    onSetLockTimeout: (LockTimeout) -> Unit,
    onToggleBiometric: () -> Unit,
    duressPinSet: Boolean,
    onSetDuressPin: () -> Unit,
    onClearDuressPin: () -> Unit,
    onSetDefaultTtl: (Ttl) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemAutostart: () -> Unit,
    onEditProfile: () -> Unit,
    onChangePhoto: () -> Unit,
    onShowQr: () -> Unit,
    onShowMnemonic: () -> Unit,
    onPickDefaultSound: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
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
            // Perfil
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(onClick = onChangePhoto),
                    ) {
                        Avatar(
                            name = profileName.ifBlank { "?" },
                            fingerprint = fingerprint,
                            size = 64,
                            photo = profilePhoto,
                        )
                        Text(
                            "📷 cambiar",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onEditProfile),
                    ) {
                        Text(
                            profileName.ifBlank { "(sin nickname)" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Toca para cambiar tu nickname ✏️",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Identidad
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Tu huella de seguridad", style = MaterialTheme.typography.titleMedium)
                    Text(
                        fingerprint,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Compárala en persona con tus contactos: es lo único que no se puede falsificar. " +
                            "Los nicknames sí.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "Mostrar mi código QR",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onShowQr)
                            .padding(vertical = 8.dp),
                    )
                }
            }

            // Bloqueo de la app
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🔒 Bloqueo de la app", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pedir PIN al abrir", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (lockEnabled) {
                                    "Nadie ve tus chats sin el PIN, aunque tenga el teléfono " +
                                        "desbloqueado"
                                } else {
                                    "Quien agarre tu teléfono desbloqueado puede leerlo todo"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lockEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        Switch(checked = lockEnabled, onCheckedChange = { onToggleLock() })
                    }

                    if (lockEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            "Cambiar el PIN",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onChangePin)
                                .padding(vertical = 8.dp),
                        )

                        if (biometricAvailable) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Desbloquear con huella", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Atajo cómodo; el PIN sigue funcionando siempre",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = biometricEnabled,
                                    onCheckedChange = { onToggleBiometric() },
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Bloquear automáticamente", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Tras salir de la app, cuánto tarda en pedir el PIN de nuevo.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LockTimeout.entries.toList().chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { option ->
                                    FilterChip(
                                        selected = lockTimeout == option,
                                        onClick = { onSetLockTimeout(option) },
                                        label = { Text(option.label, maxLines = 1) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        Text(
                            "Si olvidas el PIN no hay forma de recuperarlo: habría que borrar " +
                                "los datos de la app y restaurar con tus 12 palabras y una copia.",
                            style = MaterialTheme.typography.labelSmall,
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        Text(
                            "🚨 PIN de emergencia",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Un segundo PIN que, en vez de abrir la app, borra todo en silencio " +
                                "y la deja como recién instalada. Para cuando alguien te obliga " +
                                "a desbloquearla.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Lo que NO hace: recuperar lo que ya se hayan copiado del teléfono, " +
                                "ni borrar lo que el otro tenga en el suyo. Contra alguien que " +
                                "primero clona el móvil, no sirve — para eso lo que protege es " +
                                "la autodestrucción, que hace que haya menos que copiar.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            if (duressPinSet) "Cambiar el PIN de emergencia" else "Configurar PIN de emergencia",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onSetDuressPin)
                                .padding(vertical = 8.dp),
                        )
                        if (duressPinSet) {
                            Text(
                                "Quitar el PIN de emergencia",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onClearDuressPin)
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            // Privacidad de pantalla
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🛡 Privacidad de pantalla", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloquear capturas", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (screenSecurity) {
                                    "Las capturas salen en negro y la app no se ve en «recientes»"
                                } else {
                                    "Se pueden hacer capturas con normalidad"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = screenSecurity,
                            onCheckedChange = { onToggleScreenSecurity() },
                        )
                    }
                    Text(
                        "También impide grabar la pantalla y que otras apps lean el contenido. " +
                            "No puede evitar que alguien fotografíe la pantalla con otro móvil: " +
                            "eso no lo impide ninguna app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Autodestrucción general
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("⏱ Autodestrucción general", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Se aplica a todos los chats y grupos que no tengan un ajuste propio. " +
                            "Los mensajes se borran solos en tu teléfono y en el de tus contactos.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TtlChips(
                        options = Ttl.globalOptions,
                        selected = defaultTtl,
                        onSelect = onSetDefaultTtl,
                    )
                }
            }

            // Notificaciones
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🔔 Notificaciones", style = MaterialTheme.typography.titleMedium)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onPickDefaultSound)
                            .padding(vertical = 4.dp),
                    ) {
                        Text("Tono de los mensajes", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (defaultSoundSet) "Personalizado — toca para cambiarlo"
                            else "El del sistema — toca para elegir otro",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "Cada chat puede tener su propio tono desde sus ajustes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (blockedCount > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            "$blockedCount contacto(s) bloqueado(s). Desbloquéalos desde los " +
                                "ajustes de su chat.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Copia de seguridad
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Copia de seguridad", style = MaterialTheme.typography.titleMedium)
                    if (hasMnemonic) {
                        Text(
                            "Tus 12 palabras restauran esta identidad en cualquier teléfono. " +
                                "Si no las tienes apuntadas, hazlo ahora.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Ver mi frase de recuperación",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onShowMnemonic)
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            "⚠️ Esta identidad se creó antes del sistema de frases y no tiene " +
                                "copia de seguridad. Si borras la app, se pierde para siempre.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        "La frase recupera tu identidad. Para conservar contactos, grupos e " +
                            "historial, exporta un archivo cifrado con contraseña — puedes " +
                            "guardarlo donde quieras, sin la contraseña es ruido.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Exportar copia cifrada",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onExportBackup)
                            .padding(vertical = 8.dp),
                    )
                    Text(
                        "Restaurar desde archivo",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onImportBackup)
                            .padding(vertical = 8.dp),
                    )
                }
            }

            // Conexión
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Conexión", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (relaysOnline) "● Conectado a relays públicos" else "○ Sin conexión a relays",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (relaysOnline) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Los mensajes intentan primero ir directos por WiFi local; si no, viajan por " +
                            "relays públicos de internet que solo ven ruido cifrado.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    if (batteryOptimized) {
                        Text(
                            "⚠️ Android está limitando la app en segundo plano. Sin push de " +
                                "Google (que filtraría metadatos), la app necesita quedar exenta " +
                                "del ahorro de batería para recibir mensajes con la pantalla apagada.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Desactivar el ahorro de batería para PrivMsg",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onRequestBatteryExemption)
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            "✅ Exenta del ahorro de batería: los mensajes llegan también con la " +
                                "pantalla apagada.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (oemGuide != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        Text(
                            "${oemGuide.name} cierra apps con su propio gestor, aunque Android " +
                                "las haya eximido. Si los mensajes no llegan con la pantalla " +
                                "apagada:",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        oemGuide.steps.forEachIndexed { index, step ->
                            Text(
                                "${index + 1}. $step",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Abrir ajustes de ${oemGuide.name}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenOemAutostart)
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }

            // Seguridad
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Cifrado", style = MaterialTheme.typography.titleMedium)
                    InfoLine("Intercambio de claves", "X25519 + ML-KEM-768 (híbrido)")
                    InfoLine("Cifrado", "XChaCha20-Poly1305")
                    InfoLine("Derivación", "HKDF-SHA256")
                    InfoLine("Almacenamiento", "AES-256 · Android Keystore")
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "ML-KEM-768 es el estándar post-cuántico del NIST (FIPS 203). Para leer un " +
                            "mensaje habría que romper a la vez curvas elípticas y retículos.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "PrivMsg 0.3 · sin cuentas, sin servidores propios, sin teléfono ni email",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}
