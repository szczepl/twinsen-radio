package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Release names taken from the traces of 09-10.09.2026, not invented. The rule
 * exists to shorten a dashboard line, so anything it changes has to be
 * decoration about the pressing - never a different record.
 */
class AlbumNamesTest {

    private fun assertUnchanged(name: String) = assertEquals(name, AlbumNames.shorten(name))

    @Test
    fun `soundtrack notes come off`() {
        assertEquals("The Pianist", AlbumNames.shorten("The Pianist (Original Motion Picture Soundtrack)"))
        assertEquals("Mo' Money", AlbumNames.shorten("Mo' Money (Original Motion Picture Soundtrack)"))
    }

    @Test
    fun `edition and remaster notes come off`() {
        assertEquals("Love Zone", AlbumNames.shorten("Love Zone (Expanded Edition)"))
        assertEquals("Kick", AlbumNames.shorten("Kick (30th Deluxe Edition)"))
        assertEquals("FutureSex/LoveSounds", AlbumNames.shorten("FutureSex/LoveSounds (Deluxe Edition)"))
        assertEquals("Break Out", AlbumNames.shorten("Break Out (1984 Version)"))
    }

    /** Catalogues stack them, so one pass is not enough. */
    @Test
    fun `stacked notes all come off`() {
        assertEquals(
            "La double vie de Veronique",
            AlbumNames.shorten("La double vie de Veronique (Original Motion Picture Soundtrack) [Remastered]")
        )
        assertEquals(
            "The Traveling Wilburys Collection",
            AlbumNames.shorten("The Traveling Wilburys Collection (Deluxe Edition) [2016 Remaster]")
        )
    }

    @Test
    fun `ordinary release names are left alone`() {
        assertUnchanged("Think Like a Girl")
        assertUnchanged("The Madman's Return")
        assertUnchanged("Forever Your Girl")
        assertUnchanged("Greatest Hits I, II & III: The Platinum Collection")
        assertUnchanged("W Pustyni I W Puszczy")
    }

    /** A bracket at the front is part of the name, not a note about a pressing. */
    @Test
    fun `a leading bracket is not decoration`() {
        assertUnchanged("(What's the Story) Morning Glory?")
    }

    /**
     * These say what the recording is, which is a different record - not the
     * same one in a fancier box.
     */
    @Test
    fun `live and acoustic stay`() {
        assertUnchanged("Under a Blood Red Sky (Live)")
        assertUnchanged("Unplugged (Acoustic)")
    }

    @Test
    fun `a name made only of decoration is kept whole`() {
        assertUnchanged("(Deluxe Edition)")
    }
}
