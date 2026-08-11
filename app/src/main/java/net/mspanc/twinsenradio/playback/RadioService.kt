package net.mspanc.twinsenradio.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Usluga odtwarzania i jednoczesnie zrodlo drzewa przegladania dla Android Auto.
 *
 * MediaLibraryService z Media3 wystawia rownoczesnie nowe API sesji i stary
 * MediaBrowserService, ktorego Android Auto nadal uzywa - dlatego w manifescie
 * sa dwa filtry intencji.
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

    /** Okladka doszukana dla biezacego utworu; null = pokazujemy logo stacji. */
    @Volatile
    private var coverArtUrl: String? = null

    /** Co katalog wie o biezacym utworze - wydawnictwo, rok, okladka. */
    @Volatile
    private var trackInfo: CoverArtLookup.TrackInfo? = null

    /** Rosnie przy kazdej zmianie utworu - odsiewa spoznione wyniki wyszukiwania. */
    private var coverGeneration = 0
    private var coverRevertJob: Job? = null

    /**
     * Utwor, do ktorego nalezy okladka trzymana w [coverArtUrl]. Sluzy do
     * wykrycia momentu, w ktorym okladka przestaje pasowac do tego, co gra.
     */
    private var coverTrackKey: String? = null

    /** Format z dekodera i przeplywnosc z naglowka ICY - razem daja opis jakosci. */
    @Volatile
    private var audioFormat: androidx.media3.common.Format? = null

    @Volatile
    private var icyBitrateKbps = 0

    /** Czeka po znaczniku sterujacym na to, co rozglosnia wstawi dalej. */
    private var pendingMarkerJob: Job? = null

    /** Pilnuje, czy opis utworu nie zwietrzal, gdy stacja nic nie oglosila. */
    private var staleJob: Job? = null

    /** Odcisk ostatniego zrzutu metadanych - odsiewa powtorki w logu. */
    private var lastDump: String? = null

    /** Ostatni slogan stacji - pokazujemy go, gdy nie wiemy, co akurat leci. */
    @Volatile
    private var lastSlogan: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_STYLE_BROWSABLE, Prefs.KEY_STYLE_PLAYABLE, Prefs.KEY_M3U -> {
                session.notifyChildrenChanged(NODE_ROOT, Int.MAX_VALUE, null)
                BROWSE_NODES.forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
            }
            Prefs.KEY_DIAG -> {
                refreshCurrentMetadata(force = true)
                // Odciecie ICY dzieje sie przy tworzeniu zrodla danych, wiec zeby
                // przelacznik zadzialal od razu, trzeba przygotowac strumien na nowo.
                if (player.playWhenReady && player.currentMediaItem != null) {
                    player.prepare()
                }
            }
            Prefs.KEY_DIAG_API, Prefs.KEY_ARTWORK,
            Prefs.KEY_LINE_TOP, Prefs.KEY_LINE_MIDDLE, Prefs.KEY_LINE_BOTTOM,
            Prefs.KEY_CLOCK_FACE, Prefs.KEY_CLOCK_ALWAYS, Prefs.KEY_CLOCK_BG,
            Prefs.KEY_CLOCK_FG, Prefs.KEY_ENRICH_ALBUM -> refreshCurrentMetadata(force = true)
            Prefs.KEY_BUFFER -> Log.i(TAG, "Zmieniono bufor - zadziala po restarcie odtwarzania")
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = StationRepository.get(this)
        metadata = MetadataFactory(this, prefs)

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
            PlaybackStatusBus.setStatus(
                when (status) {
                    ReconnectController.Status.RECONNECTING -> PlaybackStatusBus.Status.RECONNECTING
                    ReconnectController.Status.WAITING_FOR_NETWORK -> PlaybackStatusBus.Status.WAITING_FOR_NETWORK
                    ReconnectController.Status.OK -> PlaybackStatusBus.Status.PLAYING
                }
            )
        }
        reconnect.start()
        prefs.registerListener(prefsListener)
        scheduleClockTick()

        scope.launch { repo.refreshUserLists() }

        // Zmiana ulubionych - skadkolwiek przyszla - musi od razu przelozyc sie na
        // gwiazdke przy odtwarzaczu i na listy, ktore ja pokazuja.
        scope.launch {
            Prefs.favouritesFlow.collect { favourites ->
                if (!this@RadioService::session.isInitialized) return@collect
                Log.i(TAG, "ulubione zmienione (${favourites.size}) - odswiezam przyciski i wezly")
                session.setCustomLayout(customLayout())
                notifyBrowseNodesChanged(NODE_FAVOURITES, NODE_ALL, NODE_RECENT)
            }
        }

        // Stacja dodana z katalogu ma pojawic sie w aucie bez restartu aplikacji.
        // Dochodzi tez nowy gatunek, wiec odswiezamy takze ich liste.
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
        super.onDestroy()
    }

    // --- budowa odtwarzacza ---------------------------------------------------

    private fun buildPlayer(): ExoPlayer {
        val profile = BufferProfile.at(prefs.bufferProfile)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.forPlaybackMs,
                profile.afterRebufferMs
            )
            // Dla strumienia na zywo interesuje nas czas, nie rozmiar bufora.
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

    // --- reakcje na zdarzenia odtwarzacza -------------------------------------

    private inner class PlayerEvents : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastRawIcyTitle = null
            lastSlogan = null
            // Zmiana stacji zaczyna wszystko od zera. Bez tego okladka utworu z
            // poprzedniej stacji zostawala w polu `coverArtUrl` i wracala na ekran
            // przy najblizszym odswiezeniu metadanych - po przejsciu z RMF na RNS
            // przez chwile widac bylo okladke z RMF zamiast logo Nowego Swiata.
            resetCoverArt()
            audioFormat = null
            icyBitrateKbps = 0
            PlaybackStatusBus.setQuality(null)
            val id = mediaItem?.mediaId?.let { Station.idFromMediaId(it) }
            PlaybackStatusBus.setStation(id)
            id?.let { prefs.pushRecent(it) }

            // Gwiazdka dotyczy konkretnej stacji, wiec przy zmianie trzeba zbudowac
            // uklad przyciskow na nowo. Bez tego po wejsciu w stacje pokazywala stan
            // poprzedniej i pierwsze klikniecie wygladalo, jakby nic nie robilo.
            if (this@RadioService::session.isInitialized) {
                session.setCustomLayout(customLayout())
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
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
     * Nowy tytul z Icecasta. Aktualizujemy metadane pozycji, a nie sam strumien -
     * `replaceMediaItem` z ta sama konfiguracja zrodla nie przerywa odtwarzania.
     */
    /** Naglowki icy-* z odpowiedzi HTTP - stale dla calego strumienia. */
    private fun logIcyHeaders(h: IcyHeaders) {
        Log.i(TAG_ICY, "== naglowki ICY strumienia ==")
        h.name?.let { Log.i(TAG_ICY, "icy-name = $it") }
        h.genre?.let { Log.i(TAG_ICY, "icy-genre = $it") }
        h.url?.let { Log.i(TAG_ICY, "icy-url = $it") }
        if (h.bitrate > 0) {
            Log.i(TAG_ICY, "icy-br = ${h.bitrate}")
            // Zapas na wypadek, gdyby dekoder nie podal przeplywnosci - przy AAC
            // w ADTS jest to regula, bo w samym strumieniu nie ma jej wcale.
            icyBitrateKbps = h.bitrate
            publishQuality()
        }
        if (h.metadataInterval > 0) Log.i(TAG_ICY, "icy-metaint = ${h.metadataInterval}")
        Log.i(TAG_ICY, "icy-pub = ${h.isPublic}")
    }

    /**
     * Blok metadanych wstrzykiwany w strumien. ExoPlayer parsuje z niego tylko
     * StreamTitle i StreamUrl, ale rozglosnie wpychaja tam wiecej par klucz=wartosc
     * (RMF np. oznacza reklamy przez adw_ad i durationMilliseconds). Dlatego obok
     * pol rozpoznanych logujemy tez cala surowa zawartosc.
     */
    private fun logIcyInfo(info: IcyInfo): String {
        val raw = runCatching { String(info.rawMetadata, Charsets.UTF_8).trim(Char(0), ' ') }
            .getOrDefault("")
        Log.i(TAG_ICY, "surowy blok: $raw")
        info.url?.let { Log.i(TAG_ICY, "StreamUrl = $it") }

        // rozbij wszystkie pary klucz='wartosc', zeby bylo widac pola nietypowe
        Regex("""(\w+)='([^']*)'""").findAll(raw).forEach { m ->
            Log.i(TAG_ICY, "  ${m.groupValues[1]} = ${m.groupValues[2]}")
        }
        return raw
    }

    private fun handleIcyTitle(rawTitle: String?, rawBlock: String? = null) {
        val title = rawTitle?.trim().orEmpty()
        // Sam tytul nie wystarczy do rozpoznania powtorki: reklamy maja pusty
        // tytul, a rozne wstawki roznia sie dopiero polami adId.
        val fingerprint = title + "|" + rawBlock.orEmpty()
        if (fingerprint == lastRawIcyTitle) return
        lastRawIcyTitle = fingerprint

        // Odstep miedzy blokami ICY jest tym, czego nie wiemy o rozglosniach:
        // czy metadane leca raz na utwor, czy okresowo. Logujemy, zeby dalo sie
        // to policzyc z zewnatrz.
        val nowMs = System.currentTimeMillis()
        val sinceLast = if (lastIcyAtMs == 0L) -1 else (nowMs - lastIcyAtMs) / 1000
        lastIcyAtMs = nowMs
        Log.i(TAG_ICY, "po ${sinceLast}s | StreamTitle='$title'")

        val stationName = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it)?.name }
        val now = NowPlaying.parse(title, stationName, rawBlock)
        Log.i(
            TAG_ICY,
            "  -> artist='${now?.artist}' title='${now?.songTitle}' " +
                "slogan='${now?.slogan}' reklama=${now?.isAd} " +
                "znacznik=${now?.isControlMarker} utwor=${now?.isRealSong}"
        )
        publish(now)
    }

    /**
     * Decyduje, czy zdarzenie z ICY ma od razu zmienic to, co widac na ekranie.
     *
     * Znacznik sterujacy (np. STOP_AD_BREAK) sam z siebie nie znaczy, ze wraca
     * muzyka - RMF potrafi zaraz po nim wstawic kolejna reklame. Gdybysmy od razu
     * wracali do widoku stacji, ekran mrugalby miedzy "Reklama" a nazwa stacji
     * przy kazdej wstawce. Dlatego znacznik jedynie uzbraja timer: jesli w ciagu
     * [MARKER_GRACE_MS] przyjdzie cokolwiek konkretnego - utwor albo nastepna
     * reklama - to ono wygrywa, a jesli nie przyjdzie nic, dopiero wtedy
     * zostawiamy sama stacje.
     */
    private fun publish(now: NowPlaying?) {
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

        // Dane katalogowe dotycza POPRZEDNIEGO utworu, wiec zerujemy je, zanim
        // cokolwiek narysujemy. Inaczej przez chwile widac nowego wykonawce
        // sklejonego ze stara plyta - "Taylor Swift - Black Gold: The Best of
        // Soul Asylum [1992]". Okladke zdejmuje updateCoverArt, tez natychmiast.
        trackInfo = null
        PlaybackStatusBus.setTrackInfo(null)

        PlaybackStatusBus.setNowPlaying(now)

        // Kolejnosc ma znaczenie: najpierw zdejmujemy okladke, dopiero potem
        // wysylamy metadane. Odwrotnie do auta trafial nowy opis ze stara
        // grafika - po piosence wchodzilo studio i przy "Pion i poziom!"
        // wisiala okladka plyty sprzed chwili.
        updateCoverArt(now)
        refreshCurrentMetadata(force = true, now = now)
        scheduleStaleCheck(now)
    }

    /**
     * Sprzataniecie po utworze, ktorego koniec nie zostal ogloszony.
     *
     * RMF potrafi wejsc w blok reklamowy bez zadnego zdarzenia ICY - wtedy na
     * ekranie zostawal tytul sprzed kilku minut, bo nie mielismy sygnalu, ze
     * cokolwiek sie zmienilo. Zamiast zgadywac stalym limitem, korzystamy z
     * dlugosci utworu z katalogu: skoro piosenka trwa 3:20, to po 4:20 na pewno
     * juz nie leci. Gdy dlugosci nie znamy, przyjmujemy [FALLBACK_TRACK_MS].
     */
    private fun scheduleStaleCheck(now: NowPlaying?) {
        staleJob?.cancel()
        if (now?.isRealSong != true) return

        val known = trackInfo?.durationMs ?: 0
        val timeout = (if (known > 0) known else FALLBACK_TRACK_MS) + STALE_GRACE_MS
        staleJob = scope.launch {
            delay(timeout)
            if (PlaybackStatusBus.nowPlaying.value?.raw != now.raw) return@launch
            Log.i(
                TAG_ICY,
                "utwor '${now.raw}' powinien byc juz po ${timeout / 1000}s - " +
                    "stacja nic nie przyslala, czyszcze opis"
            )
            // Jesli stacja kiedykolwiek podala swoj slogan, lepiej pokazac jego
            // niz pusta linie - RNS ma "Pion i poziom!", RMF "FAKTY" przy serwisie.
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
        }
    }

    /**
     * Zdejmuje wszystko, co wiedzielismy o poprzednim utworze. Wolane przy
     * zmianie stacji, gdzie zaden slad po poprzedniej nie ma prawa zostac.
     */
    private fun resetCoverArt() {
        coverGeneration++
        coverRevertJob?.cancel()
        staleJob?.cancel()
        coverTrackKey = null
        coverArtUrl = null
        trackInfo = null
        PlaybackStatusBus.setCoverArt(null)
        PlaybackStatusBus.setTrackInfo(null)
    }

    /**
     * Podmienia okladke przy zmianie utworu.
     *
     * Zasada jest jedna: **okladka nigdy nie przezywa utworu, do ktorego nalezy**.
     * Wczesniej bylo odwrotnie - stara grafika zostawala az do znalezienia nowej,
     * zeby miedzy utworami nie mrugalo logo stacji. W praktyce dawalo to gorszy
     * efekt niz mrugniecie: przez ulamek sekundy (a po wygasnieciu opisu nawet
     * przez [COVER_GRACE_MS]) obok nazwiska nowego wykonawcy wisiala plyta
     * poprzedniego, co wyglada po prostu na blad.
     *
     * Dlatego przy kazdej zmianie utworu wracamy natychmiast do logo stacji, a
     * okladke pokazujemy dopiero wtedy, gdy katalog naprawde ja znajdzie. Gdy
     * stacja przysyla metadane od razu przy podlaczeniu, wyszukiwanie trwa zwykle
     * ~200 ms i logo praktycznie nie zdazy sie pojawic.
     */
    private fun updateCoverArt(now: NowPlaying?) {
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
            refreshCurrentMetadata(force = true, now = PlaybackStatusBus.nowPlaying.value)
            // Znamy juz dlugosc utworu - przelicz moment, w ktorym opis zwietrzeje
            scheduleStaleCheck(PlaybackStatusBus.nowPlaying.value)
        }

        val key = if (now?.isRealSong == true) "${now.artist}|${now.songTitle}" else null
        if (key != coverTrackKey) {
            coverTrackKey = key
            if (coverArtUrl != null) {
                coverArtUrl = null
                trackInfo = null
                PlaybackStatusBus.setCoverArt(null)
                PlaybackStatusBus.setTrackInfo(null)
            }
        }

        // Reklama albo wlasny slogan stacji - nie ma czego szukac w katalogu.
        if (key == null) return

        scope.launch {
            val info = CoverArtLookup.find(now!!.artist, now.songTitle)
            if (generation != coverGeneration) return@launch
            applyIfCurrent(info)
        }
    }

    /** Sklada opis jakosci z formatu dekodera i z naglowka icy-br. */
    private fun publishQuality() {
        val format = audioFormat
        if (format == null) {
            PlaybackStatusBus.setQuality(null)
            return
        }
        PlaybackStatusBus.setQuality(
            StreamQuality.of(format, icyBitrateKbps).label().ifBlank { null }
        )
    }

    /**
     * Odswieza metadane rowno na granicy minuty, a nie co 60 s od startu - inaczej
     * zegar w podtytule dryfowalby wzgledem zegara w aucie.
     */
    private fun scheduleClockTick() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        val delayToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
        val runnable = Runnable {
            // Zegar odswiezamy tylko wtedy, gdy jest gdzie go pokazac - w trybie
            // diagnostycznym zawsze, poza nim jedynie w ukladach z zegarem.
            val needed = prefs.diagnosticMode || prefs.presentation.needsClock
            if (needed) refreshCurrentMetadata(force = true)
            scheduleClockTick()
        }
        clockTick = runnable
        clockHandler.postDelayed(runnable, delayToNextMinute + 100)
    }

    private fun refreshCurrentMetadata(force: Boolean, now: NowPlaying? = PlaybackStatusBus.nowPlaying.value) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val station = repo.byMediaId(item.mediaId) ?: return
        if (!force && prefs.diagnosticMode) return
        val fresh = metadata.forPlayback(station, now, coverArtUrl, trackInfo)
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(fresh).build())
        dumpMetadata(station, fresh)
    }

    /**
     * Wypisuje komplet pol wyslanych do sesji. Sluzy okienku podgladu na Windows
     * (tools/meta-watch.ps1), ktore czyta to przez `adb logcat -s MetaDump`.
     */
    private fun dumpMetadata(station: Station, m: MediaMetadata) {
        // Zrzut identyczny z poprzednim nie niesie zadnej informacji, a potrafi
        // sie powtarzac co minute (tykniecie zegara) albo przy kazdym
        // odswiezeniu przyciskow. Okno podgladu zalewalo sie wtedy kopiami.
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

    // --- drzewo przegladania dla Android Auto ---------------------------------

    /**
     * Przycisk w szablonie odtwarzacza Android Auto. To jedyny sposob, zeby dac
     * uzytkownikowi jakiekolwiek wlasne sterowanie - AA nie pozwala rysowac
     * wlasnego UI, ale custom actions renderuje w swoim layoucie (to samo robi
     * ReplaIO ze swoja gwiazdka i serduszkiem).
     *
     * Po co akurat ten: pozwala przelaczyc tryb diagnostyczny wprost z ekranu
     * auta, bez siegania po telefon w trakcie jazdy.
     */
    /**
     * Ogloszenie zmiany zawartosci wezlow przegladania.
     *
     * Wersja bez wskazania odbiorcy trafia tylko do kontrolerow zapisanych jako
     * subskrybenci po stronie Media3. Android Auto laczy sie starym API i jego
     * subskrypcje sa prowadzone gdzie indziej, przez co przy otwartej liscie
     * ulubionych nie dostawalo nic i lista zostawala nieodswiezona. Dlatego
     * powiadamiamy adresowo kazdy podlaczony kontroler.
     */
    private fun notifyBrowseNodesChanged(vararg nodes: String) {
        val controllers = session.connectedControllers
        // Media3 dopasowuje powiadomienia do parametrow, z jakimi kontroler sie
        // zapisal. Wyslanie z null trafialo w prozne - Android Auto subskrybuje
        // z parametrami stylu tresci, wiec podajemy te same.
        val params = contentStyleParams()
        for (node in nodes) {
            // Prawdziwa liczba pozycji, nie Int.MAX_VALUE - HDU dostaje wtedy
            // sensowna informacje o tym, jak bardzo zmienila sie lista.
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

    /** Zawartosc wezla przegladania - wspolna dla odpowiedzi i dla powiadomien. */
    private fun childrenOf(parentId: String): List<Station> = when {
        parentId == NODE_FAVOURITES -> repo.favourites()
        parentId == NODE_ALL -> repo.all()
        parentId == NODE_RECENT -> repo.recent()
        parentId.startsWith(NODE_GENRE_PREFIX) ->
            repo.byGenre(parentId.removePrefix(NODE_GENRE_PREFIX))
        else -> emptyList()
    }

    /** Przyciski w szablonie odtwarzacza Android Auto. */
    private fun customLayout(): ImmutableList<CommandButton> =
        ImmutableList.of(favouriteButton(), diagnosticButton())

    /**
     * Gwiazdka ulubionych. Bez niej stacji granej w aucie nie dalo sie dodac do
     * ulubionych w ogole - a to wlasnie w aucie czlowiek stwierdza, ze chce ja
     * miec pod reka.
     */
    private fun favouriteButton(): CommandButton {
        val id = PlaybackStatusBus.stationId.value
        val isFav = id != null && id in prefs.favourites
        Log.i(TAG, "buduje gwiazdke dla stacji '$id': ulubiona=$isFav")
        // Ikona idzie DWOMA kanalami naraz i to nie jest nadmiarowosc.
        //
        // Kanal wlasciwy to extras: dokumentacja Androida dla samochodow mowi
        // wprost, ze jesli ikona odpowiada ktorejs ze stalych CommandButton.ICON_,
        // nalezy wpisac jej wartosc pod kluczem EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT,
        // bo to "nadpisuje zasob ikony przekazany do CustomAction.Builder i pozwala
        // systemowi narysowac akcje spojnie z pozostalymi". Innymi slowy glowica
        // rysuje wtedy WLASNA gwiazdke i nie oglada sie na nasze zasoby.
        //
        // Kanal zapasowy to numer zasobu - dla systemow, ktore tego klucza nie
        // znaja. I tylko tam ma znaczenie, ze wektor jest bez android:tint:
        // odwolanie do @color/... glowica musialaby rozwiazac w naszym pakiecie
        // przy inflacji we wlasnym procesie, i wlasnie na tym sie wykladalo.
        //
        // Czego NIE robic: podawac stalej semantycznej w konstruktorze
        // CommandButton.Builder(ICON_STAR_FILLED) i liczyc, ze wystarczy. Media3
        // zamienia ja wtedy dla starego API na numer wlasnego zasobu z AAR-a, a
        // Desktop Head Unit narysowal z tego nutke i napis "1.8X".
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

    /** Tak samo jak gwiazdka - wlasny wektor, numer zasobu przybity na stale. */
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
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(CMD_TOGGLE_DIAG)
                .add(CMD_TOGGLE_FAV)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                // Swiezo podlaczony kontroler musi dostac aktualny stan gwiazdki
                .setCustomLayout(customLayout())
                .build()
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
                    // prefsListener zajmie sie odswiezeniem metadanych
                    session.setCustomLayout(customLayout())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // Akcja z menu przy pozycji listy - glowica dokleja id pozycji
                ACTION_FAVOURITE, ACTION_UNFAVOURITE -> {
                    val mediaId = args.getString(KEY_ACTION_MEDIA_ITEM_ID)
                    val station = mediaId?.let { repo.byMediaId(it) }
                    val result = Bundle()
                    if (station != null) {
                        val added = prefs.toggleFavourite(station.id)
                        Log.i(TAG, "z listy: ${station.name} ${if (added) "dodana do" else "usunieta z"} ulubionych")
                        // Kaz glowicy odswiezyc te pozycje, zeby ikona sie przelaczyla
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
                        // notifyChildrenChanged jest tylko na MediaLibrarySession,
                        // a tutaj `session` ma szerszy typ MediaSession
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
            // System (i glowica) pyta osobno o korzen "do wznowienia" - wtedy
            // oczekuje krotkiej listy ostatnio sluchanych, a nie calego drzewa.
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
         * Wznawianie odtwarzania po podlaczeniu do auta albo z panelu systemowego.
         *
         * Bez tego Media3 nie wie, co wlaczyc, i wybor bywal przypadkowy. Bierzemy
         * ostatnio sluchana stacje, a gdy historia jest pusta - pierwsza ulubiona,
         * i dopiero na koncu pierwsza z listy.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val station = repo.recent().firstOrNull()
                ?: repo.favourites().firstOrNull()
                ?: repo.all().firstOrNull()

            if (station == null) {
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("brak stacji do wznowienia")
                )
            }

            Log.i(TAG, "wznawiam po podlaczeniu: ${station.name} (zlecil ${controller.packageName})")
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(playableItem(station)),
                    0,
                    // Radio na zywo - pozycja startowa nie ma znaczenia
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
         * Android Auto przysyla pozycje z samym mediaId. Tutaj dokladamy adres
         * strumienia i pelne metadane - bez tego nie byloby czego odtwarzac.
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

        /** Glosowe "zagraj X" trafia tutaj przez wyszukiwanie. */
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
     * Pozycja listy z akcja "ulubione" dostepna wprost z menu kontekstowego
     * w Android Auto. Bez tego stacje dalo sie dodac do ulubionych tylko wtedy,
     * gdy juz gra - a naturalne jest zaznaczenie jej podczas przegladania listy.
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

    /** Definicje akcji, ktore glowica pokaze przy pozycjach listy. */
    private fun browseActionsRootList(): ArrayList<Bundle> {
        // Tu ikona moze byc podana wylacznie adresem, wiec zamiast
        // android.resource:// z numerem zasobu (niestabilnym miedzy wersjami
        // i cache'owanym przez glowice) idzie staly adres z LogoProvider.
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
     * Schemat prezentacji wybrany w Opcjach. To sa te cztery uklady, ktore
     * Android Auto realnie oferuje aplikacjom medialnym.
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

        // Akcje przy pozycjach listy w Android Auto. Klucze pochodza z
        // androidx.media.utils.MediaConstants - wpisane wprost, bo Media3 nie
        // wystawia ich we wlasnym MediaConstants.
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
         * Ile czekamy po znaczniku sterujacym, zanim uznamy, ze rozglosnia nie
         * ma nam nic wiecej do powiedzenia. Wstawki w RMF ida jedna za druga
         * w odstepie kilku sekund, wiec 15 s spokojnie je przykrywa.
         */
        private const val MARKER_GRACE_MS = 15_000L

        /** Przyjmowana dlugosc utworu, gdy katalog jej nie zna. */
        private const val FALLBACK_TRACK_MS = 5 * 60_000L

        /**
         * Zapas doliczany do dlugosci utworu, zanim uznamy opis za nieaktualny.
         *
         * Rozglosnie skracaja utwory, zagaduja koncowki i puszczaja wersje radiowe
         * krotsze niz katalogowe, wiec czekanie dlugo po czasie nic nie daje.
         * Pol minuty pokrywa naturalny rozjazd, a jednoczesnie nie zostawia
         * nieaktualnego tytulu na ekranie na dluzej.
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
