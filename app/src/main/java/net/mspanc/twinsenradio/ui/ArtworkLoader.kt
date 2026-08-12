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
 * Loads cover art (or, failing that, a station logo) into an ImageView.
 * Deliberately without an external library - there's only one need: fetch a
 * single image over HTTP and show it, with a simple cache.
 */
object ArtworkLoader {

    private val cache = LruCache<String, Bitmap>(8)

    /**
     * @param coverUri track cover art from session metadata; null or non-http(s)
     *   means we fall through to [logoUri] instead.
     * @param logoUri the station's own logo - a custom one (file://) is decoded
     *   immediately and shown as the resting image; a remote one (http(s)) is
     *   fetched the same way cover art is. This is what's shown whenever there's
     *   no cover art - previously callers only passed a static resource here,
     *   so a station with a custom or remote logo but no cover art showed the
     *   bare placeholder instead of its own logo.
     * @param fallbackRes shown when there's neither cover art nor any logo.
     */
    fun into(
        scope: LifecycleCoroutineScope,
        coverUri: Uri?,
        logoUri: Uri?,
        fallbackRes: Int,
        target: ImageView
    ) {
        // The custom logo lives in the app's directory - decode it right away as
        // the resting image, without downloading and without caching by URL.
        val localLogo = logoUri?.takeIf { it.scheme == "file" }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        if (localLogo != null) {
            target.setImageBitmap(localLogo)
        } else {
            target.setImageResource(fallbackRes)
        }

        // Cover art wins when we have one; a remote station logo is the next best
        // thing to fetch and show instead of the bare placeholder.
        val url = coverUri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
            ?: logoUri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        if (url == null) return

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }

        target.setTag(TAG_KEY, url)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url) }
            // the track (or station) may have changed in the meantime
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
