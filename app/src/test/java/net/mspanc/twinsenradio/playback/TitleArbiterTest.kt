package net.mspanc.twinsenradio.playback

import net.mspanc.twinsenradio.playback.TitleArbiter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream against title API, for a station that has both. Times are in ms on
 * the arbiter's own clock; the titles are RNS's from 27.10.2026.
 */
class TitleArbiterTest {

    private val rns = "Radio Nowy Świat"
    private fun np(s: String) = NowPlaying.parse(s, rns)!!
    private val slogan = "Radio Nowy Swiat - Pion i poziom!"

    @Test
    fun `with only the placeholder the API goes on`() {
        val a = TitleArbiter()
        assertEquals(Verdict.PUBLISH, a.fromIcy(null, placeholder = true, atMs = 0).verdict)
        assertEquals(Verdict.PUBLISH, a.fromApi(np("Sonic Youth - Incinerate"), 1_000).verdict)
        assertEquals(Verdict.PUBLISH, a.fromApi(np("New Tutenkhamen - Joburg Bound"), 200_000).verdict)
    }

    /**
     * 18:12 that day: the mount sent its slogan on connect while Albert
     * Hammond played. A fixed string must not hide the track.
     */
    @Test
    fun `an unproven stream's slogan does not cover a track`() {
        val a = TitleArbiter()
        assertEquals(Verdict.PUBLISH, a.fromApi(np("Albert Hammond - Shake a Bone"), 0).verdict)
        val icy = a.fromIcy(np(slogan), false, 300)
        assertEquals(Verdict.API_RULES, icy.verdict)
        assertEquals(false, icy.agrees)
        assertFalse(a.streamTrusted)
    }

    /** A slogan from both is talk, whoever says it first. */
    @Test
    fun `a slogan both agree on is one slogan`() {
        val a = TitleArbiter()
        assertEquals(Verdict.PUBLISH, a.fromIcy(np(slogan), false, 0).verdict)
        assertEquals(Verdict.SAME, a.fromApi(np("Radio Nowy Świat - Pion i poziom!"), 3_000).verdict)
    }

    /** The first real track from the stream is what proves it works again. */
    @Test
    fun `a track named by the stream earns it the say`() {
        val a = TitleArbiter()
        a.fromApi(np("Sonic Youth - Incinerate"), 0)
        val icy = a.fromIcy(np("Sonic Youth - Incinerate"), false, 4_000)
        assertTrue(a.streamTrusted)
        assertEquals(Verdict.SAME, icy.verdict)
        assertEquals(true, icy.agrees)
        assertEquals(4_000L, icy.apartMs)
    }

    /** Trusted, its slogan means talk - even with the web page still on the last track. */
    @Test
    fun `a trusted stream's slogan is talk`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        a.fromApi(np("Sonic Youth - Incinerate"), 2_000)
        assertEquals(Verdict.PUBLISH, a.fromIcy(np(slogan), false, 240_000).verdict)
        // The page never moved on - nothing new from it, nothing to arbitrate,
        // and no clock started against the stream.
        assertTrue(a.streamRules(600_000))
    }

    @Test
    fun `a trusted stream overrules a different API track`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        val api = a.fromApi(np("Massive Attack - Teardrop"), 5_000)
        assertEquals(Verdict.STREAM_RULES, api.verdict)
        assertEquals(false, api.agrees)
    }

    /** The API saw the change first; a working stream names it moments later. */
    @Test
    fun `a stream that follows keeps the say`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        assertEquals(Verdict.STREAM_RULES, a.fromApi(np("Ferris & Sylvester - Dark Side"), 200_000).verdict)
        assertEquals(Verdict.PUBLISH, a.fromIcy(np("Ferris & Sylvester - Dark Side"), false, 205_000).verdict)
        assertTrue(a.streamRules(200_000 + TitleArbiter.FOLLOW_MS + 1))
    }

    /** It broke again: the API moved on and the stream never named the track. */
    @Test
    fun `a stream that stops following loses the say`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        a.fromApi(np("Ferris & Sylvester - Dark Side"), 200_000)
        assertTrue(a.streamRules(200_000 + TitleArbiter.FOLLOW_MS - 1))
        assertFalse(a.streamRules(200_000 + TitleArbiter.FOLLOW_MS))
        // The poll re-offers the held-back track, and now it goes on.
        assertEquals(Verdict.PUBLISH, a.fromApi(np("Ferris & Sylvester - Dark Side"), 260_000).verdict)
    }

    /** The API going to its slogan is not a track the stream must follow. */
    @Test
    fun `an API slogan starts no clock`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        a.fromApi(np("Radio Nowy Świat - Pion i poziom!"), 200_000)
        assertTrue(a.streamRules(10_000_000))
    }

    @Test
    fun `the placeholder ends trust at once`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        a.fromIcy(null, placeholder = true, atMs = 1_000)
        assertFalse(a.streamRules(1_001))
        assertEquals(Verdict.PUBLISH, a.fromApi(np("Massive Attack - Teardrop"), 2_000).verdict)
    }

    /** An empty block is the stream pausing, not giving up. */
    @Test
    fun `an empty block keeps trust`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        a.fromIcy(null, placeholder = false, atMs = 1_000)
        assertTrue(a.streamRules(1_001))
    }

    @Test
    fun `a new station starts untrusted`() {
        val a = TitleArbiter()
        a.fromIcy(np("Sonic Youth - Incinerate"), false, 0)
        a.reset()
        assertFalse(a.streamTrusted)
        val api = a.fromApi(np("Sonic Youth - Incinerate"), 1)
        assertEquals(Verdict.PUBLISH, api.verdict)
        assertNull(api.agrees)
    }
}
