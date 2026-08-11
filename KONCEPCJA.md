# Koncepcja

Jak aplikacja jest zbudowana i **dlaczego akurat tak**. Decyzje, które kosztowały
nas nieudane podejścia, mają tu zapisane uzasadnienie — żeby nikt (łącznie z nami)
nie wracał do rozwiązań, które już raz nie zadziałały.

---

## 1. Czym w ogóle jest Android Auto

To **projekcja**, nie system w aucie. Telefon rysuje cały interfejs i wysyła go
jako obraz; głowica daje ekran, dotyk i głośniki. Wygląd list, kolory, motyw
dzień/noc i układ paska to domena Android Auto na telefonie oraz ustawień w
aucie — aplikacja steruje wyłącznie **treścią**.

Odbiornik AA w samochodzie to zamknięta biblioteka Google licencjonowana
producentom, wkompilowana w firmware MIB3. Nie ma jej w AOSP i nie da się jej
„zwirtualizować". DHU jest osobnym narzędziem testowym, zamrożonym w 2022 roku —
dlatego część rzeczy zachowuje się na biurku inaczej niż w aucie.

Praktyczny wniosek: **wszystko, co dotyczy wyglądu w aucie, trzeba potwierdzić
w aucie.**

---

## 2. Warstwy

```
RadioService (MediaLibraryService)
   ├─ ExoPlayer + ICY  ──►  NowPlaying  ──►  MetadataFactory  ──►  MediaMetadata
   │                            │                                      │
   │                            └─► CoverArtLookup ─► TrackInfo ────────┘
   │
   ├─ PlaybackStatusBus  ──►  UI telefonu (MainActivity, NowPlayingActivity)
   └─ drzewo przeglądania ──►  Android Auto
```

`RadioService` jest jedynym właścicielem stanu odtwarzania. UI telefonu czyta go
przez `PlaybackStatusBus` (zwykłe `StateFlow`, bo oba żyją w jednym procesie),
a auto — przez sesję medialną.

---

## 3. Metadane: skąd się biorą i co z nimi robimy

Strumień ICY niesie **jedną linijkę tekstu** i nic więcej:

```
StreamTitle='Bonobo & Joy Crookes - Always on Your Side'   <- wykonawca i utwór
StreamTitle='Radio Nowy Świat - Pion i poziom!'            <- stacja i slogan
StreamTitle=''  adw_ad='true'  durationMilliseconds='30000' <- reklama w RMF
StreamTitle='STOP_AD_BREAK'                                 <- znacznik sterujący
```

Naiwne dzielenie po `" - "` daje w drugim przypadku „wykonawcę" równego nazwie
stacji — i nazwa ląduje na ekranie dwa razy. **W tę pułapkę wpadają i ReplaIO,
i oficjalna aplikacja RNŚ.** Dlatego `NowPlaying.parse` porównuje lewą stronę
z nazwą stacji i rozpoznaje osobno: prawdziwy utwór, slogan stacji, reklamę,
serwis informacyjny i znacznik sterujący.

Kilka reguł wypracowanych na żywym materiale:

* **Reklamy w RMF anonsowane są na trzy sposoby** — pustym tytułem z `adw_ad`,
  słowem z listy (`AD_WORDS`), albo wcale. Stąd dodatkowo wygasanie opisu.
* **`STOP_AD_BREAK` nie znaczy, że wraca muzyka.** RMF potrafi zaraz po nim
  wstawić kolejną reklamę. Znacznik jedynie uzbraja timer `MARKER_GRACE_MS`;
  jeśli w tym czasie przyjdzie cokolwiek konkretnego, to ono wygrywa.
* **Znacznik sterujący rozpoznajemy regułą** `^[A-Z0-9][A-Z0-9_]{3,}$` — bez
  tego `STOP_AD_BREAK` trafiał do iTunes jako tytuł utworu.
* **Wygasanie opisu.** Skoro katalog zna długość utworu, to po
  `długość + STALE_GRACE_MS` na pewno już nie leci. Gdy długości nie znamy —
  `FALLBACK_TRACK_MS`. Zamiast pustki pokazujemy wtedy ostatni znany slogan
  stacji.
* **Wielkie litery.** Jacaranda FM podaje wszystko WERSALIKAMI, wytwórnie robią
  to samo w katalogach. Porządkuje to `TextCase` w trzech krokach, w tej
  kolejności: napis niekrzykliwy zostaje nietknięty → jeśli krzyczy, a katalog
  zna ten sam napis porządnie zapisany, bierzemy wersję z katalogu (pochodzi od
  wydawcy) → dopiero gdy katalog też krzyczy, normalizujemy sami. Krótkie wyrazy
  bez samogłosek zostawiamy w spokoju, bo to prawie zawsze skrótowce — inaczej
  „DJ Snake" zamieniłby się w „Dj Snake".

### Trzy wiersze opisu i dlaczego nie da się ich rozdzielić

Pomiar w aucie (BADANIA, punkt 1) pokazał, że Active Info Display czyta
`subtitle`, `description` i `displayTitle` — czyli **te same pola**, z których
korzysta ekran centralny Android Auto. Ekran centralny pokazuje z nich dwa:
`displayTitle` jako dużą linię i `subtitle` jako małą.

Stąd konstrukcja opcji: użytkownik wybiera treść **osobno dla każdego z trzech
wierszy** (tytuł / wykonawca / wykonawca z albumem i rokiem / wykonawca — tytuł /
nazwa stacji / zegar / puste), a nie z listy gotowych układów. Gotowe presety
były wygodne, dopóki nie wiedzieliśmy, co jest gdzie; teraz tylko ograniczały.

Konsekwencja, którą trzeba znać: **wiersza 1 i 3 nie da się ukryć przed ekranem
centralnym**. Zegar wstawiony w linię tekstu pojawi się w obu miejscach. Jedynym
wierszem widocznym wyłącznie na AID jest środkowy — i dlatego domyślnie stoi
w nim nazwa stacji, a nie kopia czegoś, co już widać gdzie indziej.

Pola semantyczne (`title`, `artist`, `albumTitle`) wypełniamy niezależnie od
wierszy, zgodnie z ich znaczeniem. Na żadnym ekranie w aucie się nie pojawiają,
ale opisują to, co faktycznie leci, i inne systemy potrafią po nie sięgać.

### Tryb diagnostyczny

Każde pole `MediaMetadata` dostaje swoją krótką polską etykietę (`TYTUŁ`,
`PODTYTUŁ`, `OPIS`…) razem z zegarem odświeżanym równo na granicy minuty.
Zegar jest po to, żeby na desce było widać, że wartość jest świeża, a nie
zamrożona.

W tym trybie **odcinamy metadane ICY** (`IcyFilteringDataSource`) — inaczej
ExoPlayer nadpisałby `title`, `station` i `genre` tym, co przysyła Icecast, i na
desce zamiast etykiety byłaby nazwa rozgłośni.

Etykiety są w czystym ASCII: okno „Media Playback Status" w DHU czyta UTF-8 jak
Latin-1, a o fontach w desce Passata nic pewnego nie wiemy. Etykieta ma służyć
do rozpoznania pola, nie do typografii.

---

## 4. Okładki

ICY **nie niesie żadnej grafiki** — rozgłośnie doklejają okładki po stronie
klienta. Webplayer Radia Nowy Świat odpytuje iTunes i my robimy to samo:

1. **iTunes Search API** — darmowe, bez klucza, zwraca też nazwę wydawnictwa,
   rok i długość utworu.
2. **MusicBrainz + Cover Art Archive** — dopiero gdy iTunes nie zna utworu.
   Apple ma słabe pokrycie starszego polskiego repertuaru (Czesław Niemen,
   „Lipowa łyżka" — u Apple nie ma wcale, MusicBrainz zna i utwór, i płytę).

### Zasada: okładka nigdy nie przeżywa utworu, do którego należy

To była nasza pomyłka projektowa, poprawiona po dwóch zgłoszeniach. Pierwotnie
stara grafika zostawała aż do znalezienia nowej, żeby między utworami nie migało
logo stacji. W praktyce dawało to gorszy efekt niż mrugnięcie:

* obok nazwiska nowego wykonawcy wisiała płyta poprzedniego,
* po wejściu studia przy „Pion i poziom!" zostawała okładka sprzed chwili.

Teraz przy każdej zmianie utworu wracamy natychmiast do logo stacji, a okładkę
pokazujemy dopiero gdy katalog ją znajdzie (zwykle ~200 ms, więc logo rzadko
zdąży się pojawić). **Kolejność w `apply()` też ma znaczenie**: najpierw
zerujemy okładkę, dopiero potem wysyłamy metadane — odwrotnie do auta trafiał
nowy opis ze starą grafiką.

Ta sama zasada dotyczy danych katalogowych: `trackInfo` zerujemy na wejściu,
inaczej przez chwilę widać było „Taylor Swift · Black Gold: The Best of Soul
Asylum [1992]".

---

## 5. Logotypy: dlaczego własny ContentProvider

Logo szło do Android Auto jako `android.resource://pakiet/2131165359`. W takim
adresie siedzi **numeryczny identyfikator zasobu, który zmienia się przy niemal
każdej przebudowie** — wystarczy dołożyć plik do `res/drawable`, bo aapt2
przydziela numery po kolei, alfabetycznie.

Android Auto pamięta pobraną grafikę pod adresem. Po aktualizacji ten sam numer
wskazywał już inną stację i HDU rysowało z pamięci logo Radia Nowy Świat pod
napisem „RMF FM".

`LogoProvider` wystawia je pod `content://…/logo/<id-stacji>?v=<numer>`:
adres opisuje **stację**, a nie zasób, więc jest stabilny między wersjami.
Numer zasobu doklejamy jako parametr tylko po to, żeby przy podmianie samej
grafiki cache unieważnił się dokładnie raz.

---

## 6. Ikony przycisków w Android Auto

Tu droga przez numer zasobu jest nie do uniknięcia w starym API, ale jest
udokumentowane obejście. [Dokumentacja Androida dla samochodów](https://developer.android.com/training/cars/media/enable-playback)
mówi wprost: jeśli ikona odpowiada którejś ze stałych `CommandButton.ICON_`,
jej wartość należy wpisać pod kluczem `EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT`
w extras akcji, bo to *„nadpisuje zasób ikony przekazany do
`CustomAction.Builder` i pozwala systemowi narysować akcję spójnie z
pozostałymi"*. Głowica rysuje wtedy **własną** gwiazdkę.

Dlatego podajemy ikonę dwoma kanałami: extras (właściwy, dla nowoczesnych HDU)
i numer zasobu (zapasowy, dla starych — takich jak DHU 2.1 z 2022).

**Czego nie robić, sprawdzone:**

* Nie podawać stałej semantycznej w konstruktorze
  `CommandButton.Builder(ICON_STAR_FILLED)` licząc, że wystarczy. Media3
  zamienia ją dla starego API na numer własnego zasobu z AAR-a i DHU narysowało
  z tego nutkę oraz napis „1.8X".
* Nie używać `android:tint="@color/…"` w wektorach dla AA. Głowica inflatuje je
  we własnym procesie i odwołanie do zasobu musi rozwiązać w naszym pakiecie —
  właśnie na tym się wykładało. Kolor podajemy wprost w `fillColor`.

---

## 7. Ulubione jako jedno źródło prawdy

Przez trzy podejścia łataliśmy ulubione osobno na każdym ekranie — lista przy
wchodzeniu na wierzch, ekran odtwarzania przy renderowaniu, Android Auto przy
budowaniu przycisków — i stany rozjeżdżały się między sobą.

Teraz jest jeden `StateFlow` w `Prefs`, a wszyscy zainteresowani go obserwują:
lista na telefonie, ekran odtwarzania, gwiazdka przy odtwarzaczu w aucie i węzły
drzewa przeglądania. Zmiana skądkolwiek przekłada się natychmiast na wszystko.

Tak samo działają stacje dociągnięte z katalogu (`discoveredFlow`).

Uwaga historyczna: lista Ulubionych w Android Auto **nie odświeżała się na
żywo** mimo poprawnych `notifyChildrenChanged`. Winny okazał się most Media3 do
starego API `MediaBrowser` — podniesienie Media3 z 1.8.1 na **1.11.0** naprawiło
to bez zmiany naszego kodu.

---

## 8. Odporność na utratę zasięgu

Dwie warstwy, obie celowo ciche — w aucie nie ma migać żaden komunikat:

1. `InfiniteLoadErrorHandlingPolicy` — ExoPlayer ponawia w nieskończoność
   z narastającym opóźnieniem (0,5 s → 15 s), zostając w stanie `BUFFERING`.
   Wyjątek: trwałe 4xx przepuszczamy dalej, bo samo się to nie naprawi.
2. `ReconnectController` — nasłuchuje `ConnectivityManager` i wznawia
   natychmiast po odzyskaniu zwalidowanej sieci, zamiast czekać na kolejny krok
   backoffu.

Dodatkowo `WAKE_MODE_NETWORK` trzyma radio przy życiu, a
`handleAudioBecomingNoisy` pauzuje przy rozłączeniu Bluetooth.

### Dziura, przez którą odtwarzacze wiszą godzinami

Cichy ponawiacz z punktu 1 ma skutek uboczny: skoro **nie zgłasza błędu**, to
`onPlayerError` może się nigdy nie odpalić. Odtwarzacz wisi wtedy w `BUFFERING`
ze statusem „OK", a warunek „reaguj na powrót sieci tylko gdy status ≠ OK"
nie przepuszcza niczego. Tak właśnie zachowują się ReplaIO i TuneIn, które
potrafią wisieć godzinę po wyjeździe z garażu i dopiero potem zorientować się,
że sieć wróciła.

Dwie poprawki zamykają to szczelnie:

* **Wyzwalacz sieciowy patrzy na odtwarzacz, nie na status** — reaguje zawsze,
  gdy użytkownik chce grać, a odtwarzacz nie jest gotowy.
* **Buforowanie ma limit.** Ciągnące się ponad 12 s nie jest napełnianiem
  bufora, tylko brakiem połączenia — wtedy nazywamy rzecz po imieniu i sami
  zaczynamy ponawiać.

Gdy sieci nie ma, w **środkowym wierszu AID** pojawia się „Oczekuję na sieć…".
To jedyne pole widoczne wyłącznie na desce, więc ekran centralny zostaje czysty.

`KeepCurrentStreamPlayer` ignoruje `setMediaItem` dla stacji, która już gra —
bez tego wejście na grającą stację z listy zrywało połączenie i było słychać
przerwę.

---

## 9. Stacje spoza listy

Źródłem jest **radio-browser.info** — otwarty, społecznościowy katalog około
50 tys. rozgłośni. Wybrany, bo jako jedyny spełnia komplet warunków: darmowy,
bez klucza i rejestracji, z jasną licencją, publicznym API i — co najważniejsze —
sam odsiewa martwe strumienie (`hidebroken`), bo cyklicznie je sprawdza.

Odrzucone: TuneIn i iHeartRadio nie mają otwartego API, Shoutcast wymaga klucza
wydawanego ręcznie, a list M3U z sieci nikt nie utrzymuje.

Dodane stacje zapisujemy **w całości**, a nie po identyfikatorze: katalog może
przestać odpowiadać albo usunąć pozycję, a stacja raz dodana ma działać w aucie
także bez zasięgu do katalogu.

---

## 10. Ograniczenia, o których trzeba pamiętać

* **HLS Polskiego Radia nie niesie tytułów utworów.** W segmentach są wyłącznie
  znaczniki czasu (`com.apple.streaming.transportStreamTimestamp`), żadnego
  ID3 z `TIT2`/`TPE1`. Jedynka, Dwójka, Trójka, Czwórka, PR24 i Kierowcy pokażą
  więc tylko nazwę stacji. Alternatywy nie ma — cały plant Shoutcasta Polskiego
  Radia jest martwy (połączenie TCP wchodzi, danych brak).
* **triple j ma jeden strumień ogólnokrajowy.** Wersji melbourneńskiej nie ma —
  `3TJW` przekierowuje na stronę WWW.
* **AID rysuje samo auto.** Kanał Instrument Cluster w AA przenosi wyłącznie
  nawigację, więc kafelka muzyki na zegarach nie da się podejrzeć na biurku.
