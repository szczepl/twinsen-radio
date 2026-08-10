# Narzedzia do pracy z telefonem podczas rozwoju aplikacji.
#
#   .\tools\phone.ps1 mirror     - podglad i sterowanie ekranem (scrcpy)
#   .\tools\phone.ps1 wireless   - przelacz adb na WiFi, kabel przestaje byc potrzebny
#   .\tools\phone.ps1 usb        - powrot na kabel
#   .\tools\phone.ps1 log        - logi aplikacji na zywo
#   .\tools\phone.ps1 play rns   - wlacz stacje o podanym id
#   .\tools\phone.ps1 stop       - ubij aplikacje
#   .\tools\phone.ps1 state      - stan sesji medialnej
param(
    [Parameter(Position = 0)][string]$Action = 'state',
    [Parameter(Position = 1)][string]$Arg
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$pkg = 'net.mspanc.twinsenradio'

switch ($Action) {
    'mirror' {
        # --stay-awake zeby ekran nie gasl w trakcie testow
        & scrcpy --stay-awake --window-title 'S20+ / Twinsen Radio'
    }

    'wireless' {
        $ip = (& $adb shell ip -f inet addr show wlan0 | Select-String -Pattern 'inet (\d+\.\d+\.\d+\.\d+)').Matches.Groups[1].Value
        if (-not $ip) { throw 'Nie moge odczytac adresu IP telefonu - czy WiFi jest wlaczone?' }
        & $adb tcpip 5555
        Start-Sleep -Seconds 3
        & $adb connect "${ip}:5555"
        Write-Host "Telefon dostepny pod ${ip}:5555 - kabel mozna odlaczyc." -ForegroundColor Green
        & $adb devices -l
    }

    'usb' {
        & $adb disconnect
        & $adb usb
        & $adb devices -l
    }

    'log' {
        & $adb logcat -c
        & $adb logcat -v brief -s RadioService:* ReconnectController:* BrowseTest:* StationRepository:* ExoPlayerImpl:* AndroidRuntime:E
    }

    'play' {
        if (-not $Arg) { throw 'Podaj id stacji, np. rns / r357 / chillizet' }
        & $adb shell am start -n "$pkg/.ui.MainActivity" --es play_station $Arg
    }

    'stop' {
        & $adb shell am force-stop $pkg
    }

    'state' {
        $d = & $adb shell dumpsys media_session
        $i = ($d | Select-String -Pattern $pkg | Select-Object -First 1).LineNumber
        if (-not $i) { Write-Host 'Brak sesji medialnej - aplikacja nie gra.'; break }
        $d[($i + 5)..([Math]::Min($i + 13, $d.Count - 1))]
    }

    default { Write-Host "Nieznana akcja: $Action" -ForegroundColor Yellow }
}
