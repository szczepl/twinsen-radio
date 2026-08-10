# Twinsen Radio

Odtwarzacz polskich rozgłośni internetowych z obsługą Android Auto.
Pierwsza iteracja jest **diagnostyczna**: każde pole metadanych dostaje swoją
polską nazwę zamiast prawdziwej wartości, żeby dało się rozpoznać, co konkretnie
wyświetla Active Info Display w VW Passacie MY2020.

---

## 1. Co zostało zainstalowane na tym Windowsie

| Składnik | Ścieżka | Wersja |
|---|---|---|
| JDK (Temurin) | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` | 17.0.20 |
| Android SDK | `C:\Android\Sdk` | platform 35 i 36, build-tools 35.0.1 / 36.1.0 |
| platform-tools (`adb`) | `C:\Android\Sdk\platform-tools` | 37.0.1 |
| Desktop Head Unit | `C:\Android\Sdk\extras\google\auto` | 2.0 |
| Google USB Driver | `C:\Android\Sdk\extras\google\usb_driver` | 13 |
| Gradle | `C:\Android\tools\gradle-8.14.3` | 8.14.3 |

Zmienne `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` oraz `PATH` są ustawione
na poziomie użytkownika. Kompilator Kotlina nie jest instalowany osobno — Gradle
ściąga go sam (Kotlin 2.2.21, AGP 8.13.2).

---

## 2. Budowanie

```powershell
cd C:\Users\twinsen\projects\twinsen-radio
.\gradlew.bat :app:assembleDebug
```

APK ląduje w `app\build\outputs\apk\debug\app-debug.apk`.

Skrót, który buduje i od razu wgrywa na podłączony telefon:

```powershell
.\tools\install.ps1
```

---

## 3. Wgranie na telefon (Galaxy S20+, Android 13)

**Na telefonie, po kolei:**

1. *Ustawienia → Informacje o telefonie → Informacje o oprogramowaniu* →
   stuknij **7× w „Numer kompilacji”**. Pojawią się Opcje programisty.
2. *Ustawienia → Opcje programisty* → włącz **Debugowanie USB**.
3. Podłącz kabel USB-C do laptopa. Wybierz tryb **Transfer plików / MTP**
   (przy „Tylko ładowanie” adb bywa niewidoczny).
4. Na telefonie wyskoczy okno **„Zezwolić na debugowanie USB?”** — to jest to
   miejsce, w którym trzeba „pacnąć”. Zaznacz *Zawsze zezwalaj z tego komputera*
   i potwierdź.

**Na laptopie:**

```powershell
adb devices -l     # musi pokazać urządzenie ze statusem "device"
.\tools\install.ps1
```

Jeśli `adb devices` pokazuje `unauthorized` — okno zgody nie zostało
potwierdzone. Jeśli lista jest pusta — najczęściej kabel jest tylko do ładowania
albo tryb USB stoi na „Tylko ładowanie”.

**Uprawnienia w samej aplikacji:** przy pierwszym uruchomieniu poprosi o zgodę
na powiadomienia (Android 13 tego wymaga dla powiadomienia odtwarzania). Zezwól —
bez tego usługa pierwszoplanowa nie pokaże kontrolek. Nic więcej nie potrzebuje,
roota też nie.

---

## 4. Android Auto — włączenie aplikacji spoza sklepu

Android Auto domyślnie odmawia uruchamiania aplikacji, których nie ma w Google
Play. Trzeba to odblokować raz:

1. Otwórz aplikację **Android Auto** na telefonie
   (*Ustawienia → Aplikacje → Android Auto → Ustawienia dodatkowe*).
2. Przewiń na dół i stuknij **10× w „Wersja”**, aż pojawi się zgoda na tryb
   dewelopera — potwierdź.
3. Menu ⋮ w prawym górnym rogu → **Ustawienia dla programistów**.
4. Włącz **„Nieznane źródła”** (*Unknown sources*).
5. Tam samo włącz **„Uruchom serwer jednostki głównej”** (*Start head unit
   server*) — to jest potrzebne do DHU, patrz niżej.

Po tym Twinsen Radio pojawi się na liście źródeł dźwięku w aucie.

---

## 5. Desktop Head Unit — próba na biurku

Zainstalowana jest **DHU 2.1** (build 2022-12-15). Uwaga: w domyślnym listingu
`sdkmanager` widać tylko 2.0 — 2.1 siedzi w kanale preview i trzeba go pobrać
jawnie:

```powershell
sdkmanager --sdk_root=C:\Android\Sdk --channel=3 "extras;google;auto"
```

Uruchamianie:

```powershell
.\tools\dhu.bat                  # 1280x640 - natywna geometria Discover Pro 9,2"
.\tools\dhu.bat small            # 800x480  - Composition / Discover Media 8"
.\tools\dhu.bat cluster          # jw. + wirtualny zegar/AID
.\tools\dhu.bat small cluster
```

### Trzy rzeczy, bez których to nie ruszy

Wszystkie trzy kosztowały nas po kilka podejść, więc są tu zapisane wprost.

1. **DHU musi dostać własną konsolę.** Ma interaktywny prompt; uruchomione ze
   skryptu bez konsoli dostaje EOF na stdin i kończy pracę w ułamku sekundy,
   zabierając ze sobą okna projekcji. Stąd `cmd /c start` w `dhu.bat`, a nie
   zwykłe wywołanie.

2. **USB nie może być w trybie „tylko debugowanie".** Przy gołym adb
   (`kernel_function_list=adb`) połączenie dochodzi do końca handshake'u TLS,
   po czym car service loguje `Detected charge only` oraz
   `Critical error 18 ... Failed to read message` i zrywa sesję. Trzeba
   przełączyć USB na **Transfer plików / Android Auto** — wtedy lista funkcji
   to `mtp,acm,conn_gadget,adb,ss_mon` i projekcja wstaje.

3. **Serwer head unit trzeba przeklikać wyłącz → włącz przed każdym startem DHU.**
   Sam napis „Wyłącz serwer radioodtwarzacza" w menu nie oznacza, że nasłuch
   żyje — zdarzało się, że przełącznik pokazywał „włączony", a telefon nie
   odnotował ani jednej próby połączenia. Każde uruchomienie DHU zużywa jedną
   sesję serwera.

Menu jest w: Android Auto → ⋮ → „Włącz serwer radioodtwarzacza". W tym samym
menu jest „Ustawienia programisty" → **Tryb aplikacji: Deweloperska** — to
nowszy odpowiednik dawnych „Nieznanych źródeł", bez którego AA nie pokaże
aplikacji spoza sklepu.

### Rozdzielczości: sztuczka z marginesem

DHU przyjmuje wyłącznie 800x480, 1280x720 i 1920x1080 — natywnych 1280x640
Discover Pro nie da się wpisać wprost. Rozwiązanie podpatrzone w
`config/default_wide.ini` Google'a: wziąć 1280x720 i obciąć marginesem.

```ini
resolution = 1280x720
marginheight = 80      ; efektywnie 1280x640
```

### Układ ekranu: geometria **i** przełącznik w AA

Geometria robi swoje — nasze pomiary przy `dpi = 160`:

| profil | proporcje | co wyszło |
|---|---|---|
| 800x480 | 1,67 | pasek aplikacji na dole |
| 1280x720 | 1,78 | pulpit Coolwalk, pasek z lewej |
| 1280x640 | 2,00 | jw. |

Ale to nie cała prawda. Pozycję paska da się przestawić ręcznie, tyle że
ustawienie jest **w interfejsie Android Auto na ekranie głowicy** (kółko zębate
w rogu projekcji), a nie w aplikacji Android Auto na telefonie:

> **Szybkie sterowanie aplikacjami** (*Show Quick Controls for Apps*)
> * włączone → pasek na **dole** (musi zmieścić szybkie sterowanie)
> * wyłączone → pasek **z boku**

Druga zasada: pełny ekran aplikacji przerzuca pasek z powrotem na bok. Czyli
„odtwarzacz na całą szerokość" i „pasek na dole" to w dużej mierze wybór
albo-albo.

Żadne z tego nie zależy od naszej aplikacji — `CONTENT_STYLE_*` steruje wyłącznie
wyglądem list w przeglądarce mediów, nie układem systemowym.

Źródła: [How to Move the Android Auto Taskbar to the Side (Or Bottom)](https://www.howtogeek.com/how-to-move-the-android-auto-taskbar-to-the-side-or-bottom/),
[Navigation bar — Android for Cars design](https://developers.google.com/cars/design/android-auto/product-experience/system-ui/nav-bar)

### Instrument Cluster — czego DHU *nie* pokaże

Profile w `tools/` mają `instrumentcluster = true`, ale trzeba wiedzieć, co to
naprawdę daje. Kanał clustera w protokole Android Auto obsługuje wyłącznie
nawigację — w binarce DHU są tylko komunikaty
`INSTRUMENT_CLUSTER_NAVIGATION_STATE`, `..._TURN_EVENT`, `..._DISTANCE_EVENT`
i pokrewne. **Nie ma kanału wideo dla mediów.**

Wniosek praktyczny: tego, co Passat rysuje na AID podczas odtwarzania muzyki,
nie da się zobaczyć w DHU. Ten ekran renderuje samo auto, na podstawie pól
`MediaMetadata` przesłanych przez sesję medialną. Dlatego tryb diagnostyczny
z punktu 6 trzeba przeklikać w aucie — i dlatego w ogóle powstał.

Rozdzielczości, dla porządku:

* Digital Cockpit Pro w Passacie B8 (10,25") — **1280 × 480** na cały wyświetlacz.
* Obszar treści między zegarami — **szacunkowo ~500 × 400 px** w widoku ze
  zmniejszonymi zegarami i węższy pasek (~400 × 200) w widoku klasycznym.
  To jest oszacowanie z proporcji ekranu, nie specyfikacja VW — potraktuj jako
  punkt wyjścia i skoryguj po pierwszej jeździe.
* Ekran centralny: Composition/Discover Media 8" — 800 × 480; Discover Pro
  9,2" — 1280 × 640.

---

## 6. Tryb diagnostyczny metadanych

Domyślnie **włączony**. Zamiast tytułu utworu aplikacja wstawia w każde pole
jego polską nazwę:

| pole API (Media3) | wartość wysyłana |
|---|---|
| `title` | TYTUŁ |
| `artist` | ARTYSTA |
| `albumTitle` | ALBUM |
| `albumArtist` | ART.ALBUMU |
| `displayTitle` | TYT.WYŚW |
| `subtitle` | PODTYTUŁ |
| `description` | OPIS |
| `station` | STACJA |
| `genre` | GATUNEK |
| `composer` | KOMPOZYTOR |
| `writer` | AUTOR |
| `conductor` | DYRYGENT |
| `compilation` | SKŁADANKA |
| `trackNumber` / `totalTrackCount` | 11 / 22 |
| `discNumber` / `totalDiscCount` | 3 / 4 |
| `recordingYear` / `releaseYear` | 1979 / 1983 |

Etykiety są celowo krótkie — na AID jest mało miejsca i dłuższe napisy zostaną
przycięte. W Opcjach można włączyć dopisywanie nazwy pola z API
(`TYTUŁ<title>`), jeśli okaże się, że skróty są niejednoznaczne.

Żeby to działało deterministycznie, w trybie diagnostycznym aplikacja **odcina
metadane ICY** — inaczej ExoPlayer nadpisałby `title`, `station` i `genre` tym,
co przysyła Icecast, i na desce zobaczyłbyś nazwę rozgłośni zamiast etykiety.
Realizuje to `IcyFilteringDataSource`.

Po zakończeniu pozycjonowania: *Opcje → wyłącz tryb diagnostyczny*. Wtedy
aplikacja wraca do normalnych metadanych, z tytułem utworu z ICY.

---

## 7. Opcje

* **Schemat Android Auto — foldery / stacje.** Cztery układy, które AA realnie
  udostępnia aplikacjom medialnym: `LIST`, `GRID`, `CATEGORY_LIST`,
  `CATEGORY_GRID`. To są klucze `CONTENT_STYLE_*_HINT` w rozszerzeniach
  MediaBrowser. Zmiana działa od razu — AA dostaje `notifyChildrenChanged`.
  Uwaga: motyw dzień/noc i kolorystyka to domena auta, aplikacja tego nie
  przestawia.
* **Bufor strumienia.** Mały (~20 s zapasu, start ~1 s), Średni (45 s, start
  ~2 s, domyślny), Duży (120 s, start ~4 s). Wchodzi w życie po restarcie
  odtwarzania. Realny zapas ogranicza serwer — większość Icecastów wysyła
  „burst on connect” rzędu kilkudziesięciu kB, więc bufor napełnia się w tempie
  odtwarzania.
* **Źródło okładki.** `android.resource://` (domyślne), bajty PNG w
  `artworkData` (gdy głowica nie radzi sobie z URI), albo brak — do sprawdzenia,
  co AID pokazuje bez grafiki.
* **Własne listy M3U.** Jeden URL na linię. Parser czyta `#EXTINF` razem
  z `tvg-logo` i `group-title`.

---

## 8. Zachowanie przy utracie zasięgu

Dwie warstwy, obie celowo ciche:

1. `InfiniteLoadErrorHandlingPolicy` — ExoPlayer ponawia pobranie
   w nieskończoność z narastającym opóźnieniem (0,5 s → 15 s). Odtwarzacz
   zostaje w stanie `BUFFERING`, do Android Auto **nie leci żaden błąd**.
   Wyjątek: trwałe 4xx (np. 404 na wycofanym adresie) przepuszczamy dalej, bo
   samo się to nie naprawi.
2. `ReconnectController` — nasłuchuje `ConnectivityManager` i wznawia
   natychmiast, gdy telefon odzyska zwalidowaną sieć, zamiast czekać na kolejny
   krok backoffu. Równolegle trzyma własny backoff na wypadek, gdy sieć formalnie
   jest, ale CDN nie odpowiada.

Dodatkowo `WAKE_MODE_NETWORK` trzyma WiFi/radio przy życiu, a
`handleAudioBecomingNoisy` pauzuje przy rozłączeniu Bluetooth.

---

## 9. Stacje

26 rozgłośni w `app/src/main/assets/stations.json`, wszystkie sprawdzone
zapytaniem HTTP z nagłówkiem `Icy-MetaData: 1`. 20 logotypów w
`res/drawable-nodpi` (512×512 PNG); tam gdzie nie udało się znaleźć oficjalnej
grafiki — Radio Nowy Świat ma prawdziwe logo, Polskie Radio i Trójka mają
wygenerowane kafelki z nazwą.

Uwagi z weryfikacji:

* **RMF FM** wstrzykuje w strumień reklamy, oznaczone `adw_ad='true'` z pustym
  `StreamTitle`. Parser `NowPlaying.parse` odrzuca puste tytuły, więc na desce
  nie miga pustka.
* **Radio Nowy Świat** podaje sensowny `StreamTitle` (np. nazwę audycji).
* **Trójka** działa przez HLS (`playlist.m3u8`) — stąd zależność
  `media3-exoplayer-hls`.
* Jedynka, Dwójka, Czwórka, PR24 i Radio Kierowców korzystają z Icecasta na
  portach 8900–8918. **Nie udało się ich zweryfikować z tej sieci** (timeout,
  najpewniej blokada wysokich portów wychodzących) — adresy są standardowe
  i powinny działać na LTE, ale to jedyne pozycje na liście bez potwierdzenia.

---

## 10. Co zostało sprawdzone na emulatorze (API 33)

Zanim APK trafił na telefon, przeszedł test na emulatorze — nie tylko „czy się
uruchamia":

* Aplikacja startuje bez wyjątku, lista renderuje się z logotypami i polskimi
  napisami.
* Odtwarzanie działa dla MP3 (Radio Nowy Świat), AAC (Radio 357) i strumienia
  z przekierowaniem (Chilli ZET). `dumpsys audio` potwierdza aktywny
  `AudioTrack` z `usage=USAGE_MEDIA`, dekoder AAC 48 kHz stereo.
* `dumpsys media_session` pokazuje `state=3` (PLAYING) i `metadata: size=16,
  description=TYT.WYŚW, PODTYTUŁ, OPIS` — czyli diagnostyczne etykiety
  faktycznie przechodzą przez sesję medialną tak, jak przeczyta je głowica.
* Powiadomienie ma `android.title=TYTUŁ`, `android.text=ARTYSTA` — to dowód, że
  odcięcie ICY działa: prawdziwy `StreamTitle` z Icecasta **nie** nadpisuje pola
  `title`.
* Ścieżka `MediaBrowserCompat` (dokładnie ta, której używa Android Auto) została
  sprawdzona osobnym klientem testowym `BrowseTestActivity` (tylko wariant
  debug): korzeń `/`, 4 zakładki, 26 stacji w „Wszystkie stacje", 17 gatunków,
  3 ostatnio słuchane, ikony jako `android.resource://`, poprawne flagi
  browsable/playable.
* Schemat prezentacji dociera do klienta: `supported=true, browsable=3
  (CATEGORY_LIST), playable=1 (LIST)`.
* Wyszukiwanie działa i ignoruje diakrytyki: `"nowy swiat"` → Radio Nowy Świat,
  `"rock"` → Antyradio, ESKA ROCK.

Klient testowy uruchamia się tak:

```powershell
adb shell am start -n net.mspanc.twinsenradio/.debug.BrowseTestActivity
adb logcat -s BrowseTest RadioService
```

Można też włączyć stację bez dotykania ekranu (przydatne przy testach z DHU):

```powershell
adb shell am start -n net.mspanc.twinsenradio/.ui.MainActivity --es play_station rns
```

### Znane, nieistotne dla auta

SystemUI zgłasza `Cannot resume with ComponentInfo{...RadioService}` — to sonda
„wznowienia odtwarzania" z panelu powiadomień. Media3 odrzuca to połączenie
**wewnątrz swojego legacy stuba, zanim wywoła kod aplikacji** (nasz
`onGetLibraryRoot` w ogóle się nie odpala dla tego zapytania). Zwykłe
przeglądanie — to, z czego korzysta Android Auto — działa bez zarzutu, co
potwierdza test powyżej. Efekt jest czysto kosmetyczny: brak kafelka „wznów"
w cieniu powiadomień po restarcie telefonu.

---

## 11. Pierwsze wyniki eksperymentu z metadanymi

Zebrane **na realnym Galaxy S20+ w projekcji Android Auto** (DHU 2.1, 2026-08-10).
To jeszcze nie AID w Passacie, ale już konkret o tym, które pola dokąd trafiają.

### Ekran odtwarzania Android Auto

| co widać | z którego pola |
|---|---|
| duża linia | `displayTitle` |
| mała linia pod nią | `subtitle` |
| okładka | `artworkUri` (`android.resource://` działa) |

Ani `title`, ani `artist` **nie pojawiają się** na tym ekranie — mimo że są
ustawione. Android Auto bierze warianty „display".

### Okno „Media Playback Status" w DHU

| etykieta DHU | z którego pola |
|---|---|
| Song | `displayTitle` |
| Artist | `subtitle` |
| Album | `description` |

`description` lądujący w polu „Album" to najmniej oczywisty wynik z całej
serii — warto o nim pamiętać przy układaniu docelowych metadanych.

### Dlaczego etykiety są bez polskich znaków

Projekcja AA renderuje diakrytyki poprawnie („TYT.WYŚW" wyświetlało się dobrze),
ale okno „Media Playback Status" w DHU czyta UTF-8 jak Latin-1 i pokazuje
`TYT.WYÅ?W`. Ponieważ etykieta ma służyć do rozpoznania pola, a nie do
typografii — a o możliwościach fontów w desce Passata nic pewnego nie wiemy —
wszystkie etykiety są w czystym ASCII.

### AID w Passacie — obserwacje z jazdy (inne aplikacje)

Zanim jeszcze pojechaliśmy z własną aplikacją, wiadomo z obserwacji ReplaIO
i oficjalnej apki Radia Nowy Świat, że **Active Info Display pokazuje trzy linie
tekstu i małą grafikę** — czyli o jedną linię więcej niż ekran centralny AA:

```
        [ kwadrat: logo stacji albo okladka ]
        wykonawca
        nazwa stacji
        tytul
```

Istotny szczegół: oficjalna apka RNŚ **nie** wyświetla nazwy stacji w środkowej
linii, ReplaIO wyświetla ją zawsze. To znaczy, że środkowa linia nie jest
generowana przez głowicę z nazwy źródła — bierze się z pola, które aplikacja
albo wypełnia, albo nie.

Stąd hipoteza do potwierdzenia jednym spojrzeniem w trybie diagnostycznym:

| linia na AID | prawdopodobne pole |
|---|---|
| górna | `artist` |
| środkowa | `albumTitle` |
| dolna | `title` |

Byłby to klasyczny układ artysta / album / tytuł, w którym radiowe aplikacje
wpisują nazwę stacji w `albumTitle`. Build diagnostyczny pokazuje w każdym polu
jego nazwę razem z zegarem, więc wystarczy odczytać, co się wyświetli.

### Czego robić nie należy: podwójna nazwa stacji

ReplaIO ma wadę wartą uniknięcia. Gdy nie leci utwór, wpisuje nazwę stacji
także w `displayTitle`, przez co na ekranie widać dwa razy to samo —
„Radio Nowy Świat" nad „Radio Nowy Świat". Docelowe metadane muszą traktować
brak utworu jako osobny przypadek, a nie powielać tę samą wartość w kilku
polach.

### Co zostało do sprawdzenia w aucie

Sam AID. Kanał Instrument Cluster w protokole AA przenosi wyłącznie nawigację
(patrz punkt 5), więc kafelka muzyki na zegarach nie da się podejrzeć na
biurku — rysuje go głowica z pól `MediaMetadata`. To jedyna rzecz, po którą
trzeba pojechać.

---

## 12. Struktura projektu

```
app/src/main/java/net/mspanc/twinsenradio/
├── App.kt
├── data/
│   ├── Station.kt              model stacji
│   ├── StationRepository.kt    assets + listy M3U, wyszukiwanie bez diakrytyków
│   ├── M3uParser.kt            #EXTINF, tvg-logo, group-title
│   └── Prefs.kt                ustawienia + ContentStyle/ArtworkMode/BufferProfile
├── playback/
│   ├── RadioService.kt         MediaLibraryService: drzewo AA, wyszukiwanie, sesja
│   ├── MetadataFactory.kt      budowa MediaMetadata (diagnostyczna i normalna)
│   ├── DiagnosticFields.kt     mapa pole → polska etykieta
│   ├── IcyFilteringDataSource.kt   odcięcie ICY w trybie diagnostycznym
│   ├── InfiniteLoadErrorHandlingPolicy.kt
│   ├── ReconnectController.kt  ConnectivityManager + backoff
│   └── PlaybackStatusBus.kt
└── ui/
    ├── MainActivity.kt         lista, wyszukiwarka, mini-player
    ├── StationAdapter.kt
    └── SettingsActivity.kt
```
