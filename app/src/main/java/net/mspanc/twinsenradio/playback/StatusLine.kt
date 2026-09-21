package net.mspanc.twinsenradio.playback

import android.content.Context
import net.mspanc.twinsenradio.R

/**
 * What the state of the connection has to say on the dashboard.
 *
 * Until now this lived only on the phone's own screen, which is the one screen
 * nobody is looking at while driving: the head unit went on showing the last
 * track it was told about, and a station that had stopped answering looked
 * exactly like a station playing something quiet. The message belongs where the
 * driver is actually looking.
 *
 * It goes in the middle line - the only one the AID has to itself (FINDINGS.md,
 * section 1), so a warning there does not push the track off the central screen.
 *
 * null means this state has nothing to add, and the line keeps whatever the
 * layout puts there. [PlaybackStatusBus.Status.BUFFERING] is deliberately one of
 * those: refilling a buffer for a second happens on any road, and a line that
 * flashes a warning and takes it back reads worse than a line that says nothing.
 * Buffering that outlasts its welcome arrives here as
 * [PlaybackStatusBus.Status.CONNECTING] instead - see [RadioService.armConnecting].
 */
object StatusLine {

    fun textFor(context: Context, status: PlaybackStatusBus.Status): String? = when (status) {
        PlaybackStatusBus.Status.CONNECTING -> context.getString(R.string.aid_connecting)
        PlaybackStatusBus.Status.RECONNECTING -> context.getString(R.string.aid_reconnecting)
        PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> context.getString(R.string.aid_waiting_network)
        PlaybackStatusBus.Status.STATION_UNREACHABLE -> context.getString(R.string.aid_unreachable)
        PlaybackStatusBus.Status.IDLE,
        PlaybackStatusBus.Status.BUFFERING,
        PlaybackStatusBus.Status.PLAYING -> null
    }
}
