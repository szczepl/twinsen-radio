package net.mspanc.twinsenradio.playback

/**
 * Mapa "pole metadanych -> polska etykieta". W trybie diagnostycznym kazde pole
 * dostaje wlasnie taka wartosc, dzieki czemu po spojrzeniu na ekran AID / HU
 * widac od razu, ktore pole gdzie ladzi.
 *
 * Etykiety sa celowo krotkie - na wyswietlaczu miedzy zegarami w Passacie jest
 * malo miejsca i dluzsze napisy zostana przyciete wielokropkiem.
 */
object DiagnosticFields {

    data class Field(val api: String, val label: String)

    val TEXT: List<Field> = listOf(
        Field("title", "TYTUŁ"),
        Field("artist", "ARTYSTA"),
        Field("albumTitle", "ALBUM"),
        Field("albumArtist", "ART.ALBUMU"),
        Field("displayTitle", "TYT.WYŚW"),
        Field("subtitle", "PODTYTUŁ"),
        Field("description", "OPIS"),
        Field("station", "STACJA"),
        Field("genre", "GATUNEK"),
        Field("composer", "KOMPOZYTOR"),
        Field("writer", "AUTOR"),
        Field("conductor", "DYRYGENT"),
        Field("compilation", "SKŁADANKA")
    )

    /** Pola liczbowe dostaja rozpoznawalne, nieprzypadkowe wartosci. */
    const val TRACK_NUMBER = 11
    const val TOTAL_TRACKS = 22
    const val DISC_NUMBER = 3
    const val TOTAL_DISCS = 4
    const val RECORDING_YEAR = 1979
    const val RELEASE_YEAR = 1983

    val NUMERIC_LEGEND: List<String> = listOf(
        "trackNumber = $TRACK_NUMBER",
        "totalTrackCount = $TOTAL_TRACKS",
        "discNumber = $DISC_NUMBER",
        "totalDiscCount = $TOTAL_DISCS",
        "recordingYear = $RECORDING_YEAR",
        "releaseYear = $RELEASE_YEAR"
    )

    fun value(field: Field, withApiName: Boolean): String =
        if (withApiName) "${field.label}<${field.api}>" else field.label

    fun legend(withApiName: Boolean): String = buildString {
        TEXT.forEach { appendLine("${it.api} = ${value(it, withApiName)}") }
        NUMERIC_LEGEND.forEach { appendLine(it) }
    }
}
