package net.mspanc.twinsenradio.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Wyszukiwanie stacji spoza wbudowanej listy.
 *
 * Zrodlo: **radio-browser.info** - otwarty, spolecznosciowy katalog okolo 50 tys.
 * rozglosni. Wybrany, bo jako jedyny sensowny spelnia komplet warunkow: jest
 * darmowy, nie wymaga klucza ani rejestracji, ma jasna licencje na dane,
 * publiczne API i - co najwazniejsze - sam odsiewa martwe strumienie
 * (`hidebroken`), bo cyklicznie je sprawdza. Alternatywy odpadaly: TuneIn i
 * iHeartRadio nie maja otwartego API, Shoutcast wymaga klucza wydawanego
 * recznie, a listy M3U z sieci nikt nie utrzymuje.
 *
 * Uwaga na kolejnosc pol: `url` bywa przekierowaniem albo plikiem .pls,
 * a `url_resolved` to juz konkretny strumien - i tego uzywamy.
 */
object RadioBrowser {

    private const val TAG = "RadioBrowser"

    /**
     * Serwery katalogu. `all.api` rozklada ruch po zywych wezlach, ale bywa, ze
     * nie odpowiada - wtedy schodzimy po konkretnych mirrorach. Regulamin API
     * wymaga rozpoznawalnego User-Agenta i tego przestrzegamy.
     */
    private val MIRRORS = listOf(
        "https://all.api.radio-browser.info",
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    /** Jedna pozycja z katalogu, jeszcze nie dodana do listy uzytkownika. */
    data class Found(
        val uuid: String,
        val name: String,
        val stream: String,
        val faviconUrl: String?,
        val country: String?,
        val tags: String?,
        val codec: String?,
        val bitrate: Int
    ) {
        /** Druga linia na liscie wynikow: "PL · MP3 128 kb/s · rock". */
        fun describe(): String = listOfNotNull(
            country?.takeIf { it.isNotBlank() },
            listOfNotNull(
                codec?.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", true) },
                bitrate.takeIf { it > 0 }?.let { "$it kb/s" }
            ).joinToString(" ").takeIf { it.isNotBlank() },
            tags?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        fun toStation(): Station = Station(
            id = "$DISCOVERED_PREFIX$uuid",
            name = name,
            genre = tags?.split(',')?.firstOrNull()?.trim()?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.isNotBlank() } ?: "Z sieci",
            stream = stream,
            logoUrl = faviconUrl,
            source = Station.Source.DISCOVERED
        )
    }

    /**
     * @param query fragment nazwy stacji; puste zapytanie nie ma sensu, bo
     *   katalog oddalby wtedy przypadkowe 50 tysiecy pozycji.
     */
    suspend fun search(query: String, limit: Int = 40): List<Found> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        val path = "/json/stations/search?limit=$limit&hidebroken=true" +
            "&order=votes&reverse=true&name=${URLEncoder.encode(q, "UTF-8")}"

        for (mirror in MIRRORS) {
            val body = runCatching { get(mirror + path) }
                .onFailure { Log.w(TAG, "$mirror nie odpowiedzial: ${it.message}") }
                .getOrNull() ?: continue
            val parsed = runCatching { parse(body) }.getOrNull() ?: continue
            Log.i(TAG, "'$q' -> ${parsed.size} wynikow z $mirror")
            return@withContext parsed
        }
        Log.w(TAG, "zaden serwer katalogu nie odpowiedzial")
        emptyList()
    }

    private fun parse(body: String): List<Found> {
        val arr = JSONArray(body)
        val seen = HashSet<String>()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val stream = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (stream.isBlank() || name.isBlank()) return@mapNotNull null
            // Ten sam strumien bywa w katalogu pod kilkoma nazwami - pokazywanie
            // czterech wariantow tej samej stacji tylko utrudnia wybor.
            if (!seen.add(stream)) return@mapNotNull null
            Found(
                uuid = o.optString("stationuuid").ifBlank { stream.hashCode().toString() },
                name = name,
                stream = stream,
                faviconUrl = o.optString("favicon").ifBlank { null },
                country = o.optString("countrycode").ifBlank { null },
                tags = o.optString("tags").ifBlank { null },
                codec = o.optString("codec").ifBlank { null },
                bitrate = o.optInt("bitrate", 0)
            )
        }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    const val DISCOVERED_PREFIX = "rb:"
}
