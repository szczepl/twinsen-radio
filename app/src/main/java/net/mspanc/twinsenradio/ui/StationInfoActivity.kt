package net.mspanc.twinsenradio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.RadioBrowser
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.databinding.ActivityStationInfoBinding

/**
 * Szczegoly stacji znalezionej w katalogu, zanim trafi na liste.
 *
 * Po co osobny ekran: w wynikach wyszukiwania mieszcza sie dwie linie, a katalog
 * wie duzo wiecej - kraj, jezyk, kodek, przeplywnosc, strone stacji i liczbe
 * glosow, ktora niezle mowi o tym, czy pozycja jest zywa. Przy nazwach w rodzaju
 * "Radio 1" to bywa jedyny sposob, zeby odroznic wlasciwa stacje od pieciu innych.
 */
@UnstableApi
class StationInfoActivity : AppCompatActivity() {

    private lateinit var b: ActivityStationInfoBinding
    private lateinit var prefs: Prefs
    private lateinit var station: Station

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStationInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val stream = intent.getStringExtra(EXTRA_STREAM).orEmpty()
        if (name.isBlank() || stream.isBlank()) {
            finish()
            return
        }

        station = Station(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            name = name,
            genre = intent.getStringExtra(EXTRA_GENRE).orEmpty().ifBlank { "Z sieci" },
            stream = stream,
            logoUrl = intent.getStringExtra(EXTRA_LOGO),
            source = Station.Source.DISCOVERED
        )

        b.name.text = station.name
        // Katalog sklada tagi bez spacji ("acid,acid jazz,dance") - rozdzielamy je,
        // zeby dalo sie to przeczytac.
        val tags = intent.getStringExtra(EXTRA_TAGS)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")
        b.tagline.text = listOfNotNull(intent.getStringExtra(EXTRA_COUNTRY_NAME), tags)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        ArtworkLoader.into(
            lifecycleScope,
            station.logoUrl?.let { Uri.parse(it) },
            R.drawable.logo_placeholder,
            b.logo
        )

        addDetail(R.string.info_codec, buildString {
            append(intent.getStringExtra(EXTRA_CODEC).orEmpty())
            val bitrate = intent.getIntExtra(EXTRA_BITRATE, 0)
            if (bitrate > 0) {
                if (isNotEmpty()) append(" · ")
                append("$bitrate kb/s")
            }
        })
        addDetail(R.string.info_language, intent.getStringExtra(EXTRA_LANGUAGE).orEmpty())
        addDetail(
            R.string.info_votes,
            intent.getIntExtra(EXTRA_VOTES, 0).takeIf { it > 0 }?.toString().orEmpty()
        )
        addDetail(R.string.info_stream, station.stream)

        val homepage = intent.getStringExtra(EXTRA_HOMEPAGE)
        if (!homepage.isNullOrBlank()) {
            b.btnHomepage.visibility = android.view.View.VISIBLE
            b.btnHomepage.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(homepage)))
                }
            }
        }

        // Dodanie konczy sprawe: wracamy tam, skad przyszlismy, zeby stacja od
        // razu byla widoczna na liscie "Twoje stacje dodane z sieci". Trzymanie
        // uzytkownika na ekranie szczegolow po dodaniu nie daje mu juz nic.
        b.btnToggle.setOnClickListener {
            val added = !prefs.isDiscovered(station.id)
            if (added) prefs.addDiscovered(station) else prefs.removeDiscovered(station.id)
            Toast.makeText(
                this,
                if (added) R.string.info_added_toast else R.string.info_removed_toast,
                Toast.LENGTH_SHORT
            ).show()
            setResult(RESULT_OK, Intent().putExtra(RESULT_CHANGED, true))
            finish()
        }
        renderToggle()
    }

    /** Wiersz "etykieta / wartosc"; puste wartosci pomijamy zamiast pokazywac kreske. */
    private fun addDetail(labelRes: Int, value: String) {
        if (value.isBlank()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        row.addView(
            TextView(this).apply {
                text = getString(labelRes)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelMedium
                )
                alpha = 0.75f
            }
        )
        row.addView(
            TextView(this).apply {
                text = value
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
            }
        )
        b.details.addView(row)
    }

    private fun renderToggle() {
        val added = prefs.isDiscovered(station.id)
        b.btnToggle.setText(if (added) R.string.info_remove else R.string.info_add)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Ustawiane, gdy uzytkownik dodal albo usunal stacje na tym ekranie. */
        const val RESULT_CHANGED = "changed"

        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_GENRE = "genre"
        private const val EXTRA_STREAM = "stream"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_COUNTRY_NAME = "country_name"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_TAGS = "tags"
        private const val EXTRA_CODEC = "codec"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_HOMEPAGE = "homepage"
        private const val EXTRA_VOTES = "votes"

        fun intent(context: Context, found: RadioBrowser.Found): Intent {
            val station = found.toStation()
            return Intent(context, StationInfoActivity::class.java)
                .putExtra(EXTRA_ID, station.id)
                .putExtra(EXTRA_NAME, station.name)
                .putExtra(EXTRA_GENRE, station.genre)
                .putExtra(EXTRA_STREAM, station.stream)
                .putExtra(EXTRA_LOGO, station.logoUrl)
                .putExtra(EXTRA_COUNTRY_NAME, found.countryName)
                .putExtra(EXTRA_LANGUAGE, found.language)
                .putExtra(EXTRA_TAGS, found.tags)
                .putExtra(EXTRA_CODEC, found.codec)
                .putExtra(EXTRA_BITRATE, found.bitrate)
                .putExtra(EXTRA_HOMEPAGE, found.homepage)
                .putExtra(EXTRA_VOTES, found.votes)
        }

        /** Wariant dla stacji juz dodanej - katalog nie jest wtedy potrzebny. */
        fun intent(context: Context, station: Station): Intent =
            Intent(context, StationInfoActivity::class.java)
                .putExtra(EXTRA_ID, station.id)
                .putExtra(EXTRA_NAME, station.name)
                .putExtra(EXTRA_GENRE, station.genre)
                .putExtra(EXTRA_STREAM, station.stream)
                .putExtra(EXTRA_LOGO, station.logoUrl)
                .putExtra(EXTRA_TAGS, station.genre)
    }
}
