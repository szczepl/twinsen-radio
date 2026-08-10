package net.mspanc.twinsenradio.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.StationRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Szuka okladki dla aktualnie granego utworu.
 *
 * Skad wiadomo, ze tak sie to robi: strumien ICY **nie niesie zadnej grafiki** -
 * przychodzi w nim wylacznie tekst (StreamTitle i ewentualnie StreamUrl).
 * Rozglosnie doklejaja okladki po stronie klienta, odpytujac zewnetrzny katalog.
 * Webplayer Radia Nowy Swiat robi dokladnie to, co ponizej - w jego
 * `neoplayer-min.js` siedzi:
 *
 *     $.ajax({ url: `https://itunes.apple.com/search?term=${title} ${artist}&media=music` })
 *
 * Katalog iTunes jest darmowy, nie wymaga klucza ani rejestracji i zwraca adres
 * okladki w polu artworkUrl100, ktory da sie podmienic na wieksza rozdzielczosc.
 *
 * Alternatywy, gdyby trafienia byly slabe: MusicBrainz + Cover Art Archive
 * (darmowe, wolniejsze), Deezer API (darmowe), Last.fm i Spotify (wymagaja klucza).
 */
object CoverArtLookup {

    private const val TAG = "CoverArt"
    private const val ENDPOINT = "https://itunes.apple.com/search"

    /** Male, ograniczone cache - w aucie i tak krecimy sie po kilku stacjach. */
    private const val CACHE_LIMIT = 64
    private val cache = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>) =
            size > CACHE_LIMIT
    }

    /**
     * @return adres okladki albo null, jesli nic nie znaleziono. Null jest tez
     *   zapamietywany, zeby nie odpytywac w kolko o utwory, ktorych w katalogu
     *   nie ma (a takich w polskich rozglosniach jest sporo).
     */
    suspend fun find(artist: String?, title: String?): String? = withContext(Dispatchers.IO) {
        val a = artist?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return@withContext null

        val key = "$a|$t".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }

        val result = runCatching { query("$a $t".trim()) }
            .onFailure { Log.w(TAG, "zapytanie nie wyszlo: ${it.message}") }
            .getOrNull()

        synchronized(cache) { cache[key] = result }
        Log.i(TAG, "okladka dla '$a - $t': ${result ?: "brak"}")
        result
    }

    private fun query(term: String): String? {
        val url = "$ENDPOINT?term=${URLEncoder.encode(term, "UTF-8")}&media=music&limit=1"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val art = results.getJSONObject(0).optString("artworkUrl100").ifBlank { return null }
            // iTunes oddaje miniature 100x100; podmiana w adresie daje pelny rozmiar
            return art.replace("100x100bb", "600x600bb")
        } finally {
            conn.disconnect()
        }
    }
}
