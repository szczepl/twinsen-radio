package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Triton's now-playing for Smooth FM 91.5, as it answered on 30.10.2026. */
class TritonNowPlayingTest {

    private fun info(type: String, title: String, artist: String, start: Long, durMs: String) =
        """<nowplaying-info mountName="SMOOTH915" timestamp="${start / 1000}" type="$type">""" +
            """<property name="cue_time_duration"><![CDATA[$durMs]]></property>""" +
            """<property name="cue_time_start"><![CDATA[$start]]></property>""" +
            """<property name="cue_title"><![CDATA[$title]]></property>""" +
            (if (artist.isEmpty()) "" else """<property name="track_artist_name"><![CDATA[$artist]]></property>""") +
            "</nowplaying-info>"

    private fun answer(vararg infos: String) =
        """<?xml version="1.0" encoding="UTF-8"?><nowplaying-info-list>${infos.joinToString("")}</nowplaying-info-list>"""

    private val track = info("track", "Tainted Love", "Soft Cell", 1793374151361, "155673.7276")
    private val ad = info("ad", "NT200826V1 - National Tiles", "", 1793374307000, "30000")
    private val link = info("ad", "(Link) - SWP-MMALT SMOOTH 915-1-COLD-SMOOTH 915", "", 1793374727000, "6000")

    @Test
    fun `the mount of a streamtheworld address`() {
        assertEquals("SMOOTH915", TritonNowPlaying.mountOf("http://playerservices.streamtheworld.com/api/livestream-redirect/SMOOTH915_AAC320.aac"))
        assertEquals("SMOOTH_80S", TritonNowPlaying.mountOf("https://playerservices.streamtheworld.com/api/livestream-redirect/SMOOTH_80S_AAC128.aac"))
        assertNull(TritonNowPlaying.mountOf("https://live.jacarandafm.com/jacarandahigh.mp3"))
    }

    @Test
    fun `a track with its exact start and length`() {
        val e = TritonNowPlaying.latest(answer(track)) as TritonNowPlaying.Event.Track
        assertEquals("Soft Cell - Tainted Love", e.streamTitle)
        assertEquals(1793374151361, e.startMs)
        assertEquals(155673, e.durationMs)
    }

    @Test
    fun `an ad is an ad, and becomes an ICY ad block with what is left`() {
        val e = TritonNowPlaying.latest(answer(ad, track)) as TritonNowPlaying.Event.Ad
        assertFalse(e.isLink)
        val now = NowPlaying.parse("", "Smooth FM", e.icyBlock(25_000))!!
        assertTrue(now.isAd)
        assertEquals(25_000, now.adDurationMs)
    }

    @Test
    fun `the station's own link is no ad`() {
        val e = TritonNowPlaying.latest(answer(link)) as TritonNowPlaying.Event.Ad
        assertTrue(e.isLink)
    }

    /** Heard 5 s after Triton's clock, a track is still on 5 s past its end there. */
    @Test
    fun `whether an event is still on depends on how far behind we hear`() {
        val end = 1793374151361 + 155673
        assertNull(TritonNowPlaying.current(answer(track), end + 3_000))
        assertTrue(TritonNowPlaying.current(answer(track), end + 3_000, lagMs = 5_000) is TritonNowPlaying.Event.Track)
    }

    @Test
    fun `asked again just after the current event ends`() {
        val start = 1793374307000
        assertEquals(10_700, TritonNowPlaying.nextAskInMs(answer(ad), start + 20_000, 30_000))
        assertEquals(1_000, TritonNowPlaying.nextAskInMs(answer(ad), start + 40_000, 30_000))
    }
}
