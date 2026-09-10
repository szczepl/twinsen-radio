package net.mspanc.twinsenradio.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ContentStyle
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.ui.MainActivity

/**
 * Playback service that is also the browse tree source for Android Auto.
 *
 * Media3's MediaLibraryService exposes both the new session API and the old
 * MediaBrowserService, which Android Auto still uses - that's why the manifest
 * has two intent filters.
 */
@UnstableApi
class RadioService : MediaLibraryService() {

    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var reconnect: ReconnectController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastRawIcyTitle: String? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockTick: Runnable? = null
    private var lastIcyAtMs = 0L

    /** Cover art found for the current track; null = we show the station logo. */
    @Volatile
    private var coverArtUrl: String? = null

    /** What the catalog knows about the current track - release, year, cover art. */
    @Volatile
    private var trackInfo: CoverArtLookup.TrackInfo? = null

    /** Increments on every track change - filters out stale search results. */
    private var coverGeneration = 0
    private var coverRevertJob: Job? = null

    /**
     * The track that the cover art held in [coverArtUrl] belongs to. Used to
     * detect the moment when the cover art stops matching what's playing.
     */
    private var coverTrackKey: String? = null

    /** Format from the decoder and bitrate from the ICY header - together they give the quality description. */
    @Volatile
    private var audioFormat: androidx.media3.common.Format? = null

    @Volatile
    private var icyBitrateKbps = 0

    /** Waits after a control marker for whatever the station inserts next. */
    private var pendingMarkerJob: Job? = null

    /** Watches whether the track description has gone stale because the station announced nothing. */
    private var staleJob: Job? = null

    /**
     * When the current description stops being believable, on the monotonic
     * clock; 0 means nothing is armed.
     *
     * A deadline rather than a timer, because the way this app is actually used
     * has the phone asleep in a pocket with the radio on the head unit, and a
     * coroutine `delay` in Doze fires minutes late - measured at five to eight
     * on the 09-10.09.2026 traces. Being late does no harm as long as nobody is
     * reading: what matters is that the description is right at the moment
     * something starts reading it again, and every such moment already passes
     * through [refreshCurrentMetadata]. [staleJob] stays on as one more trigger,
     * for when nothing else wakes us.
     */
    private var staleAtMs = 0L

    /** What [staleAtMs] was worked out from - only the trace reads it. */
    private var staleBasis: String? = null

    /** The track [staleAtMs] belongs to, so a late check can't clear a newer one. */
    private var staleForRaw: String? = null

    /**
     * Whether we joined the current track partway through, with no way of
     * knowing how far.
     *
     * True right after a station change or a reconnect, because the server
     * dumps whatever `StreamTitle` is current the moment we attach - on
     * 09-10.09.2026 four of Smooth FM's five opening blocks arrived within
     * three seconds of connecting, mid-song every time. ICY carries no position
     * in the track, so until the station announces a change we have actually
     * witnessed, a catalog length says nothing about how much is left.
     */
    private var positionUnknown = true

    /** Holds back the first description of a freshly started station - see [publish]. */
    private var settleJob: Job? = null

    /**
     * When the current station was started, on the monotonic clock. Its
     * description may go on screen [STATION_SETTLE_MS] later, and everything the
     * station does is measured from here in the trace.
     */
    private var stationAtMs = 0L

    /** Fingerprint of the last metadata dump - filters out repeats in the log. */
    private var lastDump: String? = null

    /** Address we're playing from - used to detect a quality variant change. */
    private var currentStreamUrl: String? = null

    /** Last station slogan - shown when we don't know what's currently playing. */
    @Volatile
    private var lastSlogan: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A setting changed halfway through a drive changes what the following
        // lines in the trace mean, so the change is a line of its own. Not the
        // app's own bookkeeping though - the play counter and the recent list
        // are written on every station change, and two lines saying so after
        // every switch is noise in a file meant to be read.
        if (key != null && !isBookkeeping(key)) Trace.write("cfg") { put("key", key) }
        when (key) {
            Prefs.KEY_STYLE_BROWSABLE, Prefs.KEY_STYLE_PLAYABLE, Prefs.KEY_M3U -> {
                session.notifyChildrenChanged(NODE_ROOT, Int.MAX_VALUE, null)
                BROWSE_NODES.forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
            }
            Prefs.KEY_TRACE -> if (prefs.traceEnabled) startTrace() else Trace.close()
            Prefs.KEY_DIAG -> {
                refreshCurrentMetadata(force = true, why = "diag")
                // Stripping ICY happens when the data source is created, so for
                // the toggle to take effect immediately the stream must be re-prepared.
                if (player.playWhenReady && player.currentMediaItem != null) {
                    player.prepare()
                }
            }
            Prefs.KEY_DIAG_API, Prefs.KEY_ARTWORK,
            Prefs.KEY_LINE_TOP, Prefs.KEY_LINE_MIDDLE, Prefs.KEY_LINE_BOTTOM,
            Prefs.KEY_CLOCK_FACE, Prefs.KEY_CLOCK_ALWAYS, Prefs.KEY_CLOCK_BG,
            Prefs.KEY_CLOCK_FG, Prefs.KEY_ENRICH_ALBUM ->
                refreshCurrentMetadata(force = true, why = "opcje")
            Prefs.KEY_BUFFER -> Log.i(TAG, "Zmieniono bufor - zadziala po restarcie odtwarzania")
            // A quality change or a custom address change concerns a specific station.
            // The keys are prefixed with its identifier, so we check the beginning.
            else -> if (key?.startsWith("stream_") == true) reloadCurrentStation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = StationRepository.get(this)
        metadata = MetadataFactory(this, prefs)
        if (prefs.traceEnabled) startTrace()

        player = buildPlayer()
        player.addListener(PlayerEvents())
        player.addAnalyticsListener(
            LoadDiagnostics { format ->
                audioFormat = format
                publishQuality()
            }
        )

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaLibrarySession.Builder(this, KeepCurrentStreamPlayer(player), LibraryCallback())
            .setSessionActivity(sessionActivity)
            .setCustomLayout(customLayout())
            .build()

        reconnect = ReconnectController(this, player) { status ->
            Trace.write("net") { put("status", status.name) }
            // A dropped stream means the next block will be a fresh server dump
            // of whatever is playing by then - mid-track again, just as at a
            // station change.
            if (status == ReconnectController.Status.RECONNECTING) positionUnknown = true
            PlaybackStatusBus.setStatus(
                when (status) {
                    ReconnectController.Status.RECONNECTING -> PlaybackStatusBus.Status.RECONNECTING
                    ReconnectController.Status.WAITING_FOR_NETWORK -> PlaybackStatusBus.Status.WAITING_FOR_NETWORK
                    ReconnectController.Status.STATION_UNREACHABLE -> PlaybackStatusBus.Status.STATION_UNREACHABLE
                    ReconnectController.Status.OK -> PlaybackStatusBus.Status.PLAYING
                }
            )
            // No network hits the middle line on the dashboard, so the metadata
            // must be pushed again - otherwise the message would only appear at
            // the next track, which in practice means never.
            refreshCurrentMetadata(force = true, why = "siec")
        }
        reconnect.start()
        prefs.registerListener(prefsListener)
        scheduleClockTick()

        scope.launch { repo.refreshUserLists() }

        // A favourites change - wherever it came from - must immediately translate
        // into the star next to the player and into the lists that show it.
        scope.launch {
            Prefs.favouritesFlow.collect { favourites ->
                if (!this@RadioService::session.isInitialized) return@collect
                Log.i(TAG, "ulubione zmienione (${favourites.size}) - odswiezam przyciski i wezly")
                session.setCustomLayout(customLayout())
                notifyBrowseNodesChanged(NODE_FAVOURITES, NODE_ALL, NODE_RECENT)
            }
        }

        // A station added from the catalog should appear in the car without restarting the app.
        // A new genre may also show up, so we refresh their list as well.
        scope.launch {
            Prefs.discoveredFlow.collect { stations ->
                if (!this@RadioService::session.isInitialized) return@collect
                Log.i(TAG, "stacje z sieci zmienione (${stations.size}) - odswiezam wezly")
                notifyBrowseNodesChanged(NODE_ALL, NODE_GENRES)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        prefs.unregisterListener(prefsListener)
        reconnect.stop()
        session.release()
        player.release()
        scope.cancel()
        Trace.close()
        super.onDestroy()
    }

    /**
     * Keys the app writes to itself on every station change. A star pressed in
     * the car is a decision and stays in the trace; a play counter is not.
     */
    private fun isBookkeeping(key: String): Boolean =
        key.startsWith("plays_") || key == Prefs.KEY_RECENT

    /**
     * Opens the trace and writes down the settings it was recorded under.
     *
     * The layout is in the file because the same events read differently under
     * a different one: an empty middle line is a station saying nothing when
     * the line carries the artist, and it is the layout when the line is set
     * to "off". Reading that off the phone weeks later is not an option -
     * by then the settings have moved.
     */
    private fun startTrace() {
        Trace.open(this)
        val p = prefs.presentation
        Trace.write("uklad") {
            put("gora", p.top.name)
            put("srodek", p.middle.name)
            put("dol", p.bottom.name)
            put("zegar", p.clockFace.name)
            put("zegarZawsze", prefs.clockCoverAlways)
            put("okladka", prefs.artworkMode)
            put("album", prefs.enrichWithYear)
            put("diag", prefs.diagnosticMode)
            put("bufor", prefs.bufferProfile)
        }
    }

    // --- player construction ----------------------------------------------------

    private fun buildPlayer(): ExoPlayer {
        val profile = BufferProfile.at(prefs.bufferProfile)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.forPlaybackMs,
                profile.afterRebufferMs
            )
            // For a live stream we care about time, not buffer size.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(StationRepository.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(false)

        val filtered = IcyFilteringDataSource.Factory(
            DefaultDataSource.Factory(this, httpFactory)
        ) { prefs.diagnosticMode && prefs.stripIcyInDiagnostic }

        val mediaSourceFactory = DefaultMediaSourceFactory(filtered)
            .setLoadErrorHandlingPolicy(InfiniteLoadErrorHandlingPolicy())

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    // --- reactions to player events --------------------------------------------

    private inner class PlayerEvents : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A station change starts everything from scratch. Without this, the cover art
            // from the previous station would stay in the `coverArtUrl` field and come back
            // on screen at the next metadata refresh - after switching from RMF to RNS
            // the RMF cover art would briefly show instead of the Nowy Swiat logo.
            resetStationState()
            val id = mediaItem?.mediaId?.let { Station.idFromMediaId(it) }
            PlaybackStatusBus.setStation(id)

            Trace.station(id)
            Trace.write("stacja") {
                put("nazwa", id?.let { repo.byId(it)?.name })
                put("url", mediaItem?.localConfiguration?.uri?.toString())
                put("powod", reason)
            }

            // The new station starts from the state it would be in with no track:
            // whatever the layout says belongs on screen when nothing is playing.
            // Sending it right here means the previous station's title cannot
            // survive on the dashboard until the new one happens to say something -
            // and a station that says nothing at all leaves this standing, which
            // is exactly what it should show.
            refreshCurrentMetadata(force = true, now = null, why = "zmiana-stacji")

            id?.let {
                prefs.pushRecent(it)
                // Play count - the only sensible source for a "most listened"
                // ordering, which after a few weeks of driving arranges the list
                // better than anything we could come up with upfront.
                prefs.bumpPlayCount(it)
            }

            // The star belongs to a specific station, so on change the button
            // layout needs to be rebuilt. Without this, entering a station showed
            // the previous one's state and the first tap looked like it did nothing.
            if (this@RadioService::session.isInitialized) {
                session.setCustomLayout(customLayout())
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            Trace.write("stan") {
                put(
                    "stan",
                    when (state) {
                        Player.STATE_IDLE -> "idle"
                        Player.STATE_BUFFERING -> "buforuje"
                        Player.STATE_READY -> "gotowy"
                        else -> "koniec"
                    }
                )
                put("gra", player.playWhenReady)
            }
            PlaybackStatusBus.setStatus(
                when (state) {
                    Player.STATE_BUFFERING -> PlaybackStatusBus.Status.BUFFERING
                    Player.STATE_READY -> if (player.playWhenReady) {
                        PlaybackStatusBus.Status.PLAYING
                    } else {
                        PlaybackStatusBus.Status.IDLE
                    }
                    else -> PlaybackStatusBus.Status.IDLE
                }
            )
        }

        /**
         * Only for the record - the rescue itself is [ReconnectController]'s
         * job. An error that coincides with a station change is worth being
         * able to see afterwards, and logcat will not have kept it.
         */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Trace.write("blad") {
                put("kod", error.errorCodeName)
                put("opis", error.message)
            }
        }

        override fun onMetadata(meta: Metadata) {
            for (i in 0 until meta.length()) {
                when (val entry = meta.get(i)) {
                    is IcyHeaders -> logIcyHeaders(entry)
                    is IcyInfo -> {
                        val raw = logIcyInfo(entry)
                        handleIcyTitle(entry.title, raw)
                    }
                    else -> Log.i(TAG_ICY, "inny typ metadanych: ${entry.javaClass.simpleName} | $entry")
                }
            }
        }
    }

    /**
     * New title from Icecast. We update the item's metadata, not the stream itself -
     * `replaceMediaItem` with the same source configuration doesn't interrupt playback.
     */
    /** icy-* headers from the HTTP response - constant for the whole stream. */
    private fun logIcyHeaders(h: IcyHeaders) {
        Log.i(TAG_ICY, "== naglowki ICY strumienia ==")
        h.name?.let { Log.i(TAG_ICY, "icy-name = $it") }
        h.genre?.let { Log.i(TAG_ICY, "icy-genre = $it") }
        h.url?.let { Log.i(TAG_ICY, "icy-url = $it") }
        if (h.bitrate > 0) {
            Log.i(TAG_ICY, "icy-br = ${h.bitrate}")
            // Fallback in case the decoder doesn't report a bitrate - with AAC
            // in ADTS this is the rule, since the stream itself carries none at all.
            icyBitrateKbps = h.bitrate
            publishQuality()
        }
        if (h.metadataInterval > 0) Log.i(TAG_ICY, "icy-metaint = ${h.metadataInterval}")
        Log.i(TAG_ICY, "icy-pub = ${h.isPublic}")
        // Once per connection, and it says what the station promises about
        // itself - including metaint, which is 0 on a stream that will never
        // send a title. That distinguishes "said nothing yet" from "never will".
        Trace.write("naglowki") {
            put("name", h.name)
            put("genre", h.genre)
            put("br", h.bitrate)
            put("metaint", h.metadataInterval)
            put("poStacji", sinceStationMs())
        }
    }

    /**
     * The metadata block injected into the stream. ExoPlayer only parses
     * StreamTitle and StreamUrl out of it, but stations push in more key=value
     * pairs (RMF, for instance, marks ads via adw_ad and durationMilliseconds). So
     * alongside the recognized fields we also log the whole raw content.
     */
    private fun logIcyInfo(info: IcyInfo): String {
        val raw = runCatching { String(info.rawMetadata, Charsets.UTF_8).trim(Char(0), ' ') }
            .getOrDefault("")
        Log.i(TAG_ICY, "surowy blok: $raw")
        info.url?.let { Log.i(TAG_ICY, "StreamUrl = $it") }

        // split all key='value' pairs so unusual fields are visible
        Regex("""(\w+)='([^']*)'""").findAll(raw).forEach { m ->
            Log.i(TAG_ICY, "  ${m.groupValues[1]} = ${m.groupValues[2]}")
        }
        return raw
    }

    private fun handleIcyTitle(rawTitle: String?, rawBlock: String? = null) {
        val title = rawTitle?.trim().orEmpty()
        // The title alone isn't enough to detect a repeat: ads have an empty
        // title, and different insertions only differ in their adId fields.
        val fingerprint = title + "|" + rawBlock.orEmpty()
        if (fingerprint == lastRawIcyTitle) {
            // A block identical to the last one changes nothing on screen, but
            // it is not nothing: the station is alive and still naming this
            // track. Smooth FM re-sent "Paula Abdul - Straight Up" 242s after
            // the first copy on 10.09.2026. Until now this returned before
            // Trace.write, so repeats were invisible in the file and only ever
            // showed up in logcat - which is why nobody could tell whether they
            // mean "still playing" or "nothing new to say". Recorded, not acted
            // on: whether a repeat should push the stale deadline out depends on
            // whether stations also repeat during ad blocks, and that is a
            // question for the trace, not for a guess here.
            Trace.write("icy-powtorka") {
                put("blok", rawBlock)
                put("poStacji", sinceStationMs())
                put("poPoprzednim", if (lastIcyAtMs == 0L) null else System.currentTimeMillis() - lastIcyAtMs)
            }
            return
        }
        lastRawIcyTitle = fingerprint

        // The gap between ICY blocks is something we don't know about stations:
        // whether metadata arrives once per track or periodically. We log it so
        // it can be measured from the outside.
        val nowMs = System.currentTimeMillis()
        val gapMs = if (lastIcyAtMs == 0L) null else nowMs - lastIcyAtMs
        val sinceLast = gapMs?.div(1000) ?: -1
        lastIcyAtMs = nowMs
        Log.i(TAG_ICY, "po ${sinceLast}s | StreamTitle='$title'")

        // The first block of a station (or of a fresh connection after a drop)
        // is the server telling us what is already playing; every block after
        // it is a change we saw happen. That difference is the whole basis of
        // the stale deadline - see [positionUnknown].
        if (gapMs != null) positionUnknown = false

        val stationName = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it)?.name }
        val now = NowPlaying.parse(title, stationName, rawBlock)
        Log.i(
            TAG_ICY,
            "  -> artist='${now?.artist}' title='${now?.songTitle}' " +
                "slogan='${now?.slogan}' reklama=${now?.isAd} " +
                "znacznik=${now?.isControlMarker} utwor=${now?.isRealSong}"
        )
        // Both halves on one line: the block exactly as it arrived, and what we
        // made of it. Reading them apart is how a parser fault gets blamed on a
        // station, and a station's oddity on the parser.
        Trace.write("icy") {
            put("blok", rawBlock)
            put("tytul", title)
            put("poStacji", sinceStationMs())
            put("poPoprzednim", gapMs)
            put("wykonawca", now?.artist)
            put("utwor", now?.songTitle)
            put("slogan", now?.slogan)
            put("reklama", now?.isAd)
            put("znacznik", now?.isControlMarker)
            put("prawdziwy", now?.isRealSong)
            put("pominiete", now == null)
        }

        // An empty StreamTitle with no ad marker is the only thing NowPlaying.parse
        // returns null for, and it means the station had nothing to say - not that
        // nothing is playing. Treating the two as one wiped a description in the
        // middle of a song: on 10.09.2026 Smooth FM sent an empty block 55s into
        // "Boyzone - A Picture Of You" (3:26 long) and the dashboard sat on the
        // station name for three minutes until the next track was announced.
        //
        // So we record it and leave the screen and the deadline exactly as they
        // are. Deciding that a track is over is the stale deadline's job, and it
        // works from how long the track runs rather than from a station's silence.
        // A station that really has stopped playing music says so - RMF marks its
        // ads, and an empty title arrives there alongside adw_ad, which parses to
        // an ad and not to null.
        if (now == null) {
            Log.i(TAG_ICY, "pusty blok bez znacznika reklamy - zostawiam opis bez zmian")
            return
        }
        publish(now)
    }

    /**
     * Holds back the first description of a freshly started station.
     *
     * The station change has just put the "no track" state on screen, and a
     * stream that already has metadata waiting answers within a fraction of a
     * second - the two arrive so close together that the head unit renders one
     * flicker instead of two states. So for [STATION_SETTLE_MS] after a change
     * the description waits; whatever came last during that window is what goes
     * up. Nothing arriving is not a case to handle: the "no track" state stands
     * and we wait for the station to say something, whenever that is.
     */
    private fun publish(now: NowPlaying?) {
        settleJob?.cancel()
        val wait = stationAtMs + STATION_SETTLE_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            Log.i(TAG_ICY, "stacja dopiero ruszyla - opis poczeka ${wait}ms")
            Trace.write("wstrzymanie") { put("ms", wait) }
            settleJob = scope.launch {
                delay(wait)
                publishNow(now)
            }
            return
        }
        publishNow(now)
    }

    /** How long the current station has been playing, for the trace. */
    private fun sinceStationMs(): Long? =
        if (stationAtMs == 0L) null else SystemClock.elapsedRealtime() - stationAtMs

    /**
     * Decides whether an event from ICY should immediately change what's shown on screen.
     *
     * A control marker (e.g. STOP_AD_BREAK) by itself doesn't mean the music is
     * back - RMF can insert another ad right after it. If we switched back to the
     * station view immediately, the screen would flicker between "Ad" and the
     * station name on every insertion. So the marker only arms a timer: if
     * something concrete arrives within [MARKER_GRACE_MS] - a track or another
     * ad - it wins, and if nothing arrives, only then do we fall back to just
     * the station.
     */
    private fun publishNow(now: NowPlaying?) {
        pendingMarkerJob?.cancel()

        if (now?.isControlMarker == true && now.isAd != true) {
            Log.i(TAG_ICY, "znacznik '${now.raw}' - czekam ${MARKER_GRACE_MS}ms na to, co dalej")
            pendingMarkerJob = scope.launch {
                delay(MARKER_GRACE_MS)
                Log.i(TAG_ICY, "po znaczniku nic nie przyszlo - zostawiam sama stacje")
                apply(null)
            }
            return
        }

        apply(now)
    }

    private fun apply(now: NowPlaying?) {
        now?.slogan?.let { lastSlogan = it }

        // Some stations re-announce the very same song's ICY metadata mid-track
        // (a periodic StreamTitle repeat, not an actual track change) - the raw
        // block differs just enough to dodge the fingerprint check in
        // handleIcyTitle, so we still land here. Comparing against the track the
        // cover art already belongs to catches that case and skips the
        // clear-and-relookup cycle, which is what showed up as the station logo
        // flashing back on for an instant in the middle of a song.
        val key = if (now?.isRealSong == true) "${now.artist}|${now.songTitle}" else null
        val sameTrack = key != null && key == coverTrackKey

        if (!sameTrack) {
            // Catalog data belongs to the PREVIOUS track, so we clear it before drawing
            // anything. Otherwise the new artist briefly shows up glued to the old
            // record - "Taylor Swift - Black Gold: The Best of Soul Asylum [1992]".
            // The cover art is cleared by updateCoverArt, also immediately.
            trackInfo = null
            PlaybackStatusBus.setTrackInfo(null)
        }

        PlaybackStatusBus.setNowPlaying(now)

        // Order matters: we clear the cover art first, only then send the
        // metadata. The other way around, the car would get a new description
        // with the old artwork - after the song, the studio would come on and
        // "Pion i poziom!" would still show the previous track's cover.
        updateCoverArt(now, key, sameTrack)
        refreshCurrentMetadata(force = true, now = now, why = "icy")
        scheduleStaleCheck(now)
    }

    /**
     * Arms the deadline after which a track whose end was never announced stops
     * being shown.
     *
     * RMF can enter an ad block without any ICY event - the screen would then
     * keep a title from several minutes ago, because we had no signal that
     * anything had changed. How long to wait depends on what we actually know,
     * and the three cases below used to be treated as one, which is how a
     * station name ended up over the middle of a song.
     */
    private fun scheduleStaleCheck(now: NowPlaying?) {
        staleJob?.cancel()
        staleAtMs = 0L
        staleBasis = null
        staleForRaw = null
        if (now?.isRealSong != true) return

        val catalogMs = trackInfo?.durationMs ?: 0
        val (budget, basis) = when {
            // We joined this track partway through and the stream won't say how
            // far, so its length tells us nothing about what is left. Whatever
            // we pick here is a guess; we make it a generous one, because taking
            // a correct description off the screen is worse than leaving a
            // finished one up a while longer.
            positionUnknown -> FALLBACK_TRACK_MS to "pozycja-nieznana"
            // A length below any radio edit means the catalog matched a
            // different release, not that the song is short. "Stayin' Alive"
            // came back as a 1:33 soundtrack cut on 09.09.2026 and wiped the
            // description two minutes into a song that was still playing.
            catalogMs in 1 until MIN_TRACK_MS -> MIN_TRACK_MS to "katalog-za-krotki"
            catalogMs > 0 -> catalogMs to "katalog"
            else -> FALLBACK_TRACK_MS to "bez-dlugosci"
        }

        val timeout = budget + STALE_GRACE_MS
        staleAtMs = SystemClock.elapsedRealtime() + timeout
        staleBasis = basis
        staleForRaw = now.raw
        // Which of the three cases we landed in, and on what number. Without it
        // the expiry showed up in the trace as an ordinary "icy" push, and the
        // only way to tell them apart was to correlate timestamps by hand.
        Trace.write("termin") {
            put("utwor", now.raw)
            put("podstawa", basis)
            put("dlugoscKatalog", catalogMs)
            put("zaMs", timeout)
        }
        staleJob = scope.launch {
            delay(timeout)
            checkStale("licznik")
        }
    }

    /**
     * Drops a description whose track must be over by now, if it is time.
     *
     * Called from [refreshCurrentMetadata] - that is, from every point where
     * something is about to read what we show - rather than from the timer
     * alone. See [staleAtMs] for why a timer on its own isn't enough.
     *
     * @return whether it fired. A caller that gets `true` has nothing left to
     *   send: [apply] has already pushed a complete fresh set.
     */
    private fun checkStale(trigger: String): Boolean {
        val deadline = staleAtMs
        if (deadline == 0L) return false
        val late = SystemClock.elapsedRealtime() - deadline
        if (late < 0) return false

        val expired = staleForRaw
        // Something newer is already on screen - the deadline outlived what it
        // was armed for and has nothing left to clear.
        if (PlaybackStatusBus.nowPlaying.value?.raw != expired) {
            staleAtMs = 0L
            return false
        }

        Log.i(TAG_ICY, "utwor '$expired' powinien byc juz po - czyszcze opis ($trigger)")
        Trace.write("przeterminowanie") {
            put("utwor", expired)
            put("podstawa", staleBasis)
            put("wyzwalacz", trigger)
            // How far past the deadline we actually got. On a sleeping phone
            // this runs into minutes, and that number is the whole reason the
            // check doesn't live in the timer.
            put("spoznienie", late)
        }
        // Cleared before apply(), which refreshes the metadata and so re-enters
        // this function: the second call sees no deadline and returns at once.
        staleAtMs = 0L
        staleBasis = null
        staleForRaw = null

        // If the station has ever given its slogan, it's better to show it
        // than an empty line - RNS has "Pion i poziom!", RMF "FAKTY" during the news.
        val slogan = lastSlogan
        if (slogan != null) {
            apply(
                NowPlaying(
                    raw = slogan,
                    artist = null,
                    songTitle = slogan,
                    isStationSelfTitle = true
                )
            )
        } else {
            apply(null)
        }
        return true
    }

    /**
     * Clears everything we knew about the previous station. Called on a station
     * change, where no trace of the previous one is allowed to remain - not the
     * cover art, not the description, and not a timer armed by something the
     * previous station said. A marker left running was the worst of them: a
     * STOP_AD_BREAK from the station we just left would fire fifteen seconds
     * into the new one and wipe a description that had just arrived.
     */
    private fun resetStationState() {
        coverGeneration++
        coverRevertJob?.cancel()
        staleJob?.cancel()
        staleAtMs = 0L
        staleBasis = null
        staleForRaw = null
        // Whatever the new station says first describes a track already in
        // progress, exactly as it did for the one we are leaving.
        positionUnknown = true
        pendingMarkerJob?.cancel()
        settleJob?.cancel()
        stationAtMs = SystemClock.elapsedRealtime()
        coverTrackKey = null
        coverArtUrl = null
        trackInfo = null
        lastRawIcyTitle = null
        lastSlogan = null
        // The gap between ICY blocks is measured within one station; carried
        // across a change it would report the time since the previous one.
        lastIcyAtMs = 0L
        // A new station gets its own first dump, even if the description happens
        // to read the same as the one we just left (two stations, both showing
        // just the clock).
        lastDump = null
        audioFormat = null
        icyBitrateKbps = 0
        PlaybackStatusBus.setCoverArt(null)
        PlaybackStatusBus.setTrackInfo(null)
        PlaybackStatusBus.setNowPlaying(null)
        PlaybackStatusBus.setQuality(null)
    }

    /**
     * Swaps the cover art on a track change.
     *
     * There's one rule: **the cover art never outlives the track it belongs to**.
     * Previously it was the opposite - the old artwork stayed until a new one was
     * found, so the station logo wouldn't flicker between tracks. In practice that
     * gave a worse result than a flicker: for a fraction of a second (and after
     * the description expired, even for [COVER_GRACE_MS]) the previous record's
     * cover hung next to the new artist's name, which simply looks like a bug.
     *
     * So on every track change we immediately fall back to the station logo, and
     * only show the cover art once the catalog actually finds it. When a station
     * sends metadata right at connection time, the lookup usually takes
     * ~200 ms and the logo barely has time to appear.
     */
    private fun updateCoverArt(now: NowPlaying?, key: String?, sameTrack: Boolean) {
        // Same track re-announced - we already have its art (or a lookup for it
        // already in flight), nothing to clear or re-fetch.
        if (sameTrack) return

        val generation = ++coverGeneration
        coverRevertJob?.cancel()

        fun applyIfCurrent(info: CoverArtLookup.TrackInfo?) {
            if (generation != coverGeneration) return
            val url = info?.artworkUrl
            if (coverArtUrl == url && trackInfo == info) return
            coverArtUrl = url
            trackInfo = info
            PlaybackStatusBus.setCoverArt(url)
            PlaybackStatusBus.setTrackInfo(info)
            refreshCurrentMetadata(force = true, now = PlaybackStatusBus.nowPlaying.value, why = "katalog")
            // We now know the track length - recompute the moment the description goes stale
            scheduleStaleCheck(PlaybackStatusBus.nowPlaying.value)
        }

        coverTrackKey = key
        if (coverArtUrl != null) {
            coverArtUrl = null
            trackInfo = null
            PlaybackStatusBus.setCoverArt(null)
            PlaybackStatusBus.setTrackInfo(null)
        }

        // An ad or the station's own slogan - nothing to look up in the catalog.
        if (key == null) return

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val info = CoverArtLookup.find(now!!.artist, now.songTitle)
            // The answer, and the decision taken on it. The swapped flag is here
            // because the station's own order cannot be judged without it -
            // Jacaranda sends title first, and that only shows against a catalogue.
            Trace.write("katalog") {
                put("pytanieWykonawca", now.artist)
                put("pytanieUtwor", now.songTitle)
                put("ms", SystemClock.elapsedRealtime() - startedAt)
                put("znaleziono", info != null)
                put("album", info?.album)
                put("rok", info?.year)
                put("dlugoscMs", info?.durationMs)
                put("katalogUtwor", info?.trackName)
                put("katalogWykonawca", info?.artistName)
                put("zamiana", info?.looksSwapped(now.artist, now.songTitle))
                put("aktualne", generation == coverGeneration)
            }
            if (generation != coverGeneration) return@launch
            applyIfCurrent(info)
        }
    }

    /**
     * Reloads the current station at a new address.
     *
     * Used after a quality variant change. We go straight to ExoPlayer, bypassing
     * [KeepCurrentStreamPlayer] - it deliberately ignores setting the same
     * station so that tapping the currently playing item doesn't drop the
     * connection, but here dropping it is exactly the point.
     */
    private fun reloadCurrentStation() {
        val id = PlaybackStatusBus.stationId.value ?: return
        val station = repo.byId(id) ?: return
        if (station.stream == currentStreamUrl) return
        currentStreamUrl = station.stream
        Log.i(TAG, "zmieniono strumien stacji ${station.name} na ${station.stream}")
        val wasPlaying = player.playWhenReady
        player.setMediaItem(playableItem(station))
        player.prepare()
        if (wasPlaying) player.play()
    }

    /** Builds the quality description from the decoder format and the icy-br header. */
    private fun publishQuality() {
        val format = audioFormat
        if (format == null) {
            PlaybackStatusBus.setQuality(null)
            return
        }
        val quality = StreamQuality.of(format, icyBitrateKbps)
        Trace.write("jakosc") {
            put("opis", quality.label().ifBlank { null })
            put("kbps", quality.bitrateKbps.takeIf { it > 0 })
            put("codec", format.sampleMimeType)
            put("hz", format.sampleRate.takeIf { it > 0 })
            put("poStacji", sinceStationMs())
        }
        PlaybackStatusBus.setQuality(
            quality.label().ifBlank { null },
            quality.bitrateKbps.takeIf { it > 0 }
        )
    }

    /**
     * Refreshes metadata exactly on the minute boundary, not every 60s from
     * start - otherwise the clock in the subtitle would drift against the car's clock.
     */
    private fun scheduleClockTick() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        val delayToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
        val runnable = Runnable {
            // We only refresh the clock when there's somewhere to show it - always
            // in diagnostic mode, otherwise only in layouts that have a clock.
            val needed = prefs.diagnosticMode || prefs.presentation.needsClock
            if (needed) refreshCurrentMetadata(force = true, why = "zegar")
            scheduleClockTick()
        }
        clockTick = runnable
        clockHandler.postDelayed(runnable, delayToNextMinute + 100)
    }

    /**
     * @param why what prompted the push. Only the trace reads it, and it is the
     *   difference between "the description changed" and "the minute did".
     */
    private fun refreshCurrentMetadata(
        force: Boolean,
        now: NowPlaying? = PlaybackStatusBus.nowPlaying.value,
        why: String = "?"
    ) {
        // Every refresh is something about to read what we show, so it is also
        // the moment to ask whether it is still true. Returning here is not an
        // optimisation: `now` was captured before this line, so carrying on
        // would push the description checkStale has just retired.
        if (checkStale(why)) return

        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val station = repo.byMediaId(item.mediaId) ?: return
        if (!force && prefs.diagnosticMode) return
        val fresh = metadata.forPlayback(station, now, coverArtUrl, trackInfo)
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(fresh).build())
        traceMetadata(station, fresh, why)
        dumpMetadata(station, fresh)
    }

    /**
     * The set as it goes to the session, on one line.
     *
     * Every push is written down, including one that changes nothing - unlike
     * [dumpMetadata], which drops repeats to keep the preview window readable.
     * Here a repeat is a fact: it says the car was handed the same thing again,
     * which is exactly the sort of thing worth being able to count afterwards.
     */
    private fun traceMetadata(station: Station, m: MediaMetadata, why: String) {
        Trace.write("wyslano") {
            put("powod", why)
            put("stacjaNazwa", station.name)
            put("poStacji", sinceStationMs())
            put("subtitle", m.subtitle?.toString())
            put("description", m.description?.toString())
            put("displayTitle", m.displayTitle?.toString())
            put("title", m.title?.toString())
            put("artist", m.artist?.toString())
            put("albumTitle", m.albumTitle?.toString())
            put("station", m.station?.toString())
            put("genre", m.genre?.toString())
            put(
                "grafika",
                when {
                    m.artworkData != null -> "bajty:${m.artworkData?.size}"
                    m.artworkUri != null -> m.artworkUri.toString()
                    else -> null
                }
            )
        }
    }

    /**
     * Prints out the full set of fields sent to the session. Used by the Windows
     * preview window (tools/meta-watch.ps1), which reads it via `adb logcat -s MetaDump`.
     */
    private fun dumpMetadata(station: Station, m: MediaMetadata) {
        // A dump identical to the previous one carries no information, and it
        // can repeat every minute (the clock tick) or on every button refresh.
        // The preview window would otherwise flood with duplicates.
        val fingerprint = listOf(
            m.title, m.artist, m.albumTitle, m.displayTitle, m.subtitle,
            m.description, m.station, m.genre, m.artworkUri
        ).joinToString("|")
        if (fingerprint == lastDump) return
        lastDump = fingerprint

        Log.i(TAG_DUMP, "--- ${station.name} @ ${MetadataFactory.clockText()} ---")
        listOf(
            "title" to m.title,
            "artist" to m.artist,
            "albumTitle" to m.albumTitle,
            "albumArtist" to m.albumArtist,
            "displayTitle" to m.displayTitle,
            "subtitle" to m.subtitle,
            "description" to m.description,
            "station" to m.station,
            "genre" to m.genre,
            "composer" to m.composer,
            "writer" to m.writer,
            "conductor" to m.conductor,
            "compilation" to m.compilation
        ).forEach { (name, value) ->
            if (value != null) Log.i(TAG_DUMP, "$name = $value")
        }
        m.trackNumber?.let { Log.i(TAG_DUMP, "trackNumber = $it") }
        m.recordingYear?.let { Log.i(TAG_DUMP, "recordingYear = $it") }
        Log.i(TAG_DUMP, "artworkUri = ${m.artworkUri}")
        m.artworkData?.let { Log.i(TAG_DUMP, "artworkData = ${it.size} B (grafika w metadanych)") }
    }

    // --- browse tree for Android Auto -------------------------------------------

    /**
     * A button in the Android Auto player template. It's the only way to give
     * the user any custom control at all - AA doesn't allow drawing your own UI,
     * but it renders custom actions in its own layout (ReplaIO does the same
     * with its star and heart).
     *
     * Why this one specifically: it lets you toggle diagnostic mode right from
     * the car screen, without reaching for the phone while driving.
     */
    /**
     * Announces a change in browse node contents.
     *
     * The version without a specified recipient only reaches controllers
     * registered as subscribers on the Media3 side. Android Auto connects via
     * the old API and its subscriptions are tracked elsewhere, so with the
     * favourites list open it received nothing and the list stayed stale. So we
     * notify each connected controller by address instead.
     */
    private fun notifyBrowseNodesChanged(vararg nodes: String) {
        val controllers = session.connectedControllers
        // Media3 matches notifications to the parameters the controller
        // registered with. Sending with null missed the target - Android Auto
        // subscribes with content style parameters, so we pass the same ones.
        val params = contentStyleParams()
        for (node in nodes) {
            // The actual item count, not Int.MAX_VALUE - this way the HDU gets
            // a meaningful sense of how much the list changed.
            val count = childrenOf(node).size
            session.notifyChildrenChanged(node, count, params)
            session.notifyChildrenChanged(node, count, null)
            for (controller in controllers) {
                session.notifyChildrenChanged(controller, node, count, params)
                session.notifyChildrenChanged(controller, node, count, null)
            }
        }
        Log.i(TAG, "powiadomiono ${controllers.size} kontroler(ow) o ${nodes.joinToString()}")
    }

    /** Browse node content - shared between responses and notifications. */
    private fun childrenOf(parentId: String): List<Station> = when {
        parentId == NODE_FAVOURITES -> repo.favourites()
        parentId == NODE_ALL -> repo.all()
        parentId == NODE_RECENT -> repo.recent()
        parentId.startsWith(NODE_GENRE_PREFIX) ->
            repo.byGenre(parentId.removePrefix(NODE_GENRE_PREFIX))
        else -> emptyList()
    }

    /**
     * Buttons in the Android Auto player template - the same set also shows up
     * in the phone's playback notification, since both read the session's
     * custom layout.
     *
     * The diagnostic toggle only appears once diagnostic mode is actually on -
     * it's a developer convenience for turning it back off without leaving the
     * car screen or the notification, not the way to turn it on in the first
     * place (that's the switch in Options). Showing it all the time, for
     * everyone, made no sense for a mode that's off by default.
     */
    private fun customLayout(): ImmutableList<CommandButton> =
        if (prefs.diagnosticMode) {
            ImmutableList.of(favouriteButton(), diagnosticButton())
        } else {
            ImmutableList.of(favouriteButton())
        }

    /**
     * The favourites star. Without it, a station playing in the car couldn't
     * be added to favourites at all - and it's exactly in the car that a person
     * decides they want it within reach.
     */
    private fun favouriteButton(): CommandButton {
        val id = PlaybackStatusBus.stationId.value
        val isFav = id != null && id in prefs.favourites
        Log.i(TAG, "buduje gwiazdke dla stacji '$id': ulubiona=$isFav")
        // The icon goes through TWO channels at once, and that's not redundancy.
        //
        // The proper channel is extras: Android's documentation for cars says
        // explicitly that if an icon matches one of the CommandButton.ICON_
        // constants, its value should be put under the
        // EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT key, because that "overrides the
        // icon resource passed to CustomAction.Builder and lets the system draw
        // the action consistently with the others". In other words, the head
        // unit then draws its OWN star and doesn't look at our resources at all.
        //
        // The fallback channel is the resource id - for systems that don't know
        // that key. And only there does it matter that the vector has no
        // android:tint: a reference to @color/... would have to be resolved by
        // the head unit in our package while inflating in its own process, and
        // that's exactly where it used to fail.
        //
        // What NOT to do: pass the semantic constant to the
        // CommandButton.Builder(ICON_STAR_FILLED) constructor and assume that's
        // enough. Media3 then translates it for the old API into the resource id
        // of its own bundled resource, and the Desktop Head Unit rendered from
        // that a music note and the text "1.8X".
        val icon = if (isFav) {
            CommandButton.ICON_STAR_FILLED
        } else {
            CommandButton.ICON_STAR_UNFILLED
        }
        @Suppress("DEPRECATION")
        return CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_FAV)
            .setDisplayName(
                getString(
                    if (isFav) net.mspanc.twinsenradio.R.string.fav_remove
                    else net.mspanc.twinsenradio.R.string.fav_add
                )
            )
            .setIconResId(
                if (isFav) net.mspanc.twinsenradio.R.drawable.ic_star_filled_aa
                else net.mspanc.twinsenradio.R.drawable.ic_star_outline_aa
            )
            .setExtras(
                Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT, icon)
                }
            )
            .build()
    }

    /** Same as the star - our own vector, resource id hardcoded. */
    private fun diagnosticButton(): CommandButton =
        @Suppress("DEPRECATION")
        CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_DIAG)
            .setDisplayName(
                if (prefs.diagnosticMode) "Diagnostyka: WL" else "Diagnostyka: WYL"
            )
            .setIconResId(net.mspanc.twinsenradio.R.drawable.ic_diag_aa)
            .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            ConnectionLog.connected(
                this@RadioService,
                controller.packageName,
                controller.uid,
                controller.controllerVersion,
                controller.interfaceVersion,
                controller.connectionHints
            )
            // Which events happened with the car attached and which on the
            // phone alone is the first question any of this will be asked, and
            // the connecting package is the only thing that answers it -
            // com.google.android.projection.gearhead is the head unit.
            Trace.write("klient") {
                put("pakiet", controller.packageName)
                put("wersja", controller.controllerVersion)
            }
            // A controller attaching is the head unit starting to read us again,
            // and after a stretch of Doze what it would read may be minutes out
            // of date. This is the most important of the stale checks: the phone
            // slept through the deadline precisely because nobody was looking,
            // and now somebody is.
            //
            // Posted, not launched: the scope runs on Main.immediate, which on
            // this thread would run the check inside onConnect and hand the
            // player a new media item before we have returned the controller its
            // connection result. The post puts it after that.
            clockHandler.post { checkStale("klient") }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(CMD_TOGGLE_DIAG)
                .add(CMD_TOGGLE_FAV)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                // A freshly connected controller must get the current star state
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Trace.write("klient-koniec") { put("pakiet", controller.packageName) }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_TOGGLE_DIAG.customAction -> {
                    prefs.diagnosticMode = !prefs.diagnosticMode
                    Log.i(TAG, "przelaczono tryb diagnostyczny na ${prefs.diagnosticMode}")
                    // prefsListener will take care of refreshing the metadata
                    session.setCustomLayout(customLayout())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // An action from the list item's menu - the head unit attaches the item id
                ACTION_FAVOURITE, ACTION_UNFAVOURITE -> {
                    val mediaId = args.getString(KEY_ACTION_MEDIA_ITEM_ID)
                    val station = mediaId?.let { repo.byMediaId(it) }
                    val result = Bundle()
                    if (station != null) {
                        val added = prefs.toggleFavourite(station.id)
                        Log.i(TAG, "z listy: ${station.name} ${if (added) "dodana do" else "usunieta z"} ulubionych")
                        // Tell the head unit to refresh this item so the icon switches
                        result.putString(KEY_ACTION_RESULT_REFRESH_ITEM, mediaId)
                        result.putString(
                            KEY_ACTION_RESULT_MESSAGE,
                            getString(
                                if (added) net.mspanc.twinsenradio.R.string.fav_added_toast
                                else net.mspanc.twinsenradio.R.string.fav_removed_toast,
                                station.name
                            )
                        )
                        this@RadioService.session
                            .notifyChildrenChanged(NODE_FAVOURITES, Int.MAX_VALUE, null)
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, result)
                    )
                }
                CMD_TOGGLE_FAV.customAction -> {
                    val id = PlaybackStatusBus.stationId.value
                    if (id != null) {
                        val added = prefs.toggleFavourite(id)
                        Log.i(TAG, "stacja $id ${if (added) "dodana do" else "usunieta z"} ulubionych")
                        session.setCustomLayout(customLayout())
                        // notifyChildrenChanged only exists on MediaLibrarySession,
                        // and here `session` has the wider MediaSession type
                        this@RadioService.session
                            .notifyChildrenChanged(NODE_FAVOURITES, Int.MAX_VALUE, null)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            ConnectionLog.libraryRoot(this@RadioService, browser.packageName, params?.extras)
            Log.i(
                TAG,
                "onGetLibraryRoot od ${browser.packageName} (uid=${browser.uid}), " +
                    "isRecent=${params?.isRecent}, isSuggested=${params?.isSuggested}, " +
                    "ostatnio sluchane=${repo.recent().size}"
            )
            // The system (and the head unit) asks separately for the "resume"
            // root - in that case it expects a short recently-played list, not the whole tree.
            if (params?.isRecent == true) {
                if (repo.recent().isEmpty()) {
                    return Futures.immediateFuture(
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                    )
                }
                val recentRoot = MediaItem.Builder()
                    .setMediaId(NODE_RECENT)
                    .setMediaMetadata(
                        metadata.forFolder(
                            getString(net.mspanc.twinsenradio.R.string.node_recent),
                            MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                        )
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(recentRoot, params))
            }

            val root = MediaItem.Builder()
                .setMediaId(NODE_ROOT)
                .setMediaMetadata(
                    metadata.forFolder(
                        getString(net.mspanc.twinsenradio.R.string.root_title),
                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    )
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, contentStyleParams()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children: List<MediaItem> = when {
                parentId == NODE_ROOT -> listOf(
                    folder(NODE_FAVOURITES, net.mspanc.twinsenradio.R.string.node_favourites),
                    folder(NODE_ALL, net.mspanc.twinsenradio.R.string.node_all),
                    folder(NODE_RECENT, net.mspanc.twinsenradio.R.string.node_recent),
                    folder(NODE_GENRES, net.mspanc.twinsenradio.R.string.node_genres)
                )

                parentId == NODE_FAVOURITES -> repo.favourites().map(::browseItem)
                parentId == NODE_ALL -> repo.all().map(::browseItem)
                parentId == NODE_RECENT -> repo.recent().map(::browseItem)

                parentId == NODE_GENRES -> repo.genres().map { genre ->
                    MediaItem.Builder()
                        .setMediaId("$NODE_GENRE_PREFIX$genre")
                        .setMediaMetadata(
                            metadata.forFolder(genre, MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                        )
                        .build()
                }

                parentId.startsWith(NODE_GENRE_PREFIX) ->
                    repo.byGenre(parentId.removePrefix(NODE_GENRE_PREFIX)).map(::browseItem)

                else -> emptyList()
            }
            Log.i(TAG, "onGetChildren($parentId) od ${browser.packageName} -> ${children.size} pozycji")
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), contentStyleParams())
            )
        }

        /**
         * Resuming playback after connecting to the car or from the system panel.
         *
         * The rule: **it must play exactly the same station as before** -
         * regardless of whether it was playing from the phone or via Android
         * Auto. There's one shared state (one service, one session), and the
         * history in [Prefs.recent] is updated on every item change, so both
         * paths record it the same way.
         *
         * Order: the station currently loaded in the player (if something was
         * already playing, it must not be swapped out), then the most recently
         * listened one from history, then the first favourite, and finally the
         * first from the list.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val current = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it) }
            val station = current
                ?: repo.recent().firstOrNull()
                ?: repo.favourites().firstOrNull()
                ?: repo.all().firstOrNull()

            if (station == null) {
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("brak stacji do wznowienia")
                )
            }

            val skad = if (current != null) "juz zaladowana" else "z historii"
            Log.i(
                TAG,
                "wznawiam po podlaczeniu: ${station.name} ($skad, zlecil ${controller.packageName})"
            )
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(playableItem(station)),
                    0,
                    // Live radio - start position doesn't matter
                    C.TIME_UNSET
                )
            )
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.i(TAG, "SUBSKRYPCJA $parentId od ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String
        ): ListenableFuture<LibraryResult<Void>> {
            Log.i(TAG, "KONIEC SUBSKRYPCJI $parentId od ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = repo.byMediaId(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(browseItem(station), null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val hits = repo.search(query).size
            session.notifySearchResultChanged(browser, query, hits, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val hits = repo.search(query).map(::browseItem)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(hits), contentStyleParams())
            )
        }

        /**
         * Android Auto sends items with just a mediaId. Here we add the stream
         * address and full metadata - without this there'd be nothing to play.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                repo.byMediaId(item.mediaId)?.let(::playableItem)
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Voice command "play X" arrives here via search. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.mapNotNull { item ->
                repo.byMediaId(item.mediaId)
                    ?: item.requestMetadata.searchQuery?.let { repo.search(it).firstOrNull() }
            }.map(::playableItem).toMutableList()

            val safeIndex = if (startIndex in resolved.indices) startIndex else 0
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, safeIndex, startPositionMs)
            )
        }
    }

    private fun folder(id: String, titleRes: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                metadata.forFolder(getString(titleRes), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
            )
            .build()

    /**
     * A list item with a "favourite" action available directly from the context
     * menu in Android Auto. Without this, a station could only be added to
     * favourites while it was already playing - but marking it while browsing
     * the list is the natural thing to do.
     */
    private fun browseItem(station: Station): MediaItem {
        val isFav = station.id in prefs.favourites
        val extras = Bundle().apply {
            putStringArrayList(
                KEY_ACTION_ID_LIST,
                arrayListOf(if (isFav) ACTION_UNFAVOURITE else ACTION_FAVOURITE)
            )
        }
        return MediaItem.Builder()
            .setMediaId(station.mediaId)
            .setMediaMetadata(
                metadata.forBrowseItem(station).buildUpon().setExtras(extras).build()
            )
            .build()
    }

    /** Definitions of actions the head unit shows on list items. */
    private fun browseActionsRootList(): ArrayList<Bundle> {
        // Here the icon can only be given as a URI, so instead of
        // android.resource:// with a resource id (unstable across versions
        // and cached by head units), we use a stable address from LogoProvider.
        fun action(id: String, labelRes: Int, iconName: String, iconRes: Int) = Bundle().apply {
            putString(KEY_ACTION_ID, id)
            putString(KEY_ACTION_LABEL, getString(labelRes))
            putString(
                KEY_ACTION_ICON_URI,
                LogoProvider.iconUri(this@RadioService, iconName, iconRes).toString()
            )
        }
        return arrayListOf(
            action(
                ACTION_FAVOURITE,
                net.mspanc.twinsenradio.R.string.fav_add,
                "star_outline",
                net.mspanc.twinsenradio.R.drawable.ic_star_outline_aa
            ),
            action(
                ACTION_UNFAVOURITE,
                net.mspanc.twinsenradio.R.string.fav_remove,
                "star_filled",
                net.mspanc.twinsenradio.R.drawable.ic_star_filled_aa
            )
        )
    }

    private fun playableItem(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.mediaId)
            .setUri(station.stream)
            .setMediaMetadata(metadata.forPlayback(station, null))
            .build()

    /**
     * The presentation scheme chosen in Options. These are the four layouts
     * Android Auto actually offers media apps.
     */
    private fun contentStyleParams(): MediaLibraryService.LibraryParams {
        val extras = Bundle().apply {
            putBoolean(ContentStyle.EXTRA_SUPPORTED, true)
            putInt(ContentStyle.EXTRA_BROWSABLE_HINT, prefs.browsableStyle)
            putInt(ContentStyle.EXTRA_PLAYABLE_HINT, prefs.playableStyle)
            putParcelableArrayList(KEY_ACTION_ROOT_LIST, browseActionsRootList())
        }
        return MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()
    }

    companion object {
        private const val TAG = "RadioService"
        private const val TAG_ICY = "IcyMeta"
        private const val TAG_DUMP = "MetaDump"

        private val CMD_TOGGLE_DIAG =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_DIAG", Bundle.EMPTY)
        private val CMD_TOGGLE_FAV =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_FAV", Bundle.EMPTY)

        // List item actions in Android Auto. The keys come from
        // androidx.media.utils.MediaConstants - hardcoded here because Media3
        // doesn't expose them in its own MediaConstants.
        private const val ACTION_FAVOURITE = "net.mspanc.twinsenradio.FAVOURITE"
        private const val ACTION_UNFAVOURITE = "net.mspanc.twinsenradio.UNFAVOURITE"

        private const val KEY_ACTION_ROOT_LIST =
            "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ROOT_LIST"
        private const val KEY_ACTION_ID_LIST =
            "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ID_LIST"
        private const val KEY_ACTION_ID =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ID"
        private const val KEY_ACTION_LABEL =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_LABEL"
        private const val KEY_ACTION_ICON_URI =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ICON_URI"
        private const val KEY_ACTION_MEDIA_ITEM_ID =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID"
        private const val KEY_ACTION_RESULT_REFRESH_ITEM =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM"
        private const val KEY_ACTION_RESULT_MESSAGE =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE"

        /**
         * How long we wait after a control marker before deciding the station
         * has nothing more to tell us. Insertions in RMF follow one another a
         * few seconds apart, so 15s comfortably covers them.
         */
        private const val MARKER_GRACE_MS = 15_000L

        /**
         * How long a new station's description waits before it goes on screen.
         *
         * Long enough for the head unit to draw the "no track" state as a state
         * of its own, short enough that nobody reads it as the station being
         * slow. Measured from the station change, not from the metadata - a
         * station that answers instantly and one that takes a second both land
         * at the same moment.
         */
        private const val STATION_SETTLE_MS = 1_500L

        /** Assumed track length when the catalog doesn't know it. */
        private const val FALLBACK_TRACK_MS = 5 * 60_000L

        /**
         * Shortest catalog length we're willing to believe.
         *
         * Below this the number is evidence that the lookup matched a different
         * release, not that a short song is playing - radio simply doesn't play
         * anything this brief. "Stayin' Alive" matched a 1:33 soundtrack cut,
         * and the description came off the screen two minutes into a song that
         * had another two and a half to run.
         */
        private const val MIN_TRACK_MS = 150_000L

        /**
         * Margin added to the track length before we consider the description stale.
         *
         * Stations shorten tracks, talk over endings, and play radio edits
         * shorter than the catalog version, so waiting long past the nominal
         * time gains nothing. Half a minute covers the natural drift while not
         * leaving a stale title on screen for too long.
         */
        private const val STALE_GRACE_MS = 30_000L

        const val NODE_ROOT = "/"
        const val NODE_FAVOURITES = "/fav"
        const val NODE_ALL = "/all"
        const val NODE_RECENT = "/recent"
        const val NODE_GENRES = "/genres"
        const val NODE_GENRE_PREFIX = "/genre/"

        private val BROWSE_NODES = listOf(NODE_FAVOURITES, NODE_ALL, NODE_RECENT, NODE_GENRES)
    }
}
