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
* **Radio Nowy Świat** used to provide a sensible `StreamTitle`; during talk
  shows it inserts the slogan "Pion i poziom!" in the format
  `Radio Nowy Świat - Pion i poziom!`. **Since its studio moved to Mińska
  (October 2026) the stream carries no titles at all.** Measured 27.10.2026
  on all four mounts: the 128, the backup 128 and the AAC send
  `StreamTitle='Now Playing info goes here'`, which is Revma's placeholder for
  a station that feeds it nothing, and the 256 sends empty blocks. The headers
  are unchanged (`icy-metaint` 16000). Their site and app were never reading
  ICY: the web player polls `GET /api/oZeta/stat?tsapi=<ms>` every 10 s and
  gets
  `{"status":true,"data":{"ts":…,"title":"Ferris & Sylvester","artist":"Dark Side","source":"currenttxt"}}`
  — **`title` is the artist and `artist` is the track**, and between tracks
  it is `Radio Nowy Świat` / `Pion i poziom!`. The answer carries
  `Cache-Control: max-age` of a year and Cloudflare honours it: a repeated
  `tsapi` came back with a track from days earlier, so it must be unique. The
  official app (Flutter, `com.thehouseofcode.radio_nowy_swiat`) uses
  `/api/Mobile/get_current_player_data` and friends, but those answer a plain
  client with a Cloudflare challenge page.

  The same afternoon (18:12) the 128 and AAC mounts swapped the placeholder
  for a fixed `Radio Nowy Swiat - Pion i poziom!`, sent once on connect while
  Albert Hammond played. So a slogan in the stream **cannot** tell talk from
  a broken feed. `TitleArbiter` therefore makes the stream earn trust: it gets
  the say only once it names a real track (no fixed encoder string names a
  new song every few minutes), and loses it when the API moves to a new track
  the stream doesn't name within 60 s, or on the placeholder. Untrusted, the
  API decides. Only a *change* in the API is news — the web page can keep a
  finished track, and the stale deadline takes that down. Verified on the
  phone with RMF's stream standing in for a repaired RNS feed: trusted after
  the first track, API held back, trust lost 61 s after the API moved on.
  Every comparison goes into the trace as `zgodnosc`; `api-pola` records the
  API's field set whenever it changes, to catch `schedule_img`, which their
  web player reads and which may mark a programme rather than a track
  (not seen yet).

  **28.10.2026 the stream had its titles back** — real tracks from 08:24 on
  the morning drive, slogans between them, UTF-8. The arbiter trusted it from
  the first track and every one of 13 tracks agreed with the API. The API is
  kept as the fallback and the cross-check. Two timings from that day:
  * Over that drive the stream named each change **4–13 s after** we had seen
    it in the API (9 s on average, API polled every 10 s). On a fresh
    connection at 16:28 the stream came *first*, the API within 8 s after.
    So the gap is mostly how far behind live our own playback has drifted —
    it grows with every rebuffer on the road — not a property of the station.
  * The user saw titles 2–3 s before the song, or while it sat on hold at the
    desk. ICY is delivered at the playback position it was cut in at, so this
    is RNS's playout naming a track when it is cued. A title *change* is now
    held 3 s from the stream and 3 + 9 s from the API (`StationTitleApi`,
    trace event `odroczenie`); the first title of a connection is not held.
    The 9 s is the drive's average, so on a fresh connection an API-only
    title will now come a few seconds late rather than early.
  **30.10.2026 the stream froze on a real track.** Every mount — the 256,
  `stream.nowyswiat.online/mp3` (which redirects to the backup 128) and the
  rest — sends `StreamTitle='Trent Reznor and Atticus Ross - Normal Life'`
  once on connect and then nothing. A real title looks exactly like a working
  stream, so the arbiter trusted it: twice that morning it covered the API's
  correct track for 3 minutes, and the next API change then waited 60 s for
  the stream to follow plus the 12 s hold (07:26:01 → 07:27:19, 08:23:31 →
  08:24:47). Three breakages in four days, each beating the rule written for
  the one before, so **the API alone now decides** for RNŚ. ICY is still
  read and goes into the trace as `zgodnosc` (`StreamWitness`), to show if it
  ever comes back. The hold on API changes is a setting (default 3 s, the
  user's pick): on the 28.10 drive the API led the stream by 9 s on average,
  but that was with our playback drifting behind live; on a fresh connection
  the stream came first.

  **The station's own app (2.0.303, Flutter), decompiled the same day with
  blutter**, does not poll for titles at all. ICY is only a trigger: on a new
  block that doesn't contain "PION I POZIOM" it asks
  `/api/Mobile/get_current_player_data` (behind Cloudflare, needs the
  `X-RNS-API-KEY` baked into the app) for `{rds: {title, artist, img, ts}}` —
  the same fields as `oZeta/stat` plus a picture. Otherwise it refreshes on
  start, on resume and on a programme change (an hourly timer). With no song
  it shows "PION I POZIOM" / "Radio Nowy Świat" and the programme's picture.
  Phone and Android Auto get the same session; the AA handler only does
  browsing and search. On the frozen stream its title therefore never moves —
  measured 10:02–10:14: three changes in the API, none in its session. Our
  10 s poll of `oZeta` is the better mechanism.

  **Programme guide.** The public `nowyswiat.online/ramowka` carries ten days:
  start time, programme, presenters (the text before the first "|"; guests
  and topics follow it), and a 150 px thumbnail whose original, without
  `_thumb_150x150`, is a wide banner up to 1.6 MB. `OnAirGuide` reads it twice
  a day and fills the new line blocks "Audycja" and "Prowadzący" and the
  picture choice "Zdjęcie prowadzącego" between tracks, the photo cut to a
  512 px square. The guide doesn't list the news, so the first N minutes of
  an hour without a track count as news (setting, default 5) and give the
  slogan and logo back.

  **The picture before play** in their app (white, red corners, a pale
  "N") is public: `userfiles/rbroadcasts/radio-nowy-swiat-placeholder.jpg`,
  8000x4500, 1.2 MB, linked from the home page as a 150 px thumbnail. Its
  middle square is what the app shows; `standbyArt` in `stations.json`
  points at it. Every other station gets its logo faded to 30 % there —
  and only there: anything faded means "not playing".

  **The 256 kb/s mount is the patrons' one.** The site's stream list for an
  anonymous visitor has the 256 entry with `src: "-"`; `4md4m0a0fs8uv` is that
  stream (frames decode as 256 kb/s) and plays without logging in. Its
  `icy-name` is now plain "Radio Nowy Swiat", no longer "… - MP3 256".
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

### How wide the AID's lines are — not a question with an answer

It kept appearing on the open list, and it does not belong there. The cluster
draws a **proportional font**: the photograph of 11.09.2026 shows
"Tylko kochaj mnie" and "18:48 · pt. 11.09" sitting in fields whose capacity in
characters differs with every word put in them. "IIII" and "mmmm" are the same
length and nowhere near the same width.

So there is no character count to measure, and a probe with a numbered ruler
would answer a question nobody can use. What is known is what matters and is
already known: **three lines, and longer text is cut from the end.**

The consequence is a design rule rather than a measurement. Where two things
share a line, the second one is kept short — see `MetadataFactory.shortenSecond`
— because the cut falls at the end, so an over-long second half doesn't lose
itself, it loses whatever the line was for. The budget there is a judgement
made against ordinary station names, and it is labelled as one.

### A census of what stations actually send (11.09.2026)

Every station on the list was tuned in turn until it produced an ICY block or
ran out of patience (100 s). The point was to find the formats rather than to
listen, so the sweep moved on the moment a real track appeared.

**Artist and title are separated four different ways.**

| Form | Stations |
|---|---|
| `Artist - Title` | RMF FM, Radio 357, Smooth FM, Smooth Jazz Lounge, "- 0 N -" |
| `Title - Artist` — **reversed** | RMF 80s, RMF Ballady, RMF Polskie Przeboje, Jacaranda FM, Jazz Radio Soul |
| `Artist / Title` | Radio Złote Przeboje, Kiss FM |
| the station's own name and slogan | RMF MAXXX, RMF24, Eska, Eska ROCK, VOX FM, Radio Wnet, Skyrock, RMF Classic |

The reversed group is the surprise: RMF FM sends `Artist - Title`, but its own
thematic sisters send it the other way round. Nothing needed doing — the
catalogue-based swap detection already catches all of them, and the census
confirmed it on four separate stations in one pass (`zamiana=true`), with no
false rejections from the new plausibility check.

**Silence is common and comes in two kinds.** A station that sends
`StreamTitle=''` and nothing else — Chilli ZET, Radio ZET, Meloradio, TOK FM,
Radio Plus, Kiss FM Australia — and a station that sends no ICY at all:
the whole Polskie Radio HLS plant (Jedynka, Dwójka, Czwórka, PR24, Kierowcy),
Triple J, Triple M Melbourne, ABC Lounge Jazz, 101.9 The Fox. For the second
kind there is nothing to wait for, ever.

**Two formats are beyond the catalogue rather than beyond the parser.**
Rockserwis sends a talk-show episode where a track goes
(`LEPSZY PUNKT SLYSZENIA - Jakub 'Bizon' Michalski - #566 11/09/26`), and
RMF Classic sends Polish spellings of composers with descriptive titles
(`Sergiusz Prokofiew - Symfonia Klasyczna 2`, which iTunes files under
Prokofiev). Both parse correctly and simply aren't in the catalogue; neither
is a fault to fix in the app.

---

## 8. What the dashboard says while it is not playing (21.09.2026)

### Radio Nowy Świat's 256 kb/s stream — they changed nothing

Reported as dead on the morning of 21.09. It is not. `stations.json` holds
`https://stream.rcs.revma.com/4md4m0a0fs8uv`, and that is character for
character what RNŚ publishes on `nowyswiat.online/jak-nas-sluchac` — under
**"Streaming w wysokiej jakości dla Patronów"**, a section the page only shows
to a logged-in patron, which is why an anonymous fetch of that page finds the
128 and the AAC and nothing else. The mount is alive and honest: `icy-name`
`Radio Nowy Swiat - MP3 256`, `icy-metaint` 16000, and the frames decode as
MPEG-1 Layer III 256 kb/s, 44.1 kHz, joint stereo. All 20 CDN nodes the
redirector handed out answered 200.

What actually happened is in the trace, three times in half an hour:

```
07:28:25  stacja rns  url=…/4md4m0a0fs8uv
07:28:25  stan  buforuje
07:28:29  net   RECONNECTING
07:28:34  net   STATION_UNREACHABLE      (then ERROR_CODE_TIMEOUT, twice)
07:55:05  cfg   stream_choice_rns         ← switched to the 128 by hand
07:55:52  stan  gotowy
```

and again at 07:53 and 08:00. The same address played for an hour without a
break on 19.09, and it played on the phone at 12:57 and 12:59 the same day —
on Wi-Fi in 1.2 s, on LTE in 1.7 s. So: a temporary outage of the patron
mount, not a change on their side. **Not reproduced**, so the cause is not
established; a listener cap on the high-bitrate mount during the morning peak
fits the timing but is a guess, not a measurement. Nothing to change in the
catalogue.

### A station that never answers used to hang in silence

Found while testing the above. `ReconnectController.scheduleStuckCheck()`
returned early when `playWhenReady` was false — and a station change reaches
`STATE_BUFFERING` *before* `playWhenReady` is set, which is the one moment the
check gets armed. Nothing was armed, no further state change followed, and
nothing rearmed it.

Measured on an address that accepts TCP and never sends data: **100 s of
buffering, not one event in the trace** — no reconnect, no error, no status.
The condition now lives in the runnable, where it is asked at the moment it
matters. The same test afterwards:

```
t+0.0 s   stacja / buforuje
t+12 s    net RECONNECTING
t+27 s    net STATION_UNREACHABLE
```

### The connection state now reaches the middle line

It used to live only on the phone's own screen, which is the one screen nobody
is looking at while driving — the head unit went on showing the last track it
had been told about, and a station that had stopped answering looked exactly
like a station playing something quiet. Only "no network" ever made it across.

It goes in `description` — the middle AID line, the only one the cluster has to
itself (section 1), so a warning there costs the central screen nothing.
Measured end to end on the phone, black-holed address, medium buffer profile:

| on the line | when |
|---|---|
| *Łączę ze stacją…* | t+4.1 s |
| *Wznawiam połączenie…* | t+12 s |
| *Stacja nie odpowiada…* | t+27 s |
| the layout's own content again | as soon as the station answers |

**Plain buffering is deliberately silent.** A second of refilling happens on
any road, and a line that flashes a warning and takes it back reads worse than
a line that says nothing. The grace before buffering counts as connecting is
the chosen profile's own `forPlaybackMs` plus 2 s — 4 s on the default — so it
scales with what the user asked for rather than being guessed at once. A
station that answers normally (RNŚ, 1 s) never puts anything on the line:
verified, the trace goes straight from the station name to the track.

**Two writers, one status.** The player and `ReconnectController` were both
writing `PlaybackStatusBus.setStatus`, and a reconnect drives the player into
BUFFERING — which arrived *after* RECONNECTING and erased it. They are separate
fields now, and trouble outranks: while the network has a complaint, that is
what gets shown.

### A pause leaves you behind for good, not for a bufferful (21.09.2026)

Reported from the car: park, shop, come back, and the radio picks up *where it
was* — then jumps to live "after a while". The obvious explanation is the
buffer draining. It is not that.

Measured on RMF FM, with a probe reading `StreamTitle` live from a desk at the
same time:

```
13:33:05  phone plays   Armin van Buuren / SACHA - Everlasting
13:33:17  PAUSE
13:33:26  live is       DJ Snake / Justin Bieber - Let Me Love You
          (no ICY block reaches the phone for the whole pause -
           metadata is delivered at playback time, not load time)
13:35:54  RESUME
13:35:56  phone plays   DJ Snake / Justin Bieber - Let Me Love You   ← 2.5 min old
13:39:16  phone plays   Wiktor Dyduła - Tam słońce gdzie my
13:40:33  live is       (advert break)                               ← still behind
```

**The lag equals the length of the pause and does not close by itself.** Four
minutes after resuming, the phone was still exactly that far back, with no
error and no reconnect. Icecast holds the socket; TCP back-pressure stalls the
sender while the client is not reading; on resume the server simply carries on
from where it got to. What clears it eventually is the connection breaking for
some unrelated reason — which is what "jumps to live after a while" actually
is. A bigger buffer profile does not change this; the buffer is not what is
holding the delay.

So resuming has to rejoin rather than continue: `resetStationState()`,
`stop()`, `prepare()`. The same test afterwards:

```
13:43:44  PAUSE
13:45:40  RESUME        live is  Pitbull / Christina Aguilera - Feel this moment
13:45:41  "pauza 116318ms - porzucam opozniony strumien i lacze sie od nowa"
13:45:43  phone plays   Pitbull / Christina Aguilera - Feel this moment   ← live
```

3.9 s from resume to live audio. The threshold is 10 s of pause: verified that
a 5 s pause resumes seamlessly with no reconnect, and a 20 s one rejoins. It is
a judgement rather than a measurement — below it the lag is shorter than the
1–2 s of silence that removing it costs.

### Losing the signal: 8 s of buffer, then a minute of grace (30.10.2026)

Measured on RNŚ with airplane mode switched on from adb (USB keeps adb alive):

```
+0 s    network gone          sound goes on, from the buffer
+8 s    silence               middle line "Łączę ze stacją…", then "Oczekuję na sieć…"
...     still silent          before: the programme / track and its photo stayed up
                              for as long as it lasted (2.5 min in the test)
back    network               middle line read "Stacja nie odpowiada…" for ~10 s
```

**The buffer is about 8 s, not the profile's 45.** A live stream arrives as
fast as it plays, so all the buffer ever holds is what the server sends on
connect. Anything timed against a loss of signal has to count from the
silence, not from the network.

"Stacja nie odpowiada…" on the way back was the attempts made *without* a
network counted against the station: the fifth try, the first one with a
network, read as a station that keeps refusing. A returning network now
resets the count.

Now (`RadioService.checkSilence`, `SILENCE_GRACE_MS`): the first minute of
silence changes nothing — a tunnel is over before anyone reads the
dashboard. Past it the station's state is reset as on a station change, and
the dashboard shows "nie gra" (the station's picture or faded logo, its
slogan) with the reason still in the middle line. Verified end to end, twice:

```
13:05:43  airplane ON       "Zasypiasz sama", cover, "Oczekuję na sieć…"
13:06:51  60 s of silence   "Pion i poziom!", RNŚ placeholder, "Oczekuję na sieć…"
13:07:19  airplane OFF      "Wznawiam połączenie…"
13:07:31  sound             "W środku dnia", presenters' photo — the API had moved
                            on to "Pion i poziom!" at 13:07:29 (Ucho's record)

13:10:27  60 s of silence   "nie gra", as above
13:10:46  airplane OFF
13:10:57  first API answer  "Youth's Fountain" — still on air since 13:08:11,
                            so back on screen as fresh news, cover and
                            "MP3 · stereo" included
```

A 30 s outage changes nothing but the middle line, and comes back to the
station name without the false "Stacja nie odpowiada…".

### What the trace could not see, and what it can now (21.09.2026)

Three faults reported from the car had never left an answer in the trace. Going
through them one at a time:

**"The app is sometimes not in the Android Auto list."** This one cannot be
logged and never will be. Android Auto builds that list from a package-manager
query for the media-browser intent filter — **our process is never started**, so
there is nothing running to write anything down. What can be done is to record,
the next time we *do* run, the state that would have caused it. Hence
`srodowisko`, written once per run: the app standby bucket and whether we are
exempt from battery optimisation. At `ograniczony` (RESTRICTED) Android holds
back exactly the kind of binding Android Auto needs. First reading from the test
phone: `kubelek=aktywny`, `bezOszczedzania=false`. The second of those is worth
changing before blaming the head unit.

The other half of the answer is negative evidence that already exists: a drive
with no trace file at all means we never started, and a run whose file has no
closing `end` line means the process was killed.

**"The head unit goes quiet with Android Auto up and the app apparently
playing."** Nothing recorded whether sound was actually coming out. `gra`
(`playWhenReady`) is a wish and `stan: gotowy` is a readiness; neither promises
audio, and this fault lives precisely in the gap between them. A `stan` line is
now a full snapshot — `slychac` (`isPlaying`), `tlumienie` (the playback
suppression reason), `wyjscia` (available audio outputs) and `skad` (why
`playWhenReady` moved). **`gra: true, slychac: false` is the signature**, and
the same line says which of the two it was.

**Verified on the phone:** the snapshot fires from all three of
`onPlaybackStateChanged`, `onPlayWhenReadyChanged` and `onIsPlayingChanged`;
`slychac` tracks playback correctly across play, pause and buffering; `wyjscia`
lists real devices; `srodowisko` is written at start.

**Not verified:** no non-`brak` value of `tlumienie` was produced on the bench.
Audio-focus suppression could not be provoked from adb — the voice recorder, a
second media app and the speech recogniser all declined to take focus. The
override itself compiles against Media3 1.11's `Player.Listener`, so the
callback exists and is wired, and both reason mappings are plain constant
lookups; but the interesting branch will first run in the car. Treat the first
`tlumienie` line from a real drive as the measurement.
