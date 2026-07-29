package com.privmsg.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Instrucciones y atajos propios de cada fabricante.
 *
 * Android estandarizó la exención de batería, pero casi todos los grandes
 * fabricantes añadieron encima su propio mataprocesos, cada uno con su nombre
 * y su pantalla. Decirle a alguien con un Samsung que busque el «Autoencendido»
 * de Xiaomi no sirve de nada, así que aquí va la guía correcta para cada uno.
 */
data class OemGuide(
    val name: String,
    /** Pasos concretos, con la terminología real de esa capa. */
    val steps: List<String>,
    /** Pantallas del sistema a las que intentar saltar, en orden. */
    val screens: List<ComponentName>,
)

/**
 * Permisos que Android exige para recibir mensajes con la app cerrada.
 *
 * Ninguno se puede conceder desde el APK: son ajustes protegidos que solo
 * otorga el usuario. Lo máximo que puede hacer la app es pedirlos bien, una
 * sola vez y explicando por qué.
 *
 * Sin push de Google (que filtraría a un tercero cuándo recibes mensajes y de
 * quién), la única alternativa es que el sistema no duerma el proceso.
 */
object BackgroundPermissions {

    /** ¿Nos ha eximido ya el usuario del ahorro de batería? */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Diálogo del sistema para eximir a la app: un solo toque en "Permitir". */
    fun requestExemption(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    /** Nombre comercial del fabricante, para hablarle al usuario en sus términos. */
    fun oemName(): String = when {
        matches("xiaomi", "redmi", "poco") -> "Xiaomi"
        matches("huawei", "honor") -> "Huawei"
        matches("oppo") -> "Oppo"
        matches("realme") -> "realme"
        matches("vivo") -> "vivo"
        matches("oneplus") -> "OnePlus"
        matches("samsung") -> "Samsung"
        matches("asus") -> "ASUS"
        matches("meizu") -> "Meizu"
        matches("tecno", "infinix", "itel") -> "Transsion"
        else -> Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    }

    /** Guía del fabricante, o null si esta capa no necesita pasos extra. */
    fun guide(): OemGuide? = when {
        matches("xiaomi", "redmi", "poco") -> OemGuide(
            name = "Xiaomi",
            steps = listOf(
                "Abre «Recientes», mantén pulsada PrivMsg y toca el candado 🔒. " +
                    "Es lo que mejor funciona: deja de cerrarse al limpiar memoria.",
                "Activa «Autoencendido» (o «Inicio automático») para PrivMsg.",
                "En Ajustes → Batería, pon PrivMsg en «Sin restricciones».",
            ),
            screens = listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
        )

        matches("huawei", "honor") -> OemGuide(
            name = "Huawei",
            steps = listOf(
                "Ve a «Inicio de aplicaciones» y desactiva la gestión automática para PrivMsg.",
                "Deja marcadas las tres casillas: inicio automático, inicio secundario " +
                    "y ejecución en segundo plano.",
                "En «Recientes», desliza PrivMsg hacia abajo para bloquearla 🔒.",
            ),
            screens = listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            ),
        )

        matches("oppo", "realme") -> OemGuide(
            name = if (matches("realme")) "realme" else "Oppo",
            steps = listOf(
                "Activa «Inicio automático» para PrivMsg.",
                "En Ajustes → Batería → Uso en segundo plano, elige «Permitir en segundo plano».",
                "En «Recientes», mantén pulsada PrivMsg y bloquéala 🔒.",
            ),
            screens = listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            ),
        )

        matches("vivo", "iqoo") -> OemGuide(
            name = "vivo",
            steps = listOf(
                "Activa «Inicio en segundo plano» para PrivMsg.",
                "En Ajustes → Batería → Alto consumo en segundo plano, permite PrivMsg.",
                "En «Recientes», bloquea PrivMsg 🔒.",
            ),
            screens = listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            ),
        )

        matches("oneplus") -> OemGuide(
            name = "OnePlus",
            steps = listOf(
                "En Ajustes → Batería → Optimización de batería, pon PrivMsg en «No optimizar».",
                "Desactiva «Optimización avanzada de batería» si la tienes activa.",
                "En «Recientes», bloquea PrivMsg 🔒.",
            ),
            screens = listOf(
                ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
            ),
        )

        matches("samsung") -> OemGuide(
            name = "Samsung",
            steps = listOf(
                "En Ajustes → Batería → Límites de uso en segundo plano, quita PrivMsg " +
                    "de «Aplicaciones en suspensión» y de «Aplicaciones en suspensión profunda».",
                "En la ficha de la app, elige «Sin restricciones» en Batería.",
                "En «Recientes», mantén pulsado el icono de PrivMsg y elige «Mantener abierta».",
            ),
            screens = listOf(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity",
                ),
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            ),
        )

        matches("asus") -> OemGuide(
            name = "ASUS",
            steps = listOf(
                "En Mobile Manager → Gestor de arranque, activa PrivMsg.",
                "En «Recientes», bloquea PrivMsg 🔒.",
            ),
            screens = listOf(
                ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"),
            ),
        )

        matches("meizu") -> OemGuide(
            name = "Meizu",
            steps = listOf(
                "En Seguridad → Permisos → Gestión en segundo plano, permite PrivMsg.",
                "En «Recientes», bloquea PrivMsg 🔒.",
            ),
            screens = listOf(
                ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
            ),
        )

        matches("tecno", "infinix", "itel") -> OemGuide(
            name = "Transsion",
            steps = listOf(
                "En Phone Master → Gestor de arranque automático, permite PrivMsg.",
                "En «Recientes», bloquea PrivMsg 🔒.",
            ),
            screens = emptyList(),
        )

        // Pixel, Motorola, Nokia, Sony… usan Android tal cual: con la exención basta.
        else -> null
    }

    /**
     * Abre la pantalla del fabricante. Si su componente no existe (cambian de
     * nombre entre versiones), cae en la ficha de la app en Ajustes, que
     * siempre está.
     */
    fun openOemScreen(context: Context): Boolean {
        guide()?.screens?.forEach { component ->
            if (resolves(context, component)) {
                val ok = runCatching {
                    context.startActivity(
                        Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
        }
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    /** Comprueba que la pantalla existe antes de lanzarla, para no fallar. */
    private fun resolves(context: Context, component: ComponentName): Boolean = runCatching {
        val intent = Intent().setComponent(component)
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

    private fun matches(vararg names: String): Boolean {
        val haystack = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return names.any { haystack.contains(it) }
    }
}
