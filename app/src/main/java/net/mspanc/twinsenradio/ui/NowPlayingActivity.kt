package net.mspanc.twinsenradio.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivityNowPlayingBinding
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.playback.RadioService

/**
 * Pelnoekranowy odtwarzacz na telefonie: duza okladka, metadane, sterowanie
 * i strzalka powrotu do listy stacji. Otwiera sie stuknieciem w pasek
 * odtwarzania na ekranie glownym.
 */
@UnstableApi
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var b: ActivityNowPlayingBinding
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var prefs: Prefs
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(b.root)

        repo = StationRepository.get(this)
        prefs = Prefs(this)
        metadata = MetadataFactory(this, prefs)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        b.prev.setOnClickListener { step(-1) }
        b.next.setOnClickListener { step(+1) }
        b.favourite.setOnClickListener {
            val id = PlaybackStatusBus.stationId.value ?: return@setOnClickListener
            prefs.toggleFavourite(id)
            render()
        }

        listOf(
            PlaybackStatusBus.stationId,
            PlaybackStatusBus.nowPlaying,
            PlaybackStatusBus.status,
            PlaybackStatusBus.coverArtUrl,
            PlaybackStatusBus.trackInfo,
            Prefs.favouritesFlow
        ).forEach { flow ->
            lifecycleScope.launch { flow.collect { render() } }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = render()
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = render()
                    // bez tego okladka doszukana juz w trakcie utworu nigdy by sie
                    // nie pojawila - metadane zmieniaja sie osobno od pozycji
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = render()
                })
            }
            render()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    /** Przeskok na sasiednia stacje z tej samej listy, z zawijaniem. */
    private fun step(delta: Int) {
        val all = repo.all()
        if (all.isEmpty()) return
        val currentId = PlaybackStatusBus.stationId.value
        val index = all.indexOfFirst { it.id == currentId }
        val target = if (index < 0) 0 else ((index + delta) % all.size + all.size) % all.size
        play(all[target])
    }

    private fun play(station: Station) {
        val c = controller ?: return
        c.setMediaItem(MediaItem.Builder().setMediaId(station.mediaId).build())
        c.prepare()
        c.play()
    }

    private fun render() {
        val station = PlaybackStatusBus.stationId.value?.let { repo.byId(it) }
        val now = PlaybackStatusBus.nowPlaying.value

        b.stationName.text = station?.name ?: getString(R.string.nothing_playing)

        // Ta sama logika co w metadanych dla auta: prawdziwy utwor, slogan stacji
        // albo reklama - nigdy powielona nazwa stacji.
        when {
            now?.isRealSong == true -> {
                // Ta sama linia co w aucie, razem z wydawnictwem i rokiem
                val artistLine = MetadataFactory.composeArtistLine(
                    now,
                    PlaybackStatusBus.trackInfo.value,
                    prefs.enrichWithAlbum
                )
                val title = now.songTitle.orEmpty()
                if (prefs.swapTitleArtist) {
                    b.songTitle.text = artistLine
                    b.songArtist.text = title
                } else {
                    b.songTitle.text = title
                    b.songArtist.text = artistLine
                }
            }
            now?.slogan != null -> {
                b.songTitle.text = now.slogan
                b.songArtist.text = ""
            }
            now?.isAd == true -> {
                val seconds = now.adDurationMs / 1000
                b.songTitle.text = if (seconds > 0) {
                    getString(R.string.ad_with_length, seconds)
                } else {
                    getString(R.string.ad)
                }
                b.songArtist.text = ""
            }
            else -> {
                b.songTitle.text = ""
                b.songArtist.text = ""
            }
        }

        val isFav = station != null && station.id in prefs.favourites
        b.favourite.setImageResource(
            if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
        b.favourite.contentDescription =
            getString(if (isFav) R.string.fav_remove else R.string.fav_add)

        b.diagnosticBanner.visibility =
            if (prefs.diagnosticMode) android.view.View.VISIBLE else android.view.View.GONE
        // Okladke bierzemy z wlasnej magistrali, a nie z metadanych sesji.
        // W sesji moze siedziec zegar zamiast okladki - to ficzer wylacznie dla
        // ekranu w aucie, na telefonie ma byc zawsze okladka albo logo stacji.
        ArtworkLoader.into(
            lifecycleScope,
            PlaybackStatusBus.coverArtUrl.value?.let { android.net.Uri.parse(it) },
            station?.let { metadata.logoResId(it) } ?: R.drawable.logo_placeholder,
            b.art
        )
        b.status.text = getString(
            when (PlaybackStatusBus.status.value) {
                PlaybackStatusBus.Status.CONNECTING -> R.string.status_connecting
                PlaybackStatusBus.Status.BUFFERING -> R.string.status_buffering
                PlaybackStatusBus.Status.PLAYING -> R.string.status_playing
                PlaybackStatusBus.Status.RECONNECTING -> R.string.status_reconnecting
                PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> R.string.status_waiting_network
                PlaybackStatusBus.Status.IDLE -> R.string.status_idle
            }
        )
        b.playPause.setImageResource(
            if (controller?.isPlaying == true) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }
}
