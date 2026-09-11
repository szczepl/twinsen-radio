package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a StreamTitle actually means. The blocks below are copied from traces
 * and from FINDINGS.md, section 3 - stations mark the same thing several ways
 * and change which one they use without telling anybody.
 */
class NowPlayingParseTest {

    @Test
    fun `artist and title split on the dash`() {
        val now = NowPlaying.parse("David Guetta / SIA - Titanium")!!
        assertEquals("David Guetta / SIA", now.artist)
        assertEquals("Titanium", now.songTitle)
        assertTrue(now.isRealSong)
    }

    /** The station put its own name and slogan where a track goes. */
    @Test
    fun `a station announcing itself is not a track`() {
        val now = NowPlaying.parse(
            "Radio Nowy Swiat - Pion i poziom!",
            stationName = "Radio Nowy Swiat"
        )!!
        assertTrue(now.isStationSelfTitle)
        assertEquals("Pion i poziom!", now.slogan)
        assertFalse(now.isRealSong)
    }

    /** RMF marks ads three ways. This is the one with the block. */
    @Test
    fun `an empty title with an ad marker is an ad`() {
        val now = NowPlaying.parse(
            "",
            rawBlock = "StreamTitle='';adw_ad='true';durationMilliseconds='30014';adId='38320';"
        )!!
        assertTrue(now.isAd)
        assertEquals(30014L, now.adDurationMs)
    }

    /** And this is the one where it just says so. */
    @Test
    fun `the word alone is an ad`() {
        assertTrue(NowPlaying.parse("Reklama")!!.isAd)
    }

    /**
     * A control marker is not a title. Taken literally it used to sit on
     * screen next to the previous song's cover art.
     */
    @Test
    fun `a control marker is recognised`() {
        val now = NowPlaying.parse("STOP_AD_BREAK")!!
        assertTrue(now.isControlMarker)
        assertFalse(now.isRealSong)
    }

    @Test
    fun `an empty title on its own says nothing`() {
        assertNull(NowPlaying.parse(""))
        assertNull(NowPlaying.parse(null))
    }

    @Test
    fun `the news is named, not blanked`() {
        val now = NowPlaying.parse("RMF FM - FAKTY", stationName = "RMF FM")!!
        assertTrue(now.isNews)
    }
}

/**
 * Radio Zlote Przeboje separates the artist from the title with a slash, not a
 * dash - measured 11.09.2026. Before this the whole thing became the title, the
 * artist line stayed empty, and the catalogue was asked a question with no
 * artist in it.
 */
class SlashSeparatorTest {

    @Test
    fun `a single slash with no dash splits artist from title`() {
        val now = NowPlaying.parse("KIM CARNES / BETTE DAVIS EYES")!!
        assertEquals("KIM CARNES", now.artist)
        assertEquals("BETTE DAVIS EYES", now.songTitle)
        assertTrue(now.isRealSong)
    }

    @Test
    fun `the other two from the same station`() {
        assertEquals("SANTANA", NowPlaying.parse("SANTANA / CORAZON ESPINADO")!!.artist)
        assertEquals("GDY NIE MA DZIECI", NowPlaying.parse("KULT / GDY NIE MA DZIECI")!!.songTitle)
    }

    /**
     * A dash wins, because a slash inside the artist half is how several
     * artists are joined - "Jonatan / Livka / Pezet - Obejmuje noce".
     */
    @Test
    fun `a dash still takes precedence`() {
        val now = NowPlaying.parse("Jonatan / Livka / Pezet - Obejmuje noce")!!
        assertEquals("Jonatan / Livka / Pezet", now.artist)
        assertEquals("Obejmuje noce", now.songTitle)
    }

    /** Several slashes and no dash is a list of names, not a split. */
    @Test
    fun `more than one slash is left alone`() {
        val now = NowPlaying.parse("Shimza / AR/CO / Kasango")!!
        assertNull(now.artist)
        assertEquals("Shimza / AR/CO / Kasango", now.songTitle)
    }

    /** The station announcing itself is still not a track. */
    @Test
    fun `a station naming itself twice is not a track`() {
        val now = NowPlaying.parse("ZLOTE PRZEBOJE / ZLOTE PRZEBOJE", stationName = "Radio Zlote Przeboje")!!
        assertTrue(now.isStationSelfTitle)
        assertFalse(now.isRealSong)
    }
}
