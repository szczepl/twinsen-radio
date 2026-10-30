package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Collaborators as they reach the screen: Jacaranda's "[+]", and the " / " of Smooth FM and RMF. */
class JoinedNamesTest {

    @Test
    fun `a plus in brackets becomes an ampersand`() {
        assertEquals("Beyonce & Jay Z", MetadataFactory.joinedNames("Beyonce [+] Jay Z"))
    }

    @Test
    fun `every one of several collaborators`() {
        assertEquals(
            "Pitbull & Ne-Yo & Afrojack & Nayer",
            MetadataFactory.joinedNames("Pitbull [+] Ne-Yo [+] Afrojack [+] Nayer")
        )
    }

    @Test
    fun `the artist line on screen has no brackets`() {
        val now = NowPlaying.parse("BEYONCE [+] JAY Z - CRAZY IN LOVE", "Jacaranda FM")!!
        assertEquals("Beyonce & Jay Z", MetadataFactory.displayArtist(now, null))
    }

    /** The raw text keeps "[+]" - the catalogue lookup splits names on it. */
    @Test
    fun `the parsed artist is left as the station sent it`() {
        val now = NowPlaying.parse("BEYONCE [+] JAY Z - CRAZY IN LOVE", "Jacaranda FM")!!
        assertEquals("BEYONCE [+] JAY Z", now.artist)
    }

    /** Smooth FM, 30.10.2026. */
    @Test
    fun `a slash between artists becomes an ampersand`() {
        val now = NowPlaying.parse("Bryan Adams / Mel C - When You're Gone", "Smooth FM 91.5")!!
        assertEquals("Bryan Adams & Mel C", MetadataFactory.composeArtistOnly(now, null))
    }

    @Test
    fun `three of them, and a slash inside a name stays`() {
        val now = NowPlaying.parse("Shimza / AR/CO / Kasango - Mlomo", "RMF FM")!!
        assertEquals("Shimza & AR/CO & Kasango", MetadataFactory.composeArtistOnly(now, null))
    }

    /** RMF's "artist / album": the album comes off first, and nothing is left to join. */
    @Test
    fun `an album after the slash still comes off rather than becoming an artist`() {
        val now = NowPlaying.parse("Wiktoria Kida / Księga - Pustka", "RMF FM")!!
        val info = CoverArtLookup.TrackInfo(null, "Księga", 2025, isSingle = false)
        assertEquals("Wiktoria Kida", MetadataFactory.composeArtistOnly(now, info, slashMayBeAlbum = true))
    }

    /** RMF without the catalogue can't say which its slash is, so it stays a slash. */
    @Test
    fun `on rmf with no catalogue answer the slash is left alone`() {
        val now = NowPlaying.parse("Wiktoria Kida / Księga - Pustka", "RMF FM")!!
        assertEquals("Wiktoria Kida / Księga", MetadataFactory.composeArtistOnly(now, null, slashMayBeAlbum = true))
    }

    /** RMF, 30.10.2026 16:17 - two artists, the catalogue's album being something else. */
    @Test
    fun `on rmf with the catalogue saying it is no album, two artists`() {
        val now = NowPlaying.parse("Sean Paul / Alexis Jordan - Got 2 Luv U", "RMF FM")!!
        val info = CoverArtLookup.TrackInfo(null, "Got 2 Luv U", 2011, isSingle = true)
        assertEquals("Sean Paul & Alexis Jordan", MetadataFactory.composeArtistOnly(now, info, slashMayBeAlbum = true))
    }

    @Test
    fun `a band with a slash in its name is one band`() {
        val now = NowPlaying.parse("AC/DC - Thunderstruck", "RMF FM")!!
        assertEquals("AC/DC", MetadataFactory.composeArtistOnly(now, null))
    }
}
