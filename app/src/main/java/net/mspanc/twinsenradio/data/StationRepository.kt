package net.mspanc.twinsenradio.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Source of the station list: the built-in assets/stations.json plus any M3U
 * lists provided by the user in Options. Keeps the result in memory, because
 * Android Auto can query the browsing tree frequently.
 */
class StationRepository private constructor(private val appContext: Context) {

    private val prefs = Prefs(appContext)

    @Volatile
    private var cache: List<Station> = emptyList()

    @Volatile
    private var userLoadedFor: List<String> = listOf("<never>")

    /**
     * The last [all], until anything it is made from changes. Every screen asks
     * for stations all the time - the station on air by id, the one play would
     * start with - and each [byId] used to build the whole list afresh: parse
     * the fetched stations' JSON, resolve their names, sort by play counts.
     * Finding the station before play looked up ~20 recent ids that way, ~330 ms
     * a time, and the phone redraws on every change it hears about - ten times
     * on start, 3.8 s with the screen frozen (measured 30.10.2026: the app took
     * 4 s to open, the playback screen 3.5 s).
     */
    @Volatile
    private var memo: List<Station>? = null

    /** Bumped on every change, so a list built across one isn't kept. */
    @Volatile
    private var generation = 0

    // The list is made of settings - fetched stations, hidden ones, own names,
    // chosen streams, the sort and the play counts it may sort by - so any
    // change to them drops it. One SharedPreferences per process: this hears
    // changes made through every Prefs instance.
    private val dropMemo = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> forget() }

    init {
        prefs.registerListener(dropMemo)
    }

    private fun forget() {
        generation++
        memo = null
    }

    /** Built-in list - always available, doesn't require network access. */
    val builtIn: List<Station> by lazy { readBuiltIn() }

    /**
     * Full list: built-in + from M3U lists + stations added from the online catalog.
     *
     * Kept in [memo] until a setting it is made from changes - see there for
     * why it can't be built on every call.
     */
    fun all(): List<Station> {
        memo?.let { return it }
        val gen = generation
        if (cache.isEmpty()) cache = builtIn
        val extra = prefs.discovered
        val full = if (extra.isEmpty()) cache else cache + extra
        // We filter out hidden ones here, in one place - this way they disappear
        // everywhere at once: in the phone list, in search, and in the car's browsing tree.
        val hidden = prefs.hidden
        val visible = if (hidden.isEmpty()) full else full.filterNot { it.id in hidden }
        return sorted(visible.map(::resolve)).also { if (gen == generation) memo = it }
    }

    /**
     * Ordering of the list. By default, the order from the file - arranged
     * thematically and thought out - or, on request, alphabetically or by
     * what the user actually listens to.
     */
    private fun sorted(stations: List<Station>): List<Station> = when (prefs.stationSort) {
        StationSort.MOST_PLAYED ->
            stations.sortedWith(
                compareByDescending<Station> { prefs.playCount(it.id) }.thenBy { fold(it.name) }
            )
        StationSort.NAME -> stations.sortedBy { fold(it.name) }
        StationSort.GENRE -> stations.sortedWith(compareBy({ fold(it.genre) }, { fold(it.name) }))
        StationSort.DEFAULT -> stations
    }

    /** Full list including hidden stations - needed only for restoring them. */
    fun allIncludingHidden(): List<Station> {
        if (cache.isEmpty()) cache = builtIn
        return (cache + prefs.discovered).map(::resolve)
    }

    fun byId(id: String): Station? = all().firstOrNull { it.id == id }

    fun byMediaId(mediaId: String): Station? =
        Station.idFromMediaId(mediaId)?.let { byId(it) }

    fun favourites(): List<Station> {
        val fav = prefs.favourites
        return all().filter { it.id in fav }
    }

    fun recent(): List<Station> {
        val byId = all().associateBy { it.id }
        return prefs.recent.mapNotNull { byId[it] }
    }

    /**
     * The station play starts with when nothing is loaded: the most recently
     * listened one, then the first favourite, then the first on the list. One
     * answer for the service resuming in the car and for the phone's screen
     * before play, so the screen shows the station that will actually start.
     */
    fun resumeCandidate(): Station? =
        recent().firstOrNull() ?: favourites().firstOrNull() ?: all().firstOrNull()

    fun genres(): List<String> = all().map { it.genre }.distinct().sorted()

    fun byGenre(genre: String): List<Station> = all().filter { it.genre == genre }

    /**
     * Search that's resistant to Polish characters and case - in the car, the
     * query comes from speech recognition and rarely hits diacritics correctly.
     */
    fun search(query: String): List<Station> {
        val q = fold(query)
        if (q.isBlank()) return all()
        return all().filter { fold(it.name).contains(q) || fold(it.genre).contains(q) }
    }

    /** Fetches the user's M3U lists. Safe to call multiple times. */
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
        forget()
    }

    private fun readBuiltIn(): List<Station> = runCatching {
        val json = appContext.assets.open("stations.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // A station provides either a single "stream" or a list of "streams".
            // Both forms are valid - most stations have just one address.
            val variants = o.optJSONArray("streams")?.let { list ->
                (0 until list.length()).map { j ->
                    val v = list.getJSONObject(j)
                    StreamVariant(
                        url = v.getString("url"),
                        label = v.optString("label").ifBlank { Station.DEFAULT_LABEL },
                        kbps = v.optInt("kbps", 0)
                    )
                }
            }.orEmpty()

            Station(
                id = o.getString("id"),
                name = o.getString("name"),
                genre = o.optString("genre", "Inne"),
                stream = variants.firstOrNull()?.url ?: o.getString("stream"),
                streams = variants,
                // "logo" is a built-in resource (for logo_placeholder etc.), "logoUrl"
                // is the station's remote artwork - we don't distribute stations'
                // own logos in the repo, we just link to their servers.
                logo = o.optString("logo").ifBlank { null },
                logoUrl = o.optString("logoUrl").ifBlank { null },
                standbyArtUrl = o.optString("standbyArt").ifBlank { null },
                // The catalogue is Polish radio with a few Australian guests,
                // which say so; everything that doesn't is Polish.
                country = o.optString("country").ifBlank { "PL" },
                slashMayBeAlbum = o.optBoolean("slashMayBeAlbum", false)
            )
        }
    }.getOrElse {
        Log.e(TAG, "Nie udalo sie wczytac stations.json", it)
        emptyList()
    }

    /**
     * Replaces the address with the one the user actually wants to listen to.
     *
     * Order: a custom address entered manually, then the selected variant, and
     * if there was no selection - the variant with the highest bitrate. This
     * way the rest of the app (the player, Android Auto) still just sees
     * `station.stream` and doesn't need to know anything about variants.
     */
    private fun resolve(station: Station): Station {
        prefs.customStream(station.id)?.let { return station.copy(stream = it) }
        val variants = station.variants()
        val chosen = prefs.selectedStream(station.id)
        val variant = variants.firstOrNull { it.url == chosen }
            ?: variants.maxByOrNull { it.kbps }
            ?: return station
        return station.copy(stream = variant.url)
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
