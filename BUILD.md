# Workshop manual

Everything you need to build, install, and run Twinsen Radio —
along with the traps that have already cost us time. Steps that require
tapping the phone are marked **tap**.

---

## 1. Required environment

The tools in `tools/` (`install.ps1`, `dhu.bat`, etc.) are PowerShell scripts,
written for Windows. The project itself is a plain Gradle/Kotlin application —
on Linux/macOS you can build it with `./gradlew assembleDebug` and install it
with `adb install`, without the helper scripts.

| Component | Required version | Example path (Windows) |
|---|---|---|
| JDK | **17** (Temurin/Adoptium is recommended as tested) | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` |
| Android SDK | platform **35 and 36** | `C:\Android\Sdk` |
| build-tools | **36.1.0** (this is where `aapt2.exe` lives) | `C:\Android\Sdk\build-tools\36.1.0` |
| platform-tools (`adb`) | any current version | `C:\Android\Sdk\platform-tools` |
| Desktop Head Unit *(optional — for testing without a car)* | **2.1** (2022-12-15) | `C:\Android\Sdk\extras\google\auto` |
| Gradle | downloaded by the wrapper, nothing to install | 8.14.3 |

The paths in the right-hand column are just an example from the author's
machine — install the SDK wherever suits you and substitute the paths in your
own commands. Kotlin (2.2.21) and the Android Gradle Plugin (8.13.2) get
installed automatically by Gradle on the first build. DHU 2.1 isn't in the
default `sdkmanager` listing — it lives in the preview channel:

```powershell
sdkmanager --sdk_root=C:\Android\Sdk --channel=3 "extras;google;auto"
```

**Phone:** any device with Android 8.0+ (`minSdk 26`) and the Android Auto app
installed. Actually tested on a Galaxy S20+ (SM-G986B), Android 13 — nothing
in the code assumes this specific model.

---

## 2. Building and installing

```powershell
.\tools\install.ps1            # debug: tests, builds, installs, launches
.\tools\install.ps1 -Release   # release build signed with the debug key
```

The script runs `:app:testDebugUnitTest` first and refuses to build if it
fails. Those tests cover the text rules — how a StreamTitle is split, when a
station has the title and artist the wrong way round, when shouting gets
brought down — and every case in them was actually broadcast. It is the one
part of this app that can be checked without a phone, and the alternative is
waiting for a radio station to play the right song again:

```powershell
.\gradlew.bat :app:testDebugUnitTest    # on its own, ~5 s once warm
```

The script sets `JAVA_HOME` and `ANDROID_HOME` for you. If you build manually
via `.\gradlew.bat`, **you must set `JAVA_HOME` yourself** — otherwise the
wrapper will abort with "JAVA_HOME is not set".

The APK ends up at `app\build\outputs\apk\debug\app-debug.apk`.

---

## 3. First run on the phone

**Tap on the phone, in order:**

1. *Settings → About phone → Software information* → tap
   **"Build number" 7×**.
2. *Settings → Developer options* → **USB debugging**.
3. Plug in the cable. Choose **File transfer / Android Auto** — not "Charging
   only" and **not "USB debugging only"** (see point 5).
4. The **"Allow USB debugging?"** dialog → *Always allow from this computer*.
5. On the app's first launch — grant **notifications** permission. Without it
   the foreground service won't show its controls.

Check on the laptop: `adb devices -l` must show status `device`.
`unauthorized` means the permission dialog hasn't been confirmed yet; an
empty list means the cable is charge-only or the USB mode is wrong.

---

## 4. Android Auto — an app from outside the store

By default, Android Auto won't launch an app that isn't on Google Play.
**Tap once:**

1. *Settings → Apps → Android Auto → Additional settings in app*.
2. Scroll to the bottom, tap **"Version" 10×** → confirm developer mode.
3. **⋮** menu → *Developer settings* → **App mode: Developer**
   (the newer equivalent of the old "Unknown sources").

---

## 5. Desktop Head Unit

```powershell
.\tools\dhu.bat                  # 1280x720 at 240 dpi - matches what the Passat shows
.\tools\dhu.bat dpi=213          # same geometry, different density
.\tools\dhu.bat margin=80        # 1280x640 - native geometry of the Discover Pro 9.2"
.\tools\dhu.bat small            # 800x480  - Composition / Discover Media 8"
.\tools\dhu.bat 720              # 1280x720 at 160 dpi - comparison profile
.\tools\dhu.bat log              # also opens a window with a metadata preview
.\tools\dhu.bat ontop            # keeps the projection window on top of others
```

Arguments can be combined: `dhu.bat margin=80 dpi=213 log`.

**Keep the density within Android's standard buckets** — 160, 213, 240, 320.
At 200 dpi the projection came up with sound but **no picture**; values
outside the buckets can fail like that. The DHU documentation doesn't state
any restriction here, so trial and error is the only way to know.

**There are only three resolutions** — 800x480, 1280x720, and 1920x1080; that's
what the [DHU documentation](https://developer.android.com/training/cars/testing/dhu)
says. The Discover Pro 9.2" is physically 1280x640, and that's achieved with a
margin (`margin=80`, since `marginheight` is the total height cropped, not per
side). Side effect: the aspect ratio changes from 1.78 to 2.00 and Android
Auto moves the app bar to the left edge, even though in the car it sits at the
bottom. If you want to reconcile that, try changing the layout in Android
Auto's settings **on the projection screen** (gear icon → "Change layout"),
not in the app on the phone.

Only **the projection window** appears. The other two have been disabled and
won't come back:

* **Instrument Cluster** — the cluster channel in the Android Auto protocol
  only carries navigation (the DHU binary contains only
  `INSTRUMENT_CLUSTER_NAVIGATION_*` messages). This window will **never** show
  what the Passat draws on the AID during music playback — the AID is
  rendered by the car itself from `MediaMetadata` fields. There's no AID
  emulator in the public SDK.
* **Media Playback Status** — it used to be useful for figuring out which
  field ends up where. That measurement has already been done in the car, and
  on top of that the window reads UTF-8 as Latin-1 and mangles Polish
  characters. Use `dhu.bat log` to preview metadata instead.

### Four things you need, or it won't work at all

Each one cost us several attempts.

1. **DHU needs its own console.** It has an interactive prompt; run without a
   console, it gets EOF on stdin and exits within a fraction of a second,
   taking the projection windows down with it. Hence the `cmd /c start` in
   `dhu.bat`.

2. **USB can't be in "USB debugging only" mode.** With plain adb, the
   connection reaches the end of the TLS handshake, after which the car
   service logs `Detected charge only` and drops the session. It has to be
   **File transfer / Android Auto**.

3. **The head unit server has to be started on the phone.** Android Auto menu
   → ⋮ → **"Start head unit server"**. Every DHU launch consumes one server
   session, so you'll usually need to tap it off → on before starting.
   **This can't be done from adb** — Android Auto has no launcher activity
   for it.

4. **Port forwarding.** DHU connects over `tcp:5277`:

   ```powershell
   adb forward tcp:5277 tcp:5277
   ```

   **Unplugging and replugging the cable clears the forward.** If DHU is
   stuck on "Waiting for phone…", this is the first thing to check: `adb
   forward --list`.

### Checking whether the phone is listening

```powershell
adb shell "cat /proc/net/tcp /proc/net/tcp6" | Select-String ':149D'
```

`149D` is 5277 in hex. An empty result means the head unit server isn't
running — go back to point 3.

### Passat profile: 1280x720 at 240 dpi

The default profile (`dhu.bat` with no arguments) is **1280x720, dpi 240**,
no margin. Confirmed to match what's shown in the car (2026-08-10).

This is **not** a physically faithful profile, and that's intentional:

* **Density 240, not 156.** Physically, the Discover Pro 9.2" at 1280x640 is
  about 156 dpi. But the head unit declares a density matched to viewing
  distance — the in-car screen is viewed from about 70 cm instead of the
  ~30 cm typical for a phone — roughly one and a half times as far: 156 ×
  1.5 ≈ 234, i.e. the standard **hdpi** bucket. As a bonus, this also gets
  rid of the old problem where a non-standard dpi broke vector rasterization
  in the projection.
* **No margin**, even though the panel is physically 1280x640. An 80px margin
  changes the aspect ratio from 1.78 to 2.00, and at 2:1 Android Auto moves
  the app bar to the left edge, where it can't be pushed back down. In the
  car the bar is **at the bottom**, and the player takes up the full width —
  meaning the head unit actually behaves like a 1.78 layout. Layout fidelity
  wins over geometric fidelity.

The margin trick is still documented here because it can be needed elsewhere:
DHU only accepts 800x480, 1280x720, and 1920x1080, so non-standard geometries
are achieved via `marginheight` / `marginwidth`.

### Screen layout

The position of the app bar depends on the aspect ratio **and** on a toggle
that lives in the Android Auto UI **on the head unit screen** (gear icon),
not in the app on the phone:

> **Quick controls for apps** — enabled → bar at the **bottom**;
> disabled → bar **on the side**.

Full-screen apps move the bar back to the side. Our app has no control over
this — `CONTENT_STYLE_*` only affects how lists look.

---

## 6. Day-to-day work

```powershell
# start a station without touching the screen
adb shell am start -n net.mspanc.twinsenradio/.ui.MainActivity --es play_station rns

# preview the metadata sent to the session
.\tools\meta-log.bat

# capture the DHU windows without stealing focus
.\tools\dhu-shot.ps1 -OutDir C:\somewhere

# log receivers connecting (after a drive)
.\tools\pull-log.ps1
```

Relevant logcat tags: `MetaDump` (fields sent to the session), `IcyMeta`
(raw ICY blocks), `CoverArt`, `LoadDiag`, `RadioService`, `RadioBrowser`.

### The trace of a drive

logcat only exists while a cable is attached, and its buffer is a few minutes
deep — an hour of driving is gone before anyone looks. So the app also writes
every event to a file on the phone: one JSON object per line, on by default
(Options → *Zapis przebiegu do pliku*, which also shows the size and the path).

```powershell
.\tools\pull-trace.ps1            # -> .\slady\trace\, with a per-file summary
.\tools\pull-trace.ps1 -Clear     # and wipe the phone's copy afterwards
```

One run of the service is one file, `trace-yyyyMMdd-HHmmss.jsonl`. Every line
carries `t` (wall clock), `up` (`elapsedRealtime`, monotonic — the only one
safe to subtract, since the phone corrects its clock over the network while we
drive), `st` (station id) and `e`, the kind of event:

| `e` | what it is |
|---|---|
| `run`, `uklad`, `end` | version, device, and the layout it was recorded under |
| `klient`, `klient-koniec` | a controller attached — `…projection.gearhead` is the car |
| `stacja` | station change: name, stream URL, transition reason |
| `naglowki` | the stream's icy-* headers; `metaint=0` means it will never send a title |
| `icy` | the block as it arrived **and** what we parsed out of it |
| `wstrzymanie` | the first description held back after a switch, and for how long |
| `wyslano` | the complete set pushed to the session, with `powod` — what prompted it |
| `katalog` | the iTunes/MusicBrainz answer, and the swap decision taken on it |
| `jakosc`, `stan`, `net`, `blad` | codec, playback state, network, player errors |
| `cfg` | a setting changed mid-drive |

Read it with UTF-8 named explicitly — the blank line the app sends is U+00A0,
and PowerShell's default encoding turns it into mojibake:

```powershell
[System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8)
```

Size: roughly 300 kB per hour of active switching, capped at 8 MB per run and
32 MB across the directory, oldest runs dropped first.

---

## 7. Troubleshooting

### Odd icons show up in the HDU — diamonds, squares, a music note

**This is not a bug in the app.** The Desktop Head Unit keeps the resource
table of our APK from the **previous install** and doesn't refresh it. Adding
even a single file to `res/drawable` shifts the ID numbers of all subsequent
resources, and the button icon gets sent to the head unit as exactly that
number. Result: whatever used to sit at that number shows up where the star
icon should be.

**Fix:** reload the projection session. The cheapest way is unplugging and
replugging the cable (remember the `adb forward` from point 5). Don't kill the
Android Auto process — you'd also kill the head unit server, which can't be
restarted from adb.

This won't happen in the car, because the app isn't swapped out there during
a session. Investigation details: [FINDINGS.md](FINDINGS.md).

### DHU: "Waiting for phone…"

In order: `adb forward --list` (is 5277 there), then whether the phone is
listening (point 5), then the head unit server on the phone.

### `adb devices` shows nothing after replugging

Check the USB mode — it has to be File transfer, not charging.

### PowerShell script crashes on a strange character

Scripts in `tools/` are **plain ASCII** and must stay that way. Without a
BOM, PowerShell reads the file as ANSI and Polish characters break the
parser. Comments in the Kotlin code are also free of diacritics, for the same
reason of consistency.

### Phone screen

While working, `svc power stayon true` is set along with the maximum
`screen_off_timeout`. Once you're done, it's worth restoring these:

```powershell
adb shell svc power stayon false
adb shell settings put system screen_off_timeout 60000
```

---

## 8. Checking streams

The probe that actually fetches data and reads the ICY headers lives in the
session history; for each entry in `stations.json` it sends a request with the
`Icy-MetaData: 1` header, counts the bytes received, and prints `icy-name`,
`icy-metaint`, and `icy-br`. For HLS it fetches the playlist and the first
segment.

A curl result of `exit code 28` is **expected** — it's a deliberate cutoff
after a set time, since the stream never ends on its own.
