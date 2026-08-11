package net.mspanc.twinsenradio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.RadioBrowser
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.data.StreamProbe
import net.mspanc.twinsenradio.databinding.ActivityStationInfoBinding
import net.mspanc.twinsenradio.playback.MetadataFactory
import kotlinx.coroutines.launch

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
    private var station: Station = Station("", "", "", "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStationInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.toolbar.setNavigationOnClickListener { finish() }
        // Wlasny adres zapisujemy przy wyjsciu - osobny przycisk "zapisz" przy
        // jednym polu bylby tylko dodatkowym klikiem.
        onBackPressedDispatcher.addCallback(this) {
            saveCustomStream()
            finish()
        }

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
            // Nazwa wbudowanego zasobu; stacje z katalogu maja zamiast tego adres
            logo = intent.getStringExtra(EXTRA_LOGO_NAME),
            logoUrl = intent.getStringExtra(EXTRA_LOGO),
            source = Station.Source.DISCOVERED
        )

        // Wersja z repozytorium zna warianty strumienia; intencja niesie tylko
        // to, co potrzebne do wyswietlenia pozycji jeszcze niedodanej.
        StationRepository.get(this).allIncludingHidden()
            .firstOrNull { it.id == station.id }
            ?.let { station = it }

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

        val metadata = MetadataFactory(this, prefs)
        ArtworkLoader.into(
            lifecycleScope,
            metadata.logoDisplayUri(station),
            metadata.logoResId(station),
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
        // Data ostatniego potwierdzenia z katalogu. Sama flaga "dziala" nic nie
        // znaczy bez tej daty - patrz RadioBrowser.isCheckStale.
        val days = intent.getLongExtra(EXTRA_CHECK_DAYS, -1)
        if (days >= 0) {
            addDetail(
                R.string.info_checked,
                if (days > 30) {
                    getString(R.string.info_checked_stale, days)
                } else {
                    getString(R.string.info_checked_days, days)
                }
            )
        }
        addDetail(R.string.info_stream, station.stream)

        b.btnProbe.setOnClickListener { probe() }
        renderStreams()
        b.customStream.setText(prefs.customStream(station.id).orEmpty())
        b.logo.setOnClickListener { pickLogo.launch("image/*") }

        val homepage = intent.getStringExtra(EXTRA_HOMEPAGE)
        if (!homepage.isNullOrBlank()) {
            b.btnHomepage.visibility = android.view.View.VISIBLE
            b.btnHomepage.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(homepage)))
                }
            }
        }

        b.btnToggle.setOnClickListener { toggleOnList() }
        b.favourite.setOnClickListener {
            prefs.toggleFavourite(station.id)
            renderFavourite()
        }
        renderToggle()
    }

    /**
     * Warianty strumienia jako lista wyboru.
     *
     * Zaznaczony jest ten, ktory faktycznie poleci do odtwarzacza - czyli wybor
     * uzytkownika, a gdy go nie bylo, wariant o najwyzszej przeplywnosci.
     */
    private fun renderStreams() {
        val variants = station.variants()
        val chosen = prefs.selectedStream(station.id)
            ?: variants.maxByOrNull { it.kbps }?.url
        b.streamGroup.removeAllViews()
        variants.forEachIndexed { index, variant ->
            val button = RadioButton(this).apply {
                id = index + 1
                text = variant.label
                isChecked = variant.url == chosen
                setOnClickListener {
                    prefs.setSelectedStream(station.id, variant.url)
                    Toast.makeText(context, R.string.info_saved, Toast.LENGTH_SHORT).show()
                }
            }
            b.streamGroup.addView(button)
        }
    }

    /**
     * Wlasne logo z galerii. Kopiujemy plik do katalogu aplikacji, bo adres
     * wybrany przez systemowy wybierak traci wazoosc po zamknieciu ekranu,
     * a grafika ma byc dostepna takze dla Android Auto, z innego procesu.
     */
    private val pickLogo = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val saved = runCatching {
            val dir = java.io.File(filesDir, "logo-custom").apply { mkdirs() }
            val file = java.io.File(dir, "${station.id}.png")
            contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                    ?: return@runCatching null
                // 1024 px to ten sam rozmiar, co nasze wlasne logotypy - na HDU
                // widac roznice miedzy ostrym a rozmytym kafelkiem.
                val side = maxOf(bitmap.width, bitmap.height).coerceAtMost(1024)
                val square = android.graphics.Bitmap.createBitmap(
                    side, side, android.graphics.Bitmap.Config.ARGB_8888
                )
                android.graphics.Canvas(square).apply {
                    drawColor(android.graphics.Color.WHITE)
                    val scale = minOf(side.toFloat() / bitmap.width, side.toFloat() / bitmap.height)
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    drawBitmap(
                        bitmap,
                        null,
                        android.graphics.RectF(
                            (side - w) / 2, (side - h) / 2, (side + w) / 2, (side + h) / 2
                        ),
                        null
                    )
                }
                file.outputStream().use { out ->
                    square.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                file.absolutePath
            }
        }.getOrNull()

        if (saved != null) {
            prefs.setCustomLogo(station.id, saved)
            ArtworkLoader.into(
                lifecycleScope,
                Uri.fromFile(java.io.File(saved)),
                MetadataFactory(this, prefs).logoResId(station),
                b.logo
            )
            Toast.makeText(this, R.string.info_logo_changed, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().putExtra(RESULT_CHANGED, true))
        }
    }

    /**
     * Sprawdzenie strumienia na zadanie.
     *
     * Katalog podaje date ostatniego udanego testu, ale ta bywa sprzed miesiecy -
     * jedyna pewna odpowiedz daje wlasne polaczenie, tu i teraz.
     */
    private fun probe() {
        b.btnProbe.isEnabled = false
        b.probeResult.visibility = android.view.View.VISIBLE
        b.probeResult.setText(R.string.info_probing)
        lifecycleScope.launch {
            val report = StreamProbe.check(station.stream)
            b.probeResult.text = when (report.result) {
                StreamProbe.Result.OK -> getString(R.string.info_probe_ok, report.detail)
                StreamProbe.Result.NO_HOST -> getString(R.string.info_probe_no_host)
                StreamProbe.Result.HTTP_ERROR -> getString(R.string.info_probe_http, report.detail)
                StreamProbe.Result.TIMEOUT -> getString(R.string.info_probe_timeout)
                StreamProbe.Result.FAILED -> getString(R.string.info_probe_failed, report.detail)
            }
            b.probeResult.setTextColor(
                if (report.result == StreamProbe.Result.OK) {
                    0xFF2E7D32.toInt()
                } else {
                    0xFFC62828.toInt()
                }
            )
            b.btnProbe.isEnabled = true
        }
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

    /**
     * Czy stacja jest na liscie uzytkownika. Dotyczy tak samo wbudowanych, jak
     * i dociagnietych z katalogu - z punktu widzenia tego ekranu roznica jest
     * tylko taka, ze wbudowanej nie da sie skasowac, wiec ja ukrywamy.
     */
    private fun isOnList(): Boolean =
        StationRepository.get(this).all().any { it.id == station.id }

    private fun toggleOnList() {
        val onList = isOnList()
        when {
            // Byla na liscie - zdejmujemy. Dociagnieta znika, wbudowana chowa sie
            // do zbioru ukrytych i da sie ja przywrocic w Opcjach.
            onList && prefs.isDiscovered(station.id) -> prefs.removeDiscovered(station.id)
            onList -> prefs.hide(station.id)
            // Nie bylo - wraca. Wbudowana byla tylko ukryta, wiec ja odkrywamy.
            prefs.isHidden(station.id) -> prefs.unhide(station.id)
            else -> prefs.addDiscovered(station)
        }
        Toast.makeText(
            this,
            if (onList) R.string.info_removed_toast else R.string.info_added_toast,
            Toast.LENGTH_SHORT
        ).show()
        setResult(RESULT_OK, Intent().putExtra(RESULT_CHANGED, true))
        finish()
    }

    private fun renderToggle() {
        val onList = isOnList()
        b.btnToggle.setText(if (onList) R.string.info_remove else R.string.info_add)
        // Ulubione dotycza wylacznie stacji, ktora juz jest na liscie
        b.favourite.visibility = if (onList) android.view.View.VISIBLE else android.view.View.GONE
        if (onList) renderFavourite()
    }

    private fun renderFavourite() {
        val fav = station.id in prefs.favourites
        b.favourite.setImageResource(
            if (fav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
        b.favourite.contentDescription =
            getString(if (fav) R.string.fav_remove else R.string.fav_add)
    }

    override fun onPause() {
        saveCustomStream()
        super.onPause()
    }

    private fun saveCustomStream() {
        if (station.id.isBlank()) return
        val typed = b.customStream.text?.toString()?.trim()
        if (typed == prefs.customStream(station.id).orEmpty()) return
        prefs.setCustomStream(station.id, typed)
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
        private const val EXTRA_LOGO_NAME = "logo_name"
        private const val EXTRA_COUNTRY_NAME = "country_name"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_TAGS = "tags"
        private const val EXTRA_CODEC = "codec"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_HOMEPAGE = "homepage"
        private const val EXTRA_VOTES = "votes"
        private const val EXTRA_CHECK_DAYS = "check_days"

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
                .putExtra(EXTRA_CHECK_DAYS, found.daysSinceCheck() ?: -1L)
        }

        /** Wariant dla stacji juz dodanej - katalog nie jest wtedy potrzebny. */
        fun intent(context: Context, station: Station): Intent =
            Intent(context, StationInfoActivity::class.java)
                .putExtra(EXTRA_ID, station.id)
                .putExtra(EXTRA_NAME, station.name)
                .putExtra(EXTRA_GENRE, station.genre)
                .putExtra(EXTRA_STREAM, station.stream)
                .putExtra(EXTRA_LOGO, station.logoUrl)
                .putExtra(EXTRA_LOGO_NAME, station.logo)
                .putExtra(EXTRA_TAGS, station.genre)
    }
}
