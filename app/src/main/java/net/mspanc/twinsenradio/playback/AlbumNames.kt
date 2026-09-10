package net.mspanc.twinsenradio.playback

/**
 * Takes the catalogue's editorial decoration off a release name.
 *
 * The album is something **we** add - the station sends a track and an artist,
 * and the release name comes from the lookup. Measured on 10.09.2026 over 183
 * distinct description lines, that addition is where the length comes from:
 * "Moving to the Ghetto Oct. 31, 1940" is 34 characters and we glue
 * " · The Pianist (Original Motion Picture Soundtrack) [2002]" - another 58 -
 * behind it. The head unit then truncates the result at a width we don't know
 * and can't control.
 *
 * What comes off is only a **trailing** bracket that says which pressing this
 * is: "(Original Motion Picture Soundtrack)", "(Deluxe Edition)",
 * "[Remastered]", "(1984 Version)". None of that identifies a different record,
 * and none of it is worth a line on a dashboard.
 *
 * What stays: everything else, including a leading bracket - "(What's the
 * Story) Morning Glory?" is a name, not a note about a pressing - and words
 * like *Live* or *Acoustic*, which say what the recording actually is.
 *
 * The track title is left alone entirely. It is what the station sent, and
 * "(feat. ...)" there is information the broadcaster chose to give.
 */
object AlbumNames {

    /**
     * Words that mark a bracket as a note about the pressing rather than part
     * of the name. Deliberately narrow - see the class comment for what is
     * kept out of this list on purpose.
     */
    private const val DECOR =
        """soundtrack|motion picture|deluxe|expanded|remaster(?:ed)?|reissue""" +
            """|anniversary|bonus track|edition|\d{2,4}\s*version"""

    /** A trailing "(...)" or "[...]" whose contents are one of the above. */
    private val TRAILING_DECOR = Regex(
        """\s*[(\[][^()\[\]]*(?:$DECOR)[^()\[\]]*[)\]]\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The release name as it should be shown. Returns [raw] unchanged when it
     * has nothing to drop, which is the common case - only 22 of the 183
     * measured lines carried such a bracket at all.
     */
    fun shorten(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return raw

        // Catalogues stack them: "... (Original Motion Picture Soundtrack) [Remastered]".
        // Bounded so a pathological name cannot spin here.
        repeat(3) {
            val cut = TRAILING_DECOR.replace(s, "").trim()
            if (cut == s) return@repeat
            s = cut
        }

        // A name made only of decoration was not the kind of name this rule
        // understands, and a stub is worse than the original.
        return if (s.length < 2) raw.trim() else s
    }
}
