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
import net.mspanc.twinsenradio.playback.LogoProvider
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
     *   on the spot; a remote one (http(s)) is fetched the same way cover art
     *   is. Shown when there is no cover, or the cover can't be fetched. This is what's shown whenever there's
     *   no cover art - previously callers only passed a static resource here,
     *   so a station with a custom or remote logo but no cover art showed the
     *   bare placeholder instead of its own logo.
     * @param fallbackRes shown when there's neither cover art nor any logo.
     */
    /**
     * @param drawn a picture made on the spot - the clock face - which beats
     *   everything else and needs no loading at all.
     * @param muted the picture faded back, which means "not playing" - the
     *   logo before play, for a station with no picture of its own for that
     *   moment. Never while something plays.
     */
    fun into(
        scope: LifecycleCoroutineScope,
        coverUri: Uri?,
        logoUri: Uri?,
        fallbackRes: Int,
        target: ImageView,
        drawn: Bitmap? = null,
        muted: Boolean = false,
        blank: Boolean = false
    ) {
        target.alpha = if (muted) LogoProvider.MUTED_ALPHA else 1f
        if (blank) {
            // The picture isn't decided yet (see PlaybackStatusBus.join):
            // an empty slot now, the right picture once - not a logo first.
            target.setTag(TAG_KEY, null)
            target.setImageDrawable(null)
            return
        }
        if (drawn != null) {
            // A cover still on its way must not land on top of the clock.
            target.setTag(TAG_KEY, null)
            target.setImageBitmap(drawn)
            return
        }

        // The custom logo lives in the app's directory - decoded right away,
        // without downloading and without caching by URL.
        val localLogo = logoUri?.takeIf { it.scheme == "file" }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

        // The logo, or the bare placeholder - for when there is nothing better,
        // and only then. It used to go up first as a "resting image" under every
        // download, so a cover arriving after play was preceded by the logo for
        // the half second the download took - exactly the flash the empty slot
        // after play exists to prevent (seen 30.10.2026 on the phone, frame by
        // frame: empty, logo, cover).
        fun resting() {
            if (localLogo != null) target.setImageBitmap(localLogo) else target.setImageResource(fallbackRes)
        }

        // What has to be fetched: the presenter's photo (ours already, behind
        // LogoProvider - the same address the head unit gets), the cover, or a
        // remote station logo.
        val key = coverUri?.takeIf { it.scheme == "content" || it.scheme == "http" || it.scheme == "https" }
            ?: logoUri?.takeIf { it.scheme == "http" || it.scheme == "https" }
        if (key == null) {
            target.setTag(TAG_KEY, null)
            resting()
            return
        }
        cache.get(key.toString())?.let {
            target.setTag(TAG_KEY, null)
            target.setImageBitmap(it)
            return
        }

        // Not in the cache: what is showing stays until the picture arrives -
        // the empty slot after play, or the previous picture for the moment a
        // download takes - and the logo goes up only if the download fails.
        target.setTag(TAG_KEY, key.toString())
        val resolver = target.context.applicationContext.contentResolver
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (key.scheme == "content") {
                    runCatching { resolver.openInputStream(key)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                } else {
                    fetch(key.toString())
                }
            }
            // the track (or station) may have changed in the meantime
            if (target.getTag(TAG_KEY) != key.toString()) return@launch
            if (bitmap != null) {
                cache.put(key.toString(), bitmap)
                target.setImageBitmap(bitmap)
            } else {
                resting()
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
