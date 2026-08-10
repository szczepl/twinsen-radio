package net.mspanc.twinsenradio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Ustawienia aplikacji. Swiadomie na SharedPreferences - czyta z nich takze
 * usluga odtwarzania, a zapisy sa rzadkie.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE)

    /** Tryb, w ktorym kazde pole metadanych dostaje swoja polska nazwe zamiast wartosci. */
    var diagnosticMode: Boolean
        get() = sp.getBoolean(KEY_DIAG, true)
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

    var favourites: Set<String>
        get() = sp.getStringSet(KEY_FAV, emptySet()).orEmpty()
        set(v) = sp.edit { putStringSet(KEY_FAV, v) }

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
        const val KEY_DIAG = "diagnostic_mode"
        const val KEY_DIAG_API = "diagnostic_api_names"
        const val KEY_STRIP_ICY = "strip_icy_in_diagnostic"
        const val KEY_STYLE_BROWSABLE = "aa_style_browsable"
        const val KEY_STYLE_PLAYABLE = "aa_style_playable"
        const val KEY_BUFFER = "buffer_profile"
        const val KEY_ARTWORK = "artwork_mode"
        const val KEY_M3U = "user_m3u"
        const val KEY_FAV = "favourites"
        const val KEY_RECENT = "recent"
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
