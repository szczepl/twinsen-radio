# Badania

Wyniki ustalone doświadczalnie, nie z dokumentacji. Zapisane, bo każdy z nich
kosztował osobne dochodzenie.

---

## 1. Które pole trafia gdzie

Zebrane na realnym Galaxy S20+ w projekcji Android Auto (DHU 2.1, 2026-08-10).

### Ekran odtwarzania Android Auto

| co widać | z którego pola |
|---|---|
| duża linia | `displayTitle` |
| mała linia pod nią | `subtitle` |
| okładka | `artworkUri` |

**Ani `title`, ani `artist` nie pojawiają się na tym ekranie** — mimo że są
ustawione. Android Auto bierze warianty „display".

### Okno „Media Playback Status" w DHU

| etykieta DHU | z którego pola |
|---|---|
| Song | `displayTitle` |
| Artist | `subtitle` |
| Album | `description` |

`description` lądujący w polu „Album" to najmniej oczywisty wynik z całej serii.

### Active Info Display w Passacie — do potwierdzenia

Z obserwacji ReplaIO i oficjalnej aplikacji RNŚ wiadomo, że AID pokazuje **trzy
linie tekstu i małą grafikę** — o linię więcej niż ekran centralny:

```
        [ kwadrat: logo stacji albo okładka ]
        wykonawca
        nazwa stacji
        tytuł
```

Hipoteza do potwierdzenia jednym spojrzeniem w trybie diagnostycznym:

| linia na AID | prawdopodobne pole |
|---|---|
| górna | `artist` |
| środkowa | `albumTitle` |
| dolna | `title` |

Byłby to klasyczny układ artysta / album / tytuł, w którym radiowe aplikacje
wpisują nazwę stacji w `albumTitle`.

**To jest jedyna rzecz, po którą trzeba pojechać.**

Rozdzielczości, dla porządku:

* Digital Cockpit Pro w Passacie B8 (10,25") — 1280 × 480 na cały wyświetlacz.
* Obszar treści między zegarami — **szacunkowo** ~500 × 400 px; to oszacowanie
  z proporcji ekranu, nie specyfikacja VW.
* Ekran centralny: Composition/Discover Media 8" — 800 × 480; Discover Pro
  9,2" — 1280 × 640.

---

## 2. Mapowanie ikon w Desktop Head Unit

Dochodzenie z 2026-08-10, po tym jak w HDU zamiast gwiazdki pojawiły się romby
i kwadraty.

Metoda: sonda podmieniająca ikonę przycisku na kolejne wartości, ze zrzutem
ekranu HDU przy każdej. Wyniki dla naszych zasobów i dla stałych semantycznych:

| wysłane | co narysowało HDU |
|---|---|
| nasz **trójkąt** (`ic_probe_triangle`) | pełna gwiazdka |
| nasz `ic_star_filled_aa` | dwa romby |
| nasz `ic_star_outline_aa` | kwadrat |
| `ICON_STAR_FILLED` | nutka |
| `ICON_STAR_UNFILLED` | kreska |
| `ICON_HEART_FILLED` | napis „1.8X" |
| `ICON_THUMB_UP_FILLED` | nic |

Skan numerów zasobów dał rozwiązanie:

| numer | co rysuje DHU | co jest pod nim dziś |
|---|---|---|
| `0x7F0700A0` | pełna gwiazdka | `ic_radio` |
| `0x7F0700A2` | pusta gwiazdka | `ic_star_filled_aa` |
| `0x7F0700A7` | **logo KISS FM** | `logo_anty` |

Ostatni wiersz zdradza wszystko: HDU rysuje **nasze własne zasoby**, tylko
przesunięte. `0x7F0700A0` i `0x7F0700A2` to pozycje, jakie
`ic_star_filled_aa` i `ic_star_outline_aa` miały **przed** dołożeniem czterech
plików do `res/drawable`.

**Wniosek:** Desktop Head Unit trzyma tablicę zasobów z wersji APK sprzed
reinstalacji i nie odświeża jej. Po przeładowaniu sesji projekcji numery znów
się zgadzają i ikony rysują się poprawnie — potwierdzone zrzutem.

W aucie problem nie wystąpi, bo tam aplikacja nie jest podmieniana w trakcie
sesji.

Ślepe zaułki, których nie ma sensu powtarzać:

* `aapt2 --stable-ids` / `--emit-ids` — parametry docierają do aapt2 (nieznana
  flaga wywala build), ale AGP nie zapisuje pliku, więc nie ma czego czytać.
* `res/values/public.xml` z jawnymi numerami — aapt2 honoruje je tylko dla
  frameworka i bibliotek współdzielonych, dla aplikacji ignoruje.
* Podbicie `versionCode` — nie unieważnia tablicy po stronie DHU.
* `pm trim-caches` — bez wpływu.

---

## 3. Zachowanie stacji

* **RMF FM** oznacza reklamy na trzy sposoby: `adw_ad='true'` z pustym
  `StreamTitle`, słowem z listy, albo wcale. Serwis informacyjny anonsuje jako
  `FAKTY`. Wysyła też znaczniki sterujące (`STOP_AD_BREAK`), które **nie**
  oznaczają powrotu muzyki.
* **Radio Nowy Świat** podaje sensowny `StreamTitle`; przy audycjach wstawia
  slogan „Pion i poziom!" w formacie `Radio Nowy Świat - Pion i poziom!`.
* **Polskie Radio** — cały plant Shoutcasta (porty 8900–8918) nie odpowiada:
  połączenie TCP wchodzi, danych brak. Działa wyłącznie HLS, po jednym serwerze
  na program: `stream11/pr1`, `stream12/pr2`, `stream13/pr3`, `stream14/pr4`,
  `stream15/pr24`, a Kierowcy na nietypowej ścieżce `stream10/prk/rdk.sdp`.
  **Segmenty HLS nie niosą tytułów utworów** — jest w nich tylko
  `com.apple.streaming.transportStreamTimestamp`, żadnego `TIT2`/`TPE1`.
* Wszystkie 32 strumienie zweryfikowane sondą HTTP z nagłówkiem
  `Icy-MetaData: 1`; każdy oddaje dane i nagłówki.

---

## 4. Co sprawdzono na emulatorze (API 33)

* `dumpsys media_session` pokazuje `state=3` (PLAYING) i
  `description=TYT.WYŚW, PODTYTUŁ, OPIS` — diagnostyczne etykiety faktycznie
  przechodzą przez sesję medialną tak, jak przeczyta je głowica.
* Powiadomienie ma `android.title=TYTUŁ` — dowód, że odcięcie ICY działa
  i prawdziwy `StreamTitle` nie nadpisuje pola `title`.
* Ścieżka `MediaBrowserCompat` (ta, której używa Android Auto) sprawdzona
  osobnym klientem testowym: korzeń, zakładki, gatunki, flagi browsable/playable.
* Wyszukiwanie ignoruje diakrytyki: „nowy swiat" → Radio Nowy Świat.

### Znane, nieistotne dla auta

SystemUI zgłasza `Cannot resume with ComponentInfo{...RadioService}` — to sonda
„wznowienia odtwarzania" z panelu powiadomień, odrzucana przez Media3 wewnątrz
jego legacy stuba, zanim wywoła nasz kod. Efekt czysto kosmetyczny.

---

## 5. Czego dowiedzieliśmy się z ReplaIO

Archiwum ReplaIO było punktem odniesienia przy dwóch problemach.

* **Ikony bez tinta.** ReplaIO publikuje custom actions dokładnie jak my, z tym
  samym `pathData` gwiazdki — różnica była wyłącznie w `android:tint`.
* **Per-item favourites w liście AA: ReplaIO ich nie ma.** Zero wystąpień
  `CUSTOM_BROWSER_ACTION` w całym kodzie. Ich gwiazdka istnieje tylko przy
  odtwarzaczu. Czyli nie byli dowodem, że odświeżanie listy jest osiągalne.
* **Nie używają Media3.** W kodzie jest `MediaSessionCompat` i
  `MediaBrowserServiceCompat` — wołają stare API bezpośrednio. To naprowadziło
  na podejrzenie mostu Media3 i podniesienie wersji do 1.11.0, co naprawiło
  odświeżanie listy Ulubionych.
