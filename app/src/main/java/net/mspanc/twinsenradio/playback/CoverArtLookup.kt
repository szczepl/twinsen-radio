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

    /**
     * Co udalo sie ustalic o utworze. Poza okladka katalog zna tez nazwe wydawnictwa
     * i rok - a tego rozglosnie zwykle nie podaja.
     */
    data class TrackInfo(
        val artworkUrl: String?,
        val album: String?,
        val year: Int?,
        val isSingle: Boolean
    ) {
        /** np. "Księga [2024]" albo "singiel [2024]". */
        fun albumLabel(): String? {
            val name = when {
                isSingle -> "singiel"
                !album.isNullOrBlank() -> album
                else -> return null
            }
            return if (year != null) "$name [$year]" else name
        }
    }

    /** Male, ograniczone cache - w aucie i tak krecimy sie po kilku stacjach. */
    private const val CACHE_LIMIT = 64
    private val cache = object : LinkedHashMap<String, TrackInfo?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackInfo?>) =
            size > CACHE_LIMIT
    }

    /**
     * @return dane utworu albo null, jesli nic nie znaleziono. Null jest tez
     *   zapamietywany, zeby nie odpytywac w kolko o utwory, ktorych w katalogu
     *   nie ma (a takich w polskich rozglosniach jest sporo).
     */
    suspend fun find(artist: String?, title: String?): TrackInfo? = withContext(Dispatchers.IO) {
        val a = artist?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return@withContext null

        val key = "$a|$t".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }

        val result = runCatching { query("$a $t".trim()) }
            .onFailure { Log.w(TAG, "zapytanie nie wyszlo: ${it.message}") }
            .getOrNull()

        synchronized(cache) { cache[key] = result }
        Log.i(TAG, "'$a - $t' -> okladka=${result?.artworkUrl != null} album='${result?.albumLabel()}'")
        result
    }

    private fun query(term: String): TrackInfo? {
        val url = "$ENDPOINT?term=${URLEncoder.encode(term, "UTF-8")}&media=music&entity=song&limit=1"
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
            val row = results.getJSONObject(0)

            // iTunes oddaje miniature 100x100; podmiana w adresie daje pelny rozmiar
            val art = row.optString("artworkUrl100").ifBlank { null }
                ?.replace("100x100bb", "600x600bb")

            val rawAlbum = row.optString("collectionName").ifBlank { null }
            // Single sa w katalogu nazywane "Tytul - Single"; wtedy nazwa
            // wydawnictwa nic nie wnosi i lepiej napisac wprost "singiel".
            val isSingle = rawAlbum?.endsWith(" - Single", ignoreCase = true) == true ||
                row.optInt("trackCount", 0) == 1
            val album = rawAlbum?.removeSuffix(" - Single")?.removeSuffix(" - EP")

            val year = row.optString("releaseDate").take(4).toIntOrNull()

            return TrackInfo(art, album, year, isSingle)
        } finally {
            conn.disconnect()
        }
    }
}
