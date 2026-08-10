@echo off
rem Uruchamia Desktop Head Unit (DHU 2.1) w wlasnym oknie konsoli.
rem
rem Dlaczego przez `start` / wlasna konsole: DHU ma interaktywny prompt. Gdy
rem dostanie stdin z NUL - a tak jest, gdy odpala je skrypt bez konsoli -
rem czyta EOF i natychmiast konczy prace, zabierajac ze soba okna projekcji.
rem
rem Serwer head unit wlacza sie raz: Android Auto -> menu ... -> "Wlacz serwer
rem radioodtwarzacza". Potem mozna zamykac i otwierac DHU do woli - Android Auto
rem podlacza sie samo. Przeklikiwanie WYLACZ -> WLACZ ratuje tylko wtedy, gdy
rem polaczenie w ogole nie chce wstac.
rem
rem Uzycie:
rem   dhu.bat              -> 1280x640, natywna geometria Discover Pro 9,2"
rem   dhu.bat small        -> 800x480  (Composition / Discover Media 8")
rem   dhu.bat 720          -> czyste 1280x720
rem   dhu.bat big          -> 1280x720 przy 240 dpi
rem   dhu.bat small cluster-> jw. + wirtualny zegar/AID
rem   dhu.bat small ontop  -> okna projekcji nad innymi (domyslnie wylaczone)

set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "DHUEXE=C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
set "CFG=%~dp0dhu-passat-discover-pro.ini"
set "EXTRA="

if /I "%~1"=="small" (
  set "CFG=%~dp0dhu-passat-composition.ini"
  if /I "%~2"=="cluster" set "EXTRA=-c "%~dp0dhu-passat-cluster.ini""
) else if /I "%~1"=="720" (
  set "CFG=%~dp0dhu-720p.ini"
  if /I "%~2"=="cluster" set "EXTRA=-c "%~dp0dhu-passat-cluster.ini""
) else if /I "%~1"=="big" (
  set "CFG=%~dp0dhu-720p-hidpi.ini"
  if /I "%~2"=="cluster" set "EXTRA=-c "%~dp0dhu-passat-cluster.ini""
) else if /I "%~1"=="cluster" (
  set "EXTRA=-c "%~dp0dhu-passat-cluster.ini""
)

if not exist "%DHUEXE%" (
  echo BLAD: nie znalazlem %DHUEXE%
  goto :end
)

rem -t trzyma okna projekcji nad wszystkimi innymi. Domyslnie WYLACZONE, bo
rem przeszkadza w pracy na innych oknach. Wlacz dopiskiem "ontop" w argumentach.
set "ONTOP="
echo %* | findstr /I /C:"ontop" >nul && set "ONTOP=-t"

"%ADB%" forward tcp:5277 tcp:5277

rem Okienko podgladu metadanych - osobne okno Windows, obok projekcji
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0meta-watch.ps1"

pushd "C:\Android\Sdk\extras\google\auto"
echo Startuje DHU, profil: %CFG% %EXTRA% %ONTOP%
"%DHUEXE%" %ONTOP% -c "%CFG%" %EXTRA%
popd

:end
echo.
echo DHU zakonczylo prace. Wystarczy uruchomic ponownie - Android Auto samo
echo podlaczy sie na nowo. Przeklikiwanie serwera head unit jest potrzebne
echo tylko wtedy, gdy polaczenie w ogole nie chce wstac.
