package net.mspanc.twinsenradio.data

/**
 * Porzadek listy stacji.
 *
 * [DEFAULT] to kolejnosc z pliku - ulozona tematycznie, wiec podobne stacje
 * stoja obok siebie. [MOST_PLAYED] liczy realne wlaczenia i po kilku tygodniach
 * jazdy jest zwykle najwygodniejszy: to, czego sluchasz, ladu na gorze samo.
 */
enum class StationSort(val label: String) {
    DEFAULT("Kolejność wbudowana"),
    MOST_PLAYED("Najczęściej słuchane"),
    NAME("Alfabetycznie"),
    GENRE("Według gatunku");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { DEFAULT }
    }
}

/**
 * Porzadek wynikow z katalogu.
 *
 * [POPULARITY] to kolejnosc, w ktorej katalog je oddaje - wedlug liczby glosow,
 * co niezle odsiewa pozycje martwe i przypadkowe.
 */
enum class DiscoverSort(val label: String) {
    POPULARITY("Popularność"),
    NAME("Alfabetycznie"),
    BITRATE("Jakość strumienia"),
    COUNTRY("Kraj");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { POPULARITY }
    }
}
