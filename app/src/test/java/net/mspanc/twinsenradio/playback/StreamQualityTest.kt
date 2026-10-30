package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityTest {

    @Test
    fun `the chip says codec, bitrate and channels`() {
        assertEquals("MP3 · 128 kbit · stereo", StreamQuality("MP3", 128, 44_100, 2).chipLabel())
    }

    /** RNS's catalogue says what was chosen; that beats what the decoder guesses. */
    @Test
    fun `a declared bitrate replaces the reported one`() {
        assertEquals("AAC+ · 64 kbit · stereo", StreamQuality("AAC+", 48, 44_100, 2).chipLabel(64))
    }

    @Test
    fun `an unknown bitrate is left out, not shown as zero`() {
        assertEquals("AAC · stereo", StreamQuality("AAC", 0, 44_100, 2).chipLabel())
    }
}
