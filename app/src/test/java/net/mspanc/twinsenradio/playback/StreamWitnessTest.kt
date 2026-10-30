package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stream against title API, recorded side by side. Times are in ms on the
 * witness's own clock; the titles are RNS's from 27-30.10.2026.
 */
class StreamWitnessTest {

    private val rns = "Radio Nowy Świat"
    private fun np(s: String) = NowPlaying.parse(s, rns)!!
    private val slogan = "Radio Nowy Swiat - Pion i poziom!"

    @Test
    fun `nothing to compare with on a fresh connection`() {
        val w = StreamWitness()
        val api = w.fromApi(np("Snowy White; White Flames, The - Music All My Life"), 0)
        assertNull(api.agrees)
        assertNull(api.apartMs)
    }

    /** 30.10.2026: the frozen block every mount sends on connect, against the API's real track. */
    @Test
    fun `the frozen track on connect disagrees`() {
        val w = StreamWitness()
        w.fromApi(np("Snowy White; White Flames, The - Music All My Life"), 0)
        val icy = w.fromIcy(np("Trent Reznor and Atticus Ross - Normal Life"), 1_000)
        assertEquals(false, icy.agrees)
        assertNull(icy.apartMs)
    }

    /** 28.10.2026, when the stream worked: it named each track some seconds after the API. */
    @Test
    fun `a stream that follows agrees and says how late`() {
        val w = StreamWitness()
        w.fromApi(np("Sonic Youth - Incinerate"), 0)
        val icy = w.fromIcy(np("Sonic Youth - Incinerate"), 9_000)
        assertEquals(true, icy.agrees)
        assertEquals(9_000L, icy.apartMs)
    }

    /** A slogan is the same slogan whichever spelling of the name it carries. */
    @Test
    fun `a slogan from both agrees`() {
        val w = StreamWitness()
        w.fromIcy(np(slogan), 0)
        assertEquals(true, w.fromApi(np("Radio Nowy Świat - Pion i poziom!"), 3_000).agrees)
    }

    /** An empty block or the placeholder names nothing and is not compared. */
    @Test
    fun `a block that names nothing is not remembered`() {
        val w = StreamWitness()
        w.fromIcy(np("Sonic Youth - Incinerate"), 0)
        assertNull(w.fromIcy(null, 1_000).agrees)
        assertEquals(true, w.fromApi(np("Sonic Youth - Incinerate"), 2_000).agrees)
    }

    @Test
    fun `a new station forgets both`() {
        val w = StreamWitness()
        w.fromIcy(np("Sonic Youth - Incinerate"), 0)
        w.reset()
        assertNull(w.fromApi(np("Sonic Youth - Incinerate"), 1).agrees)
    }
}
