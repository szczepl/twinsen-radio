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
     * The shortest form that still names the station - for the second half of a
     * line, where the first half is what was actually asked for.
     *
     * [shorten] is deliberately timid because its result is the station's name
     * everywhere: on the list, in the browse tree, on the playback screen. Here
     * the job is different. The line already carries something else, the head
     * unit will cut whatever doesn't fit, and a name that eats the line is worse
     * than a name missing its city: "Smooth FM 91.5 - Melbourne" alongside a
     * title leaves no title.
     *
     * So this takes the first segment and nothing more - "Smooth FM 91.5". A
     * name with no segments to drop comes back as [shorten] left it.
     */
    fun shortest(raw: String): String {
        val short = shorten(raw)
        val head = short.split(SEGMENT, limit = 2).first().trim()
        return if (namesSomething(head)) head else short
    }

    /**
     * Whether a fragment could be read as a station's name.
     *
     * Length alone doesn't decide it: "RMF" and "ZET" are three characters and
     * perfectly real, while the first segment of "- 0 N - Smooth Jazz on Radio"
     * is "0 N" and names nothing. Two letters is what separates them.
     */
    private fun namesSomething(head: String): Boolean =
        head.count { it.isLetter() } >= 2 && head.count { it.isLetterOrDigit() } >= 3

    /** Where a catalogue name breaks into "name - place - band". */
    private val SEGMENT = Regex("""\s+[-–—|]\s+""")

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
