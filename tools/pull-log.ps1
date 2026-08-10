# Sciaga z telefonu wszystko, co wiemy o glowicy, ktora sie podlaczala.
#
# Uruchom po powrocie z auta, najlepiej ZANIM odlaczysz telefon od auta -
# czesc informacji zyje tylko poki dziala usluga Android Auto.
#
#   .\tools\pull-log.ps1

$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$pkg = 'net.mspanc.twinsenradio'
$out = Join-Path $PSScriptRoot '..\logi-glowicy'
New-Item -ItemType Directory -Force $out | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

Write-Host '--- 1. Nasz dziennik podlaczen ---' -ForegroundColor Cyan
$log = & $adb shell "run-as $pkg cat files/polaczenia.log" 2>&1
if ($log) {
    $path = Join-Path $out "polaczenia-$stamp.log"
    $log | Set-Content -Path $path -Encoding UTF8
    Write-Host "zapisano: $path"
    $log | Select-Object -Last 40
} else {
    Write-Host 'brak dziennika - aplikacja jeszcze sie z niczym nie polaczyla'
}

Write-Host ''
Write-Host '--- 2. Co Android Auto pamieta o glowicy ---' -ForegroundColor Cyan
# Tu siedzi model, producent, wersja protokolu i build oprogramowania glowicy.
$car = & $adb shell "dumpsys activity service com.google.android.projection.gearhead" 2>&1 |
    Select-String -Pattern 'last car info|CarInfoInternal'
if ($car) {
    $path = Join-Path $out "carinfo-$stamp.txt"
    $car | Set-Content -Path $path -Encoding UTF8
    Write-Host "zapisano: $path"
    $car
} else {
    Write-Host 'brak danych o glowicy - usluga Android Auto nie dziala.'
    Write-Host 'Podepnij telefon do auta i uruchom ten skrypt jeszcze raz.'
}

Write-Host ''
Write-Host '--- 3. Swieze logi Android Auto z bufora ---' -ForegroundColor Cyan
$gh = & $adb logcat -d -v time 2>&1 | Select-String -Pattern 'CAR\.|GAL\.|CarInfoInternal'
if ($gh) {
    $path = Join-Path $out "gearhead-$stamp.log"
    $gh | Set-Content -Path $path -Encoding UTF8
    Write-Host "zapisano: $path  ($($gh.Count) linii)"
} else {
    Write-Host 'bufor logcat nie zawiera juz wpisow Android Auto'
}
