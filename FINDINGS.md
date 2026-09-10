# Findings

Results established experimentally, not from documentation. Written down
because each one cost a separate investigation.

---

## 1. Which field goes where

Collected on a real Galaxy S20+ in Android Auto projection (DHU 2.1, 2026-08-10).

### Android Auto playback screen

| what's shown | from which field |
|---|---|
| large line | `displayTitle` |
| small line below it | `subtitle` |
| artwork | `artworkUri` |

**Neither `title` nor `artist` shows up on this screen** — even though they're
set. Android Auto uses the "display" variants.

### The "Media Playback Status" window in DHU

| DHU label | from which field |
|---|---|
| Song | `displayTitle` |
| Artist | `subtitle` |
| Album | `description` |

`description` landing in the "Album" field is the least obvious result of the
whole series.

### Active Info Display in the Passat — CONFIRMED

Read from the car's diagnostic mode, 2026-08-11. **The hypothesis was wrong** —
AID doesn't use `artist` / `albumTitle` / `title`, but exactly the same
"display" fields the central screen uses:

| line on AID | field |
|---|---|
| top (small) | `subtitle` |
| middle (small) | `description` |
| bottom (bold) | `displayTitle` |
| artwork | `artworkUri` |

Photo from the car in diagnostic mode:

```
        [ track artwork ]
        PODTYTUL<subtitle> 08:55
        OPIS<description> 08:55
        TYT.WYSW<displayTitle>
        08:55
```

The clock in every field updated live — meaning **AID reads metadata live**,
it doesn't freeze them at the value from the moment the track started.

**Design consequence.** AID and the central screen share fields, so they
can't be controlled independently: whatever we put in the AID line will also
show up on the central screen. The clock in the text line will be visible in
both places, and that's a deliberate trade-off, not a bug.

The mapping in the other direction, for the record:

| field | AA central screen | AID |
|---|---|---|
| `displayTitle` | large line | bottom line |
| `subtitle` | small line | top line |
| `description` | not shown | middle line |

So **the top AID line duplicates the small line of the central screen**. The
middle line is the only spot AID has exclusively. For comparison: ReplaIO
always puts the station name there, while the RNŚ app leaves it empty.

Resolutions, for the record:

* Digital Cockpit Pro in the Passat B8 (10.25") — 1280 × 480 for the whole
  display.
* Central screen: Discover Pro 9.2" — 1280 × 640 physically, but the head
  unit behaves like a 1.78 layout at 240 dpi density (see INSTRUKCJA).

Resolutions, for the record:

* Digital Cockpit Pro in the Passat B8 (10.25") — 1280 × 480 for the whole
  display.
* Content area between the clocks — **estimated** ~500 × 400 px; this is an
  estimate from screen proportions, not a VW spec.
* Central screen: Composition/Discover Media 8" — 800 × 480; Discover Pro
  9.2" — 1280 × 640.

---

## 2. Icon mapping in the Desktop Head Unit

Investigation from 2026-08-10, after diamonds and squares showed up in the
HDU instead of a star.

Method: a probe swapping the button icon through successive values, with an
HDU screenshot at each one. Results for our own resources and for the
semantic constants:

| sent | what HDU drew |
|---|---|
| our **triangle** (`ic_probe_triangle`) | a full star |
| our `ic_star_filled_aa` | two diamonds |
| our `ic_star_outline_aa` | a square |
| `ICON_STAR_FILLED` | a music note |
| `ICON_STAR_UNFILLED` | a dash |
| `ICON_HEART_FILLED` | the text "1.8X" |
| `ICON_THUMB_UP_FILLED` | nothing |

Scanning the resource numbers gave the answer:

| number | what DHU draws | what's under it today |
|---|---|---|
| `0x7F0700A0` | a full star | `ic_radio` |
| `0x7F0700A2` | an empty star | `ic_star_filled_aa` |
| `0x7F0700A7` | **the KISS FM logo** | `logo_anty` |

The last row gives it away: HDU is drawing **our own resources**, just
shifted. `0x7F0700A0` and `0x7F0700A2` are the positions `ic_star_filled_aa`
and `ic_star_outline_aa` held **before** four files were added to
`res/drawable`.

**Conclusion:** the Desktop Head Unit keeps a resource table from the APK
version before the reinstall and doesn't refresh it. After reloading the
projection session the numbers line up again and the icons draw correctly —
confirmed with a screenshot.

The problem won't occur in the car, because there the app isn't swapped out
mid-session.

Dead ends not worth repeating:

* `aapt2 --stable-ids` / `--emit-ids` — the parameters reach aapt2 (an
  unknown flag crashes the build), but AGP doesn't write the file, so
  there's nothing to read.
* `res/values/public.xml` with explicit numbers — aapt2 only honors these
  for the framework and shared libraries; it ignores them for the app.
* Bumping `versionCode` — doesn't invalidate the table on the DHU side.
* `pm trim-caches` — no effect.

---

## 3. Station behavior

* **RMF FM** marks ads in three ways: `adw_ad='true'` with an empty
  `StreamTitle`, a word from a list, or not at all. The news bulletin
  announces itself as `FAKTY`. It also sends control markers
  (`STOP_AD_BREAK`) which **don't** mean the music is back.
* **Radio Nowy Świat** provides a sensible `StreamTitle`; during talk shows
  it inserts the slogan "Pion i poziom!" in the format
  `Radio Nowy Świat - Pion i poziom!`.
* **Jacaranda FM** breaks two conventions at once. Stream sniff
  (2026-08-11):

  ```
  11:39:23  StreamTitle='THINKING ABOUT YOU - GOODLUCK'
  11:39:43  StreamTitle='WHAT'S LOVE GOT TO DO WITH IT - KYGO [+] TINA TURNER'
  ```

  First, **everything is in all caps**. Second — and more importantly — the
  order is **reversed**: title first, then artist. GOODLUCK is a band from
  South Africa, Kygo is the producer. For collaborations it uses `[+]`
  instead of a comma.

  We detect this via the catalog, not a per-station flag: if what we took to
  be the artist is the track title in iTunes — the fields are swapped.
  Comparing the artist too doesn't work, because on collaborations the
  strings diverge (station: "KYGO [+] TINA TURNER", catalog: "Tina Turner").
* **Polskie Radio** — the entire Shoutcast plant (ports 8900–8918) doesn't
  respond: the TCP connection goes through, but no data comes back. Only HLS
  works, one server per program: `stream11/pr1`, `stream12/pr2`,
  `stream13/pr3`, `stream14/pr4`, `stream15/pr24`, with Kierowcy on the
  unusual path `stream10/prk/rdk.sdp`. **HLS segments don't carry track
  titles** — they only contain `com.apple.streaming.transportStreamTimestamp`,
  no `TIT2`/`TPE1`.
* All 32 streams verified with an HTTP probe using the `Icy-MetaData: 1`
  header; each one returns data and headers.

---

## 4. What "hidebroken" really means in radio-browser

Established from the Triple M Melbourne case (2026-08-11), which buffered
forever after being added.

The catalog claimed the station was working:

```
lastcheckok    : 1
lastchecktime  : 2026-01-15      <- seven months earlier
url_resolved   : https://wz3drp.scahw.com.au/live/3mmm_32.stream/playlist.m3u8
```

And the host **has no A record** — also checked via Google's public
resolver, so it's not a matter of our own network. SCA decommissioned that
server.

**Conclusion:** `hidebroken` filters out stations that were broken at their
**last** check. It doesn't mean "checked recently." The catalog tests live
stations roughly once a day, so a date from months ago means the checker
gave up long ago, and the flag is left over from the last successful test.

Hence three safeguards on our side:

1. The details screen shows **how many days ago** the catalog confirmed the
   station was working, and above 30 days it states outright that this is a
   very long time ago.
2. The **"Check if the stream works"** button makes its own connection right
   here, right now. It distinguishes a missing host (station decommissioned),
   HTTP 403 (often a regional block), no response, and a server that returns
   nothing.
3. During playback: after four failed attempts on a working network, the
   status changes from "Reconnecting" to **"Station not responding"**. We
   keep retrying — nothing flickers in the car — but on the phone it's
   visible that the problem is on the broadcaster's side, not signal
   coverage.

---

## 5. What was checked on the emulator (API 33)

* `dumpsys media_session` shows `state=3` (PLAYING) and
  `description=TYT.WYŚW, PODTYTUŁ, OPIS` — the diagnostic labels actually do
  pass through the media session exactly as the head unit will read them.
* The notification has `android.title=TYTUŁ` — proof that the ICY cutoff
  works and the real `StreamTitle` doesn't overwrite the `title` field.
* The `MediaBrowserCompat` path (the one Android Auto uses) checked with a
  separate test client: root, tabs, genres, browsable/playable flags.
* Search ignores diacritics: "nowy swiat" → Radio Nowy Świat.

### Known, irrelevant to the car

SystemUI reports `Cannot resume with ComponentInfo{...RadioService}` — this
is a "resume playback" probe from the notification panel, rejected by Media3
inside its legacy stub before it calls our code. Purely cosmetic effect.

---

## 5. What we learned from ReplaIO

The ReplaIO archive was a reference point for two problems.

* **Icons without a tint.** ReplaIO publishes custom actions exactly like we
  do, with the same star `pathData` — the difference was solely in
  `android:tint`.
* **Per-item favorites in the AA list: ReplaIO doesn't have them.** Zero
  occurrences of `CUSTOM_BROWSER_ACTION` in the entire codebase. Their star
  only exists next to the player. So they weren't proof that refreshing the
  list is achievable.
* **They don't use Media3.** The code has `MediaSessionCompat` and
  `MediaBrowserServiceCompat` — they call the old API directly. This pointed
  toward suspecting the Media3 bridge and bumping the version to 1.11.0,
  which fixed refreshing of the Favorites list.

---

## 6. Switching stations — what actually crosses the session

Measured on the phone on 2026-09-08, on a build with the metadata dump
(`adb logcat -s MetaDump IcyMeta`) and on `dumpsys media_session`, which shows
what a controller — and therefore the head unit — is handed.

**The previous station's description does not survive in our state.** The same
switch (Jacaranda FM, playing a track with cover art → Antyradio, which says
nothing) was run on the build before the change and on the build after it. In
both cases, seconds after the switch:

```
metadata: size=11, description=null, null, null
```

Three empty text fields and no trace of Jacaranda. So the stale lines seen on
the AID are **not** ours to clear — the app sends a complete, empty set, and
what stays on the dashboard is a head unit not repainting a field it received
empty. Hence [MetadataFactory.drawable]: an empty line goes out as U+00A0,
which is a value the head unit has to draw. **Not yet confirmed in the car.**

**Antyradio sends no ICY at all.** Ninety seconds of listening, zero blocks.
Not "rarely" — never. It is the station to test a switch against.

**How long the first block takes**, from the media item change to the first
`StreamTitle` (one pass, so an order of magnitude, not a measurement):

| Station | First block |
|---|---|
| Radio 357 | 0.15 s |
| Radio Nowy Świat | 0.5 s |
| Radio ZET | 0.6 s (empty title) |
| Chilli ZET | 0.8 s (empty title) |
| RMF FM | 1.2 s (preroll ad) |
| Jacaranda FM | 1.7 s |
| Smooth FM Melbourne | 2.8 s |
| Antyradio | never |

Half of them answer inside a second, which is why the description now waits
`STATION_SETTLE_MS` after a switch: without it the empty state and the track
land together and the head unit renders one flicker instead of two states.

---

## 7. When a description stops being true

Measured on the phone on 2026-09-10 over four rotations driven by
`am start --es play_station`, the last of them 2 h 09 min long. The trace
gained three events for this (`termin`, `przeterminowanie`, `icy-powtorka`),
because until then all three causes below arrived in the file looking like
one and the same "icy" push.

### What a station sends after a track

28 complete observations — that is, excluding the ones cut short by our own
station switch, which say more about the test schedule than about the
station:

| Station | next track | ad | slogan | repeat | empty |
|---|---|---|---|---|---|
| RMF FM | 10 | 2 | — | — | — |
| Smooth FM | 3 | — | — | 3 | 2 |
| Jacaranda FM | — | — | — | 2 | 1 |
| Radio Nowy Świat | — | — | 5 | — | — |

Each station has its own habit, and **empty blocks and repeats belong to
Smooth FM and Jacaranda alone** — RMF and RNŚ never do either. RNŚ was
running film music that hour (Kilar, Preisner) and put its slogan after every
single piece.

### Stations announce early, never late

For the 16 tracks whose start we actually witnessed (deadline based on a
catalog length rather than on a guess), the next thing the station said
arrived **before** the nominal end 15 times and exactly at it once. Never
later. Mean: 49 s early.

So a catalog length **systematically overstates** time on air — radio edits,
crossfades, talking over endings. `STALE_GRACE_MS = 30 s` is measured against
that, and the stale deadline barely ever has to fire while a station behaves
normally. It matters only in the gap an ad block leaves.

### The three ways a description used to die early

* **An empty `StreamTitle`.** The only input `NowPlaying.parse` returns null
  for. It means the station has nothing to say, **not** that nothing is
  playing — Smooth FM sent one 55 s into "Boyzone - A Picture Of You" (3:26)
  and the dashboard sat on the station name for three minutes. A station that
  really has stopped playing music says so: RMF's empty title arrives next to
  `adw_ad`, which parses to an ad, not to null.
* **A catalog length for a track we joined mid-way.** The server dumps the
  current `StreamTitle` the moment we attach — four of Smooth FM's five
  opening blocks arrived within 3 s of connecting. ICY carries no position,
  so the length says nothing about what is left.
* **A catalog length from the wrong release.** "Bee Gees - Stayin' Alive"
  matched a 1:33 soundtrack cut and took the description off screen two
  minutes into a song with two and a half to run. Hence `MIN_TRACK_MS`: below
  any radio edit, the number is evidence of a bad match. It cost nothing in
  the measured run, but it does hold genuinely short pieces (Kilar's *Tango*
  2:28, Strugała's *Moving to the Ghetto* 1:46 on RNŚ) up to 74 s too long.

### Repeated blocks mean "nothing new", not "still playing"

Stations re-send an identical block. Smooth FM repeated
"The Bangles - Eternal Flame" for **8 min 46 s** on a 3 min 56 s song, the
last two copies arriving after the description had correctly expired. So a
repeat must not push the deadline out and must not put a title back on
screen. It is recorded and acted on in no other way.

Until this was traced it was invisible: `handleIcyTitle` returned on the
fingerprint check *before* `Trace.write`, so repeats appeared only in logcat.

### A deadline, not a timer

The phone normally sleeps in a pocket with the radio on the head unit. A
coroutine `delay` in Doze fires five to eight minutes late — measured at
19:08:44 against a 19:03:45 deadline, and twice more the same day, always
with Android Auto disconnected. With AA attached the minute ticker was
punctual to the second, which makes it easy to conclude wrongly that there is
no problem.

Being late does no harm while nobody is reading. So staleness is a **deadline
checked wherever metadata is refreshed** — the clock tick, an ICY block, a
network change, and a controller attaching, which is the head unit starting
to read us again. **The controller-attach path has not been confirmed against
a real head unit.**

### RMF's preroll

Every connection to RMF opens with an ad, and the block states its own
length: `adw_ad='true' insertionType='preroll' durationMilliseconds='19670'`,
with the next block arriving 20 s later. The figure is accurate but **not
constant** — 30 093 ms and 30 040 ms on other connections, 19 670 ms here. It
describes that insertion, not a rule.

### Catalog station names carry the stream description

radio-browser entries are typed by whoever added them, and the name is what
the AID shows whenever no track is playing. "Smooth FM 91.5 - Melbourne -
91.5 FM (AAC+ 320k)" is how a bitrate became the most prominent thing on the
instrument cluster. Of the eight catalog stations on the test phone, **seven
needed no change at all** — and one of them, "- 0 N - Smooth Jazz on Radio",
is what a confident rule ("cut at the first dash") turns into nothing. Hence
`StationNames`, which removes only an unambiguous codec/bitrate bracket or a
trailing frequency, and the rename field in the station's details for
everything else.
