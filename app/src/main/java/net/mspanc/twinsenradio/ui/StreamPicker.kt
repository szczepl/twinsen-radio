package net.mspanc.twinsenradio.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StreamVariant

/**
 * Sama przeplywnosc, bez kodeka - na przycisk jakosci, zarowno na Now Playing,
 * jak i przy pasku mini-playera. `null`, gdy nie znamy jej znikad - przycisk
 * ma sie wtedy schowac, a nie pokazywac placeholder.
 *
 * @param liveKbps rzeczywista przeplywnosc z dekodera (patrz
 *   [net.mspanc.twinsenradio.playback.PlaybackStatusBus.qualityKbps]) - zapasowa,
 *   gdy stacja to "goly" adres bez zadeklarowanej przeplywnosci w katalogu.
 */
fun StreamVariant.kbpsLabel(liveKbps: Int? = null): String? =
    (kbps.takeIf { it > 0 } ?: liveKbps?.takeIf { it > 0 })?.let { "$it kbit" }

/**
 * Etykieta na liste w dialogu wyboru: sam kodek i przeplywnosc, bez dopiskow
 * typu "— zapasowy" czy "— wysoka jakosc" - to ocena, nie fakt o strumieniu.
 */
fun StreamVariant.plainLabel(): String = label.substringBefore('—').trim()

/**
 * Ten sam dialog wyboru strumienia na ekranie odtwarzania i na pasku
 * mini-playera - lista wariantow z zaznaczeniem, Anuluj/OK. Zmiana leci przez
 * [Prefs], usluga sama przeladuje strumien.
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
