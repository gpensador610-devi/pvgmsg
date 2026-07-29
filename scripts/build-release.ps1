# Compila el APK de release firmado y muestra lo que hay que publicar con él.
#
# Requisito previo: haber creado la clave de firma y android/keystore.properties.
# Ver README (sección "Publicar una versión").
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

if (-not (Test-Path (Join-Path $android "keystore.properties"))) {
    Write-Host "FALTA android\keystore.properties: el APK saldria SIN FIRMAR." -ForegroundColor Red
    Write-Host "Crea la clave primero (ver README) y vuelve a ejecutar esto." -ForegroundColor Red
    exit 1
}

Write-Host "1/3  Compilando el core Rust..." -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "build-core.ps1")

Write-Host "`n2/3  Compilando el APK de release..." -ForegroundColor Cyan
Push-Location $android
try {
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "el build de release fallo" }
}
finally {
    Pop-Location
}

$apk = "C:\dev-builds\privmsg-android\app\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "no se encontro el APK en $apk" }

Write-Host "`n3/3  Verificando la firma..." -ForegroundColor Cyan
$buildTools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" |
    Sort-Object Name -Descending | Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"

& $apksigner verify --print-certs $apk

$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$size = [math]::Round((Get-Item $apk).Length / 1MB, 1)

Write-Host "`n=====================================================" -ForegroundColor Green
Write-Host " APK listo para publicar" -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Green
Write-Host " Archivo: $apk"
Write-Host " Tamano:  $size MB"
Write-Host " SHA-256: $hash"
Write-Host ""
Write-Host " Publica el SHA-256 y la huella del certificado de arriba" -ForegroundColor Yellow
Write-Host " junto al APK, para que tus contactos puedan verificarlo." -ForegroundColor Yellow
