package net.mspanc.twinsenradio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ustawienia aplikacji. Swiadomie na SharedPreferences - czyta z nich takze
 * usluga odtwarzania, a zapisy sa rzadkie.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE)

    /**
     * Tryb, w ktorym kazde pole metadanych dostaje swoja polska nazwe zamiast
     * wartosci.
     *
     * Domyslnie WYLACZONY. Byl wlaczony dopoki nie wiedzielismy, ktore pola
     * rysuje deska - pomiar zrobiony 2026-08-11 (BADANIA.md), wiec od tej pory
     * jest to narzedzie do kolejnych eksperymentow, a nie stan wyjsciowy.
     */
    var diagnosticMode: Boolean
        get() = sp.getBoolean(KEY_DIAG, false)
        set(v) = sp.edit { putBoolean(KEY_DIAG, v) }

    /**
     * Czy w trybie diagnostycznym odcinac metadane ICY.
     *
     * Domyslnie NIE. Odcinanie daje pelna determinizm etykiet, ale odbiera
     * mozliwosc podgladania, co i jak czesto nadaje rozglosnia - a to jest
     * osobny, ciekawy watek. ICY nadpisuje tylko `title`, `station` i `genre`;
     * pola, ktore Android Auto faktycznie pokazuje (`displayTitle`, `subtitle`),
     * pozostaja nasze.
     */
    var stripIcyInDiagnostic: Boolean
        get() = sp.getBoolean(KEY_STRIP_ICY, false)
        set(v) = sp.edit { putBoolean(KEY_STRIP_ICY, v) }

    /** Czy do polskiej etykiety dokleic nazwe pola z API, np. "TYTUL<title>". */
    var diagnosticShowApiName: Boolean
        get() = sp.getBoolean(KEY_DIAG_API, false)
        set(v) = sp.edit { putBoolean(KEY_DIAG_API, v) }

    /** Schemat prezentacji folderow w Android Auto (patrz [ContentStyle]). */
    var browsableStyle: Int
        get() = sp.getInt(KEY_STYLE_BROWSABLE, ContentStyle.CATEGORY_LIST)
        set(v) = sp.edit { putInt(KEY_STYLE_BROWSABLE, v) }

    /** Schemat prezentacji stacji w Android Auto. */
    var playableStyle: Int
        get() = sp.getInt(KEY_STYLE_PLAYABLE, ContentStyle.LIST)
        set(v) = sp.edit { putInt(KEY_STYLE_PLAYABLE, v) }

    /**
     * Tresc kolejnych wierszy opisu. Numeracja jak na AID, od gory - patrz
     * komentarz w [Presentation], tam jest wyjasnione, ktory wiersz idzie
     * w ktore pole metadanych i dlaczego nie da sie ich rozdzielic miedzy AID
     * a ekran centralny.
     */
    var lineTop: Int
        get() = sp.getInt(KEY_LINE_TOP, Presentation.DEFAULT.top.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_TOP, v) }

    var lineMiddle: Int
        get() = sp.getInt(KEY_LINE_MIDDLE, Presentation.DEFAULT.middle.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_MIDDLE, v) }

    var lineBottom: Int
        get() = sp.getInt(KEY_LINE_BOTTOM, Presentation.DEFAULT.bottom.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_BOTTOM, v) }

    /** Czy zamiast okladki rysowac zegar, patrz [ClockFace]. */
    var clockFace: Int
        get() = sp.getInt(KEY_CLOCK_FACE, Presentation.DEFAULT.clockFace.ordinal)
        set(v) = sp.edit { putInt(KEY_CLOCK_FACE, v) }

    /** Komplet ustawien opisu, zlozony z powyzszych. */
    val presentation: Presentation
        get() = Presentation(
            top = LineContent.at(lineTop),
            middle = LineContent.at(lineMiddle),
            bottom = LineContent.at(lineBottom),
            clockFace = ClockFace.at(clockFace)
        )

    /**
     * Przy zegarze zamiast okladki: czy ma byc widoczny zawsze (true), czy tylko
     * wtedy, gdy i tak pokazalibysmy logo stacji, bo okladki nie znaleziono.
     */
    var clockCoverAlways: Boolean
        get() = sp.getBoolean(KEY_CLOCK_ALWAYS, false)
        set(v) = sp.edit { putBoolean(KEY_CLOCK_ALWAYS, v) }

    /** Kolor tla zegara rysowanego zamiast okladki. */
    var clockBackground: Int
        get() = sp.getInt(KEY_CLOCK_BG, 0)
        set(v) = sp.edit { putInt(KEY_CLOCK_BG, v) }

    /** Kolor cyfr i wskazowek zegara; indeks 0 to dobor automatyczny. */
    var clockForeground: Int
        get() = sp.getInt(KEY_CLOCK_FG, 0)
        set(v) = sp.edit { putInt(KEY_CLOCK_FG, v) }

    /** Czy uzupelniac linie wykonawcy o wydawnictwo i rok z katalogu iTunes. */
    var enrichWithAlbum: Boolean
        get() = sp.getBoolean(KEY_ENRICH_ALBUM, true)
        set(v) = sp.edit { putBoolean(KEY_ENRICH_ALBUM, v) }

    /** Indeks profilu bufora, patrz [BufferProfile.ALL]. */
    var bufferProfile: Int
        get() = sp.getInt(KEY_BUFFER, 1)
        set(v) = sp.edit { putInt(KEY_BUFFER, v) }

    /** Sposob dostarczania okladki do Android Auto, patrz [ArtworkMode]. */
    var artworkMode: Int
        get() = sp.getInt(KEY_ARTWORK, ArtworkMode.RESOURCE_URI)
        set(v) = sp.edit { putInt(KEY_ARTWORK, v) }

    var userM3uUrls: List<String>
        get() = sp.getString(KEY_M3U, "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        set(v) = sp.edit { putString(KEY_M3U, v.joinToString("\n")) }

    init {
        // Jedno zrodlo prawdy dla wszystkich ekranow, zasilone przy pierwszym uzyciu
        synchronized(FAV_LOCK) {
            if (!favouritesSeeded) {
                _favourites.value = sp.getStringSet(KEY_FAV, emptySet()).orEmpty()
                favouritesSeeded = true
            }
        }
        synchronized(DISCOVERED_LOCK) {
            if (!discoveredSeeded) {
                _discovered.value = readDiscovered()
                discoveredSeeded = true
            }
        }
        synchronized(HIDDEN_LOCK) {
            if (!hiddenSeeded) {
                _hidden.value = sp.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
                hiddenSeeded = true
            }
        }
    }

    var favourites: Set<String>
        get() = _favourites.value
        set(v) {
            sp.edit { putStringSet(KEY_FAV, v) }
            _favourites.value = v
        }

    /** @return true jesli stacja zostala dodana do ulubionych, false jesli usunieta. */
    fun toggleFavourite(stationId: String): Boolean {
        val cur = favourites.toMutableSet()
        val added = if (cur.contains(stationId)) {
            cur.remove(stationId)
            false
        } else {
            cur.add(stationId)
            true
        }
        favourites = cur
        return added
    }

    /**
     * Stacje dodane recznie z katalogu w sieci. Trzymamy komplet danych, a nie
     * sam identyfikator: katalog moze przestac odpowiadac albo usunac pozycje,
     * a stacja raz dodana ma dzialac w aucie takze bez zasiegu do katalogu.
     */
    val discovered: List<Station> get() = _discovered.value

    fun addDiscovered(station: Station) {
        if (discovered.any { it.id == station.id }) return
        saveDiscovered(discovered + station)
    }

    fun removeDiscovered(stationId: String) {
        saveDiscovered(discovered.filterNot { it.id == stationId })
    }

    fun isDiscovered(stationId: String): Boolean = discovered.any { it.id == stationId }

    private fun saveDiscovered(list: List<Station>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("genre", s.genre)
                    .put("stream", s.stream)
                    .put("logoUrl", s.logoUrl ?: "")
            )
        }
        sp.edit { putString(KEY_DISCOVERED, arr.toString()) }
        _discovered.value = list
    }

    private fun readDiscovered(): List<Station> = runCatching {
        val raw = sp.getString(KEY_DISCOVERED, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Station(
                id = o.getString("id"),
                name = o.getString("name"),
                genre = o.optString("genre", "Z sieci"),
                stream = o.getString("stream"),
                logoUrl = o.optString("logoUrl").ifBlank { null },
                source = Station.Source.DISCOVERED
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Historie wyszukiwania - osobne dla listy wlasnej i dla katalogu w sieci,
     * bo to dwa rozne swiaty: tu szuka sie "rmf", tam "jazz radio paris".
     * Najnowsze na poczatku, bez powtorzen.
     */
    var localSearchHistory: List<String>
        get() = readHistory(KEY_HISTORY_LOCAL)
        set(v) = writeHistory(KEY_HISTORY_LOCAL, v)

    var webSearchHistory: List<String>
        get() = readHistory(KEY_HISTORY_WEB)
        set(v) = writeHistory(KEY_HISTORY_WEB, v)

    fun pushLocalSearch(query: String) = pushHistory(KEY_HISTORY_LOCAL, query)

    fun pushWebSearch(query: String) = pushHistory(KEY_HISTORY_WEB, query)

    private fun pushHistory(key: String, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val current = readHistory(key).filterNot { it.equals(q, ignoreCase = true) }
        writeHistory(key, listOf(q) + current)
    }

    private fun readHistory(key: String): List<String> =
        sp.getString(key, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun writeHistory(key: String, values: List<String>) =
        sp.edit { putString(key, values.take(HISTORY_LIMIT).joinToString("\n")) }

    /**
     * Stacje wbudowane, ktore uzytkownik usunal z listy.
     *
     * Wbudowanych nie da sie skasowac - siedza w assets - wiec zamiast tego
     * trzymamy zbior ukrytych i pomijamy je przy budowaniu listy. Dzieki temu
     * usuniecie jest odwracalne, a plik z lista zostaje nietkniety.
     */
    val hidden: Set<String> get() = _hidden.value

    fun hide(stationId: String) {
        saveHidden(hidden + stationId)
    }

    fun unhide(stationId: String) {
        saveHidden(hidden - stationId)
    }

    fun restoreAllHidden() = saveHidden(emptySet())

    fun isHidden(stationId: String) = stationId in hidden

    private fun saveHidden(value: Set<String>) {
        sp.edit { putStringSet(KEY_HIDDEN, value) }
        _hidden.value = value
    }

    /** Ostatnio sluchane, najnowsze na poczatku. */
    var recent: List<String>
        get() = sp.getString(KEY_RECENT, "").orEmpty().lines().filter { it.isNotBlank() }
        set(v) = sp.edit { putString(KEY_RECENT, v.take(20).joinToString("\n")) }

    fun pushRecent(stationId: String) {
        recent = listOf(stationId) + recent.filter { it != stationId }
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        /**
         * Ulubione jako strumien, a nie odpytywanie preferencji przy okazji.
         *
         * Wczesniej kazdy ekran czytal je we wlasnym momencie - lista przy
         * wchodzeniu na wierzch, ekran odtwarzania przy renderowaniu, Android Auto
         * przy budowaniu przyciskow - i stany rozjezdzaly sie miedzy soba.
         * Teraz jest jedno zrodlo prawdy, ktore oglasza zmiane, a wszyscy
         * zainteresowani ja obserwuja.
         */
        private val _favourites = MutableStateFlow<Set<String>>(emptySet())
        val favouritesFlow: StateFlow<Set<String>> = _favourites

        private var favouritesSeeded = false
        private val FAV_LOCK = Any()

        /**
         * Stacje dociagniete z katalogu - tak samo jak ulubione, jedno zrodlo
         * prawdy ze strumieniem zmian, zeby lista na telefonie i drzewo w aucie
         * przebudowaly sie w tej samej chwili.
         */
        private val _discovered = MutableStateFlow<List<Station>>(emptyList())
        val discoveredFlow: StateFlow<List<Station>> = _discovered

        private var discoveredSeeded = false
        private val DISCOVERED_LOCK = Any()

        /** Ukryte stacje wbudowane - tak samo obserwowalne jak ulubione. */
        private val _hidden = MutableStateFlow<Set<String>>(emptySet())
        val hiddenFlow: StateFlow<Set<String>> = _hidden

        private var hiddenSeeded = false
        private val HIDDEN_LOCK = Any()

        const val KEY_DIAG = "diagnostic_mode"
        const val KEY_DIAG_API = "diagnostic_api_names"
        const val KEY_STRIP_ICY = "strip_icy_in_diagnostic"
        const val KEY_LINE_TOP = "line_top"
        const val KEY_LINE_MIDDLE = "line_middle"
        const val KEY_LINE_BOTTOM = "line_bottom"
        const val KEY_CLOCK_FACE = "clock_face"
        const val KEY_CLOCK_ALWAYS = "clock_cover_always"
        const val KEY_CLOCK_BG = "clock_background"
        const val KEY_CLOCK_FG = "clock_foreground"
        const val KEY_ENRICH_ALBUM = "enrich_with_album"
        const val KEY_SWAP = "swap_title_artist"
        const val KEY_STYLE_BROWSABLE = "aa_style_browsable"
        const val KEY_STYLE_PLAYABLE = "aa_style_playable"
        const val KEY_BUFFER = "buffer_profile"
        const val KEY_ARTWORK = "artwork_mode"
        const val KEY_M3U = "user_m3u"
        const val KEY_FAV = "favourites"
        const val KEY_RECENT = "recent"
        const val KEY_DISCOVERED = "discovered_stations"
        const val KEY_HIDDEN = "hidden_stations"
        const val KEY_HISTORY_LOCAL = "search_history_local"
        const val KEY_HISTORY_WEB = "search_history_web"

        /** Tyle wpisow wystarczy - dluzsza lista i tak nie miesci sie na ekranie. */
        private const val HISTORY_LIMIT = 10
    }
}

/**
 * Cztery schematy prezentacji, ktore Android Auto faktycznie udostepnia aplikacjom
 * medialnym (klucze CONTENT_STYLE_* w rozszerzeniach MediaBrowser).
 */
object ContentStyle {
    const val LIST = 1
    const val GRID = 2
    const val CATEGORY_LIST = 3
    const val CATEGORY_GRID = 4

    const val EXTRA_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val EXTRA_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    val LABELS = listOf(
        "Lista (LIST)",
        "Siatka (GRID)",
        "Lista kategorii (CATEGORY_LIST)",
        "Siatka kategorii (CATEGORY_GRID)"
    )

    fun indexToValue(i: Int) = i + 1
    fun valueToIndex(v: Int) = (v - 1).coerceIn(0, 3)
}

object ArtworkMode {
    /** android.resource:// - Android Auto pobiera logo wprost z zasobow APK. */
    const val RESOURCE_URI = 0
    /** Bajty PNG w polu artworkData - dziala nawet gdy HU nie umie w URI. */
    const val EMBEDDED_BYTES = 1
    /** Brak okladki - do sprawdzenia, co AID pokazuje bez grafiki. */
    const val NONE = 2

    val LABELS = listOf(
        "URI zasobu (android.resource://)",
        "Bajty w metadanych (artworkData)",
        "Bez okładki"
    )
}

/** Profile buforowania. Wartosci w ms. */
data class BufferProfile(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val forPlaybackMs: Int,
    val afterRebufferMs: Int
) {
    companion object {
        val ALL = listOf(
            BufferProfile("Mały — start ~1 s, zapas 20 s", 20_000, 30_000, 1_000, 3_000),
            BufferProfile("Średni — start ~2 s, zapas 45 s (domyślny)", 45_000, 75_000, 2_000, 6_000),
            BufferProfile("Duży — start ~4 s, zapas 120 s", 120_000, 180_000, 4_000, 12_000)
        )

        fun at(index: Int) = ALL.getOrElse(index) { ALL[1] }
    }
}
