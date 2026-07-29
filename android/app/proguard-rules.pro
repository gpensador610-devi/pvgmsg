# R8 en release: ofusca y reduce el APK.
#
# Lo delicado aquí es que UniFFI y JNA cruzan la frontera Kotlin↔Rust por
# reflexión y por nombre. Si R8 renombra esas clases, la app compila pero
# revienta en tiempo de ejecución al llamar al core. De ahí estas reglas.

# --- JNA: puente nativo que usa UniFFI ---
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# --- Bindings generados por UniFFI ---
# Las estructuras se mapean por nombre de campo desde Rust: no se pueden tocar.
-keep class uniffi.privmsg_core.** { *; }
-keep interface uniffi.privmsg_core.** { *; }

# --- ZXing (escaneo y generación de QR) ---
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# --- Tink, que respalda EncryptedSharedPreferences ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- OkHttp (WebSockets a los relays) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Nuestro servicio y receptor: se instancian por nombre desde el sistema ---
-keep class com.privmsg.app.PrivMsgService { *; }
-keep class com.privmsg.app.BootReceiver { *; }
-keep class com.privmsg.app.MainActivity { *; }
-keep class com.privmsg.app.PortraitCaptureActivity { *; }

# Quitar los logs de depuración del binario de release: no deben quedar
# rastros de huellas ni de actividad de red en logcat.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
