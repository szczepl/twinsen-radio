package net.mspanc.twinsenradio.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StreamVariant

/**
 * Krotki opis wariantu na przycisk: "MP3, 256 kbps". Kodek bierzemy z pierwszego
 * slowa etykiety (nasze etykiety zawsze zaczynaja sie od niego, np. "MP3 256 kb/s
 * — zapasowy") - to unika osobnego pola tylko na te dwa znaki.
 */
fun StreamVariant.shortLabel(): String {
    val codec = label.substringBefore(' ').takeIf { it.isNotBlank() && it.any(Char::isLetter) }
        ?: "Strumień"
    return if (kbps > 0) "$codec, $kbps kbps" else codec
}

/** Sama przeplywnosc, bez kodeka - na ciasny przycisk przy pasku mini-playera. */
fun StreamVariant.kbpsLabel(): String = if (kbps > 0) "$kbps kbit" else shortLabel()

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
