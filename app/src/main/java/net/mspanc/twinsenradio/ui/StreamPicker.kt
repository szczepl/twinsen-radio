package net.mspanc.twinsenradio.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StreamVariant

/**
 * Bitrate alone, without the codec - for the quality button, both on Now Playing
 * and on the mini-player bar. `null` when we don't know it from anywhere - the
 * button should then hide itself rather than show a placeholder.
 *
 * @param liveKbps the actual bitrate from the decoder (see
 *   [net.mspanc.twinsenradio.playback.PlaybackStatusBus.qualityKbps]) - a fallback
 *   for when the station is a "bare" URL with no declared bitrate in the catalog.
 */
fun StreamVariant.kbpsLabel(liveKbps: Int? = null): String? =
    (kbps.takeIf { it > 0 } ?: liveKbps?.takeIf { it > 0 })?.let { "$it kbit" }

/**
 * Label for the selection dialog's list: just the codec and bitrate, without
 * suffixes like "— fallback" or "— high quality" - that's a judgment, not a fact about the stream.
 */
fun StreamVariant.plainLabel(): String = label.substringBefore('—').trim()

/**
 * The same stream selection dialog on the playback screen and on the
 * mini-player bar - a list of variants with a selection, Cancel/OK. The change
 * goes through [Prefs], the service reloads the stream on its own.
 */
object StreamPicker {
    fun show(context: Context, station: Station, prefs: Prefs, onChanged: () -> Unit = {}) {
        val variants = station.variants()
        if (variants.size < 2) return
        val current = prefs.selectedStream(station.id) ?: variants.maxByOrNull { it.kbps }?.url
        var picked = variants.indexOfFirst { it.url == current }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.stream_picker_title)
            .setSingleChoiceItems(variants.map { it.plainLabel() }.toTypedArray(), picked) { _, which ->
                picked = which
            }
            .setPositiveButton(R.string.action_ok) { _, _ ->
                prefs.setSelectedStream(station.id, variants[picked].url)
                onChanged()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
