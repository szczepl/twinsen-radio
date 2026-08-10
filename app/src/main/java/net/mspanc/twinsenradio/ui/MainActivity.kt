package net.mspanc.twinsenradio.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivityMainBinding
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.playback.RadioService

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var adapter: StationAdapter

    private var controller: MediaController? = null

    /** Stacja do wlaczenia, gdy tylko kontroler sie podepnie (patrz [EXTRA_PLAY_STATION]). */
    private var pendingStationId: String? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* nieobowiazkowe */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        prefs = Prefs(this)
        repo = StationRepository.get(this)
        metadata = MetadataFactory(this, prefs)

        adapter = StationAdapter(
            isFavourite = { it.id in prefs.favourites },
            logoResFor = { metadata.logoResId(it) },
            onClick = ::play,
            onToggleFavourite = { prefs.toggleFavourite(it.id) }
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        adapter.submitList(repo.all())

        b.search.doAfterTextChanged { text ->
            adapter.submitList(repo.search(text?.toString().orEmpty()))
        }

        b.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        // Stukniecie w pasek rozwija pelnoekranowy odtwarzacz
        b.miniPlayer.setOnClickListener {
            if (PlaybackStatusBus.stationId.value != null) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }

        askForNotificationPermission()
        observeStatus()
        handleIntent(intent)

        lifecycleScope.launch {
            repo.refreshUserLists()
            adapter.submitList(repo.search(b.search.text?.toString().orEmpty()))
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = renderMiniPlayer()
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = renderMiniPlayer()
                    override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) =
                        renderMiniPlayer()
                })
            }
            pendingStationId?.let { id ->
                pendingStationId = null
                repo.byId(id)?.let(::play)
            }
            renderMiniPlayer()
        }, MoreExecutors.directExecutor())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Pozwala wlaczyc stacje z zewnatrz, bez dotykania ekranu:
     *   adb shell am start -n net.mspanc.twinsenradio/.ui.MainActivity --es play_station rns
     * Przydatne do testow i jako punkt zaczepienia pod skroty.
     */
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PLAY_STATION) ?: return
        val station = repo.byId(id) ?: return
        val c = controller
        if (c != null) play(station) else pendingStationId = id
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    private fun play(station: Station) {
        val c = controller ?: return

        // Ta sama stacja, ktora wlasnie gra - nie ruszamy strumienia, tylko
        // otwieramy ekran odtwarzania. Ponowne ustawienie pozycji zrywalo
        // polaczenie i bylo slychac przerwe.
        if (PlaybackStatusBus.stationId.value == station.id && c.isPlaying) {
            startActivity(Intent(this, NowPlayingActivity::class.java))
            return
        }

        c.setMediaItem(MediaItem.Builder().setMediaId(station.mediaId).build())
        c.prepare()
        c.play()
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            PlaybackStatusBus.status.collect { renderMiniPlayer() }
        }
        lifecycleScope.launch {
            PlaybackStatusBus.nowPlaying.collect { renderMiniPlayer() }
        }
        lifecycleScope.launch {
            PlaybackStatusBus.stationId.collect { renderMiniPlayer() }
        }
        lifecycleScope.launch {
            PlaybackStatusBus.coverArtUrl.collect { renderMiniPlayer() }
        }
    }

    private fun renderMiniPlayer() {
        val station = PlaybackStatusBus.stationId.value?.let { repo.byId(it) }
        val now = PlaybackStatusBus.nowPlaying.value

        b.miniTitle.text = station?.name ?: getString(R.string.nothing_playing)
        // Na telefonie metadane maja byc metadanymi - tryb diagnostyczny dotyczy
        // tego, co wysylamy do auta, i sygnalizujemy go tylko na ekranie odtwarzania.
        b.miniSubtitle.text = when {
            now?.isRealSong == true -> listOfNotNull(now.artist, now.songTitle)
                .filter { it.isNotBlank() }
                .joinToString(" — ")
            now?.slogan != null -> now.slogan
            now?.isAd == true -> getString(R.string.ad)
            else -> station?.genre.orEmpty()
        }
        b.miniStatus.text = getString(
            when (PlaybackStatusBus.status.value) {
                PlaybackStatusBus.Status.CONNECTING -> R.string.status_connecting
                PlaybackStatusBus.Status.BUFFERING -> R.string.status_buffering
                PlaybackStatusBus.Status.PLAYING -> R.string.status_playing
                PlaybackStatusBus.Status.RECONNECTING -> R.string.status_reconnecting
                PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> R.string.status_waiting_network
                PlaybackStatusBus.Status.IDLE -> R.string.status_idle
            }
        )
        ArtworkLoader.into(
            lifecycleScope,
            PlaybackStatusBus.coverArtUrl.value?.let { android.net.Uri.parse(it) },
            station?.let { metadata.logoResId(it) } ?: R.drawable.logo_placeholder,
            b.miniLogo
        )
        b.playPause.setImageResource(
            if (controller?.isPlaying == true) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_PLAY_STATION = "play_station"
    }
}
