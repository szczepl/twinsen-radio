package net.mspanc.twinsenradio.ui

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import android.widget.ArrayAdapter
import android.widget.ImageView
import com.google.android.material.chip.Chip
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
 * widoku: wpisany tekst, wyniki, przycisk dodawania i przejscie do szczegolow.
 * Dodana stacja trafia do [Prefs] i od tej chwili zachowuje sie jak kazda inna -
 * da sie ja polubic, wlaczyc i znalezc w aucie.
 */
@UnstableApi
class DiscoverActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiscoverBinding
    private lateinit var prefs: Prefs
    private lateinit var metadata: MetadataFactory
    private lateinit var adapter: StationAdapter

    /** Ostatnie zapytanie w locie - kasujemy je przy kazdym nowym znaku. */
    private var searchJob: Job? = null

    /**
     * Powrot ze szczegolow. Gdy stacja zostala dodana albo usunieta, czyscimy
     * pole wyszukiwania - uzytkownik ma wtedy zobaczyc swoja liste z nowa
     * pozycja, a nie te same wyniki, w ktorych przed chwila grzebal.
     */
    private val details = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val changed = result.data?.getBooleanExtra(StationInfoActivity.RESULT_CHANGED, false)
        if (changed == true) {
            b.query.setText("")
            b.query.clearFocus()
        }
    }

    /**
     * Wyniki ostatniego wyszukiwania, zeby po stuknieciu w wiersz miec czym
     * zapelnic ekran szczegolow. [Station] nie niesie kraju ani kodeka.
     */
    private var lastResults: List<RadioBrowser.Found> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiscoverBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = Prefs(this)
        metadata = MetadataFactory(this, prefs)

        b.toolbar.setNavigationOnClickListener { goBack() }

        // Cofniecie z wynikami na ekranie wraca najpierw do stanu wyjsciowego -
        // paska wyszukiwania, ostatnich zapytan i wlasnej listy - a dopiero
        // drugie wychodzi do listy stacji. Wyskakiwanie od razu na zewnatrz po
        // dodaniu stacji odbieralo mozliwosc sprawdzenia, czy faktycznie doszla.
        onBackPressedDispatcher.addCallback(this) { goBack() }

        adapter = StationAdapter(
            subtitleFor = { it.genre },
            actionIconFor = {
                if (prefs.isDiscovered(it.id)) R.drawable.ic_check else R.drawable.ic_add
            },
            loadLogo = ::showLogo,
            onClick = ::openDetails,
            onAction = ::toggle
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.query.doAfterTextChanged { text -> scheduleSearch(text?.toString().orEmpty()) }
        b.query.setOnClickListener { showHistory() }
        b.query.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showHistory() }
        refreshHistory()

        // Dodanie albo usuniecie stacji ma od razu przelozyc sie na ikony przy
        // wierszach - takze wtedy, gdy zmiana przyszla z ekranu szczegolow.
        lifecycleScope.launch {
            Prefs.discoveredFlow.collect {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                if (queryText().length < 2) showAdded()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Wracamy ze szczegolow - stan przyciskow mogl sie tam zmienic.
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun goBack() {
        if (queryText().isNotEmpty()) {
            b.query.setText("")
            b.query.clearFocus()
            hideKeyboard()
        } else {
            finish()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.query.windowToken, 0)
    }

    private fun queryText() = b.query.text?.toString().orEmpty().trim()

    /** Podpowiedzi z historii; pokazujemy je, gdy pole jest jeszcze puste. */
    private fun refreshHistory() {
        val history = prefs.webSearchHistory
        b.query.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, history)
        )
    }

    private fun showHistory() {
        if (queryText().isEmpty() && prefs.webSearchHistory.isNotEmpty()) {
            b.query.showDropDown()
        }
    }

    /**
     * Stan pustego pola.
     *
     * Wczesniej lezala tu po prostu lista dodanych stacji, wygladajaca dokladnie
     * tak samo jak wyniki wyszukiwania - nie dalo sie odroznic, czy to reszta po
     * poprzednim szukaniu, czy cos wlasnego. Teraz sa dwie wyraznie opisane
     * rzeczy: ostatnie zapytania do ponowienia jednym stuknieciem i wlasne
     * stacje z licznikiem w naglowku.
     */
    private fun showAdded() {
        val added = prefs.discovered
        // Bez tego lista zostaje przewinieta tam, gdzie stala przy wynikach,
        // i pierwsze pozycje chowaja sie pod naglowkiem.
        adapter.submitList(added) { b.list.scrollToPosition(0) }
        renderHistoryChips()

        if (added.isEmpty()) {
            b.hint.setText(R.string.discover_empty)
            b.subhint.setText(R.string.discover_intro)
        } else {
            b.hint.text = getString(R.string.discover_added_header, added.size)
            b.subhint.setText(R.string.discover_added_hint)
        }
    }

    /** Chipy z historia - jedno stukniecie ponawia zapytanie. */
    private fun renderHistoryChips() {
        val history = prefs.webSearchHistory
        b.historyChips.removeAllViews()
        b.historyBox.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        history.forEach { query ->
            val chip = Chip(this).apply {
                text = query
                isCheckable = false
                setOnClickListener {
                    b.query.setText(query)
                    b.query.setSelection(query.length)
                }
            }
            b.historyChips.addView(chip)
        }
    }

    /**
     * Katalog jest publiczny i wspolny dla wszystkich, wiec nie wypada strzelac
     * do niego przy kazdym nacisnietym klawiszu. Czekamy, az pisanie ucichnie.
     */
    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            lastResults = emptyList()
            showAdded()
            return
        }
        // Szukamy - historia i licznik dodanych ustepuja miejsca wynikom
        b.historyBox.visibility = View.GONE
        b.subhint.text = ""

        searchJob = lifecycleScope.launch {
            delay(400)
            b.hint.setText(R.string.discover_searching)
            val found = RadioBrowser.search(query)
            lastResults = found
            adapter.submitList(found.map { it.toStation() }) { b.list.scrollToPosition(0) }
            b.hint.text = if (found.isEmpty()) {
                getString(R.string.discover_nothing, query.trim())
            } else {
                prefs.pushWebSearch(query)
                refreshHistory()
                resources.getQuantityString(R.plurals.discover_results, found.size, found.size)
            }
        }
    }

    /**
     * Stukniecie w wiersz otwiera szczegoly. Wczesniej robilo to samo, co przycisk
     * obok - stacja po cichu ladowala na liste, bez zadnego sladu na ekranie,
     * przez co pierwsze nacisniecie "+" ja usuwalo i wygladalo na nieskuteczne.
     */
    private fun openDetails(station: Station) {
        val found = lastResults.firstOrNull { it.toStation().id == station.id }
        details.launch(
            if (found != null) {
                StationInfoActivity.intent(this, found)
            } else {
                StationInfoActivity.intent(this, station)
            }
        )
    }

    private fun toggle(station: Station) {
        if (prefs.isDiscovered(station.id)) {
            prefs.removeDiscovered(station.id)
        } else {
            prefs.addDiscovered(station)
        }
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
