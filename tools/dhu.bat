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
rem   dhu.bat small        -> 800x480  (Composition / Discover Media 8")
rem   dhu.bat 720          -> 1280x720 przy 160 dpi (profil porownawczy)
rem   dhu.bat log          -> dodatkowo okno z podgladem metadanych
rem   dhu.bat ontop        -> okno projekcji nad innymi

setlocal
set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "DHUEXE=C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
set "CFG=%~dp0dhu-passat-discover-pro.ini"

echo %* | findstr /I /C:"small" >nul && set "CFG=%~dp0dhu-passat-composition.ini"
echo %* | findstr /I /C:"720"   >nul && set "CFG=%~dp0dhu-720p.ini"

rem -t trzyma okno projekcji nad wszystkimi innymi. Domyslnie WYLACZONE, bo
rem przeszkadza w pracy na innych oknach.
set "ONTOP="
echo %* | findstr /I /C:"ontop" >nul && set "ONTOP=-t"

if not exist "%DHUEXE%" (
  echo BLAD: nie znalazlem %DHUEXE%
  goto :end
)

rem Przekierowanie portu ginie przy kazdym przepieciu kabla, wiec ustawiamy je
rem przy kazdym starcie - bez tego DHU stoi na "Waiting for phone...".
"%ADB%" forward tcp:5277 tcp:5277

rem Podglad metadanych tylko na zyczenie - to kolejne okno, a domyslnie ma byc
rem widoczne wylacznie okno projekcji.
echo %* | findstr /I /C:"log" >nul && start "Twinsen Radio - metadane" cmd.exe /k "%~dp0meta-log.bat"

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
