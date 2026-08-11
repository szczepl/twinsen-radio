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
            subtitleFor = { it.genre },
            actionIconFor = {
                if (it.id in prefs.favourites) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            },
            loadLogo = ::showLogo,
            onClick = ::play,
            onAction = { prefs.toggleFavourite(it.id) },
            // Logo prowadzi do szczegolow. Sam wiersz zostaje przy graniu - to
            // najczestsza czynnosc i nie ma jej po co utrudniac.
            onLogoClick = { startActivity(StationInfoActivity.intent(this, it)) }
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        adapter.submitList(repo.all())

        b.search.doAfterTextChanged { text ->
            adapter.submitList(repo.search(text?.toString().orEmpty()))
        }
        b.search.setOnClickListener { showHistory() }
        b.search.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showHistory() }
        // Zapamietujemy dopiero zatwierdzone zapytanie, a nie kazdy znak po drodze
        b.search.setOnEditorActionListener { _, _, _ ->
            prefs.pushLocalSearch(b.search.text?.toString().orEmpty())
            refreshHistory()
            hideKeyboard()
            true
        }
        refreshHistory()

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

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            toggleSearch()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_discover -> {
            startActivity(Intent(this, DiscoverActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Pokazuje albo chowa pole wyszukiwania. Schowanie czysci zapytanie - lista
     * ma wracac do pelnej, a nie zostawac przefiltrowana przez niewidoczny tekst.
     */
    private fun toggleSearch() {
        val visible = b.searchLayout.visibility == android.view.View.VISIBLE
        if (visible) {
            b.search.setText("")
            b.searchLayout.visibility = android.view.View.GONE
            hideKeyboard()
        } else {
            b.searchLayout.visibility = android.view.View.VISIBLE
            b.search.requestFocus()
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(b.search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            showHistory()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.search.windowToken, 0)
    }

    private fun refreshHistory() {
        b.search.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                prefs.localSearchHistory
            )
        )
    }

    private fun showHistory() {
        if (b.search.text.isNullOrBlank() && prefs.localSearchHistory.isNotEmpty()) {
            b.search.showDropDown()
        }
    }

    /**
     * Logo stacji. Wbudowane siedza w APK, ale stacje dociagniete z katalogu maja
     * je pod adresem w sieci - stad dwie drogi.
     */
    private fun showLogo(station: Station, view: android.widget.ImageView) {
        ArtworkLoader.into(
            lifecycleScope,
            station.logoUrl?.let { android.net.Uri.parse(it) },
            metadata.logoResId(station),
            view
        )
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
        // Ulubione moga zmienic sie na ekranie odtwarzania albo w aucie. Stan nie
        // jest czescia modelu stacji, wiec DiffUtil sam z siebie nic nie odswiezy.
        lifecycleScope.launch {
            Prefs.favouritesFlow.collect {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        // Stacja dodana albo usunieta w wyszukiwarce ma pojawic sie tutaj od razu,
        // bez wychodzenia z ekranu.
        lifecycleScope.launch {
            Prefs.discoveredFlow.collect { refreshList() }
        }
        // Ukrycie albo przywrocenie stacji tez musi od razu przebudowac liste
        lifecycleScope.launch {
            Prefs.hiddenFlow.collect { refreshList() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Wracamy ze szczegolow - stacja mogla tam zniknac albo zmienic ulubione
        refreshList()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    /**
     * Przebudowa listy po zmianie jej zawartosci.
     *
     * Przewiniecie na gore nie jest kosmetyka: RecyclerView trzyma kotwice na
     * pozycji, ktora widac, wiec stacja przywrocona na poczatek ladowala nad
     * widocznym obszarem i wygladalo to, jakby przywrocenie nie zadzialalo.
     */
    private fun refreshList() {
        val query = b.search.text?.toString().orEmpty()
        adapter.submitList(repo.search(query)) { b.list.scrollToPosition(0) }
    }

    private fun renderMiniPlayer() {
        val station = PlaybackStatusBus.stationId.value?.let { repo.byId(it) }
        val now = PlaybackStatusBus.nowPlaying.value

        b.miniTitle.text = station?.name ?: getString(R.string.nothing_playing)
        // Na telefonie metadane maja byc metadanymi - tryb diagnostyczny dotyczy
        // tego, co wysylamy do auta, i sygnalizujemy go tylko na ekranie odtwarzania.
        b.miniSubtitle.text = when {
            now?.isRealSong == true -> {
                val info = PlaybackStatusBus.trackInfo.value
                listOf(
                    MetadataFactory.displayArtist(now, info),
                    MetadataFactory.displayTitle(now, info)
                ).filter { it.isNotBlank() }.joinToString(" — ")
            }
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
                PlaybackStatusBus.Status.STATION_UNREACHABLE -> R.string.status_unreachable
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
