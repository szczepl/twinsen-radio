package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Radio 357's "what's on now", as it answered on 30.10.2026 at 15:44:19. */
class R357NowPlayingTest {

    private val answer = """{"_":"2026-10-30 15:44:19","s":"Yazoo - Happy People","t":"2026-10-30 15:44:18",""" +
        """"p":{"i":"417667","s":"81167.6","n":"Złodziej czasu","a":"Marcin Cichoński",""" +
        """"t":"https:\/\/radio357.pl\/cdn-cgi\/image\/w=660,h=660,fit=cover,g=0.5x0\/wp-content\/uploads\/zespol\/cichy.png",""" +
        """"c":{"d":"https:\/\/radio357.pl\/wp-content\/uploads\/zespol\/cichy.png"},"d":7200,"p":6258,"u":"100050158"},""" +
        """"n":{"t":"16:00","n":"Popołudniówka w piątek"},"a":{"s":"['lp357_status_inactive']"}}"""

    private val now = LocalDateTime.of(2026, 10, 30, 15, 44, 19)

    @Test
    fun `the programme on air, with its presenter and square photo`() {
        val slot = R357NowPlaying.parse(answer, now).first()
        assertEquals("Złodziej czasu", slot.name)
        assertEquals("Marcin Cichoński", slot.hosts)
        assertEquals(
            "https://radio357.pl/cdn-cgi/image/w=660,h=660,fit=cover,g=0.5x0/wp-content/uploads/zespol/cichy.png",
            slot.imageUrl
        )
    }

    /** 6258 s into it at 15:44:19 - it started at 14:00:01, which is the 14:00 slot. */
    @Test
    fun `its start is worked out from how far into it we are`() {
        assertEquals(LocalDateTime.of(2026, 10, 30, 14, 0, 1), R357NowPlaying.parse(answer, now).first().start)
    }

    @Test
    fun `the next one follows at the end, with only its name`() {
        val next = R357NowPlaying.parse(answer, now)[1]
        assertEquals("Popołudniówka w piątek", next.name)
        assertEquals(LocalDateTime.of(2026, 10, 30, 16, 0, 1), next.start)
        assertEquals("", next.hosts)
        assertNull(next.imageUrl)
    }

    /** What the guide picks: the programme now, the next one once it has started. */
    @Test
    fun `the guide picks the right one either side of the change`() {
        val slots = R357NowPlaying.parse(answer, now)
        assertEquals("Złodziej czasu", ProgrammeGuidePage.at(slots, now)?.name)
        assertEquals("Popołudniówka w piątek", ProgrammeGuidePage.at(slots, now.plusMinutes(20))?.name)
    }

    @Test
    fun `no programme in the answer is no guide`() {
        assertTrue(R357NowPlaying.parse("""{"s":"x"}""", now).isEmpty())
    }
}
