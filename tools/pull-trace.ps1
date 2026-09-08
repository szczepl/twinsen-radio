# Sciaga slad przebiegu z telefonu i pokazuje krotkie podsumowanie.
#   .\tools\pull-trace.ps1                 -> do .\slady\
#   .\tools\pull-trace.ps1 -OutDir C:\tmp  -> gdzie indziej
#   .\tools\pull-trace.ps1 -Clear          -> po sciagnieciu kasuje z telefonu
param([string]$OutDir = "", [switch]$Clear)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$remote = '/sdcard/Android/data/net.mspanc.twinsenradio/files/trace'

if (-not $OutDir) {
    $OutDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'slady'
}
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force $OutDir | Out-Null }

$listing = & $adb shell "ls $remote 2>/dev/null"
if (-not $listing) {
    Write-Host "Nie ma zadnego sladu na telefonie. Wlaczony jest przelacznik w Opcjach?" -ForegroundColor Yellow
    exit 1
}

& $adb pull $remote $OutDir
if ($LASTEXITCODE -ne 0) { throw "adb pull sie nie powiodl" }

# Podsumowanie: ile czego jest w kazdym pliku. Zdarzenia sa w polu "e".
Get-ChildItem (Join-Path $OutDir 'trace') -Filter *.jsonl | ForEach-Object {
    $lines = [System.IO.File]::ReadAllLines($_.FullName, [System.Text.Encoding]::UTF8)
    $kinds = $lines |
        ForEach-Object { if ($_ -match '"e":"([^"]+)"') { $Matches[1] } } |
        Group-Object |
        Sort-Object Count -Descending |
        ForEach-Object { "$($_.Name)=$($_.Count)" }
    Write-Host ""
    Write-Host "$($_.Name)  ($($lines.Count) linii, $([Math]::Round($_.Length/1KB)) KB)" -ForegroundColor Cyan
    Write-Host ("  " + ($kinds -join '  '))
}

if ($Clear) {
    & $adb shell "rm -f $remote/*.jsonl"
    Write-Host ""
    Write-Host "Slad na telefonie skasowany." -ForegroundColor Yellow
}
