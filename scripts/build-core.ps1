# Compila el core Rust para Android y genera los bindings Kotlin (UniFFI).
# Uso:  powershell -File scripts\build-core.ps1
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$core = Join-Path $root "core"

# binutils completos (as.exe/dlltool) para el host windows-gnu + cargo
$env:PATH = "C:\msys64\mingw64\bin;$env:USERPROFILE\.cargo\bin;$env:PATH"
$jniLibs = Join-Path $root "android\app\src\main\jniLibs"
$kotlinOut = Join-Path $root "android\app\src\main\java"

if (-not $env:ANDROID_NDK_HOME) {
    $ndkBase = "$env:LOCALAPPDATA\Android\Sdk\ndk"
    $latest = Get-ChildItem $ndkBase | Sort-Object Name -Descending | Select-Object -First 1
    $env:ANDROID_NDK_HOME = $latest.FullName
}
Write-Host "NDK: $env:ANDROID_NDK_HOME"

Push-Location $core
try {
    # 1. Librerías nativas para las ABIs de Android (arm64 primero: el 99% de los móviles).
    cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o $jniLibs build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk falló" }

    # 2. Build de host para que uniffi-bindgen pueda leer los metadatos.
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build (host) falló" }

    # 3. Bindings Kotlin.
    cargo run --release --bin uniffi-bindgen -- generate `
        --library target\release\privmsg_core.dll `
        --language kotlin `
        --out-dir $kotlinOut
    if ($LASTEXITCODE -ne 0) { throw "uniffi-bindgen falló" }

    Write-Host "`nCore compilado. Librerias en $jniLibs, bindings en $kotlinOut" -ForegroundColor Green
    Write-Host "Ahora: cd android; .\gradlew.bat assembleDebug" -ForegroundColor Cyan
}
finally {
    Pop-Location
}
