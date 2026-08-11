@echo off
rem Uruchamia Desktop Head Unit (DHU 2.1) w wlasnym oknie konsoli.
rem
rem Domyslnie pojawia sie TYLKO okno projekcji. Zadnego Instrument Cluster ani
rem "Media Playback Status" - powody sa zapisane w plikach .ini, a w skrocie:
rem kanal clustera przenosi wylacznie nawigacje i nigdy nie pokaze tego, co
rem Passat rysuje na AID, a okno statusu psuje polskie znaki i zrobilo juz swoje.
rem
rem Dlaczego przez `start` / wlasna konsole: DHU ma interaktywny prompt. Gdy
rem dostanie stdin z NUL - a tak jest, gdy odpala je skrypt bez konsoli -
rem czyta EOF i natychmiast konczy prace, zabierajac ze soba okno projekcji.
rem
rem Serwer head unit wlacza sie raz: Android Auto -> menu ... -> "Wlacz serwer
rem radioodtwarzacza". Potem mozna zamykac i otwierac DHU do woli - Android Auto
rem podlacza sie samo. Przeklikiwanie WYLACZ -> WLACZ ratuje tylko wtedy, gdy
rem polaczenie w ogole nie chce wstac.
rem
rem Uzycie:
rem   dhu.bat              -> 1280x720 przy 240 dpi, tak jak wyglada w Passacie
rem   dhu.bat dpi=213      -> ta sama rozdzielczosc, inna gestosc
rem   dhu.bat margin=80    -> 1280x640, czyli natywna geometria Discover Pro 9,2"
rem   dhu.bat small        -> 800x480  (Composition / Discover Media 8")
rem   dhu.bat 720          -> 1280x720 przy 160 dpi (profil porownawczy)
rem   dhu.bat log          -> dodatkowo okno z podgladem metadanych
rem   dhu.bat ontop        -> okno projekcji nad innymi
rem
rem Argumenty mozna laczyc, np.:  dhu.bat margin=80 dpi=213 log
rem
rem UWAGA co do gestosci: trzymaj sie standardowych kubelkow Androida -
rem 160, 213, 240, 320. Przy 200 dpi projekcja wstala z dzwiekiem, ale BEZ
rem obrazu; wartosci spoza kubelkow potrafia tak wlasnie zawiesc.
rem
rem UWAGA co do marginesu: DHU przyjmuje tylko 800x480, 1280x720 i 1920x1080,
rem wiec 1280x640 robi sie przez margines. Skutek uboczny: proporcje zmieniaja
rem sie z 1,78 na 2,00 i Android Auto przerzuca pasek aplikacji na lewa krawedz.
rem W aucie pasek jest na dole - jesli chcesz to pogodzic, sprobuj przestawic
rem uklad w ustawieniach Android Auto NA EKRANIE projekcji (kolo zebate ->
rem "Zmien uklad"), a nie w aplikacji na telefonie.

setlocal
set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "DHUEXE=C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
set "CFG=%~dp0dhu-passat-discover-pro.ini"

rem Argumenty czytamy petla z `shift`.
rem
rem Uwaga na pulapke cmd: znak rownosci jest separatorem argumentow, wiec
rem "dpi=200" dociera tu jako DWA argumenty - "dpi" i "200". Dlatego wartosc
rem gestosci bierzemy z nastepnego argumentu, a nie z tego samego. Dziala przez
rem to zarowno "dpi=200", jak i "dpi 200".
set "ONTOP="
set "DPI="
set "MARGIN="
set "SHOWLOG="

:parse
if "%~1"=="" goto :parsed
set "ARG=%~1"
if /I "%ARG%"=="dpi"    goto :takedpi
if /I "%ARG%"=="margin" goto :takemargin
if /I "%ARG%"=="small"  set "CFG=%~dp0dhu-passat-composition.ini"
if /I "%ARG%"=="720"    set "CFG=%~dp0dhu-720p.ini"
if /I "%ARG%"=="ontop"  set "ONTOP=-t"
if /I "%ARG%"=="log"    set "SHOWLOG=1"
shift
goto :parse

:takedpi
shift
set "DPI=%~1"
shift
goto :parse

:takemargin
shift
set "MARGIN=%~1"
shift
goto :parse

:parsed

if not exist "%DHUEXE%" (
  echo BLAD: nie znalazlem %DHUEXE%
  goto :end
)

if not defined DPI if not defined MARGIN goto :nooverride
set "TMPCFG=%TEMP%\dhu-twinsen-override.ini"
copy /y "%CFG%" "%TMPCFG%" >nul
if defined DPI powershell -NoProfile -Command "(Get-Content '%TMPCFG%') -replace '^\s*dpi\s*=.*', 'dpi = %DPI%' | Set-Content -Encoding ASCII '%TMPCFG%'"
if defined MARGIN powershell -NoProfile -Command "$c = @(Get-Content '%TMPCFG%') -replace '^\s*marginheight\s*=.*', 'marginheight = %MARGIN%'; if (-not ($c -match '^\s*marginheight')) { $c = $c -replace '^(\s*resolution\s*=.*)$', ('$1' + [Environment]::NewLine + 'marginheight = %MARGIN%') }; $c | Set-Content -Encoding ASCII '%TMPCFG%'"
set "CFG=%TMPCFG%"
if defined DPI    echo Gestosc nadpisana na %DPI% dpi.
rem marginheight to LACZNA obcinana wysokosc, nie na strone - tak podaje
rem dokumentacja: 1280x720 z marginheight 120 daje ekran 600 px wysokosci.
if defined MARGIN echo Wysokosc obcieta o %MARGIN% px.

:nooverride

rem Ostrzezenie o kombinacji, ktora sie nie polaczy - patrz check-dp.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-dp.ps1" "%CFG%"

rem Przekierowanie portu ginie przy kazdym przepieciu kabla, wiec ustawiamy je
rem przy kazdym starcie - bez tego DHU stoi na "Waiting for phone...".
"%ADB%" forward tcp:5277 tcp:5277

rem Podglad metadanych tylko na zyczenie - to kolejne okno, a domyslnie ma byc
rem widoczne wylacznie okno projekcji. Uwaga: `cmd /k` zostawia je otwarte po
rem zamknieciu DHU, wiec zamykaj je sam albo nie uzywaj tego przelacznika.
if defined SHOWLOG start "Twinsen Radio - metadane" cmd.exe /k "%~dp0meta-log.bat"

pushd "C:\Android\Sdk\extras\google\auto"
echo Startuje DHU, profil: %CFG% %ONTOP%
"%DHUEXE%" %ONTOP% -c "%CFG%"
popd

:end
echo.
echo DHU zakonczylo prace. Wystarczy uruchomic ponownie - Android Auto samo
echo podlaczy sie na nowo. Przeklikiwanie serwera head unit jest potrzebne
echo tylko wtedy, gdy polaczenie w ogole nie chce wstac.
endlocal
