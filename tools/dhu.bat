@echo off
rem Uruchamia Desktop Head Unit (DHU 2.1) w wlasnym oknie konsoli.
rem
rem Dlaczego przez `start` / wlasna konsole: DHU ma interaktywny prompt. Gdy
rem dostanie stdin z NUL - a tak jest, gdy odpala je skrypt bez konsoli -
rem czyta EOF i natychmiast konczy prace, zabierajac ze soba okna projekcji.
rem
rem KOLEJNOSC MA ZNACZENIE. Przed uruchomieniem na telefonie:
rem   Android Auto -> menu ... -> "Wlacz serwer radioodtwarzacza"
rem Kazde uruchomienie DHU zuzywa jedna sesje serwera. Jesli DHU sie rozlaczy,
rem przelacznik trzeba przeklikac WYLACZ -> WLACZ (sam napis "Wylacz serwer"
rem nie gwarantuje, ze nasluch faktycznie zyje).
rem
rem Uzycie:
rem   dhu.bat              -> 1280x720, duzy ekran (najblizsze Discover Pro 9,2")
rem   dhu.bat small        -> 800x480  (Composition / Discover Media 8")
rem   dhu.bat cluster      -> 1280x720 + wirtualny zegar/AID
rem   dhu.bat small cluster-> 800x480  + wirtualny zegar/AID

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

"%ADB%" forward tcp:5277 tcp:5277
pushd "C:\Android\Sdk\extras\google\auto"
echo Startuje DHU, profil: %CFG% %EXTRA%
rem -t trzyma okna projekcji na wierzchu (nowosc w DHU 2.1)
"%DHUEXE%" -t -c "%CFG%" %EXTRA%
popd

:end
echo.
echo DHU zakonczylo prace. Zanim sprobujesz ponownie, przeklikaj na telefonie
echo "Wylacz serwer radioodtwarzacza" a potem "Wlacz serwer radioodtwarzacza".
