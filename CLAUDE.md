# Briefing for a new session

Read this first. Then, depending on the task:
[ARCHITECTURE.md](ARCHITECTURE.md) (why it's built this way),
[BUILD.md](BUILD.md) (workshop procedures),
[FINDINGS.md](FINDINGS.md) (what's been established experimentally).

Anything that has to be seen on the device rather than assumed goes through
the `driving-the-phone` skill — the adb mechanics, and the traps that cost a
round trip each.

---

## What this project is about

An internet radio player for Android Auto, written for a **VW Passat B8
MY2020** with Discover Pro and Active Info Display. The number one goal of
the first iteration: **figure out which `MediaMetadata` fields end up on the
AID's three lines** — hence the diagnostic mode, where every field shows its
own name instead of its value.

The repository is public (open source, GPLv3): `github.com/szczepl/twinsen-radio`,
branch `main`. There are no automated publishes to Google Play — it's
build-it-yourself, see [README.md](README.md).

---

## How we work

Lessons from past sessions — worth holding on to, since each one came out of
a real stumble.

* **Measure, don't theorize.** The most expensive mistake of this session: I
  formed a theory about icon caching and spent three rounds trying to work
  around it instead of measuring. The fix came only once the user told me to
  dump the whole icon set and identify them from screenshots — one pass gave
  the answer. When something looks inexplicable, build a probe.
* **Don't leave processes running in the background.** A server started for
  testing ties up a port the user needs.
* **Don't kill `com.google.android.projection.gearhead`.** You'll kill the
  head unit's server process, which can't be restarted from adb — the user
  has to tap it back to life on the phone by hand. Unplugging and replugging
  the cable is enough to reload the session.
* **Don't touch favorites without warning.** The user is watching their
  state; my test taps once ruined his measurement.
* **`adb shell input tap` doesn't work during projection** — our activity
  has no focus at that point, so taps land in the void. Don't conclude from
  this that the screen is off (I did that once already, and it was wrong).
* **Keep the screen awake while working, and put it back afterwards.** A
  session driven by coordinates falls apart the moment the display sleeps or
  the keyguard appears, and every tap after that lands nowhere — which reads
  as a broken app rather than a dark screen. So at the start:

  ```powershell
  adb shell settings get system screen_off_timeout   # write this down FIRST
  adb shell svc power stayon true
  adb shell settings put system screen_off_timeout 1800000
  ```

  **Undoing it is part of committing** — both settings, in the same breath as
  `git commit`. A phone left on `stayon true` lights up whenever it is
  plugged in, and how somebody's phone behaves is not this project's business.
  Read the old timeout *before* overwriting it: afterwards it cannot be
  recovered, and guessing at a person's setting hands back a phone that is
  subtly not theirs.
* **`am start --es play_station <id>` beats tapping by coordinate** for
  driving playback from a script — but only into a *fresh* process. Delivered
  to a running instance it lands in `onNewIntent`, which doesn't handle it,
  and the app just sits there. `am force-stop` first.
* **`SettingsActivity` isn't exported**, so `am start` on it is a
  SecurityException. Options is reached through the overflow menu.
* **State plainly what you haven't verified.** The user is testing live and
  needs to know what's actually confirmed versus merely compiled.
* **Terminology:** in Polish we say **HDU**, not "głowica" ("head unit").

---

## Code conventions

* Comments and names in Polish, but **no Polish diacritics** in `.kt` files
  or in `tools/*.ps1` scripts. PowerShell scripts without a BOM get read as
  ANSI, and diacritics break the parser. User-facing strings (`strings.xml`)
  of course use full Polish spelling.
* A comment should explain **why**, not what. Especially where the solution
  looks strange — and there's no shortage of those here, since Android Auto
  can be unpredictable.
* Diagnostic labels in ASCII — see KONCEPCJA, point 3.

---

## Environment

| What | Where |
|---|---|
| JDK 17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` |
| Android SDK | `C:\Android\Sdk` |
| adb | `C:\Android\Sdk\platform-tools\adb.exe` |
| aapt2 | `C:\Android\Sdk\build-tools\36.1.0\aapt2.exe` |
| Phone | Galaxy S20+ (SM-G986B), Android 13 |

`.\tools\install.ps1` sets `JAVA_HOME` itself. With a manual `gradlew.bat`
you have to set it yourself, or the build will fail.

Versions: Media3 **1.11.0** (don't go lower — 1.8.1 dropped notifications
about browse-node changes), AGP 8.13.2, Kotlin 2.2.21, compileSdk 36,
minSdk 26.

---

## Status and next steps

Working and confirmed live: playback of 32 stations, the AA browse tree,
search, favorites synced live in both directions, cover art, ad and jingle
detection, silent resume after signal loss, resume to the last station on
reconnect, station search over the network, stream quality on the phone.

**Measured in the car on 2026-08-11:** the AID reads `subtitle` (top line),
`description` (middle) and `displayTitle` (bottom line) — the same fields as
the central screen. Diagnostic mode did its job and is now disabled by
default.

**Open:**

1. **The "Search stations on the network" screen** hasn't been tapped
   through on the device — adb can't open a non-exported activity. The
   network layer has been checked separately.
2. **RMF's live ad block** — tune `MARKER_GRACE_MS` and `STALE_GRACE_MS` by
   observing through `tools/meta-log.bat`.
3. **`tools/pull-log.ps1` with the car connected** — capture `CarInfoInternal`
   from a real MIB3.
4. **Restore screen timeout** once work is done:
   `adb shell svc power stayon false`.
5. Optional: find a "now playing" endpoint for RNŚ and Radio 357, so that
   joining the stream doesn't require waiting for the first ICY block.

---

## Traps we've already fallen into

Full write-ups in KONCEPCJA and BADANIA — this is just a summary so we don't
repeat them:

* **Weird icons in the HDU** (diamonds, squares, a music note) are **not a
  code bug** — the DHU holds on to a resource table from before the
  reinstall. Reload the session.
* **Unplugging the cable kills `adb forward tcp:5277`** — without it the DHU
  sits at "Waiting for phone…".
* **Cover art must not outlive the track** it belongs to; ordering in
  `apply()` matters.
* **`android:tint` on vectors doesn't work for AA** — set the color directly
  in `fillColor`.
* **Resource IDs shift** every time a file is added to `res/drawable`. They
  can't be pinned down: `--stable-ids` doesn't make it through AGP, and apps
  ignore `public.xml`. Hence `LogoProvider` with `content://` addresses.
* **Polish Radio's HLS doesn't carry track titles** — those stations will
  show just the station name, and that's not a bug.
