# Uruchamia Desktop Head Unit (emulator ekranu Android Auto) przeciwko
# podlaczonemu telefonowi.
#
#   .\tools\run-dhu.ps1                 -> profil 1280x640 (Discover Pro 9.2")
#   .\tools\run-dhu.ps1 -Small          -> profil 800x480  (Composition/Discover Media 8")
#
# Warunek wstepny: w aplikacji Android Auto na telefonie musi byc wlaczony
# tryb dewelopera oraz "Start head unit server".
param([switch]$Small)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$dhuDir = 'C:\Android\Sdk\extras\google\auto'
$config = if ($Small) { "$PSScriptRoot\dhu-passat-composition.ini" } else { "$PSScriptRoot\dhu-passat-discover-pro.ini" }

$devices = & $adb devices | Select-String -Pattern "`tdevice$"
if (-not $devices) { throw "Nie widze telefonu przez adb." }

# DHU rozmawia z telefonem przez lokalny port 5277.
& $adb forward tcp:5277 tcp:5277

Push-Location $dhuDir
try {
    Write-Host "Startuje DHU z profilem: $config"
    # Transport ADB (domyslny) na porcie 5277 przekierowanym powyzej.
    & "$dhuDir\desktop-head-unit.exe" -c $config
} finally {
    Pop-Location
    & $adb forward --remove tcp:5277
}
