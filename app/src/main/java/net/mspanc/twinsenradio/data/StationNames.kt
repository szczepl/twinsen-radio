package net.mspanc.twinsenradio.data

/**
 * Turns a radio-browser station name into something that belongs on a dashboard.
 *
 * Catalog entries are typed in by whoever added the station, and a fair number
 * of them carry the stream's technical description inside the name:
 * "Smooth FM 91.5 - Melbourne - 91.5 FM (AAC+ 320k)". That whole string is what
 * the Active Info Display shows whenever no track is playing, which is how a
 * bitrate came to be the most prominent thing on the instrument cluster.
 *
 * **The rule is deliberately timid.** Of the eight catalog stations on the test
 * phone on 10.09.2026, seven needed no change at all - and one of them,
 * "- 0 N - Smooth Jazz on Radio", is exactly the name that a confident rule
 * ("cut at the first dash") turns into nothing. So this removes only what is
 * unambiguously a description of the stream rather than a name, and leaves
 * alone everything a person might have meant. It stops at
 * "Smooth FM 91.5 - Melbourne" rather than guessing that a city is noise;
 * trimming further is a matter of taste, and taste is what renaming the station
 * by hand is for.
 */
object StationNames {

    /**
     * A trailing "(AAC+ 320k)", "[MP3 128 kbps]", "(64 kbit)" - the codec and
     * the bitrate, which describe the stream and not the station.
     */
    private val TECH_BRACKET = Regex(
        """\s*[(\[][^()\[\]]*""" +
            """(?:\d+\s*k(?:b(?:it|ps)?)?\b|\b(?:aac\+?|mp3|ogg|opus|flac)\b)""" +
            """[^()\[\]]*[)\]]\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A trailing " - 91.5 FM" or " - FM 105,5" - the frequency.
     *
     * A number is required on one side and a band word on the other, so that a
     * segment like " - FM Classics" (a name) or " - Melbourne" (a place) is left
     * where it is. Without the number this ate the second half of perfectly
     * good names.
     *
     * The exception is a segment that is *nothing but* a band word - " - DAB+",
     * " - FM". Nobody names half a station that, so there is no name to lose.
     */
    private val TECH_SEGMENT = Regex(
        """\s*[-–—|]\s*(?:""" +
            """(?:fm|am|dab\+?)\s*\d{2,3}(?:[.,]\d+)?""" +
            """|\d{2,3}(?:[.,]\d+)?\s*(?:fm|am|mhz|khz|dab\+?)""" +
            """|(?:fm|am|dab\+?|hd\d?)""" +
            """)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Separators and whitespace left dangling once something has been cut off. */
    private val LOOSE_EDGES = Regex("""^[\s\-–—|,]+|[\s\-–—|,]+$""")

    /**
     * The name as it should be displayed. Returns [raw] unchanged whenever it
     * has nothing safe to remove - which is the common case.
     */
    fun shorten(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return raw

        s = TECH_BRACKET.replace(s, "")
        // Catalog entries repeat the band: "... - Melbourne - 91.5 FM - FM 91.5".
        // Each pass takes one segment, and the loop is bounded so a pathological
        // name can't spin here.
        repeat(3) {
            val cut = TECH_SEGMENT.replace(s, "")
            if (cut == s) return@repeat
            s = cut
        }
        s = LOOSE_EDGES.replace(s, "")
        s = s.replace(Regex("""\s{2,}"""), " ")

        // Anything that shortened itself into nothing (or into a fragment too
        // small to identify a station) was not the kind of name this rule
        // understands. The original is always better than a stub.
        return if (s.length < 2) raw.trim() else s
    }
}
