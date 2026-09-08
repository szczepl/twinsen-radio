---
name: driving-the-phone
description: How to drive the test phone from adb — keeping the screen awake and putting it back, starting a station without tapping, taking a screenshot that survives the shell, and the traps that eat a round trip each. Use whenever a change has to be seen on the device rather than assumed.
---

# Driving the test phone

Galaxy S20+ (SM-G986B), Android 13, `adb` at
`C:\Android\Sdk\platform-tools\adb.exe`. Everything below was paid for once
already.

## Before anything else: keep the screen awake

A session driven by coordinates falls apart the moment the display sleeps or
the keyguard appears. Every tap after that lands nowhere, which reads as a
broken app rather than a dark screen.

```powershell
adb shell settings get system screen_off_timeout   # WRITE THIS DOWN FIRST
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
```

**Undoing it is part of committing** — `svc power stayon false` and the
timeout back to what it was, in the same breath as `git commit`. Read the old
value *before* overwriting: afterwards it cannot be recovered, and guessing at
somebody's phone setting hands back a phone that is subtly not theirs.

## Starting a station without touching the screen

```powershell
adb shell am force-stop net.mspanc.twinsenradio
adb shell am start -n net.mspanc.twinsenradio/.ui.MainActivity --es play_station jacaranda
```

**The force-stop is not optional.** Delivered to a running instance the extra
lands in `onNewIntent`, which does not handle it, and the app simply sits
there — `am` even reports success ("intent has been delivered to currently
running top-most instance"), so it reads as the app ignoring you.

Station ids are the `id` fields in `app/src/main/assets/stations.json` —
`rns`, `rmffm`, `anty`, `chillizet`, `r357`, `zet` and the rest. Stations
added from radio-browser carry an `rb:<uuid>` id instead.

**Antyradio sends no ICY at all** — ninety seconds, zero blocks. It is the
station to test "the new one says nothing" against.

## Screenshots

`adb exec-out screencap -p > file.png` through PowerShell **corrupts the
image** — the redirection rewrites the bytes. Go through the device:

```powershell
adb shell screencap -p /sdcard/shot.png
adb pull /sdcard/shot.png C:\path\shot.png
```

From the Bash tool, `/sdcard/...` is rewritten to a Windows path by MSYS
before `adb` ever sees it. Prefix with `MSYS_NO_PATHCONV=1`, or use the
PowerShell tool, or pass the destination as a Windows path.

Delete what you left on `/sdcard` when you are done.

## Tapping

Screenshots come back 1080x2400 and are shown scaled — multiply the
coordinates you read off the image by the factor the tool states.

* **Do not tap the stars.** Favourites are the user's own state, and a test
  tap once ruined a measurement they were in the middle of.
* **`input tap` does nothing during projection.** Our activity has no focus
  then, so taps land in the void. That is not evidence the screen is off.
* **The station list scrolls and reorders** (play counts, sort order). Take a
  screenshot and read the rows before tapping by coordinate — or use the
  `play_station` extra above, which does not care.

## Options is not reachable from adb

`SettingsActivity` is not exported, so `am start` on it is a
SecurityException. Overflow menu (three dots, top right) → *Opcje*.

## What not to touch

**Never kill `com.google.android.projection.gearhead`.** That is the head
unit's server process and it cannot be restarted from adb — the user has to
tap it back to life by hand. Unplugging and replugging the cable reloads the
session.

## Evidence

`adb logcat -s MetaDump IcyMeta RadioService CoverArt` while a cable is
attached; the trace file for anything longer than the buffer — see
[BUILD.md](../../../BUILD.md), section 6.
