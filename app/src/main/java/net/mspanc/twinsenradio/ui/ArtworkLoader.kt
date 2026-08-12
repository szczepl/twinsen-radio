package net.mspanc.twinsenradio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.StationRepository
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads cover art into an ImageView. Deliberately without an external library -
 * there's only one need: fetch a single image over HTTP and show it, with a simple cache.
 *
 * URIs other than http(s) - e.g. android.resource:// from a station's logo - are
 * handled by ImageView itself, so we set them directly as a resource.
 */
object ArtworkLoader {

    private val cache = LruCache<String, Bitmap>(8)

    /**
     * @param uri cover art from session metadata; null or non-HTTP means we
     *   show [fallbackRes]
     */
    fun into(
        scope: LifecycleCoroutineScope,
        uri: Uri?,
        fallbackRes: Int,
        target: ImageView
    ) {
        // The user's own logo lives in the app's directory - we read it directly,
        // without downloading and without caching by URL.
        if (uri?.scheme == "file") {
            val bitmap = runCatching { BitmapFactory.decodeFile(uri.path) }.getOrNull()
            if (bitmap != null) {
                target.setImageBitmap(bitmap)
            } else {
                target.setImageResource(fallbackRes)
            }
            return
        }

        val url = uri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        if (url == null) {
            target.setImageResource(fallbackRes)
            return
        }

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }

        // show the logo until the cover art has downloaded
        target.setImageResource(fallbackRes)
        target.setTag(TAG_KEY, url)

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url) }
            // the track may have changed in the meantime
            if (bitmap != null && target.getTag(TAG_KEY) == url) {
                cache.put(url, bitmap)
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private val TAG_KEY = "artwork_url".hashCode()
}
