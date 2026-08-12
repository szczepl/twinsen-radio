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
