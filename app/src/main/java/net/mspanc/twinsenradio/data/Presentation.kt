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
    // The order of these is frozen. A choice is stored as an ordinal, so
    // moving an entry silently rewrites the layout of anybody who upgrades -
    // new entries go on the end, never in the middle.
    TITLE("Tytuł utworu"),
    ARTIST("Wykonawca"),
    ARTIST_ALBUM("Wykonawca · płyta"),
    TITLE_ALBUM("Tytuł · płyta"),
    TRACK_FULL("Wykonawca — tytuł"),
    STATION("Nazwa stacji"),
    CLOCK("Godzina"),
    EMPTY("Puste"),
    SLOGAN("Slogan stacji"),
    DATE("Data"),
    CLOCK_DATE("Godzina · data");

    companion object {
        /**
         * What can go on a line while a track is playing.
         *
         * [SLOGAN] is missing on purpose: the station's own announcement belongs
         * to the gap between tracks, and the last one we heard put next to a
         * playing song would be stale by definition.
         */
        val WHEN_PLAYING = listOf(
            TITLE, ARTIST, ARTIST_ALBUM, TITLE_ALBUM, TRACK_FULL,
            STATION, CLOCK, DATE, CLOCK_DATE, EMPTY
        )

        /**
         * What can go on a line when no track is playing.
         *
         * Everything that names a track is gone - there is none, and offering
         * the choice would only promise an empty line. What remains is what the
         * app still knows at that moment.
         */
        val WHEN_IDLE = listOf(CLOCK, DATE, CLOCK_DATE, STATION, SLOGAN, EMPTY)

        /**
         * Labels for a picker over [choices]. [ARTIST_ALBUM] and [TITLE_ALBUM]
         * append the year whenever the catalog knows it and the "add year"
         * setting is on - the suffix here just reflects that current setting, so
         * the list doesn't silently promise a year it won't show.
         */
        fun labels(choices: List<LineContent>, includeYear: Boolean): List<String> = choices.map {
            when (it) {
                ARTIST_ALBUM, TITLE_ALBUM -> it.label + if (includeYear) " (z rokiem)" else " (bez roku)"
                else -> it.label
            }
        }

        fun at(index: Int) = entries.getOrElse(index) { TITLE }
    }
}

/**
 * What goes on the three lines, in one of the two states the player can be in.
 *
 * There are two of these because the two states have nothing in common: with a
 * track playing the lines are about the track, and between tracks there is no
 * track to be about. Before this split, a line set for "Tytuł utworu" simply
 * went blank in the gaps, and the only way to get a station name there was an
 * automatic fallback hidden inside the clock option - which meant the layout
 * between tracks was something you inherited rather than chose.
 */
data class LineSet(
    val top: LineContent,
    val middle: LineContent,
    val bottom: LineContent
) {
    fun contentFor(line: Line): LineContent = when (line) {
        Line.TOP -> top
        Line.MIDDLE -> middle
        Line.BOTTOM -> bottom
    }

    fun all(): List<LineContent> = listOf(top, middle, bottom)
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
    /** Lines used while a track is playing. */
    val playing: LineSet,
    /** Lines used when it isn't - an ad, the station talking, or silence. */
    val idle: LineSet,
    val clockFace: ClockFace
) {
    /**
     * @param trackPlaying whether a real track is on air right now - an ad or a
     *   station announcing itself counts as no track, because neither has a
     *   title or an artist to put on a line.
     */
    fun setFor(trackPlaying: Boolean): LineSet = if (trackPlaying) playing else idle

    /**
     * Whether the layout needs refreshing every minute at all.
     *
     * The date is in here too. It changes once a day rather than once a minute,
     * but there is no separate tick for it, and a line reading yesterday until
     * the next track is worse than a redundant refresh.
     */
    val needsClock: Boolean
        get() = clockFace != ClockFace.NONE ||
            (playing.all() + idle.all()).any {
                it == LineContent.CLOCK || it == LineContent.DATE || it == LineContent.CLOCK_DATE
            }

    companion object {
        /**
         * Default while a track plays: artist with album on top, station name in
         * the middle, title at the bottom. The middle line gets the station
         * name, because it's the only spot visible exclusively on the AID -
         * putting anything there that already appears in the other lines would
         * waste the one free line.
         */
        val DEFAULT_PLAYING = LineSet(
            top = LineContent.ARTIST_ALBUM,
            middle = LineContent.STATION,
            bottom = LineContent.TITLE
        )

        /**
         * Default between tracks. Chosen to say something useful rather than to
         * reproduce what the old single-set layout happened to produce: the time
         * where the artist was, the station where it already was, and whatever
         * the station is saying for itself on the line the AID prints boldest.
         */
        val DEFAULT_IDLE = LineSet(
            top = LineContent.CLOCK,
            middle = LineContent.STATION,
            bottom = LineContent.SLOGAN
        )

        val DEFAULT = Presentation(
            playing = DEFAULT_PLAYING,
            idle = DEFAULT_IDLE,
            clockFace = ClockFace.NONE
        )
    }
}
