# Briefing dla nowej sesji

Przeczytaj to najpierw. Potem, zależnie od zadania:
[KONCEPCJA.md](KONCEPCJA.md) (dlaczego tak jest zbudowane),
[INSTRUKCJA.md](INSTRUKCJA.md) (procedury warsztatowe),
[BADANIA.md](BADANIA.md) (co ustalono doświadczalnie).

---

## O co chodzi w projekcie

Odtwarzacz radia internetowego dla Android Auto, pisany pod **VW Passata B8
MY2020** z Discover Pro i Active Info Display. Cel numer jeden pierwszej
iteracji: **rozpoznać, które pola `MediaMetadata` trafiają na trzy linie AID** —
stąd tryb diagnostyczny, w którym każde pole zawiera swoją nazwę zamiast
wartości.

Repozytorium jest publiczne (open source, GPLv3): `github.com/szczepl/twinsen-radio`,
gałąź `main`. Nie ma automatycznych publikacji do Google Play — to build-it-yourself,
patrz [README.md](README.md).

---

## Jak pracujemy

Ustalenia z dotychczasowych sesji — warto ich trzymać, bo każde wzięło się
z konkretnego potknięcia.

* **Sprawdzaj, nie teoretyzuj.** Najdroższy błąd tej sesji: postawiłem tezę
  o cache'owaniu ikon i przez trzy rundy próbowałem ją obchodzić, zamiast
  zmierzyć. Rozwiązanie przyszło, gdy użytkownik kazał załadować cały zestaw
  ikon i rozpoznać je zrzutami — jeden przebieg dał odpowiedź. Gdy coś
  wygląda niewytłumaczalnie, buduj sondę.
* **Nie zostawiaj procesów w tle.** Uruchomiony do testów serwer zajmuje
  użytkownikowi port.
* **Nie ubijaj `com.google.android.projection.gearhead`.** Zabijesz serwer head
  unit, którego nie da się wystartować z adb — użytkownik musi go odklikać
  ręcznie na telefonie. Do przeładowania sesji wystarczy przepięcie kabla.
* **Nie dotykaj ulubionych bez uprzedzenia.** Użytkownik obserwuje ich stan;
  moje testowe kliknięcia raz zepsuły jego pomiar.
* **`adb shell input tap` nie działa podczas projekcji** — nasza aktywność nie
  ma wtedy fokusu i kliknięcia idą w próżnię. Nie wyciągaj z tego wniosku, że
  ekran jest wygaszony (już raz to zrobiłem i było błędne).
* **Zgłaszaj wprost, czego nie sprawdziłeś.** Użytkownik testuje na żywo i
  potrzebuje wiedzieć, co jest zweryfikowane, a co tylko skompilowane.
* **Terminologia:** po polsku mówimy **HDU**, nie „głowica".

---

## Konwencje w kodzie

* Komentarze i nazwy po polsku, ale **bez polskich znaków** w plikach `.kt`
  i w skryptach `tools/*.ps1`. Skrypty PowerShell bez BOM-u są czytane jako
  ANSI i diakrytyki rozwalają parser. Napisy widoczne dla użytkownika (`strings.xml`)
  oczywiście z pełną polszczyzną.
* Komentarz ma tłumaczyć **dlaczego**, nie co. Szczególnie tam, gdzie
  rozwiązanie wygląda dziwnie — a takich miejsc jest tu sporo, bo Android Auto
  bywa nieprzewidywalny.
* Etykiety diagnostyczne w ASCII — patrz KONCEPCJA, punkt 3.

---

## Środowisko

| Co | Gdzie |
|---|---|
| JDK 17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` |
| Android SDK | `C:\Android\Sdk` |
| adb | `C:\Android\Sdk\platform-tools\adb.exe` |
| aapt2 | `C:\Android\Sdk\build-tools\36.1.0\aapt2.exe` |
| Telefon | Galaxy S20+ (SM-G986B), Android 13 |

`.\tools\install.ps1` ustawia `JAVA_HOME` sam. Przy ręcznym `gradlew.bat`
trzeba go ustawić, inaczej build padnie.

Wersje: Media3 **1.11.0** (nie schodź niżej — 1.8.1 gubiło powiadomienia
o zmianie węzłów przeglądania), AGP 8.13.2, Kotlin 2.2.21, compileSdk 36,
minSdk 26.

---

## Stan i co dalej

Działa i potwierdzone na żywo: odtwarzanie 32 stacji, drzewo przeglądania AA,
wyszukiwanie, ulubione synchronizowane na żywo w obie strony, okładki,
rozpoznawanie reklam i sloganów, ciche wznawianie po utracie zasięgu,
wznawianie po podłączeniu do ostatniej stacji, wyszukiwarka stacji w sieci,
jakość strumienia na telefonie.

**Zmierzone w aucie 2026-08-11:** AID czyta `subtitle` (górna linia),
`description` (środkowa) i `displayTitle` (dolna) — te same pola co ekran
centralny. Tryb diagnostyczny zrobił swoje i jest już domyślnie wyłączony.

**Otwarte:**

1. **Ekran „Szukaj stacji w sieci"** nie był klikany na urządzeniu — adb nie
   otworzy niewyeksportowanej aktywności. Warstwa sieciowa sprawdzona osobno.
2. **Blok reklamowy RMF na żywo** — dostroić `MARKER_GRACE_MS` i
   `STALE_GRACE_MS` obserwacją przez `tools/meta-log.bat`.
3. **`tools/pull-log.ps1` przy podłączonym aucie** — złapać `CarInfoInternal`
   z prawdziwego MIB3.
4. **Przywrócić wygaszanie ekranu** po zakończeniu prac:
   `adb shell svc power stayon false`.
5. Opcjonalnie: znaleźć endpoint „co leci teraz" dla RNŚ i Radia 357, żeby po
   dołączeniu do strumienia nie czekać na pierwszy blok ICY.

---

## Pułapki, w które już wpadliśmy

Pełne opisy w KONCEPCJA i BADANIA — tu skrót, żeby nie powtarzać:

* **Dziwne ikony w HDU** (romby, kwadraty, nutka) to **nie błąd kodu** — DHU
  trzyma tablicę zasobów sprzed reinstalacji. Przeładuj sesję.
* **Przepięcie kabla kasuje `adb forward tcp:5277`** — bez tego DHU stoi na
  „Waiting for phone…".
* **Okładka nie może przeżyć utworu**, do którego należy; kolejność w `apply()`
  ma znaczenie.
* **`android:tint` w wektorach dla AA** nie działa — kolor wprost w `fillColor`.
* **Numery zasobów wędrują** przy każdym dołożeniu pliku do `res/drawable`.
  Nie da się ich przybić: `--stable-ids` nie przechodzi przez AGP, a
  `public.xml` aplikacje ignoruje. Stąd `LogoProvider` z adresami `content://`.
* **HLS Polskiego Radia nie niesie tytułów utworów** — te stacje pokażą samą
  nazwę i to nie jest usterka.
