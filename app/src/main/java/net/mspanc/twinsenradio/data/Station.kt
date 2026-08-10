package net.mspanc.twinsenradio.data

/**
 * Jedna rozglosnia. [logo] to nazwa drawable'a wbudowanego w APK (moze byc null),
 * [logoUrl] to zdalna grafika z listy M3U (atrybut tvg-logo).
 */
data class Station(
    val id: String,
    val name: String,
    val genre: String,
    val stream: String,
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

    companion object {
        const val MEDIA_ID_PREFIX = "st:"
        fun idFromMediaId(mediaId: String): String? =
            if (mediaId.startsWith(MEDIA_ID_PREFIX)) mediaId.removePrefix(MEDIA_ID_PREFIX) else null
    }
}
