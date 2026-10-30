package net.mspanc.twinsenradio.playback

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.Station
import java.io.File

/**
 * The picture for "nie gra" - before play, or paused - where the station
 * publishes one: see [Station.standbyArtUrl].
 *
 * Fetched once and kept: RNS's is a 1.2 MB banner. Until it is in, the slot
 * shows the faded logo, and [onReady] asks for a redraw when it lands. The
 * phone reads the file directly; the head unit gets it through [LogoProvider].
 */
object StandbyArt {

    private val loading = HashSet<String>()

    /** The picture on disk, or null - in which case it is being fetched, and [onReady] follows. */
    fun file(context: Context, station: Station, scope: CoroutineScope, onReady: () -> Unit): File? {
        val url = station.standbyArtUrl ?: return null
        // The address is in the name, so a new picture in the catalogue is a new file.
        val file = File(File(context.filesDir, "standby"), "${station.id.hashCode()}-${url.hashCode()}.jpg")
        if (file.exists() && file.length() > 0) return file
        val start = synchronized(loading) { loading.add(url) }
        if (start) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { SquareArt.save(url, file) }
                synchronized(loading) { loading.remove(url) }
                if (ok) onReady()
            }
        }
        return null
    }

    fun phoneUri(context: Context, station: Station, scope: CoroutineScope, onReady: () -> Unit): Uri? =
        file(context, station, scope, onReady)?.let { Uri.fromFile(it) }
}
