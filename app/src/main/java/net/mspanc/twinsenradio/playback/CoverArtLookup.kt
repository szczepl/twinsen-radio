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
    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/recording"
    private const val COVER_ART = "https://coverartarchive.org"
    private const val MUSICBRAINZ_AGENT = "TwinsenRadio/0.1 (twinsen@mspanc.net)"

    /**
     * Co udalo sie ustalic o utworze. Poza okladka katalog zna tez nazwe wydawnictwa
     * i rok - a tego rozglosnie zwykle nie podaja.
     */
    data class TrackInfo(
        val artworkUrl: String?,
        val album: String?,
        val year: Int?,
        val isSingle: Boolean,
        /** Dlugosc utworu z katalogu; 0 gdy nieznana. */
        val durationMs: Long = 0
    ) {
        /** np. "Księga [2024]" albo "singiel [2024]". */
        fun albumLabel(): String? {
            val name = when {
                isSingle -> "singiel"
                !album.isNullOrBlank() -> tameCaps(album)
                else -> return null
            }
            return if (year != null) "$name [$year]" else name
        }

        /**
         * Czy koncowka podana przez rozglosnie to nazwa wydawnictwa, a nie kolejny
         * wykonawca.
         *
         * Po samym separatorze tego nie odroznisz: RMF pisze "Wiktoria Kida / Księga"
         * (wykonawca i plyta), ale rownie dobrze przysyla "Shimza / AR/CO / Kasango"
         * (trzech wykonawcow) albo "Nico / Vinz" (nazwa zespolu ze slashem).
         * Rozstrzyga dopiero porownanie z tym, co o utworze wie katalog.
         */
        fun tailIsAlbum(tail: String): Boolean {
            val a = album ?: return false
            return normalize(tail) == normalize(a)
        }

        private fun normalize(s: String) = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

        /**
         * Sprowadza tytuly pisane w calosci wersalikami do zapisu z wielkiej
         * litery. Wytwornie potrafia wpisac do katalogu "NO ME ARREPIENTO DE
         * SENTIR TANTO", co na waskim ekranie zjada dwie linie i krzyczy.
         *
         * Celowo bez odpytywania innego katalogu na krzyz: to kwestia typografii,
         * a nie danych, i nie warto za nia placic kolejnym zapytaniem sieciowym.
         *
         * Warunki sa zachowawcze - zmieniamy tylko napisy wielowyrazowe, w calosci
         * wersalikowe. Dzieki temu "ABBA", "U2" czy "AC/DC" zostaja nietkniete.
         */
        internal fun tameCaps(text: String): String {
            val letters = text.filter { it.isLetter() }
            if (letters.length < 5) return text
            if (!text.contains(' ')) return text
            if (letters.any { it.isLowerCase() }) return text

            return text.split(' ').joinToString(" ") { word ->
                if (word.length <= 1) word
                else word.first() + word.drop(1).lowercase()
            }
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

        var result = runCatching { query("$a $t".trim()) }
            .onFailure { Log.w(TAG, "iTunes nie odpowiedzial: ${it.message}") }
            .getOrNull()

        // iTunes ma slabe pokrycie starszego polskiego repertuaru - "Czesław
        // Niemen - Lipowa łyżka" nie ma tam wcale, a MusicBrainz zna i utwor,
        // i wydawnictwo. Pytamy go tylko wtedy, gdy Apple nie zna utworu.
        if (result == null && a.isNotEmpty()) {
            result = runCatching { queryMusicBrainz(a, t) }
                .onFailure { Log.w(TAG, "MusicBrainz nie odpowiedzial: ${it.message}") }
                .getOrNull()
        }

        synchronized(cache) { cache[key] = result }
        Log.i(TAG, "'$a - $t' -> okladka=${result?.artworkUrl != null} album='${result?.albumLabel()}'")
        result
    }

    /**
     * Zapas dla utworow, ktorych nie ma w iTunes. MusicBrainz to otwarta baza
     * z duzo lepszym pokryciem polskiej i starszej muzyki; okladki bierzemy
     * z powiazanego Cover Art Archive.
     */
    private fun queryMusicBrainz(artist: String, title: String): TrackInfo? {
        val query = "artist:\"$artist\" AND recording:\"$title\""
        val url = "$MUSICBRAINZ?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=1"
        val body = httpGet(url) ?: return null

        val recordings = JSONObject(body).optJSONArray("recordings") ?: return null
        if (recordings.length() == 0) return null
        val releases = recordings.getJSONObject(0).optJSONArray("releases") ?: return null
        if (releases.length() == 0) return null
        val release = releases.getJSONObject(0)

        val album = release.optString("title").ifBlank { null }
        val year = release.optString("date").take(4).toIntOrNull()
        val mbid = release.optString("id").ifBlank { null }

        // Cover Art Archive nie ma okladek do wszystkiego, wiec sprawdzamy, czy
        // adres w ogole cokolwiek zwraca - inaczej wyslalibysmy do auta martwy link.
        val art = mbid?.let { id ->
            val candidate = "$COVER_ART/release/$id/front-500"
            if (headOk(candidate)) candidate else null
        }

        if (album == null && art == null) return null
        return TrackInfo(art, album, year, isSingle = false, durationMs = 0)
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            // MusicBrainz wymaga rozpoznawalnego User-Agenta z kontaktem
            setRequestProperty("User-Agent", MUSICBRAINZ_AGENT)
        }
        try {
            if (conn.responseCode != 200) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun headOk(url: String): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", MUSICBRAINZ_AGENT)
        }
        try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

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
            val duration = row.optLong("trackTimeMillis", 0L)

            return TrackInfo(art, album, year, isSingle, duration)
        } finally {
            conn.disconnect()
        }
    }
}
