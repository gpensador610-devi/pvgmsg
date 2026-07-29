package com.privmsg.app

import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Iconos vectoriales mínimos (evita la dependencia material-icons-extended). */
object AppIcons {
    private fun icon(name: String, pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder)
        }.build()

    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack") {
            moveTo(20f, 11f); horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f); lineTo(12f, 4f)
            lineToRelative(-8f, 8f); lineToRelative(8f, 8f)
            lineToRelative(1.41f, -1.41f); lineTo(7.83f, 13f)
            horizontalLineTo(20f); verticalLineToRelative(-2f); close()
        }
    }

    /** Código QR. */
    val QrCode: ImageVector by lazy {
        icon("QrCode") {
            moveTo(3f, 11f); horizontalLineToRelative(8f); verticalLineTo(3f); horizontalLineTo(3f); close()
            moveTo(5f, 5f); horizontalLineToRelative(4f); verticalLineToRelative(4f); horizontalLineTo(5f); close()
            moveTo(3f, 21f); horizontalLineToRelative(8f); verticalLineToRelative(-8f); horizontalLineTo(3f); close()
            moveTo(5f, 15f); horizontalLineToRelative(4f); verticalLineToRelative(4f); horizontalLineTo(5f); close()
            moveTo(13f, 3f); verticalLineToRelative(8f); horizontalLineToRelative(8f); verticalLineTo(3f); close()
            moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(5f); horizontalLineToRelative(4f); close()
            moveTo(13f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(15f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(13f, 17f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(17f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(17f, 17f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(19f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(19f, 19f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(15f, 19f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
        }
    }

    /** Cámara para escanear. */
    val Scan: ImageVector by lazy {
        icon("Scan") {
            moveTo(3f, 3f); horizontalLineToRelative(6f); verticalLineToRelative(2f)
            horizontalLineTo(5f); verticalLineToRelative(4f); horizontalLineTo(3f); close()
            moveTo(15f, 3f); horizontalLineToRelative(6f); verticalLineToRelative(6f)
            horizontalLineToRelative(-2f); verticalLineTo(5f); horizontalLineToRelative(-4f); close()
            moveTo(3f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(4f)
            horizontalLineToRelative(4f); verticalLineToRelative(2f); horizontalLineTo(3f); close()
            moveTo(19f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(6f)
            horizontalLineToRelative(-6f); verticalLineToRelative(-2f); horizontalLineToRelative(4f); close()
            moveTo(7f, 11f); horizontalLineToRelative(10f); verticalLineToRelative(2f)
            horizontalLineTo(7f); close()
        }
    }

    /** Clip para adjuntar. */
    val Attach: ImageVector by lazy {
        icon("Attach") {
            moveTo(16.5f, 6f)
            verticalLineToRelative(11.5f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, -8f, 0f)
            verticalLineTo(5f)
            arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5f, 0f)
            verticalLineToRelative(10.5f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 0f)
            verticalLineTo(6f)
            horizontalLineTo(10f)
            verticalLineToRelative(9.5f)
            arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 5f, 0f)
            verticalLineTo(5f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, -8f, 0f)
            verticalLineToRelative(12.5f)
            arcToRelative(5.5f, 5.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 11f, 0f)
            verticalLineTo(6f)
            close()
        }
    }

    /** Micrófono. */
    val Mic: ImageVector by lazy {
        icon("Mic") {
            moveTo(12f, 14f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, 3f, -3f)
            verticalLineTo(5f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, -6f, 0f)
            verticalLineToRelative(6f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, 3f, 3f)
            close()
            moveTo(17f, 11f)
            horizontalLineToRelative(-1.7f)
            arcToRelative(3.3f, 3.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6.6f, 0f)
            horizontalLineTo(7f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 4f, 4.9f)
            verticalLineTo(19f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3.1f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 4f, -4.9f)
            close()
        }
    }

    /** Enviar (avión de papel simplificado). */
    val Send: ImageVector by lazy {
        icon("Send") {
            moveTo(2.5f, 20.5f)
            lineTo(21f, 12f)
            lineTo(2.5f, 3.5f)
            verticalLineToRelative(6.6f)
            lineToRelative(13f, 1.9f)
            lineToRelative(-13f, 1.9f)
            close()
        }
    }

    /** Play. */
    val Play: ImageVector by lazy {
        icon("Play") {
            moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
        }
    }

    /** Stop. */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            moveTo(6f, 6f); horizontalLineToRelative(12f); verticalLineToRelative(12f)
            horizontalLineTo(6f); close()
        }
    }

    /** Auricular de teléfono. */
    val Call: ImageVector by lazy {
        icon("Call") {
            moveTo(6.6f, 10.8f)
            arcToRelative(15.1f, 15.1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 6.6f, 6.6f)
            lineToRelative(2.2f, -2.2f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1f, -0.2f)
            arcToRelative(11.4f, 11.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 3.6f, 0.6f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1f, 1f)
            verticalLineTo(20f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1f, 1f)
            arcTo(17f, 17f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 4f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1f, -1f)
            horizontalLineToRelative(3.5f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1f, 1f)
            arcToRelative(11.4f, 11.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0.6f, 3.6f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, -0.2f, 1f)
            close()
        }
    }

    /** Colgar: auricular girado. */
    val CallEnd: ImageVector by lazy {
        icon("CallEnd") {
            moveTo(12f, 9f)
            curveToRelative(-2.4f, 0f, -4.7f, 0.4f, -6.8f, 1.1f)
            verticalLineToRelative(3.1f)
            curveToRelative(0f, 0.4f, -0.2f, 0.8f, -0.6f, 0.9f)
            curveToRelative(-1f, 0.5f, -2f, 1.2f, -2.8f, 1.9f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.4f, 0f)
            lineTo(0.3f, 14.2f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -1.4f)
            curveTo(3.3f, 10f, 7.4f, 8.4f, 12f, 8.4f)
            reflectiveCurveToRelative(8.7f, 1.6f, 11.7f, 4.4f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 1.4f)
            lineToRelative(-2.1f, 1.8f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.4f, 0f)
            curveToRelative(-0.8f, -0.7f, -1.8f, -1.4f, -2.8f, -1.9f)
            curveToRelative(-0.4f, -0.1f, -0.6f, -0.5f, -0.6f, -0.9f)
            verticalLineToRelative(-3.1f)
            curveTo(16.7f, 9.4f, 14.4f, 9f, 12f, 9f)
            close()
        }
    }

    /** Altavoz. */
    val Speaker: ImageVector by lazy {
        icon("Speaker") {
            moveTo(3f, 9f); verticalLineToRelative(6f); horizontalLineToRelative(4f)
            lineToRelative(5f, 5f); verticalLineTo(4f); lineTo(7f, 9f); close()
            moveTo(16.5f, 12f)
            curveToRelative(0f, -1.8f, -1f, -3.3f, -2.5f, -4f)
            verticalLineToRelative(8f)
            curveToRelative(1.5f, -0.7f, 2.5f, -2.2f, 2.5f, -4f)
            close()
            moveTo(14f, 2.2f)
            verticalLineToRelative(2.1f)
            curveToRelative(2.9f, 0.9f, 5f, 3.5f, 5f, 6.7f)
            reflectiveCurveToRelative(-2.1f, 5.8f, -5f, 6.7f)
            verticalLineToRelative(2.1f)
            curveToRelative(4f, -0.9f, 7f, -4.5f, 7f, -8.8f)
            reflectiveCurveToRelative(-3f, -7.9f, -7f, -8.8f)
            close()
        }
    }

    /** Tres puntos verticales (menú). */
    val MoreVert: ImageVector by lazy {
        icon("MoreVert") {
            moveTo(12f, 8f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, -4f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, 4f)
            close()
            moveTo(12f, 14f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, -4f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, 4f)
            close()
            moveTo(12f, 20f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, -4f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, 4f)
            close()
        }
    }
}

/**
 * Vibración corta de teclado, la misma que usa el marcador del teléfono.
 * Respeta el ajuste de vibración del sistema: si el usuario la desactivó, no
 * hace nada.
 */
@Composable
fun rememberTapFeedback(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    }
}

/**
 * Botón circular con tacto propio.
 *
 * El orden de los modificadores importa y es la causa del "cuadrado" feo:
 * hay que **recortar antes de pintar y antes de hacer clicable**, o la onda
 * de pulsación se dibuja sobre el rectángulo que envuelve al círculo.
 *
 * Añade además micro-animación de presionado y vibración, que es lo que hace
 * que un botón se sienta físico en vez de plano.
 */
@Composable
fun CircleButton(
    size: Int,
    background: Color,
    enabled: Boolean = true,
    haptic: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press",
    )
    val tap = rememberTapFeedback()

    Box(
        modifier = Modifier
            .size(size.dp)
            .scale(scale)
            // clip → background → clickable: así la onda queda dentro del círculo.
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                onClick = {
                    if (haptic) tap()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * Avatar circular: la foto de perfil si existe, si no la inicial del nombre
 * sobre un color derivado de la huella.
 */
@Composable
fun Avatar(
    name: String,
    fingerprint: String,
    size: Int = 48,
    photo: ByteArray? = null,
) {
    val bitmap = remember(photo) {
        photo?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape),
        )
        return
    }

    val palette = listOf(
        Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFF8E24AA), Color(0xFFE53935),
        Color(0xFF00897B), Color(0xFFF4511E), Color(0xFF3949AB), Color(0xFF6D4C41),
    )
    val color = palette[(fingerprint.hashCode().let { if (it < 0) -it else it }) % palette.size]
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size / 2.2).sp,
        )
    }
}

/** Hora si es de hoy, "Ayer", o fecha corta — como WhatsApp. */
fun formatChatTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Ayer"

    return SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
}
