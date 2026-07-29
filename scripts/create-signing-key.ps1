# Crea la clave de firma de PrivMsg. Se ejecuta UNA SOLA VEZ.
#
# La contrasena se pide de forma interactiva: no viaja por parametros, no
# queda en el historial de la terminal y no aparece en la lista de procesos.
#
#   powershell -File scripts\create-signing-key.ps1
#
# Nota para quien lo edite: NO redirigir la salida de keytool con 2>&1.
# En PowerShell 5.1 eso convierte cada linea de progreso del programa en un
# ErrorRecord y, con ErrorActionPreference=Stop, aborta el script a mitad de
# la generacion de la clave. Se comprueba $LASTEXITCODE en su lugar.

$root = Split-Path -Parent $PSScriptRoot
$keyDir = "C:\privmsg-signing"
$keyFile = Join-Path $keyDir "privmsg-release.jks"
$propsFile = Join-Path $root "android\keystore.properties"

Write-Host ""
Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host "  Clave de firma de PrivMsg" -ForegroundColor Cyan
Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Esta clave es lo mas valioso del proyecto:" -ForegroundColor Yellow
Write-Host "   - Si la PIERDES, no podras volver a actualizar la app nunca."
Write-Host "   - Si te la ROBAN, pueden publicar actualizaciones falsas que"
Write-Host "     los telefonos aceptaran como legitimas. No hay revocacion."
Write-Host ""

if (Test-Path $keyFile) {
    Write-Host "  YA EXISTE una clave en $keyFile" -ForegroundColor Red
    Write-Host "  Si la sobrescribes, pierdes la capacidad de actualizar la app." -ForegroundColor Red
    $answer = Read-Host "  Escribe SOBRESCRIBIR para continuar, o Enter para cancelar"
    if ($answer -ne "SOBRESCRIBIR") {
        Write-Host "  Cancelado. No se toco nada." -ForegroundColor Green
        exit 0
    }
    Remove-Item $keyFile -Force
}

# --- Contrasena ---
Write-Host "  Elige una contrasena para la clave (minimo 8 caracteres)."
Write-Host "  Puedes generar una con: scripts\generate-password.ps1"
Write-Host ""

$pass1 = Read-Host "  Contrasena" -AsSecureString
$pass2 = Read-Host "  Repite la contrasena" -AsSecureString

$plain1 = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($pass1))
$plain2 = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($pass2))

if ($plain1 -ne $plain2) {
    Write-Host "`n  Las contrasenas no coinciden. Nada se creo." -ForegroundColor Red
    exit 1
}
if ($plain1.Length -lt 8) {
    Write-Host "`n  Demasiado corta (minimo 8). Nada se creo." -ForegroundColor Red
    exit 1
}

# --- Crear la clave ---
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
if (-not (Test-Path $keytool)) {
    Write-Host "`n  No se encontro keytool en $keytool" -ForegroundColor Red
    exit 1
}
New-Item -ItemType Directory -Force $keyDir | Out-Null

# La contrasena va por variable de entorno, no por parametro: asi no queda
# visible en la lista de procesos del sistema mientras se ejecuta.
$env:PRIVMSG_KEY_PASS = $plain1

Write-Host "`n  Generando clave RSA de 4096 bits (valida 27 anos)..." -ForegroundColor Cyan
Write-Host "  Puede tardar unos segundos.`n"

& $keytool -genkeypair `
    -keystore $keyFile `
    -storepass:env PRIVMSG_KEY_PASS `
    -keypass:env PRIVMSG_KEY_PASS `
    -alias privmsg `
    -keyalg RSA -keysize 4096 -validity 10000 `
    -dname "CN=PrivMsg, OU=PrivMsg, O=PrivMsg"

if (-not (Test-Path $keyFile)) {
    Remove-Item Env:\PRIVMSG_KEY_PASS -ErrorAction SilentlyContinue
    Write-Host "`n  No se pudo crear la clave." -ForegroundColor Red
    exit 1
}

# --- Huella del certificado, antes de borrar la variable de entorno ---
Write-Host ""
Write-Host "  Huella del certificado (publicala junto a cada APK):" -ForegroundColor Cyan
$fingerprint = (& $keytool -list -v -keystore $keyFile -alias privmsg `
    -storepass:env PRIVMSG_KEY_PASS | Select-String -Pattern "SHA256:" |
    Select-Object -First 1).ToString().Trim()
Write-Host "  $fingerprint" -ForegroundColor White

# --- keystore.properties para que Gradle firme sin preguntar ---
# Contiene la contrasena en claro: por eso esta en .gitignore y nunca sale
# de esta maquina.
@"
storeFile=$($keyFile -replace '\\','/')
storePassword=$plain1
keyAlias=privmsg
keyPassword=$plain1
"@ | Set-Content -Path $propsFile -Encoding ascii

# Limpiar la contrasena de memoria y del entorno.
Remove-Item Env:\PRIVMSG_KEY_PASS -ErrorAction SilentlyContinue
$plain1 = $null; $plain2 = $null
[System.GC]::Collect()

Write-Host ""
Write-Host "==============================================================" -ForegroundColor Green
Write-Host "  Clave creada correctamente" -ForegroundColor Green
Write-Host "==============================================================" -ForegroundColor Green
Write-Host "  Clave:  $keyFile"
Write-Host "  Config: $propsFile  (en .gitignore, no se sube)"
Write-Host ""
Write-Host "  AHORA: copia la clave a un USB y sacalo del ordenador." -ForegroundColor Yellow
Write-Host "  Listo. Avisa para compilar el APK firmado." -ForegroundColor Green
Write-Host ""
