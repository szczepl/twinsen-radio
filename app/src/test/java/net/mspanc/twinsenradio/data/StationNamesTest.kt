package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The names below are the real catalog list off the test phone (10.09.2026),
 * not invented cases. Seven of the eight must come back untouched - a rule that
 * "improves" them is a rule that has started guessing.
 */
class StationNamesTest {

    private fun assertUnchanged(name: String) = assertEquals(name, StationNames.shorten(name))

    @Test
    fun `the bitrate and the repeated band come off`() {
        assertEquals(
            "Smooth FM 91.5 - Melbourne",
            StationNames.shorten("Smooth FM 91.5 - Melbourne - 91.5 FM (AAC+ 320k)")
        )
    }

    @Test
    fun `ordinary catalog names are left alone`() {
        assertUnchanged("Jazz Radio Soul")
        assertUnchanged("ABC Lounge Jazz")
        assertUnchanged("Smooth Jazz Lounge")
        assertUnchanged("Triple M Melbourne")
        assertUnchanged("Skyrock")
        assertUnchanged("101.9 The Fox Melbourne")
    }

    /**
     * The name that punishes a confident rule: cutting at the first dash leaves
     * an empty string. Only the dangling leading separator may go.
     */
    @Test
    fun `a name that begins with a dash keeps its content`() {
        assertEquals("0 N - Smooth Jazz on Radio", StationNames.shorten("- 0 N - Smooth Jazz on Radio"))
    }

    @Test
    fun `a place or a word after the dash is not a frequency`() {
        assertUnchanged("Radio X - FM Classics")
        assertUnchanged("Nowe Radio - Warszawa")
        assertUnchanged("Radio 357")
    }

    @Test
    fun `the band is recognised written either way round`() {
        assertEquals("Antyradio", StationNames.shorten("Antyradio - FM 103,5"))
        assertEquals("Antyradio", StationNames.shorten("Antyradio - 103.5 MHz"))
        assertEquals("Radio Q", StationNames.shorten("Radio Q - DAB+"))
    }

    @Test
    fun `codec brackets go whatever they hold`() {
        assertEquals("Radio Nowy Swiat", StationNames.shorten("Radio Nowy Swiat (MP3 256 kbps)"))
        assertEquals("Radio Nowy Swiat", StationNames.shorten("Radio Nowy Swiat [64k]"))
        assertEquals("Radio Nowy Swiat", StationNames.shorten("Radio Nowy Swiat (opus)"))
    }

    /** A name made only of things the rule strips must survive as it was. */
    @Test
    fun `a name that would vanish is kept whole`() {
        assertUnchanged("91.5 FM")
        assertUnchanged("(AAC+ 320k)")
    }
}

/**
 * The shortest form, for the second half of a line. Longer than this and the
 * station's name takes the room of whatever the line was actually for.
 */
class StationNamesShortestTest {

    @Test
    fun `only the first segment survives`() {
        assertEquals(
            "Smooth FM 91.5",
            StationNames.shortest("Smooth FM 91.5 - Melbourne - 91.5 FM (AAC+ 320k)")
        )
    }

    @Test
    fun `a name with no segments is left as the ordinary rule left it`() {
        assertEquals("Jacaranda FM", StationNames.shortest("Jacaranda FM"))
        assertEquals("Radio Nowy Swiat", StationNames.shortest("Radio Nowy Swiat"))
        assertEquals("Skyrock", StationNames.shortest("Skyrock"))
        assertEquals("101.9 The Fox Melbourne", StationNames.shortest("101.9 The Fox Melbourne"))
    }

    /**
     * The name that punishes a confident rule, again: taking the first segment
     * would leave "0 N", which names nothing.
     */
    @Test
    fun `a stub is not a name`() {
        assertEquals("0 N - Smooth Jazz on Radio", StationNames.shortest("- 0 N - Smooth Jazz on Radio"))
    }

    /** A hyphen inside a word is not a segment break. */
    @Test
    fun `a hyphenated name stays whole`() {
        assertEquals("Radio Wanda-Kraków", StationNames.shortest("Radio Wanda-Kraków"))
    }
}
