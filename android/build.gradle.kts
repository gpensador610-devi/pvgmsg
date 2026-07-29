plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}

// El path del proyecto contiene "Área" (no-ASCII), lo que rompe el classpath de
// los tests unitarios. Compilamos fuera de OneDrive, en una ruta 100% ASCII
// (además evita que OneDrive sincronice cientos de MB de artefactos).
allprojects {
    layout.buildDirectory.set(file("C:/dev-builds/privmsg-android/${project.name}"))
}
