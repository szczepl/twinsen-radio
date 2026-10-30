package net.mspanc.twinsenradio.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.playback.SquareArt
import java.io.File

/**
 * The picture for the screen before play, where the station publishes one -
 * see [Station.standbyArtUrl].
 *
 * Fetched once and kept: RNS's is a 1.2 MB banner, and the screen before play
 * is the first thing the app shows. Until it is in, the screen shows the faded
 * logo, and is redrawn when it lands.
 */
object StandbyArt {

    private val loading = HashSet<String>()

    /** The picture on disk, or null - in which case it is being fetched, and [onReady] follows. */
    fun uri(context: Context, station: Station, scope: LifecycleCoroutineScope, onReady: () -> Unit): Uri? {
        val url = station.standbyArtUrl ?: return null
        // The address is in the name, so a new picture in the catalogue is a new file.
        val file = File(File(context.filesDir, "standby"), "${station.id.hashCode()}-${url.hashCode()}.jpg")
        if (file.exists() && file.length() > 0) return Uri.fromFile(file)
        if (loading.add(url)) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { SquareArt.save(url, file) }
                loading.remove(url)
                if (ok) onReady()
            }
        }
        return null
    }
}
