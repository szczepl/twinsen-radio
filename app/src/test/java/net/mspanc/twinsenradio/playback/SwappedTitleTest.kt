package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a station gave the title and the artist the wrong way round.
 *
 * Every case here was actually broadcast and is in a trace from a drive - none
 * of it is invented. That matters, because the alternative way of checking this
 * is to wait for a radio station to play the right song, and a case that only
 * turns up once a fortnight is a case nobody re-tests.
 */
class SwappedTitleTest {

    private fun catalogue(track: String, artist: String) = CoverArtLookup.TrackInfo(
        artworkUrl = null,
        album = null,
        year = null,
        isSingle = false,
        trackName = track,
        artistName = artist
    )

    /**
     * The one that started it. iTunes writes the collaboration into the title,
     * the station sends the bare title, and an exact comparison said no - so
     * Pitbull was the title on the dashboard for the length of the song.
     */
    @Test
    fun `catalogue title with a feat suffix still matches`() {
        val info = catalogue("Give Me Everything (feat. Ne-Yo, Afrojack & Nayer)", "Pitbull")
        assertTrue(
            info.looksSwapped("GIVE ME EVERYTHING", "PITBULL [+] NE-YO [+] AFROJACK [+] NAYER")
        )
    }

    /** Jacaranda, 2026-09-08. The catalogue's own title is in caps here too. */
    @Test
    fun `caps in the catalogue title change nothing`() {
        val info = catalogue("SHE DID IT AGAIN (feat. Zara Larsson)", "Tyla")
        assertTrue(info.looksSwapped("SHE DID IT AGAIN", "TYLA [+] ZARA LARSSON"))
    }

    /**
     * Nothing in brackets to strip, and the catalogue's title is longer than
     * what the station sent. Only the artist cross-check can settle this one.
     */
    @Test
    fun `catalogue artist found among the names in our title`() {
        val info = catalogue("Killing Me Softly with His Song", "Fugees")
        assertTrue(info.looksSwapped("KILLING ME SOFTLY", "FUGEES"))
    }

    @Test
    fun `plain reversed pair`() {
        assertTrue(catalogue("No One", "Alicia Keys").looksSwapped("NO ONE", "ALICIA KEYS"))
    }

    /** RMF has it the right way round, and several artists in one field. */
    @Test
    fun `correct order is left alone`() {
        val info = catalogue("Titanium (feat. Sia)", "David Guetta")
        assertFalse(info.looksSwapped("David Guetta / SIA", "Titanium"))
    }

    /**
     * The reason names are compared whole and not by substring: "Sia" sits
     * inside "Anastasia", and that would be a swap declared on a coincidence.
     */
    @Test
    fun `an artist name inside a word is not a match`() {
        assertFalse(catalogue("Anastasia", "Sia").looksSwapped("Some Band", "Anastasia"))
    }

    /** A band that named a track after itself is not a station getting it wrong. */
    @Test
    fun `title equal to artist is not a swap`() {
        assertFalse(catalogue("Bo Diddley", "Bo Diddley").looksSwapped("Bo Diddley", "Bo Diddley"))
    }

    @Test
    fun `nothing from the catalogue decides nothing`() {
        val nothing = CoverArtLookup.TrackInfo(null, null, null, false)
        assertFalse(nothing.looksSwapped("GIVE ME EVERYTHING", "PITBULL"))
    }
}
