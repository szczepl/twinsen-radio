package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                "STATION", "CLOCK", "EMPTY", "SLOGAN", "DATE", "CLOCK_DATE"
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

    @Test
    fun `both lists offer the time, the date and an empty line`() {
        listOf(LineContent.CLOCK, LineContent.DATE, LineContent.CLOCK_DATE, LineContent.EMPTY)
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

    /** The year suffix is a promise about the album lines, and only those. */
    @Test
    fun `only the album lines mention the year`() {
        val withYear = LineContent.labels(LineContent.WHEN_PLAYING, includeYear = true)
        val withoutYear = LineContent.labels(LineContent.WHEN_PLAYING, includeYear = false)
        val differing = withYear.zip(withoutYear).count { (a, b) -> a != b }
        assertEquals(2, differing)
    }

    @Test
    fun `an unknown stored value does not crash`() {
        assertEquals(LineContent.TITLE, LineContent.at(999))
        assertEquals(LineContent.TITLE, LineContent.at(-1))
    }
}
