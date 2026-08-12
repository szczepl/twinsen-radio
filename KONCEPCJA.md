# Concept

How the app is built and **why, specifically, it's built that way**. Decisions
that cost us failed attempts along the way have their rationale recorded here —
so that nobody (ourselves included) goes back to a solution that has already
failed once.

---

## 1. What Android Auto Actually Is

It's **projection**, not a system running in the car. The phone renders the
entire interface and sends it over as an image; the head unit provides the
screen, touch input, and speakers. The look of lists, colors, day/night theme,
and toolbar layout are all the domain of Android Auto on the phone and the
car's own settings — the app controls only the **content**.

The AA receiver in the car is a closed-source Google library licensed to
manufacturers, compiled into the MIB3 firmware. It isn't part of AOSP and
can't be "virtualized." The DHU is a separate testing tool, frozen since
2022 — which is why some things behave differently on the desktop than in
the actual car.

Practical conclusion: **anything to do with how it looks in the car has to be
confirmed in the car.**

---

## 2. Layers

```
RadioService (MediaLibraryService)
   ├─ ExoPlayer + ICY  ──►  NowPlaying  ──►  MetadataFactory  ──►  MediaMetadata
   │                            │                                      │
   │                            └─► CoverArtLookup ─► TrackInfo ────────┘
   │
   ├─ PlaybackStatusBus  ──►  Phone UI (MainActivity, NowPlayingActivity)
   └─ browse tree ──►  Android Auto
```

`RadioService` is the sole owner of playback state. The phone UI reads it
through `PlaybackStatusBus` (a plain `StateFlow`, since both live in the same
process), while the car reads it through the media session.

---

## 3. Metadata: where it comes from and what we do with it

The ICY stream carries **a single line of text** and nothing else:

```
StreamTitle='Bonobo & Joy Crookes - Always on Your Side'   <- artist and track
StreamTitle='Radio Nowy Świat - Pion i poziom!'            <- station and slogan
StreamTitle=''  adw_ad='true'  durationMilliseconds='30000' <- ad break on RMF
StreamTitle='STOP_AD_BREAK'                                 <- control marker
```

Naively splitting on `" - "` gives, in the second case, an "artist" equal to
the station name — and the name ends up on screen twice. **Both ReplaIO and
the official RNŚ app fall into this trap.** That's why `NowPlaying.parse`
compares the left-hand side against the station name and separately
recognizes: an actual track, a station slogan, an ad, a news bulletin, and a
control marker.

A few rules worked out from live material:

* **Ads on RMF are announced three different ways** — an empty title with
  `adw_ad`, a word from a list (`AD_WORDS`), or not at all. Hence the added
  expiry mechanism for the description.
* **`STOP_AD_BREAK` doesn't mean the music is back.** RMF can slot in another
  ad right after it. The marker only arms the `MARKER_GRACE_MS` timer; if
  anything concrete arrives within that window, it wins.
* **We recognize a control marker by the rule** `^[A-Z0-9][A-Z0-9_]{3,}$` —
  without it, `STOP_AD_BREAK` was ending up in iTunes as a track title.
* **Description expiry.** Since the catalog knows the track's duration, after
  `duration + STALE_GRACE_MS` it's certainly no longer playing. When we don't
  know the duration — `FALLBACK_TRACK_MS`. Instead of showing nothing, we
  then fall back to the last known station slogan.
* **All caps.** Jacaranda FM sends everything in ALL CAPS, and record labels
  do the same in their catalogs. `TextCase` cleans this up in three steps, in
  this order: a string that isn't shouting is left untouched → if it is
  shouting and the catalog knows the same string properly capitalized, we
  take the catalog's version (it comes from the publisher) → only when the
  catalog is shouting too do we normalize it ourselves. Short words with no
  vowels are left alone, since they're almost always acronyms — otherwise
  "DJ Snake" would turn into "Dj Snake".

### Three description lines, and why they can't be split apart

Measurements taken in the car (BADANIA, point 1) showed that the Active Info
Display reads `subtitle`, `description`, and `displayTitle` — that is,
**the very same fields** the Android Auto central screen uses. The central
screen shows two of them: `displayTitle` as the large line and `subtitle` as
the small one.

Hence the design of the options screen: the user picks content **separately
for each of the three lines** (title / artist / artist with album and year /
artist — title / station name / clock / blank), rather than from a list of
ready-made layouts. Ready-made presets were convenient as long as we didn't
know what went where; now they were just a limitation.

A consequence worth knowing: **lines 1 and 3 can't be hidden from the central
screen**. A clock placed in a text line will show up in both places. The only
line visible exclusively on the AID is the middle one — which is why, by
default, it holds the station name rather than a copy of something already
visible elsewhere.

We fill the semantic fields (`title`, `artist`, `albumTitle`) independently of
the lines, according to their actual meaning. They don't appear on any screen
in the car, but they describe what's actually playing, and other systems can
read them.

### Diagnostic mode

Every `MediaMetadata` field gets its own short Polish label (`TYTUŁ` [TITLE],
`PODTYTUŁ` [SUBTITLE], `OPIS` [DESCRIPTION]…) together with a clock that
refreshes right on the minute boundary. The clock is there so it's visible on
the dashboard that the value is fresh, not stuck.

In this mode we **cut off ICY metadata** (`IcyFilteringDataSource`) —
otherwise ExoPlayer would overwrite `title`, `station`, and `genre` with
whatever Icecast sends, and the dashboard would show the station name instead
of the label.

The labels are pure ASCII: the "Media Playback Status" window in the DHU
reads UTF-8 as if it were Latin-1, and we don't know anything for certain
about the fonts on the Passat's dashboard. The label's job is to identify the
field, not to look good typographically.

---

## 4. Cover Art

ICY **carries no artwork at all** — stations attach cover art on the client
side. Radio Nowy Świat's web player queries iTunes, and we do the same:

1. **iTunes Search API** — free, no key required, also returns the release
   name, year, and track duration.
2. **MusicBrainz + Cover Art Archive** — only once iTunes doesn't know the
   track. Apple has poor coverage of older Polish repertoire (Czesław Niemen,
   "Lipowa łyżka" — not on Apple at all, while MusicBrainz knows both the
   track and the record).

### Rule: cover art never outlives the track it belongs to

This was a design mistake of ours, fixed after two bug reports. Originally,
the old artwork stayed on screen until a new one was found, so the station
logo wouldn't flash between tracks. In practice this produced a worse effect
than the flash itself:

* the previous track's cover kept hanging next to the new artist's name,
* when the studio segment for "Pion i poziom!" came on, the cover from a
  moment ago stayed put.

Now, on every track change, we immediately fall back to the station logo, and
only show the cover art once the catalog finds it (usually ~200 ms, so the
logo rarely has time to actually appear). **Order matters in `apply()` too**:
we clear the cover art first, and only then send the metadata — the other way
around, the car would receive the new description paired with the old
artwork.

The same rule applies to catalog data: we clear `trackInfo` on entry,
otherwise for a moment you'd see "Taylor Swift · Black Gold: The Best of Soul
Asylum [1992]".

---

## 5. Logos: why a custom ContentProvider

The logo used to go to Android Auto as
`android.resource://package/2131165359`. That address embeds a **numeric
resource ID that changes on almost every rebuild** — just adding a file to
`res/drawable` is enough, because aapt2 assigns numbers sequentially, in
alphabetical order.

Android Auto caches the fetched image under that address. After an update,
the same number pointed at a different station, and the head unit would draw
the cached Radio Nowy Świat logo under the "RMF FM" label.

`LogoProvider` exposes them at `content://…/logo/<station-id>?v=<number>`:
the address describes the **station**, not the resource, so it stays stable
across versions. We tack the resource number on as a parameter only so that,
when the artwork itself is swapped out, the cache gets invalidated exactly
once.

### Built-in station logos: links, not files in the repo

For the public release, built-in stations stopped having their logo as a file
in `res/drawable-nodpi` — those were the broadcasters' trademarks, and
redistributing them in a public repo/APK is a different kind of risk than
merely linking to the stream. Instead, `stations.json` now carries a
`logoUrl` pointing at artwork hosted by the station itself (the
favicon/apple-touch-icon of its own domain), and `logoUri()` in
`MetadataFactory` returns that address directly — without going through
`LogoProvider`, since it's no longer a package resource, just a plain
`http(s)://` address.

`LogoProvider` still handles: custom logos uploaded by the user (see below),
`logo_placeholder`, and the command-button icons in Android Auto — there, the
resource number is still the only channel available for older head units
(§6).

Consequence: the logo's appearance now depends on whatever the broadcaster's
site happens to be serving — the background or quality can be worse than the
previously bundled file (e.g. Radio Nowy Świat: it used to be manually
cropped and recomposed on a black background, now it's a direct link to
their original, uncropped JPG on a white background). Anyone who wants a
different look for a particular station uploads their own image on that
station's details screen (`StationInfoActivity`, tap the logo) — that
override is stored locally in `Prefs`, not in the repo, so it doesn't carry
the same legal problem.

---

## 6. Command button icons in Android Auto

Here the resource-number route is unavoidable under the old API, but there's
a documented workaround. The
[Android for Cars documentation](https://developer.android.com/training/cars/media/enable-playback)
says explicitly: if the icon corresponds to one of the `CommandButton.ICON_`
constants, its value should be placed under the key
`EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT` in the action's extras, because this
*"overrides the icon resource passed to `CustomAction.Builder` and lets the
system draw the action consistently with the rest"*. The head unit then draws
**its own** star.

That's why we supply the icon through two channels: extras (the proper one,
for modern HDUs) and the resource number (a fallback, for older ones — like
the DHU 2.1 from 2022).

**What not to do, verified:**

* Don't pass the semantic constant into the
  `CommandButton.Builder(ICON_STAR_FILLED)` constructor expecting that alone
  to be enough. For the old API, Media3 converts it into a resource number
  from its own AAR, and the DHU rendered a music note plus the text "1.8X"
  from it.
* Don't use `android:tint="@color/…"` in vector drawables meant for AA. The
  head unit inflates them in its own process, and the resource reference has
  to resolve inside our package — that's exactly where it broke down. Supply
  the color directly in `fillColor` instead.

---

## 7. Favourites as a single source of truth

Across three different approaches, we patched favourites separately on each
screen — the list when it came to the foreground, the playback screen when
rendering, Android Auto when building its buttons — and the states drifted
out of sync with each other.

Now there's a single `StateFlow` in `Prefs`, and everyone who cares observes
it: the list on the phone, the playback screen, the star next to the player
in the car, and the nodes of the browse tree. A change from anywhere
translates immediately into everything else.

Stations pulled in from the catalog work the same way (`discoveredFlow`).

Historical note: the Favourites list in Android Auto **wasn't refreshing
live**, despite correctly-called `notifyChildrenChanged`. The culprit turned
out to be Media3's bridge to the old `MediaBrowser` API — upgrading Media3
from 1.8.1 to **1.11.0** fixed it without changing any of our own code.

---

## 8. Resilience to network loss

Two layers, both deliberately silent — no message is meant to flash on
screen in the car:

1. `InfiniteLoadErrorHandlingPolicy` — ExoPlayer retries indefinitely with a
   growing delay (0.5 s → 15 s), staying in the `BUFFERING` state. Exception:
   persistent 4xx errors are passed through, since those won't fix
   themselves.
2. `ReconnectController` — listens to `ConnectivityManager` and resumes
   immediately once a validated network is regained, instead of waiting for
   the next backoff step.

In addition, `WAKE_MODE_NETWORK` keeps the radio alive, and
`handleAudioBecomingNoisy` pauses playback when Bluetooth disconnects.

### The gap that leaves players hanging for hours

The silent retrier from point 1 has a side effect: since it **never reports
an error**, `onPlayerError` may never fire at all. The player then sits in
`BUFFERING` with an "OK" status, and a condition like "only react to the
network coming back when status ≠ OK" lets nothing through. This is exactly
how ReplaIO and TuneIn behave — they can hang for an hour after you drive out
of the garage before finally noticing the network is back.

Two fixes close this off completely:

* **The network trigger looks at the player, not at its status** — it reacts
  whenever the user wants playback and the player isn't ready, regardless of
  status.
* **Buffering has a limit.** If it drags on past 12 s, that's not the buffer
  filling up, it's a lack of connection — at that point we call it what it is
  and start retrying ourselves.

When there's no network, the **middle AID line** shows "Oczekuję na
sieć…" ("Waiting for network…"). This is the only field visible exclusively
on the dashboard, so the central screen stays clean.

`KeepCurrentStreamPlayer` ignores `setMediaItem` for a station that's already
playing — without this, tapping a currently-playing station from the list
would drop the connection and you'd hear a gap.

---

## 9. Stations beyond the built-in list

The source is **radio-browser.info** — an open, community-maintained catalog
of roughly 50,000 stations. Chosen because it's the only one that meets the
full set of requirements: free, no key or registration, a clear license, a
public API, and — most importantly — it filters out dead streams itself
(`hidebroken`), since it checks them periodically.

Rejected: TuneIn and iHeartRadio have no open API, Shoutcast requires a key
issued by hand, and nobody maintains M3U lists scraped from the web.

We store added stations **in full**, not by ID: the catalog might stop
responding or remove an entry, and a station, once added, should keep working
in the car even without access to the catalog.

---

## 10. Known limitations

* **Polskie Radio's HLS carries no track titles.** The segments contain only
  timestamps (`com.apple.streaming.transportStreamTimestamp`), no ID3
  `TIT2`/`TPE1` at all. Jedynka, Dwójka, Trójka, Czwórka, PR24, and Kierowcy
  will therefore show only the station name. There's no alternative —
  Polskie Radio's entire Shoutcast plant is dead (the TCP connection goes
  through, but there's no data).
* **triple j has a single, nationwide stream.** There's no Melbourne-specific
  version — `3TJW` redirects to a web page.
* **The AID is rendered by the car itself.** The Instrument Cluster channel
  in AA carries only navigation, so the music tile on the instrument cluster
  can't be previewed on the desktop.
