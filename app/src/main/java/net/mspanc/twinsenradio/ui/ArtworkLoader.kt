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
 * Wczytuje okladke do ImageView. Swiadomie bez zewnetrznej biblioteki - potrzeba
 * jest jedna: pobrac jeden obrazek po HTTP i pokazac go, z prostym cache.
 *
 * Adresy inne niz http(s) - np. android.resource:// z logo stacji - obsluguje
 * sam ImageView, wiec ustawiamy je od razu jako zasob.
 */
object ArtworkLoader {

    private val cache = LruCache<String, Bitmap>(8)

    /**
     * @param uri okladka z metadanych sesji; null albo nie-HTTP oznacza, ze
     *   pokazujemy [fallbackRes]
     */
    fun into(
        scope: LifecycleCoroutineScope,
        uri: Uri?,
        fallbackRes: Int,
        target: ImageView
    ) {
        // Wlasne logo uzytkownika lezy w katalogu aplikacji - czytamy je wprost,
        // bez pobierania i bez cache po adresie.
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

        // pokaz logo, dopoki okladka sie nie sciagnie
        target.setImageResource(fallbackRes)
        target.setTag(TAG_KEY, url)

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url) }
            // w miedzyczasie utwor mogl sie zmienic
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
