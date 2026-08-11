package net.mspanc.twinsenradio.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Zrodlo listy stacji: wbudowany assets/stations.json plus dowolne listy M3U
 * podane przez uzytkownika w Opcjach. Trzyma wynik w pamieci, bo Android Auto
 * potrafi odpytywac drzewo przegladania czesto.
 */
class StationRepository private constructor(private val appContext: Context) {

    private val prefs = Prefs(appContext)

    @Volatile
    private var cache: List<Station> = emptyList()

    @Volatile
    private var userLoadedFor: List<String> = listOf("<never>")

    /** Wbudowana lista - zawsze dostepna, nie wymaga sieci. */
    val builtIn: List<Station> by lazy { readBuiltIn() }

    /**
     * Pelna lista: wbudowana + z list M3U + stacje dodane z katalogu w sieci.
     *
     * Dociagniete czytamy przy kazdym wywolaniu, a nie z `cache`: siedza w
     * strumieniu w [Prefs] i moga zmienic sie w dowolnym momencie, takze z
     * innego ekranu. Jest ich najwyzej kilkanascie, wiec nie ma czego optymalizowac.
     */
    fun all(): List<Station> {
        if (cache.isEmpty()) cache = builtIn
        val extra = prefs.discovered
        val full = if (extra.isEmpty()) cache else cache + extra
        // Ukryte pomijamy tu, w jednym miejscu - dzieki temu znikaja wszedzie
        // naraz: na liscie w telefonie, w wyszukiwaniu i w drzewie w aucie.
        val hidden = prefs.hidden
        return if (hidden.isEmpty()) full else full.filterNot { it.id in hidden }
    }

    /** Pelna lista razem z ukrytymi - potrzebna tylko do ich przywracania. */
    fun allIncludingHidden(): List<Station> {
        if (cache.isEmpty()) cache = builtIn
        return cache + prefs.discovered
    }

    fun byId(id: String): Station? = all().firstOrNull { it.id == id }

    fun byMediaId(mediaId: String): Station? =
        Station.idFromMediaId(mediaId)?.let { byId(it) }

    fun favourites(): List<Station> {
        val fav = prefs.favourites
        return all().filter { it.id in fav }
    }

    fun recent(): List<Station> {
        val order = prefs.recent
        return order.mapNotNull { byId(it) }
    }

    fun genres(): List<String> = all().map { it.genre }.distinct().sorted()

    fun byGenre(genre: String): List<Station> = all().filter { it.genre == genre }

    /**
     * Wyszukiwanie odporne na polskie znaki i wielkosc liter - w aucie zapytanie
     * przychodzi z rozpoznawania mowy i rzadko trafia w diakrytyki.
     */
    fun search(query: String): List<Station> {
        val q = fold(query)
        if (q.isBlank()) return all()
        return all().filter { fold(it.name).contains(q) || fold(it.genre).contains(q) }
    }

    /** Dociaga listy M3U uzytkownika. Bezpieczne do wolania wielokrotnie. */
    suspend fun refreshUserLists(force: Boolean = false) = withContext(Dispatchers.IO) {
        val urls = prefs.userM3uUrls
        if (!force && urls == userLoadedFor) return@withContext
        val extra = mutableListOf<Station>()
        urls.forEachIndexed { index, url ->
            runCatching { fetchText(url) }
                .onSuccess { body -> extra += M3uParser.parse(body, index.toString()) }
                .onFailure { Log.w(TAG, "Nie udalo sie pobrac listy $url: ${it.message}") }
        }
        cache = builtIn + extra
        userLoadedFor = urls
    }

    private fun readBuiltIn(): List<Station> = runCatching {
        val json = appContext.assets.open("stations.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Station(
                id = o.getString("id"),
                name = o.getString("name"),
                genre = o.optString("genre", "Inne"),
                stream = o.getString("stream"),
                logo = o.optString("logo").ifBlank { null }
            )
        }
    }.getOrElse {
        Log.e(TAG, "Nie udalo sie wczytac stations.json", it)
        emptyList()
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ł", "l")

    companion object {
        private const val TAG = "StationRepository"
        const val USER_AGENT = "TwinsenRadio/0.1 (Android)"

        @Volatile
        private var instance: StationRepository? = null

        fun get(context: Context): StationRepository =
            instance ?: synchronized(this) {
                instance ?: StationRepository(context.applicationContext).also { instance = it }
            }
    }
}
