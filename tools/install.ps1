# Buduje APK i wgrywa go na podlaczony telefon.
#   .\tools\install.ps1            -> debug
#   .\tools\install.ps1 -Release   -> release (podpisany kluczem debug)
param([switch]$Release)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = 'C:\Android\Sdk'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'

$variant = if ($Release) { 'Release' } else { 'Debug' }
$apkDir = if ($Release) { 'release' } else { 'debug' }

Push-Location $root
try {
    & "$root\gradlew.bat" ":app:assemble$variant"
    if ($LASTEXITCODE -ne 0) { throw "Build sie nie powiodl" }
} finally {
    Pop-Location
}

$apk = Get-ChildItem "$root\app\build\outputs\apk\$apkDir" -Filter *.apk | Select-Object -First 1
if (-not $apk) { throw "Nie znalazlem APK" }
Write-Host "APK: $($apk.FullName)  ($([Math]::Round($apk.Length/1KB)) KB)"

$devices = & $adb devices | Select-String -Pattern "`tdevice$"
if (-not $devices) {
    Write-Host "Nie widze telefonu. Sprawdz: kabel w porcie danych, USB debugging wlaczone, zgoda na komputerze." -ForegroundColor Yellow
    & $adb devices -l
    exit 1
}

& $adb install -r -d $apk.FullName
& $adb shell am start -n net.mspanc.twinsenradio/.ui.MainActivity
