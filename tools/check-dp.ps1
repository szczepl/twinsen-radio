# Sprawdza, czy profil DHU da sie w ogole polaczyc. ASCII only.
#
# Android Auto wymaga co najmniej 480 dp wysokosci. Ponizej tego telefon zrywa
# lacze zaraz po negocjacji TLS, z komunikatem:
#   [E]: Failed to read from transport - disconnect. Exiting...
# Objaw jest mylacy - wyglada na klopot z polaczeniem, a nie z geometria.
#
# Przyklad z naszych prob (1280x640, czyli Discover Pro 9,2"):
#   213 dpi -> 481 dp -> laczy sie, pasek aplikacji na dole
#   240 dpi -> 427 dp -> zrywa lacze
param([Parameter(Mandatory = $true)][string]$Profile)

if (-not (Test-Path $Profile)) { exit 0 }
$lines = Get-Content $Profile

$res = $lines | Select-String '^\s*resolution\s*=\s*(\d+)x(\d+)'
$dpi = $lines | Select-String '^\s*dpi\s*=\s*(\d+)'
if (-not $res -or -not $dpi) { exit 0 }

$height = [int]$res.Matches[0].Groups[2].Value
$density = [int]$dpi.Matches[0].Groups[1].Value

$margin = 0
$m = $lines | Select-String '^\s*marginheight\s*=\s*(\d+)'
if ($m) { $margin = [int]$m.Matches[0].Groups[1].Value }

$dp = [math]::Round(($height - $margin) / ($density / 160.0))
if ($dp -lt 480) {
    Write-Host ("UWAGA: wysokosc {0} dp to ponizej wymaganych 480 dp." -f $dp)
    Write-Host "Telefon zerwie lacze zaraz po TLS. Zmniejsz dpi albo margines."
} else {
    Write-Host ("Wysokosc projekcji: {0}x{1} px, {2} dp - w porzadku." -f `
        $res.Matches[0].Groups[1].Value, ($height - $margin), $dp)
}
