package net.mspanc.twinsenradio.ui

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.RadioBrowser
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.databinding.ActivityDiscoverBinding
import net.mspanc.twinsenradio.playback.MetadataFactory

/**
 * Wyszukiwanie stacji, ktorych nie ma na wbudowanej liscie.
 *
 * Katalog i powody jego wyboru opisuje [RadioBrowser]. Tutaj jest tylko warstwa
 * widoku: wpisany tekst, wyniki i przycisk dodawania. Dodana stacja trafia do
 * [Prefs] i od tej chwili zachowuje sie jak kazda inna - da sie ja polubic,
 * wlaczyc i znalezc w aucie.
 */
@UnstableApi
class DiscoverActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiscoverBinding
    private lateinit var prefs: Prefs
    private lateinit var metadata: MetadataFactory
    private lateinit var adapter: StationAdapter

    /** Ostatnie zapytanie w locie - kasujemy je przy kazdym nowym znaku. */
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiscoverBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = Prefs(this)
        metadata = MetadataFactory(this, prefs)

        b.toolbar.setNavigationOnClickListener { finish() }

        adapter = StationAdapter(
            subtitleFor = { it.genre },
            actionIconFor = {
                if (prefs.isDiscovered(it.id)) R.drawable.ic_check
                else R.drawable.ic_add
            },
            loadLogo = ::showLogo,
            onClick = { toggle(it) },
            onAction = { toggle(it) }
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.query.doAfterTextChanged { text -> scheduleSearch(text?.toString().orEmpty()) }

        // Na wejsciu pokazujemy to, co juz dodano - inaczej ekran jest pusty
        // i nie widac, ze cokolwiek sie tu wczesniej dodalo.
        showAdded()
    }

    private fun showAdded() {
        val added = prefs.discovered
        adapter.submitList(added)
        b.hint.setText(
            if (added.isEmpty()) R.string.discover_intro else R.string.discover_added_header
        )
    }

    /**
     * Katalog jest publiczny i wspolny dla wszystkich, wiec nie wypada strzelac
     * do niego przy kazdym nacisnietym klawiszu. Czekamy, az pisanie ucichnie.
     */
    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            showAdded()
            return
        }
        searchJob = lifecycleScope.launch {
            delay(400)
            b.hint.setText(R.string.discover_searching)
            val found = RadioBrowser.search(query)
            val stations = found.map { it.toStation() }
            adapter.submitList(stations)
            b.hint.text = if (stations.isEmpty()) {
                getString(R.string.discover_nothing, query.trim())
            } else {
                resources.getQuantityString(
                    R.plurals.discover_results, stations.size, stations.size
                )
            }
        }
    }

    /** Dodaje albo usuwa stacje z listy uzytkownika. */
    private fun toggle(station: Station) {
        if (prefs.isDiscovered(station.id)) {
            prefs.removeDiscovered(station.id)
        } else {
            prefs.addDiscovered(station)
        }
        // Gdy stoimy na liscie juz dodanych, usuniecie ma ja od razu skrocic.
        if (b.query.text?.toString().orEmpty().trim().length < 2) showAdded()
    }

    private fun showLogo(station: Station, view: ImageView) {
        ArtworkLoader.into(
            lifecycleScope,
            station.logoUrl?.let { android.net.Uri.parse(it) },
            metadata.logoResId(station),
            view
        )
    }
}
