@echo off
rem Podglad metadanych na zywo - wszystko, co rozglosnia wysyla w ICY oraz
rem komplet pol, ktore aplikacja wystawia do sesji medialnej.
rem
rem Odpal dwuklikiem albo z konsoli. Zamkniecie okna konczy podglad.
rem
rem Co zobaczysz:
rem   IcyMeta  - naglowki icy-* strumienia, surowy blok metadanych, rozbicie na
rem              wszystkie pary klucz='wartosc' oraz odstep od poprzedniego
rem              zdarzenia w sekundach
rem   MetaDump - komplet pol MediaMetadata wyslanych do Android Auto

title Twinsen Radio - metadane na zywo
set "ADB=C:\Android\Sdk\platform-tools\adb.exe"

echo Podglad metadanych. Wlacz stacje w aplikacji.
echo Ctrl+C konczy.
echo.

"%ADB%" logcat -c
"%ADB%" logcat -v time -s IcyMeta:I MetaDump:I
pause
