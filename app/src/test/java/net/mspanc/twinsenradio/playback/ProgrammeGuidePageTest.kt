package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * RNS's `/ramowka` as it was on 30.10.2026, cut down to two days - the markup
 * is copied as it is, whitespace and all, because that is what the regexes
 * have to survive.
 */
class ProgrammeGuidePageTest {

    private val base = "https://nowyswiat.online/"

    private fun entry(time: String, img: String, title: String, names: String) = """
                                    <li class="rns-switcher-single ">
                                        <div class="rns-switcher-time">
                                            $time                                        </div>

                                        <div class="rns-switcher-img">
                                                                                        <img class="rns-switcher-img"
                                                 src="$img"
                                                 alt="x">
                                        </div>

                                        <div class="rns-switcher-text-box">

                                            <h2 class="rns-switcher-title">
                                                                                                    $title
                                                                                            </h2>

                                            <div class="rns-switcher-names">
                                                $names
                                            </div>

                                        </div>
                                    </li>"""

    private val html = """
        <ul class="rns-week-switcher uk-thumbnav uk-slider-items">
            <li uk-slideshow-item="0" class="rns-week-switcher-element ">
                <a href="#">
                    piątek                                    <span class="rns-week-switcher-date">30.10</span>
                </a>
            </li>
            <li uk-slideshow-item="1" class="rns-week-switcher-element ">
                <a href="#">
                    sobota                                    <span class="rns-week-switcher-date">31.10</span>
                </a>
            </li>
        </ul>
        <ul class="uk-container rns-switcher-grid uk-slideshow-items">
            <li class="rns-switcher-grid-element">
                <ul class="rns-switcher-list">
        ${entry("0:00", "userfiles/rbroadcasts/audycja_muzyka-do-rana-light_thumb_150x150.jpg?ts=1708460930", "Muzyka do rana", "")}
        ${entry("6:00", "userfiles/rbroadcasts/audycja_poranna-manna-light_thumb_150x150.jpg?ts=1709250218",
            """<a href="https://nowyswiat.online/audycje/poranna-manna">Poranna Manna</a>""",
            """<a href="https://nowyswiat.online/zespol/wojciech-mann">Wojciech Mann</a>
               &nbsp;|&nbsp;&nbsp;<br /><b>Adriana Bąkowska</b>: Trumny z wikliny""")}
        ${entry("10:00", "userfiles/team/tyczynski-mikolaj-light_thumb_150x150.jpg?ts=1708022569",
            """<a href="https://nowyswiat.online/audycje/soulowka">Soulówka</a>""",
            """<a href="https://nowyswiat.online/zespol/mikolaj-tyczynski">Mikołaj Tyczyński</a>""")}
        ${entry("11:00", "userfiles/rbroadcasts/kwiek-damian_rabiega-patryk_thumb_150x150.jpg?ts=1719481031",
            """<a href="https://nowyswiat.online/audycje/caly-nasz-swiat">Cały nasz świat</a>""",
            """<a href="https://nowyswiat.online/zespol/damian-kwiek">Damian Kwiek</a>,
               <a href="https://nowyswiat.online/zespol/patryk-rabiega">Patryk Rabiega</a>
               &nbsp;|&nbsp;&nbsp;<br /><b>Jan Nowinowski</b> : Serbia po wyborach""")}
                </ul>
            </li>
            <li class="rns-switcher-grid-element">
                <ul class="rns-switcher-list">
        ${entry("0:00", "userfiles/rbroadcasts/audycja_muzyka-do-rana-light_thumb_150x150.jpg?ts=1708460930", "Muzyka do rana", "")}
                </ul>
            </li>
        </ul>
    """

    private val today = LocalDate.of(2026, 10, 30)
    private val slots = ProgrammeGuidePage.parse(html, base, today)

    @Test
    fun `every entry of every day is read, in order`() {
        assertEquals(5, slots.size)
        assertEquals(LocalDateTime.of(2026, 10, 30, 0, 0), slots[0].start)
        assertEquals(LocalDateTime.of(2026, 10, 31, 0, 0), slots[4].start)
    }

    @Test
    fun `the programme on air is the last one that has started`() {
        val at = ProgrammeGuidePage.at(slots, LocalDateTime.of(2026, 10, 30, 10, 42))!!
        assertEquals("Soulówka", at.name)
        assertEquals("Mikołaj Tyczyński", at.hosts)
    }

    /** The guests and topics after the bar are not presenters. */
    @Test
    fun `presenters stop at the bar`() {
        assertEquals("Wojciech Mann", slots[1].hosts)
        assertEquals("Damian Kwiek, Patryk Rabiega", slots[3].hosts)
    }

    @Test
    fun `a night of music has nobody presenting`() {
        assertEquals("Muzyka do rana", slots[0].name)
        assertEquals("", slots[0].hosts)
    }

    @Test
    fun `the picture is the original behind the thumbnail`() {
        assertEquals("https://nowyswiat.online/userfiles/team/tyczynski-mikolaj-light.jpg", slots[2].imageUrl)
    }

    @Test
    fun `the site's placeholder is no picture`() {
        val withPlaceholder = html.replace(
            "userfiles/rbroadcasts/audycja_muzyka-do-rana-light_thumb_150x150.jpg",
            "userfiles/rbroadcasts/radio-nowy-swiat-placeholder_thumb_150x150.jpg"
        )
        assertNull(ProgrammeGuidePage.parse(withPlaceholder, base, today)[0].imageUrl)
    }

    @Test
    fun `nothing before the guide begins or long after it ends`() {
        assertNull(ProgrammeGuidePage.at(slots, LocalDateTime.of(2026, 10, 29, 23, 0)))
        assertNull(ProgrammeGuidePage.at(slots, LocalDateTime.of(2026, 11, 1, 12, 0)))
    }

    /** A guide read on 30.12 that runs into January belongs to the next year. */
    @Test
    fun `the guide spans New Year`() {
        val newYear = html.replace("30.10", "31.12").replace("31.10", "01.01")
        val s = ProgrammeGuidePage.parse(newYear, base, LocalDate.of(2026, 12, 30))
        assertEquals(LocalDate.of(2027, 1, 1), s.last().start.toLocalDate())
    }

    @Test
    fun `the news window is the start of the hour`() {
        assertTrue(ProgrammeGuidePage.isNewsTime(LocalTime.of(11, 4), 5))
        assertFalse(ProgrammeGuidePage.isNewsTime(LocalTime.of(11, 5), 5))
        assertFalse(ProgrammeGuidePage.isNewsTime(LocalTime.of(11, 0), 0))
    }
}
