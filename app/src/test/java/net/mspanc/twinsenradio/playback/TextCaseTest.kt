package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bringing a station's shouting down to something readable on a narrow screen. */
class TextCaseTest {

    @Test
    fun `a single word is not shouty, but it is all caps`() {
        assertFalse(TextCase.isShouty("FUGEES"))
        assertTrue(TextCase.isAllCaps("FUGEES"))
        assertTrue(TextCase.isShouty("KILLING ME SOFTLY"))
    }

    /**
     * The single-word rule protects ABBA and U2, and it was also leaving
     * "FUGEES" shouting next to a title the catalogue had spelled properly.
     */
    @Test
    fun `catalogue spelling wins for a single all-caps word`() {
        assertEquals("Fugees", TextCase.tidy("FUGEES", "Fugees"))
    }

    @Test
    fun `a band that is genuinely all caps stays that way`() {
        assertEquals("ABBA", TextCase.tidy("ABBA", "ABBA"))
        assertEquals("U2", TextCase.tidy("U2", "U2"))
    }

    /** Different text, so the catalogue cannot be copied - we tidy it ourselves. */
    @Test
    fun `shouty text with no catalogue match is tamed`() {
        assertEquals(
            "Killing Me Softly",
            TextCase.tidy("KILLING ME SOFTLY", "Killing Me Softly with His Song")
        )
    }

    @Test
    fun `abbreviations survive taming`() {
        assertEquals("DJ Snake", TextCase.tame("DJ SNAKE"))
    }

    @Test
    fun `text that is not shouty is never touched`() {
        assertEquals("Pion i poziom!", TextCase.tidy("Pion i poziom!", "cokolwiek innego"))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals("", TextCase.tidy(null))
        assertEquals("", TextCase.tidy("   "))
    }
}
