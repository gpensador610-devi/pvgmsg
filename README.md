# PrivMsg — Mensajería privada E2E post-cuántica

App Android de mensajería con cifrado de extremo a extremo híbrido
(**X25519 + ML-KEM-768**, FIPS 203): para leer un mensaje habría que romper
tanto curvas elípticas como retículos. Sin servidores propios, sin cuentas,
sin teléfono ni email: la identidad es un par de claves generado en el
dispositivo, respaldado por 12 palabras, y los contactos se intercambian por QR.

## Estructura

```
core/      Núcleo criptográfico en Rust (UniFFI → Kotlin), 22 tests
android/   App Android (Kotlin + Jetpack Compose)
scripts/   Scripts de build
```

## Funcionalidad

**Identidad y respaldo**
- Identidad híbrida X25519 + ML-KEM-768 derivada de 12 palabras (BIP-39)
- Recuperación en cualquier teléfono conservando la misma huella
- Copia de seguridad cifrada exportable (Argon2id + XChaCha20-Poly1305)
- Perfil con nickname y foto, difundidos cifrados a los contactos

**Mensajería**
- Chats directos y **grupos** (cada mensaje se sella por separado para cada
  miembro: un grupo no debilita el cifrado)
- Texto, fotos (comprimidas y troceadas) y notas de voz
- **Autodestrucción** configurable por chat y general; el TTL viaja dentro del
  mensaje, así que se aplica en todos los teléfonos
- Silenciar chats, tono personalizado global y por chat, bloquear contactos
- **Bloqueo de capturas** (FLAG_SECURE, activado por defecto): capturas en
  negro, sin vista previa en «recientes» y sin grabación de pantalla
- **Bloqueo de la app** con PIN propio (Argon2id, distinto al del teléfono) y
  huella opcional, con bloqueo automático configurable y espera creciente tras
  intentos fallidos
- **PIN de emergencia**: un segundo PIN que borra todo en silencio y deja la
  app como recién instalada. No protege contra un adversario que clone el
  dispositivo antes; para eso lo que sirve es la autodestrucción

**Llamadas**
- Voz cifrada P2P (Opus 24 kbps) con claves de sesión derivadas del KEM híbrido
- UDP directo con perforación de NAT vía STUN público; sin relay de medios

**Transporte**
- WiFi local directa (mDNS + TCP) cuando el contacto está en la misma red
- Relays públicos Nostr como buzones tontos para el resto del mundo
- Etiquetas de encuentro que rotan a diario: los relays no saben quién habla
  con quién
- Servicio en primer plano + arranque tras reinicio: los mensajes llegan con la
  app cerrada
- Asistente de permisos al primer arranque, con guía por fabricante (Xiaomi,
  Huawei, Oppo, realme, vivo, OnePlus, Samsung, ASUS, Meizu, Transsion). En
  Pixel/Motorola/Nokia/Sony no aparece: con la exención estándar basta

## Compilar

Requisitos: Rust (targets Android), JDK 17, Android SDK + NDK, cargo-ndk.

```powershell
powershell -File scripts\build-core.ps1
```

```powershell
cd android; .\gradlew.bat assembleDebug
```

La APK queda en `C:\dev-builds\privmsg-android\app\outputs\apk\debug\app-debug.apk`
(el build se hace fuera de OneDrive porque el path del proyecto tiene acentos y
eso rompe el classpath de los tests y las herramientas de Rust).

Tests: `cargo test` en `core/` (22) y `.\gradlew.bat testDebugUnitTest` (12).

## Cifrado, en capas

1. **Establecimiento** — KEM híbrido X25519 + ML-KEM-768 (FIPS 203). Hay que
   romper curvas elípticas *y* retículos a la vez.
2. **Conversación** — Double Ratchet sobre esa raíz: clave nueva por mensaje,
   destruida al usarse (*forward secrecy*), y aleatoriedad fresca en cada
   respuesta (*post-compromise security*: la sesión se cura sola).
3. **Mensaje** — XChaCha20-Poly1305 con la cabecera del ratchet como AAD.
4. **En reposo** — AES-256 vía Android Keystore.

El ratchet DH usa X25519, pero su raíz nace del intercambio híbrido: romper
X25519 en el futuro no basta para reconstruirla. Es el razonamiento de PQXDH.

## Publicar una versión

### 1. Crear la clave de firma (una sola vez)

**Esta clave es lo más valioso del proyecto.** Android solo acepta una
actualización si va firmada con la misma clave que la versión instalada: si la
pierdes, no puedes volver a actualizar la app nunca; si te la roban, pueden
publicar actualizaciones que los teléfonos aceptarán como legítimas.

```bash
keytool -genkeypair -v -keystore privmsg-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias privmsg
```

Guarda el `.jks` **fuera del repositorio y fuera de OneDrive** (un USB
guardado físicamente). Después crea `android/keystore.properties`:

```properties
storeFile=C:/ruta/segura/privmsg-release.jks
storePassword=...
keyAlias=privmsg
keyPassword=...
```

Ese archivo está en `.gitignore` y no debe salir de tu máquina.

### 2. Compilar

```powershell
powershell -File scripts\build-release.ps1
```

Compila el core, genera el APK firmado con R8, verifica la firma e imprime el
SHA-256 y la huella del certificado.

### 3. Distribuir

Publica junto al APK **el SHA-256 y la huella del certificado**. Tus contactos
pueden comprobar que el archivo es el tuyo y no una versión manipulada.

## Limitaciones conocidas

- Las llamadas por internet fallan con **NAT simétrico** (típico de datos
  móviles); resolverlo requeriría un relay TURN, es decir, un servidor.
- El borrado por autodestrucción es **cooperativo**: como en cualquier app,
  no puede impedir que alguien fotografíe la pantalla con otro móvil.
- Las señales de llamada y el control de grupos **no pasan por el ratchet**
  (van solo con el sobre híbrido): las primeras son urgentes y llevan su propio
  secreto de sesión, las segundas pueden venir de alguien sin sesión aún.
