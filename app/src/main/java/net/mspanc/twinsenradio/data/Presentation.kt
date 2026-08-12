package net.mspanc.twinsenradio.data

/**
 * What should appear in each line of the description on the dashboard.
 *
 * The line breakdown comes directly from measurements taken in the Passat
 * (2026-08-11, see FINDINGS.md). The Active Info Display reads three metadata
 * fields:
 *
 *   top line      <- subtitle
 *   middle        <- description
 *   bottom        <- displayTitle
 *
 * The Android Auto central screen reads the same fields, except it only
 * shows two of them: the large line is displayTitle, the small one is
 * subtitle. It doesn't show the description at all.
 *
 * The conclusion that governs this whole class: **AID cannot be controlled
 * independently of the central screen**. Whatever we put in the top or
 * bottom line will appear in both places. So a clock in a text line will
 * also be visible on the central screen, and this is a deliberate
 * trade-off, not a bug. The middle line is the only one AID has exclusively.
 */
enum class LineContent(val label: String) {
    TITLE("Tytuł utworu"),
    ARTIST("Wykonawca"),
    ARTIST_ALBUM("Wykonawca · płyta"),
    TITLE_ALBUM("Tytuł · płyta"),
    TRACK_FULL("Wykonawca — tytuł"),
    STATION("Nazwa stacji"),
    CLOCK("Zegar"),
    EMPTY("Puste");

    companion object {
        /**
         * Labels for the picker. [ARTIST_ALBUM] and [TITLE_ALBUM] append the year
         * whenever the catalog knows it and the "add year" setting is on - the
         * suffix here just reflects that current setting, so the list doesn't
         * silently promise a year it won't show.
         */
        fun labels(includeYear: Boolean): List<String> = entries.map {
            when (it) {
                ARTIST_ALBUM, TITLE_ALBUM -> it.label + if (includeYear) " (z rokiem)" else " (bez roku)"
                else -> it.label
            }
        }
        fun at(index: Int) = entries.getOrElse(index) { TITLE }
    }
}

/**
 * Which line on the dashboard. Order as on the AID, from the top.
 */
enum class Line(val label: String, val hint: String) {
    TOP(
        "Wiersz 1 — górny",
        "Na AID linia górna. Na ekranie centralnym mała linia pod tytułem."
    ),
    MIDDLE(
        "Wiersz 2 — środkowy",
        "Widoczny wyłącznie na AID. ReplaIO wstawia tu nazwę stacji, aplikacja RNŚ zostawia pusty."
    ),
    BOTTOM(
        "Wiersz 3 — dolny",
        "Na AID linia pogrubiona. Na ekranie centralnym duża linia."
    )
}

/** Whether a clock replaces the artwork, and if so, in what form. */
enum class ClockFace(val label: String) {
    NONE("Okładka płyty / logo stacji"),
    DIGITAL("Zegar cyfrowy"),
    ANALOG("Zegar analogowy");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { NONE }
    }
}

/** Color scheme for the clock drawn in place of the artwork. */
object ClockColors {

    val BACKGROUNDS: List<Pair<String, Int>> = listOf(
        "Czarne" to 0xFF000000.toInt(),
        "Granatowe (motyw aplikacji)" to 0xFF0B3D91.toInt(),
        "Ciemnoszare" to 0xFF202124.toInt(),
        "Białe" to 0xFFFFFFFF.toInt()
    )

    /** null means automatic selection, contrasting with the background. */
    val FOREGROUNDS: List<Pair<String, Int?>> = listOf(
        "Auto — kontrastowo do tła" to null,
        "Białe" to 0xFFFFFFFF.toInt(),
        "Czarne" to 0xFF000000.toInt(),
        "Bursztynowe" to 0xFFF2A900.toInt()
    )

    fun background(index: Int) = BACKGROUNDS.getOrElse(index) { BACKGROUNDS[0] }.second

    /**
     * Color of the digits and hands. For "auto", we compute the background's
     * brightness using the luminance formula and pick black or white - the
     * same thing every sensible theming system does, avoiding illegible
     * white-on-white.
     */
    fun foreground(index: Int, backgroundColor: Int): Int {
        FOREGROUNDS.getOrElse(index) { FOREGROUNDS[0] }.second?.let { return it }
        val r = (backgroundColor shr 16 and 0xFF) / 255.0
        val g = (backgroundColor shr 8 and 0xFF) / 255.0
        val b = (backgroundColor and 0xFF) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    val BACKGROUND_LABELS get() = BACKGROUNDS.map { it.first }
    val FOREGROUND_LABELS get() = FOREGROUNDS.map { it.first }
}

/**
 * Complete set of description settings: what goes in which line, and whether
 * a clock should replace the artwork.
 */
data class Presentation(
    val top: LineContent,
    val middle: LineContent,
    val bottom: LineContent,
    val clockFace: ClockFace
) {
    fun contentFor(line: Line): LineContent = when (line) {
        Line.TOP -> top
        Line.MIDDLE -> middle
        Line.BOTTOM -> bottom
    }

    /** Whether the layout needs refreshing every minute at all. */
    val needsClock: Boolean
        get() = clockFace != ClockFace.NONE ||
            top == LineContent.CLOCK ||
            middle == LineContent.CLOCK ||
            bottom == LineContent.CLOCK

    companion object {
        /**
         * Default: artist with album on top, station name in the middle, title
         * at the bottom. The middle line gets the station name, because it's
         * the only spot visible exclusively on the AID - putting anything
         * there that already appears in the other lines would waste the one
         * free line.
         */
        val DEFAULT = Presentation(
            top = LineContent.ARTIST_ALBUM,
            middle = LineContent.STATION,
            bottom = LineContent.TITLE,
            clockFace = ClockFace.NONE
        )
    }
}
