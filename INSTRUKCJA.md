# Instrukcja obsługi warsztatu

Wszystko, co trzeba zrobić, żeby zbudować, wgrać i uruchomić Twinsen Radio —
razem z pułapkami, które już nas kosztowały czas. Miejsca wymagające dotknięcia
telefonu są oznaczone **pacnij**.

---

## 1. Środowisko na tym Windowsie

| Składnik | Ścieżka | Wersja |
|---|---|---|
| JDK (Temurin) | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` | 17.0.20 |
| Android SDK | `C:\Android\Sdk` | platform 35 i 36 |
| build-tools | `C:\Android\Sdk\build-tools\36.1.0` | 36.1.0 (tu jest `aapt2.exe`) |
| platform-tools (`adb`) | `C:\Android\Sdk\platform-tools` | 37.0.1 |
| Desktop Head Unit | `C:\Android\Sdk\extras\google\auto` | **2.1** (2022-12-15) |
| Gradle | pobierany przez wrapper | 8.14.3 |

Kotlin (2.2.21) i AGP (8.13.2) ściąga Gradle. DHU 2.1 nie ma w domyślnym
listingu `sdkmanager` — siedzi w kanale preview:

```powershell
sdkmanager --sdk_root=C:\Android\Sdk --channel=3 "extras;google;auto"
```

Telefon testowy: **Galaxy S20+ (SM-G986B)**, Android 13.

---

## 2. Budowanie i wgrywanie

```powershell
.\tools\install.ps1            # debug: buduje, wgrywa, uruchamia
.\tools\install.ps1 -Release   # release podpisany kluczem debug
```

Skrypt sam ustawia `JAVA_HOME` i `ANDROID_HOME`. Jeśli budujesz ręcznie przez
`.\gradlew.bat`, **musisz ustawić `JAVA_HOME` sam** — inaczej wrapper przerwie
z „JAVA_HOME is not set".

APK ląduje w `app\build\outputs\apk\debug\app-debug.apk`.

---

## 3. Pierwsze uruchomienie telefonu

**Pacnij na telefonie, po kolei:**

1. *Ustawienia → Informacje o telefonie → Informacje o oprogramowaniu* → stuknij
   **7× w „Numer kompilacji"**.
2. *Ustawienia → Opcje programisty* → **Debugowanie USB**.
3. Podłącz kabel. Wybierz **Transfer plików / Android Auto** — nie „Tylko
   ładowanie" i **nie „Tylko debugowanie"** (patrz punkt 5).
4. Okno **„Zezwolić na debugowanie USB?"** → *Zawsze zezwalaj z tego komputera*.
5. Przy pierwszym starcie aplikacji — zgoda na **powiadomienia**. Bez niej
   usługa pierwszoplanowa nie pokaże kontrolek.

Sprawdzenie na laptopie: `adb devices -l` musi pokazać status `device`.
`unauthorized` = okno zgody niepotwierdzone; pusta lista = kabel tylko do
ładowania albo zły tryb USB.

---

## 4. Android Auto — aplikacja spoza sklepu

Android Auto domyślnie nie uruchomi aplikacji, której nie ma w Google Play.
**Pacnij raz:**

1. *Ustawienia → Aplikacje → Android Auto → Ustawienia dodatkowe w aplikacji*.
2. Przewiń na dół, stuknij **10× w „Wersja"** → zgoda na tryb dewelopera.
3. Menu **⋮** → *Ustawienia programisty* → **Tryb aplikacji: Deweloperska**
   (nowszy odpowiednik dawnych „Nieznanych źródeł").

---

## 5. Desktop Head Unit

```powershell
.\tools\dhu.bat                  # 1280x720 przy 240 dpi - tak wygląda w Passacie
.\tools\dhu.bat small            # 800x480  - Composition / Discover Media 8"
.\tools\dhu.bat 720              # 1280x720 przy 160 dpi - profil porównawczy
.\tools\dhu.bat log              # dodatkowo okno z podglądem metadanych
.\tools\dhu.bat ontop            # okno projekcji nad innymi
```

Pojawia się **tylko okno projekcji**. Dwa pozostałe zostały wyłączone i nie
wrócą:

* **Instrument Cluster** — kanał clustera w protokole Android Auto przenosi
  wyłącznie nawigację (w binarce DHU są tylko komunikaty
  `INSTRUMENT_CLUSTER_NAVIGATION_*`). To okno **nigdy** nie pokaże tego, co
  Passat rysuje na AID podczas odtwarzania muzyki — AID renderuje samo auto
  z pól `MediaMetadata`. Emulatora AID w publicznym SDK nie ma.
* **Media Playback Status** — było przydatne przy ustalaniu, które pole gdzie
  trafia. Pomiar w aucie jest zrobiony, a okno dodatkowo czyta UTF-8 jak
  Latin-1 i psuje polskie znaki. Do podglądu metadanych służy `dhu.bat log`.

### Cztery rzeczy, bez których to nie ruszy

Każda kosztowała nas po kilka podejść.

1. **DHU musi dostać własną konsolę.** Ma interaktywny prompt; uruchomione bez
   konsoli dostaje EOF na stdin i kończy pracę w ułamku sekundy, zabierając ze
   sobą okna projekcji. Stąd `cmd /c start` w `dhu.bat`.

2. **USB nie może być w trybie „tylko debugowanie".** Przy gołym adb połączenie
   dochodzi do końca handshake'u TLS, po czym car service loguje
   `Detected charge only` i zrywa sesję. Musi być **Transfer plików /
   Android Auto**.

3. **Serwer head unit trzeba wystartować na telefonie.** Menu Android Auto → ⋮ →
   **„Włącz serwer radioodtwarzacza"** (*Start head unit server*). Każde
   uruchomienie DHU zużywa jedną sesję serwera, więc zwykle trzeba pacnąć
   wyłącz → włącz przed startem. **Nie da się tego zrobić z adb** — Android Auto
   nie ma aktywności w launcherze.

4. **Przekierowanie portu.** DHU łączy się przez `tcp:5277`:

   ```powershell
   adb forward tcp:5277 tcp:5277
   ```

   **Przepięcie kabla kasuje przekierowanie.** Jeśli DHU stoi na „Waiting for
   phone…", to jest pierwsza rzecz do sprawdzenia: `adb forward --list`.

### Sprawdzenie, czy telefon nasłuchuje

```powershell
adb shell "cat /proc/net/tcp /proc/net/tcp6" | Select-String ':149D'
```

`149D` to szesnastkowo 5277. Pusty wynik = serwer head unit nie działa, wróć do
punktu 3.

### Profil Passata: 1280x720 przy 240 dpi

Domyślny profil (`dhu.bat` bez argumentów) to **1280x720, dpi 240**, bez
marginesu. Rozpoznane jako identyczne z tym, co widać w aucie (2026-08-10).

To **nie jest** profil wierny fizycznie i tak ma być:

* **Gęstość 240, nie 156.** Fizycznie Discover Pro 9,2" przy 1280x640 ma ~156
  dpi. Ale głowica deklaruje gęstość dobraną do odległości patrzenia — ekran
  w aucie ogląda się z ~70 cm zamiast ~30 cm jak telefon — czyli mniej więcej
  półtora raza więcej: 156 × 1,5 ≈ 234, czyli standardowy kubełek **hdpi**.
  Przy okazji znika stary problem: niestandardowe dpi psuło rasteryzację
  wektorów w projekcji.
* **Bez marginesu**, choć panel ma fizycznie 1280x640. Margines 80 px zmienia
  proporcje z 1,78 na 2,00, a przy 2:1 Android Auto przerzuca pasek aplikacji
  na lewą krawędź i nie da się go zepchnąć na dół. W aucie pasek jest **na
  dole**, a odtwarzacz zajmuje całą szerokość — czyli głowica zachowuje się jak
  układ 1,78. Wierność układu wygrywa z wiernością geometrii.

Sztuczka z marginesem zostaje udokumentowana, bo bywa potrzebna gdzie indziej:
DHU przyjmuje wyłącznie 800x480, 1280x720 i 1920x1080, więc nietypowe
geometrie robi się przez `marginheight` / `marginwidth`.

### Układ ekranu

Pozycja paska aplikacji zależy od proporcji **i** od przełącznika, który siedzi
w interfejsie Android Auto **na ekranie głowicy** (kółko zębate), a nie w
aplikacji na telefonie:

> **Szybkie sterowanie aplikacjami** — włączone → pasek na **dole**;
> wyłączone → pasek **z boku**.

Pełny ekran aplikacji przerzuca pasek z powrotem na bok. Nasza aplikacja nie ma
na to wpływu — `CONTENT_STYLE_*` steruje wyłącznie wyglądem list.

---

## 6. Codzienna praca

```powershell
# włącz stację bez dotykania ekranu
adb shell am start -n net.mspanc.twinsenradio/.ui.MainActivity --es play_station rns

# podgląd metadanych wysyłanych do sesji
.\tools\meta-log.bat

# zrzut okien DHU bez zabierania focusu
.\tools\dhu-shot.ps1 -OutDir C:\gdzies

# log podłączających się odbiorników (po jeździe)
.\tools\pull-log.ps1
```

Ważne tagi w logcat: `MetaDump` (pola wysłane do sesji), `IcyMeta` (surowe bloki
ICY), `CoverArt`, `LoadDiag`, `RadioService`, `RadioBrowser`.

---

## 7. Rozwiązywanie problemów

### W HDU pojawiają się dziwne ikony — romby, kwadraty, nutka

**To nie jest błąd aplikacji.** Desktop Head Unit trzyma tablicę zasobów naszego
APK z **poprzedniej instalacji** i nie odświeża jej. Dołożenie choćby jednego
pliku do `res/drawable` przesuwa numery wszystkich kolejnych zasobów, a ikona
przycisku jedzie do głowicy właśnie jako numer. Efekt: w miejscu gwiazdki
pojawia się cokolwiek, co pod tym numerem było wcześniej.

**Lekarstwo:** przeładuj sesję projekcji. Najtaniej przepięciem kabla (pamiętaj
o `adb forward` z punktu 5). Nie ubijaj procesu Android Auto — zabijesz też
serwer head unit, którego nie da się wystartować z adb.

W aucie problem nie wystąpi, bo tam aplikacja nie jest podmieniana w trakcie
sesji. Szczegóły dochodzenia: [BADANIA.md](BADANIA.md).

### DHU: „Waiting for phone…"

Po kolei: `adb forward --list` (czy jest 5277), potem czy telefon nasłuchuje
(punkt 5), potem serwer head unit na telefonie.

### `adb devices` pokazuje pusto po przepięciu

Sprawdź tryb USB — musi być Transfer plików, nie ładowanie.

### Skrypt PowerShell wywala się na dziwnym znaku

Skrypty w `tools/` są **czystym ASCII** i tak ma zostać. Bez BOM-u PowerShell
czyta plik jako ANSI i polskie znaki rozwalają parser. Komentarze w kodzie
Kotlina też są bez diakrytyków — z tego samego powodu spójności.

### Ekran telefonu

Na czas prac ustawione jest `svc power stayon true` i maksymalny
`screen_off_timeout`. Po zakończeniu warto przywrócić:

```powershell
adb shell svc power stayon false
adb shell settings put system screen_off_timeout 60000
```

---

## 8. Sprawdzanie strumieni

Sonda, która realnie pobiera dane i czyta nagłówki ICY, jest w historii sesji;
działa tak, że dla każdej pozycji `stations.json` wykonuje żądanie z nagłówkiem
`Icy-MetaData: 1`, liczy pobrane bajty i wypisuje `icy-name`, `icy-metaint`
oraz `icy-br`. Dla HLS pobiera playlistę i pierwszy segment.

Wynik `exit code 28` z curla jest **poprawny** — to celowe ucięcie po zadanym
czasie, strumień nigdy się nie kończy.
