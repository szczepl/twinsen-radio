package net.mspanc.twinsenradio.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import net.mspanc.twinsenradio.data.StationRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A wide picture from a station's website, cut to the square the head unit and
 * the phone draw it in, and kept on disk.
 *
 * Both of its users get banners: a presenter's photo from the programme guide
 * (Mikolaj Tyczynski's is 8:3) and RNS's picture for the screen before play
 * (16:9, 8000 px wide). The middle square is what the station's own thumbnails
 * cut out too, and fetching the original once beats a soft 150 px thumbnail
 * every time.
 */
object SquareArt {

    private const val TAG = "SquareArt"
    private const val TIMEOUT_MS = 10_000

    const val SIZE = 512

    /** Downloads [url], cuts the middle square, scales it to [SIZE], saves it as [file]. */
    fun save(url: String, file: File): Boolean = runCatching {
        val bytes = download(url) ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(file)
    }.onFailure { Log.w(TAG, "$url: ${it.message}") }.getOrDefault(false)

    private fun download(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "$url: ${it.message}") }.getOrNull()
}
