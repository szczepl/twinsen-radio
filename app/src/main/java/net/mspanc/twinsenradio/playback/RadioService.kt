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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
            Prefs.KEY_DIAG_API, Prefs.KEY_ARTWORK -> refreshCurrentMetadata(force = true)
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
        player.addAnalyticsListener(LoadDiagnostics())

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .setCustomLayout(ImmutableList.of(diagnosticButton()))
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
            val id = mediaItem?.mediaId?.let { Station.idFromMediaId(it) }
            PlaybackStatusBus.setStation(id)
            id?.let { prefs.pushRecent(it) }
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
                        logIcyInfo(entry)
                        handleIcyTitle(entry.title)
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
        if (h.bitrate > 0) Log.i(TAG_ICY, "icy-br = ${h.bitrate}")
        if (h.metadataInterval > 0) Log.i(TAG_ICY, "icy-metaint = ${h.metadataInterval}")
        Log.i(TAG_ICY, "icy-pub = ${h.isPublic}")
    }

    /**
     * Blok metadanych wstrzykiwany w strumien. ExoPlayer parsuje z niego tylko
     * StreamTitle i StreamUrl, ale rozglosnie wpychaja tam wiecej par klucz=wartosc
     * (RMF np. oznacza reklamy przez adw_ad i durationMilliseconds). Dlatego obok
     * pol rozpoznanych logujemy tez cala surowa zawartosc.
     */
    private fun logIcyInfo(info: IcyInfo) {
        val raw = runCatching { String(info.rawMetadata, Charsets.UTF_8).trim(Char(0), ' ') }
            .getOrDefault("<nieczytelne>")
        Log.i(TAG_ICY, "surowy blok: $raw")
        info.url?.let { Log.i(TAG_ICY, "StreamUrl = $it") }

        // rozbij wszystkie pary klucz='wartosc', zeby bylo widac pola nietypowe
        Regex("""(\w+)='([^']*)'""").findAll(raw).forEach { m ->
            Log.i(TAG_ICY, "  ${m.groupValues[1]} = ${m.groupValues[2]}")
        }
    }

    private fun handleIcyTitle(rawTitle: String?) {
        val title = rawTitle?.trim().orEmpty()
        if (title == lastRawIcyTitle) return
        lastRawIcyTitle = title

        // Odstep miedzy blokami ICY jest tym, czego nie wiemy o rozglosniach:
        // czy metadane leca raz na utwor, czy okresowo. Logujemy, zeby dalo sie
        // to policzyc z zewnatrz.
        val nowMs = System.currentTimeMillis()
        val sinceLast = if (lastIcyAtMs == 0L) -1 else (nowMs - lastIcyAtMs) / 1000
        lastIcyAtMs = nowMs
        Log.i(TAG_ICY, "po ${sinceLast}s | StreamTitle='$title'")

        val now = NowPlaying.parse(title)
        Log.i(TAG_ICY, "  -> artist='${now?.artist}' title='${now?.songTitle}'")
        PlaybackStatusBus.setNowPlaying(now)
        refreshCurrentMetadata(force = false, now = now)
    }

    /**
     * Odswieza metadane rowno na granicy minuty, a nie co 60 s od startu - inaczej
     * zegar w podtytule dryfowalby wzgledem zegara w aucie.
     */
    private fun scheduleClockTick() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        val delayToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
        val runnable = Runnable {
            if (prefs.diagnosticMode) refreshCurrentMetadata(force = true)
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
        val fresh = metadata.forPlayback(station, now)
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(fresh).build())
        dumpMetadata(station, fresh)
    }

    /**
     * Wypisuje komplet pol wyslanych do sesji. Sluzy okienku podgladu na Windows
     * (tools/meta-watch.ps1), ktore czyta to przez `adb logcat -s MetaDump`.
     */
    private fun dumpMetadata(station: Station, m: MediaMetadata) {
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
    private fun diagnosticButton(): CommandButton =
        CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_DIAG)
            .setDisplayName(
                if (prefs.diagnosticMode) "Diagnostyka: WL" else "Diagnostyka: WYL"
            )
            .setIconResId(net.mspanc.twinsenradio.R.drawable.ic_radio)
            .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(CMD_TOGGLE_DIAG)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_TOGGLE_DIAG.customAction) {
                prefs.diagnosticMode = !prefs.diagnosticMode
                Log.i(TAG, "przelaczono tryb diagnostyczny na ${prefs.diagnosticMode}")
                // prefsListener zajmie sie odswiezeniem metadanych
                session.setCustomLayout(ImmutableList.of(diagnosticButton()))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
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

    private fun browseItem(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.mediaId)
            .setMediaMetadata(metadata.forBrowseItem(station))
            .build()

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
        }
        return MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()
    }

    companion object {
        private const val TAG = "RadioService"
        private const val TAG_ICY = "IcyMeta"
        private const val TAG_DUMP = "MetaDump"

        private val CMD_TOGGLE_DIAG =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_DIAG", Bundle.EMPTY)

        const val NODE_ROOT = "/"
        const val NODE_FAVOURITES = "/fav"
        const val NODE_ALL = "/all"
        const val NODE_RECENT = "/recent"
        const val NODE_GENRES = "/genres"
        const val NODE_GENRE_PREFIX = "/genre/"

        private val BROWSE_NODES = listOf(NODE_FAVOURITES, NODE_ALL, NODE_RECENT, NODE_GENRES)
    }
}
