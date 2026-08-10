# Twinsen Radio

Odtwarzacz internetowych rozgłośni radiowych dla **Android Auto**, pisany pod
konkretne auto: **VW Passat B8 MY2020** z Discover Pro i Active Info Display.

Pierwsza iteracja jest **diagnostyczna**: w trybie diagnostycznym każde pole
metadanych dostaje swoją polską nazwę zamiast prawdziwej wartości, żeby dało się
rozpoznać, co dokładnie rysuje deska rozdzielcza. Tego nie da się sprawdzić na
biurku — kanał Instrument Cluster w protokole Android Auto przenosi wyłącznie
nawigację, więc AID rysuje samo auto z pól `MediaMetadata`.

---

## Dokumentacja

| Plik | O czym |
|---|---|
| [INSTRUKCJA.md](INSTRUKCJA.md) | Jak to zbudować, wgrać i uruchomić. Procedury, pułapki, rozwiązywanie problemów. |
| [KONCEPCJA.md](KONCEPCJA.md) | Jak to jest zbudowane i **dlaczego tak**. Architektura i uzasadnienia decyzji. |
| [BADANIA.md](BADANIA.md) | Co ustaliliśmy doświadczalnie: które pole gdzie trafia, jak zachowują się stacje, mapowanie ikon w DHU. |
| [CLAUDE.md](CLAUDE.md) | Briefing dla nowej sesji Claude Code. Zacznij tam, jeśli wracasz do projektu po przerwie. |

---

## Stan na dziś

Działa i jest sprawdzone na żywo:

* Odtwarzanie 32 stacji, wszystkie strumienie zweryfikowane sondą HTTP.
* Drzewo przeglądania w Android Auto: Ulubione, Wszystkie, Ostatnie, Gatunki.
* Wyszukiwanie głosem i tekstem, odporne na brak polskich znaków.
* Ulubione synchronizowane **na żywo** między telefonem a autem — lista, ekran
  odtwarzania i gwiazdka przy odtwarzaczu zmieniają się w tej samej chwili.
* Okładki utworów z iTunes, z zapasem w MusicBrainz i Cover Art Archive.
* Rozpoznawanie reklam, sloganów stacji, serwisów informacyjnych i znaczników
  sterujących w strumieniu ICY.
* Ciche wznawianie po utracie zasięgu — do auta nie leci żaden komunikat błędu.
* Wznawianie po podłączeniu wraca do **ostatnio słuchanej** stacji.
* Wyszukiwanie stacji spoza listy w katalogu radio-browser.info.
* Jakość strumienia (kodek, przepływność, próbkowanie) na ekranie odtwarzania.

Czeka na potwierdzenie w aucie:

* Które pola `MediaMetadata` trafiają na trzy linie Active Info Display.
* Zachowanie przy realnym połączeniu z MIB3 (log w `files/polaczenia.log`).

---

## Szybki start

```powershell
.\tools\install.ps1          # buduje i wgrywa na podłączony telefon
.\tools\dhu.bat              # wirtualna głowica na Windowsie
```

Szczegóły, wraz z tym co trzeba pacnąć na telefonie — w [INSTRUKCJA.md](INSTRUKCJA.md).

---

## Stacje

32 rozgłośnie w `app/src/main/assets/stations.json`. Polskie: Radio Nowy Świat,
Radio 357, RMF (FM, MAXXX, Classic, 24, Polskie Przeboje, 80s, Ballady), Radio
ZET, Chilli ZET, Meloradio, Antyradio, Rockserwis.fm, TOK FM, Kiss FM, Złote
Przeboje, Polskie Radio (Jedynka, Dwójka, Trójka, Czwórka, 24, Kierowców), ESKA,
ESKA ROCK, VOX FM, Radio Plus, Radio Wnet. Zagraniczne: triple j (Australia),
smoothfm 80s (Australia), Kiss FM Australia, Jacaranda FM (RPA).

Własne stacje dokłada się na dwa sposoby: listą M3U w Opcjach albo wyszukiwarką
w sieci (menu ⋮ → *Szukaj stacji w sieci*).

---

## Struktura projektu

```
app/src/main/java/net/mspanc/twinsenradio/
├── data/
│   ├── Station.kt              model stacji
│   ├── StationRepository.kt    assets + M3U + stacje z katalogu
│   ├── RadioBrowser.kt         wyszukiwanie w radio-browser.info
│   ├── M3uParser.kt            #EXTINF, tvg-logo, group-title
│   ├── Prefs.kt                ustawienia; ulubione i stacje z sieci jako StateFlow
│   └── Presentation.kt         układy opisu utworu, zegar, kolory
├── playback/
│   ├── RadioService.kt         MediaLibraryService: drzewo AA, sesja, ICY, okładki
│   ├── MetadataFactory.kt      budowa MediaMetadata; rozpoznawanie reklam i sloganów
│   ├── CoverArtLookup.kt       iTunes, potem MusicBrainz + Cover Art Archive
│   ├── LogoProvider.kt         logotypy pod stałym adresem content://
│   ├── ClockArt.kt             zegar rysowany zamiast okładki
│   ├── StreamQuality.kt        opis kodeka i przepływności
│   ├── DiagnosticFields.kt     mapa pole → polska etykieta
│   ├── IcyFilteringDataSource.kt        odcięcie ICY w trybie diagnostycznym
│   ├── InfiniteLoadErrorHandlingPolicy.kt
│   ├── ReconnectController.kt  ConnectivityManager + backoff
│   ├── KeepCurrentStreamPlayer.kt       brak restartu grającej stacji
│   ├── ConnectionLog.kt        trwały log podłączających się odbiorników
│   └── PlaybackStatusBus.kt    kanał usługa → UI telefonu
└── ui/
    ├── MainActivity.kt         lista, wyszukiwarka, mini-odtwarzacz
    ├── NowPlayingActivity.kt   pełnoekranowy odtwarzacz
    ├── DiscoverActivity.kt     wyszukiwanie stacji w sieci
    ├── SettingsActivity.kt     Opcje
    ├── StationAdapter.kt       lista stacji (współdzielona)
    └── ArtworkLoader.kt        pobieranie okładek do ImageView
```

Narzędzia warsztatowe są w [tools/](tools/) — opisane w [INSTRUKCJA.md](INSTRUKCJA.md).
