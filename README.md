# Twinsen Radio

*An open-source internet radio player for Android Auto, built for a specific
car (VW Passat B8 MY2020, Discover Pro / Active Info Display). Not on Google
Play — clone it, build it yourself with Android Studio or the command line,
side-load it. GPLv3, fork away.*

Odtwarzacz internetowych rozgłośni radiowych dla **Android Auto**, pisany pod
konkretne auto: **VW Passat B8 MY2020** z Discover Pro i Active Info Display.

**Ten projekt nie jest i nie będzie w Google Play.** To build-it-yourself —
klonujesz repo, budujesz Android Studio albo linią poleceń, wgrywasz na swój
telefon jako aplikację deweloperską. Kod jest w całości open source (GPLv3) —
forkuj, zmieniaj, rób z nim co chcesz, byle zgodnie z licencją.

---

## Dokumentacja

| Plik | O czym |
|---|---|
| [INSTRUKCJA.md](INSTRUKCJA.md) | Jak to zbudować, wgrać i uruchomić. Procedury, pułapki, rozwiązywanie problemów. |
| [KONCEPCJA.md](KONCEPCJA.md) | Jak to jest zbudowane i **dlaczego tak**. Architektura i uzasadnienia decyzji. |
| [BADANIA.md](BADANIA.md) | Co ustaliliśmy doświadczalnie: które pole gdzie trafia, jak zachowują się stacje, mapowanie ikon w DHU. |
| [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) | Pełna lista bibliotek i usług sieciowych, z jakich korzysta aplikacja. |
| [CLAUDE.md](CLAUDE.md) | Briefing dla nowej sesji Claude Code. Zacznij tam, jeśli wracasz do projektu po przerwie. |

---

## Co potrafi

* Odtwarzanie stacji internetowych, drzewo przeglądania w Android Auto:
  Ulubione, Wszystkie, Ostatnie, Gatunki.
* Wyszukiwanie stacji spoza wbudowanej listy w katalogu radio-browser.info —
  tekstem i głosem, z historią wyszukiwań.
* Wiele strumieni na stację (różne bitrate/kodeki) z ręcznym i pamiętanym
  wyborem jakości — także dla stacji wbudowanych.
* Ulubione i lista stacji synchronizowane **na żywo** między telefonem a
  autem; własne stacje (M3U albo z sieci), usuwanie/przywracanie, sortowanie.
* Własne logo stacji (wgrywane z telefonu) tam, gdzie wbudowane nie pasuje.
* Okładki utworów z iTunes, z zapasem w MusicBrainz i Cover Art Archive.
* Rozpoznawanie reklam, sloganów stacji, serwisów informacyjnych i znaczników
  sterujących w strumieniu ICY; normalizacja pisowni stacji nadających WIELKIMI
  LITERAMI.
* Ciche wznawianie po utracie zasięgu — do auta nie leci żaden komunikat błędu,
  a odtwarzacz aktywnie czeka na powrót sieci zamiast się poddawać.
* Wznawianie po podłączeniu wraca do **ostatnio słuchanej** stacji.
* Jakość strumienia (kodek, przepływność) widoczna i wybieralna na ekranie
  odtwarzania.
* **Zmierzone w aucie:** Active Info Display czyta pola `subtitle`,
  `description` i `displayTitle` — patrz [BADANIA.md](BADANIA.md). Treść
  każdego z trzech wierszy AID wybiera się osobno w Opcjach.

---

## Szybki start

```powershell
.\tools\install.ps1          # buduje i wgrywa na podłączony telefon
.\tools\dhu.bat               # wirtualna głowica na Windowsie, do testów bez auta
```

Skrypty w `tools/` są pisane pod Windows + PowerShell (tak pracuje autor). Na
Linuksie/macOS zbudujesz i wgrasz to samo ręcznie przez `./gradlew assembleDebug`
i `adb install` — patrz [INSTRUKCJA.md](INSTRUKCJA.md) po dokładne kroki,
wymagane wersje JDK/SDK i to, co trzeba pacnąć na telefonie (tryb dewelopera
Android Auto, zgoda na debugowanie USB).

---

## Stacje

32 rozgłośnie wpisane domyślnie w `app/src/main/assets/stations.json` — to
prywatny wybór autora, nie oficjalna lista partnerska żadnej z nich. Polskie:
Radio Nowy Świat, Radio 357, RMF (FM, MAXXX, Classic, 24, Polskie Przeboje,
80s, Ballady), Radio ZET, Chilli ZET, Meloradio, Antyradio, Rockserwis.fm,
TOK FM, Kiss FM, Złote Przeboje, Polskie Radio (Jedynka, Dwójka, Trójka,
Czwórka, 24, Kierowców), ESKA, ESKA ROCK, VOX FM, Radio Plus, Radio Wnet.
Zagraniczne: triple j (Australia), smoothfm 80s (Australia), Kiss FM
Australia, Jacaranda FM (RPA).

Własne stacje dokłada się na dwa sposoby: listą M3U w Opcjach albo wyszukiwarką
w sieci (menu ⋮ → *Szukaj stacji w sieci*, katalog radio-browser.info).

Domyślne logo każdej wbudowanej stacji to link do grafiki hostowanej przez
samą stację (patrz [Zastrzeżenia prawne](#zastrzeżenia-prawne)) — czasem więc
wygląda inaczej, niż byś chciał (inne tło, gorsza jakość, czasem żadne).
Jeśli wolisz swoją wersję, stuknij w logo na ekranie szczegółów stacji i
wgraj własny obrazek z telefonu — nadpisuje domyślne, tylko lokalnie u
Ciebie.

---

## Struktura projektu

```
app/src/main/java/net/mspanc/twinsenradio/
├── data/
│   ├── Station.kt               model stacji, warianty strumieni
│   ├── StationRepository.kt     assets + M3U + stacje z katalogu, sortowanie
│   ├── RadioBrowser.kt          wyszukiwanie w radio-browser.info
│   ├── StreamProbe.kt           sonda dostępności strumienia na żądanie
│   ├── M3uParser.kt             #EXTINF, tvg-logo, group-title
│   ├── Sorting.kt                kryteria sortowania list
│   ├── Prefs.kt                  ustawienia; ulubione i stacje z sieci jako StateFlow
│   └── Presentation.kt           układy opisu utworu, zegar, kolory
├── playback/
│   ├── RadioService.kt           MediaLibraryService: drzewo AA, sesja, ICY, okładki
│   ├── MetadataFactory.kt        budowa MediaMetadata; rozpoznawanie reklam i sloganów
│   ├── CoverArtLookup.kt         iTunes, potem MusicBrainz + Cover Art Archive
│   ├── LogoProvider.kt           logotypy pod stałym adresem content://
│   ├── TextCase.kt               normalizacja WIELKICH LITER i kolejności pól
│   ├── ClockArt.kt               zegar rysowany zamiast okładki
│   ├── StreamQuality.kt          opis kodeka i przepływności
│   ├── DiagnosticFields.kt       mapa pole → polska etykieta
│   ├── IcyFilteringDataSource.kt odcięcie ICY w trybie diagnostycznym
│   ├── InfiniteLoadErrorHandlingPolicy.kt
│   ├── ReconnectController.kt    ConnectivityManager + backoff + wykrywanie zawieszenia
│   ├── KeepCurrentStreamPlayer.kt        brak restartu grającej stacji
│   ├── ConnectionLog.kt          trwały log podłączających się odbiorników
│   └── PlaybackStatusBus.kt      kanał usługa → UI telefonu
└── ui/
    ├── MainActivity.kt           lista, wyszukiwarka, mini-odtwarzacz, zakładki
    ├── NowPlayingActivity.kt     pełnoekranowy odtwarzacz
    ├── DiscoverActivity.kt       wyszukiwanie stacji w sieci
    ├── StationInfoActivity.kt    szczegóły stacji, edycja strumieni/logo
    ├── StreamPicker.kt           wspólny dialog wyboru jakości
    ├── SettingsActivity.kt       Opcje
    ├── StationAdapter.kt         lista stacji (współdzielona)
    └── ArtworkLoader.kt          pobieranie okładek/logo do ImageView
```

Narzędzia warsztatowe są w [tools/](tools/) — opisane w [INSTRUKCJA.md](INSTRUKCJA.md).

---

## Zastrzeżenia prawne

* **Logotypy stacji.** Aplikacja nie rozdystrybuowuje żadnych logotypów
  rozgłośni — `stations.json` zawiera tylko adresy (`logoUrl`) wskazujące na
  grafiki hostowane przez same stacje albo publiczny katalog
  radio-browser.info, analogicznie do tego, jak przeglądarka pokazuje favikonę
  strony. Nazwy i znaki graficzne stacji są własnością ich nadawców; ten
  projekt nie jest z żadną z nich powiązany ani przez nią sponsorowany. Chcesz
  inny wygląd logo dla konkretnej stacji (np. inne tło, wyższa rozdzielczość) —
  wgraj własny obrazek na ekranie szczegółów stacji, patrz [Stacje](#stacje).
* **Strumienie audio.** Aplikacja jest cienkim klientem ICY/HLS — łączy się
  wyłącznie z publicznymi adresami strumieni udostępnianymi przez same stacje,
  niczego nie nagrywa ani nie retransmituje. Dostępność, legalność i prawa do
  treści każdego strumienia leżą po stronie nadawcy; upewnij się, że masz
  prawo słuchać danej stacji w swojej jurysdykcji.
* **Brak gwarancji.** Kod jest udostępniony na licencji GPLv3 „tak jak jest",
  bez żadnej gwarancji — patrz [LICENSE](LICENSE), sekcje 15–16.

---

## Licencja

```
Twinsen Radio — internetowy odtwarzacz radiowy dla Android Auto
Copyright (C) 2026  Leszek Szczepanowski

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Pełny tekst: [LICENSE](LICENSE). Lista bibliotek i ich licencji:
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
