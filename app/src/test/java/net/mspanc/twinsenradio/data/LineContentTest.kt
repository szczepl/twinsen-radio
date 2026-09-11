package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineContentTest {

    /**
     * A line choice is stored as an ordinal, so this order is part of the saved
     * settings rather than an implementation detail. Moving an entry - or
     * inserting one anywhere but the end - silently rewrites the layout of
     * everybody who upgrades. That failure is invisible in review and obvious
     * only in the car, which is why it gets a test.
     */
    @Test
    fun `the stored order never moves`() {
        assertEquals(
            listOf(
                "TITLE", "ARTIST", "ARTIST_ALBUM", "TITLE_ALBUM", "TRACK_FULL",
                "STATION", "CLOCK", "EMPTY", "SLOGAN", "DATE", "CLOCK_DATE", "ALBUM"
            ),
            LineContent.entries.map { it.name }
        )
    }

    @Test
    fun `nothing that names a track is offered between tracks`() {
        val trackShaped = listOf(
            LineContent.TITLE, LineContent.ARTIST, LineContent.ARTIST_ALBUM,
            LineContent.TITLE_ALBUM, LineContent.TRACK_FULL
        )
        trackShaped.forEach { assertFalse("$it", it in LineContent.WHEN_IDLE) }
    }

    /**
     * The station's own words are what fills the gap between tracks, and next to
     * a playing track they would be whatever was said before it - stale by
     * definition.
     */
    @Test
    fun `the slogan is offered only between tracks`() {
        assertTrue(LineContent.SLOGAN in LineContent.WHEN_IDLE)
        assertFalse(LineContent.SLOGAN in LineContent.WHEN_PLAYING)
    }

    /**
     * The two together are what "Godzina · data" used to be - see
     * [RetiredCompositeTest]. Pairing them is now the user's job, which is why
     * both have to be on both lists.
     */
    @Test
    fun `both lists offer the time, the date and an empty line`() {
        listOf(LineContent.CLOCK, LineContent.DATE, LineContent.EMPTY)
            .forEach {
                assertTrue("$it gra", it in LineContent.WHEN_PLAYING)
                assertTrue("$it bez utworu", it in LineContent.WHEN_IDLE)
            }
    }

    /** One label per entry, in the order the picker will show them. */
    @Test
    fun `labels follow the list they are asked for`() {
        val labels = LineContent.labels(LineContent.WHEN_IDLE, includeYear = false)
        assertEquals(LineContent.WHEN_IDLE.size, labels.size)
        assertEquals(LineContent.CLOCK.label, labels.first())
    }

    /**
     * The year suffix is a promise about the album, and there is exactly one
     * entry that carries an album now - it used to be spread across two
     * pre-joined pairs.
     */
    @Test
    fun `only the album mentions the year`() {
        val withYear = LineContent.labels(LineContent.WHEN_PLAYING, includeYear = true)
        val withoutYear = LineContent.labels(LineContent.WHEN_PLAYING, includeYear = false)
        val differing = withYear.zip(withoutYear).count { (a, b) -> a != b }
        assertEquals(1, differing)
        assertTrue(withYear.any { it.startsWith(LineContent.ALBUM.label) })
    }

    @Test
    fun `an unknown stored value does not crash`() {
        assertEquals(LineContent.TITLE, LineContent.at(999))
        assertEquals(LineContent.TITLE, LineContent.at(-1))
    }
}

/**
 * The picture slot. Like the lines, it is stored as an ordinal and chosen per
 * state, so it carries the same hazard and gets the same guard.
 */
class ArtContentTest {

    @Test
    fun `the stored order never moves`() {
        assertEquals(
            listOf("COVER", "LOGO", "CLOCK_DIGITAL", "CLOCK_ANALOG", "EMPTY"),
            ArtContent.entries.map { it.name }
        )
    }

    /** Every state can be told to show nothing at all. */
    @Test
    fun `an empty picture is always on offer`() {
        assertTrue(ArtContent.EMPTY in ArtContent.entries)
        assertEquals(ArtContent.entries.size, ArtContent.LABELS.size)
    }

    @Test
    fun `only the clocks resolve to a face`() {
        assertEquals(ClockFace.DIGITAL, ArtContent.CLOCK_DIGITAL.clockFace)
        assertEquals(ClockFace.ANALOG, ArtContent.CLOCK_ANALOG.clockFace)
        listOf(ArtContent.COVER, ArtContent.LOGO, ArtContent.EMPTY)
            .forEach { assertNull("$it", it.clockFace) }
    }

    @Test
    fun `an unknown stored value falls back to the cover`() {
        assertEquals(ArtContent.COVER, ArtContent.at(99))
        assertEquals(ArtContent.COVER, ArtContent.at(-1))
    }

    /**
     * A layout needs the minute tick if anything on it tells the time - a line
     * or the picture. Missing the picture here would leave a drawn clock frozen
     * at whatever minute the track started.
     */
    @Test
    fun `a drawn clock alone still asks for the minute tick`() {
        val still = LineSet(LineSpec(LineContent.TITLE), LineSpec(LineContent.ARTIST), LineSpec(LineContent.STATION))
        val noClockAnywhere = Presentation(still, still, ArtContent.COVER, ArtContent.LOGO)
        assertFalse(noClockAnywhere.needsClock)

        assertTrue(noClockAnywhere.copy(artIdle = ArtContent.CLOCK_ANALOG).needsClock)
        assertTrue(noClockAnywhere.copy(artPlaying = ArtContent.CLOCK_DIGITAL).needsClock)
    }

    @Test
    fun `each state gets its own picture`() {
        val still = LineSet(LineSpec(LineContent.TITLE), LineSpec(LineContent.ARTIST), LineSpec(LineContent.STATION))
        val p = Presentation(still, still, ArtContent.COVER, ArtContent.CLOCK_DIGITAL)
        assertEquals(ArtContent.COVER, p.artFor(trackPlaying = true))
        assertEquals(ArtContent.CLOCK_DIGITAL, p.artFor(trackPlaying = false))
    }
}

/**
 * Two things on one line, joined by a dot. The pairing exists because the head
 * unit gives us three fields and some of the choices are five characters long.
 */
class LineSpecTest {

    @Test
    fun `a line with nothing after the dot is a plain single line`() {
        val spec = LineSpec(LineContent.CLOCK)
        assertEquals(LineContent.EMPTY, spec.secondary)
    }

    /** needsClock has to see both halves, or a clock in the second one would freeze. */
    @Test
    fun `the second half counts towards the minute tick`() {
        val still = LineSet(
            LineSpec(LineContent.TITLE),
            LineSpec(LineContent.ARTIST),
            LineSpec(LineContent.STATION)
        )
        val p = Presentation(still, still, ArtContent.COVER, ArtContent.LOGO)
        assertFalse(p.needsClock)

        val withDate = still.copy(top = LineSpec(LineContent.TITLE, LineContent.DATE))
        assertTrue(p.copy(playing = withDate).needsClock)
        assertTrue(p.copy(idle = withDate).needsClock)
    }

    @Test
    fun `all reports both halves of every line`() {
        val set = LineSet(
            LineSpec(LineContent.CLOCK, LineContent.DATE),
            LineSpec(LineContent.STATION),
            LineSpec(LineContent.SLOGAN)
        )
        assertTrue(LineContent.DATE in set.all())
        assertTrue(LineContent.SLOGAN in set.all())
        assertEquals(6, set.all().size)
    }
}

/**
 * Two entries can say the same thing without being the same entry, and putting
 * both on one line prints it twice.
 */
class LineOverlapTest {

    @Test
    fun `a composite overlaps its parts`() {
        assertTrue(LineContent.CLOCK_DATE.overlaps(LineContent.CLOCK))
        assertTrue(LineContent.CLOCK_DATE.overlaps(LineContent.DATE))
        assertTrue(LineContent.DATE.overlaps(LineContent.CLOCK_DATE))
        assertTrue(LineContent.TRACK_FULL.overlaps(LineContent.ARTIST))
        assertTrue(LineContent.TRACK_FULL.overlaps(LineContent.TITLE))
        assertTrue(LineContent.ARTIST_ALBUM.overlaps(LineContent.TITLE_ALBUM))
    }

    @Test
    fun `unrelated entries do not overlap`() {
        assertFalse(LineContent.CLOCK.overlaps(LineContent.DATE))
        assertFalse(LineContent.STATION.overlaps(LineContent.SLOGAN))
        assertFalse(LineContent.ARTIST.overlaps(LineContent.TITLE))
        assertFalse(LineContent.CLOCK_DATE.overlaps(LineContent.STATION))
    }

    /** Nothing collides with nothing, which is why it is always selectable. */
    @Test
    fun `empty never overlaps`() {
        LineContent.entries.forEach {
            assertFalse("$it", LineContent.EMPTY.overlaps(it))
            assertFalse("$it", it.overlaps(LineContent.EMPTY))
        }
    }

    /** Everything says something, except the one entry that says nothing. */
    @Test
    fun `every entry declares its parts`() {
        LineContent.entries.filter { it != LineContent.EMPTY }
            .forEach { assertTrue("$it", it.parts.isNotEmpty()) }
    }

    /** The default layouts must not be caught by their own rule. */
    @Test
    fun `no default line says anything twice`() {
        listOf(Presentation.DEFAULT_PLAYING, Presentation.DEFAULT_IDLE).forEach { set ->
            listOf(set.top, set.middle, set.bottom).forEach {
                assertFalse("$it", it.secondary.overlaps(it.primary))
            }
        }
    }
}

/**
 * Two entries were two things joined by a dot, from before a line could hold
 * two things. They are gone from the pickers and unfold into their halves.
 */
class RetiredCompositeTest {

    @Test
    fun `no composite is offered any more`() {
        LineContent.RETIRED.keys.forEach {
            assertFalse("$it gra", it in LineContent.WHEN_PLAYING)
            assertFalse("$it bez utworu", it in LineContent.WHEN_IDLE)
        }
    }

    /** The stored order still has them, because stored choices must keep parsing. */
    @Test
    fun `they stay in the enum`() {
        assertEquals(
            listOf(
                "TITLE", "ARTIST", "ARTIST_ALBUM", "TITLE_ALBUM", "TRACK_FULL",
                "STATION", "CLOCK", "EMPTY", "SLOGAN", "DATE", "CLOCK_DATE", "ALBUM"
            ),
            LineContent.entries.map { it.name }
        )
    }

    @Test
    fun `a stored composite unfolds into its halves`() {
        assertEquals(
            LineSpec(LineContent.CLOCK, LineContent.DATE),
            LineContent.expandedBy(LineContent.CLOCK_DATE, LineContent.EMPTY)
        )
        assertEquals(
            LineSpec(LineContent.ARTIST, LineContent.TITLE),
            LineContent.expandedBy(LineContent.TRACK_FULL, LineContent.EMPTY)
        )
    }

    /** Unfolding wins over whatever second half was stored - the halves are the whole line. */
    @Test
    fun `unfolding replaces a stored second half`() {
        assertEquals(
            LineSpec(LineContent.CLOCK, LineContent.DATE),
            LineContent.expandedBy(LineContent.CLOCK_DATE, LineContent.STATION)
        )
    }

    @Test
    fun `an ordinary pair is left alone`() {
        assertEquals(
            LineSpec(LineContent.STATION, LineContent.SLOGAN),
            LineContent.expandedBy(LineContent.STATION, LineContent.SLOGAN)
        )
    }

    /**
     * The point of the whole exercise: a line is assembled from parts, and no
     * offered entry is a pair of them any more.
     */
    @Test
    fun `every offered entry is a single block`() {
        (LineContent.WHEN_PLAYING + LineContent.WHEN_IDLE)
            .filter { it != LineContent.EMPTY }
            .forEach { assertEquals("$it", 1, it.parts.size) }
    }

    /** The album became a block of its own, which is what let the last two go. */
    @Test
    fun `the album is a block now`() {
        assertTrue(LineContent.ALBUM in LineContent.WHEN_PLAYING)
        assertEquals(
            LineSpec(LineContent.ARTIST, LineContent.ALBUM),
            LineContent.expandedBy(LineContent.ARTIST_ALBUM, LineContent.EMPTY)
        )
        assertEquals(
            LineSpec(LineContent.TITLE, LineContent.ALBUM),
            LineContent.expandedBy(LineContent.TITLE_ALBUM, LineContent.EMPTY)
        )
    }
}
