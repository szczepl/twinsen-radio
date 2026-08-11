package net.mspanc.twinsenradio.data

/**
 * Jeden z adresow, pod ktorymi nadaje ta sama stacja.
 *
 * Rozglosnie czesto daja kilka: Radio Nowy Swiat wystawia MP3 256 kb/s,
 * MP3 128 i AAC+ 64. Katalog radio-browser trzyma je jako osobne pozycje
 * o tej samej nazwie - my scalamy je w jedna stacje z lista wariantow.
 */
data class StreamVariant(
    val url: String,
    val label: String,
    /** Przeplywnosc w kb/s; 0 gdy nieznana. Decyduje o wyborze domyslnym. */
    val kbps: Int = 0
)

/**
 * Jedna rozglosnia. [logo] to nazwa drawable'a wbudowanego w APK (moze byc null),
 * [logoUrl] to zdalna grafika z listy M3U albo z katalogu.
 *
 * [stream] to adres **efektywny** - ten, ktory faktycznie poleci do odtwarzacza.
 * Ustala go [StationRepository] na podstawie wyboru uzytkownika, a gdy wyboru
 * nie ma - biorac wariant o najwyzszej przeplywnosci.
 */
data class Station(
    val id: String,
    val name: String,
    val genre: String,
    val stream: String,
    /** Wszystkie znane adresy tej stacji. Nigdy pusta po przejsciu przez repozytorium. */
    val streams: List<StreamVariant> = emptyList(),
    val logo: String? = null,
    val logoUrl: String? = null,
    val source: Source = Source.BUILT_IN
) {
    enum class Source {
        BUILT_IN,
        USER_M3U,

        /** Dodana recznie z katalogu radio-browser.info, patrz [RadioBrowser]. */
        DISCOVERED
    }

    /** Identyfikator uzywany w drzewie przegladania Android Auto. */
    val mediaId: String get() = "$MEDIA_ID_PREFIX$id"

    /** Warianty do pokazania - gdy stacja ma jeden adres, robimy z niego pozycje. */
    fun variants(): List<StreamVariant> =
        streams.ifEmpty { listOf(StreamVariant(stream, DEFAULT_LABEL)) }

    companion object {
        const val MEDIA_ID_PREFIX = "st:"
        const val DEFAULT_LABEL = "Strumień stacji"

        fun idFromMediaId(mediaId: String): String? =
            if (mediaId.startsWith(MEDIA_ID_PREFIX)) mediaId.removePrefix(MEDIA_ID_PREFIX) else null
    }
}
