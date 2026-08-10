package net.mspanc.twinsenradio.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import java.io.ByteArrayOutputStream

/**
 * Buduje metadane, ktore trafiaja do MediaSession, a stamtad do Android Auto
 * i dalej do wyswietlacza w desce rozdzielczej.
 */
class MetadataFactory(private val context: Context, private val prefs: Prefs) {

    private val artworkBytesCache = HashMap<String, ByteArray?>()

    /** Metadane pozycji na liscie przegladania (Android Auto rysuje z nich kafelek). */
    fun forBrowseItem(station: Station): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(station.name)
            .setSubtitle(station.genre)
            .setArtist(station.genre)
            .setStation(station.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setArtworkUri(logoUri(station))
            .build()

    fun forFolder(title: String, mediaType: Int, artwork: Uri? = null): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setArtworkUri(artwork)
            .build()

    /**
     * Metadane aktualnie odtwarzanej stacji.
     *
     * W trybie diagnostycznym kazde pole dostaje swoja polska nazwe - to jest ta
     * wersja, ktora sluzy do rozpoznania ukladu na AID w Passacie.
     */
    fun forPlayback(station: Station, now: NowPlaying?): MediaMetadata {
        val b = MediaMetadata.Builder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)

        if (prefs.diagnosticMode) {
            val withApi = prefs.diagnosticShowApiName
            fun v(api: String) = DiagnosticFields.TEXT.first { it.api == api }
                .let { DiagnosticFields.value(it, withApi) }

            b.setTitle(v("title"))
                .setArtist(v("artist"))
                .setAlbumTitle(v("albumTitle"))
                .setAlbumArtist(v("albumArtist"))
                .setDisplayTitle(v("displayTitle"))
                .setSubtitle(v("subtitle"))
                .setDescription(v("description"))
                .setStation(v("station"))
                .setGenre(v("genre"))
                .setComposer(v("composer"))
                .setWriter(v("writer"))
                .setConductor(v("conductor"))
                .setCompilation(v("compilation"))
                .setTrackNumber(DiagnosticFields.TRACK_NUMBER)
                .setTotalTrackCount(DiagnosticFields.TOTAL_TRACKS)
                .setDiscNumber(DiagnosticFields.DISC_NUMBER)
                .setTotalDiscCount(DiagnosticFields.TOTAL_DISCS)
                .setRecordingYear(DiagnosticFields.RECORDING_YEAR)
                .setReleaseYear(DiagnosticFields.RELEASE_YEAR)
        } else {
            val song = now?.songTitle
            val artist = now?.artist
            b.setTitle(song ?: station.name)
                .setArtist(artist ?: station.name)
                .setAlbumTitle(station.name)
                .setAlbumArtist(station.name)
                .setDisplayTitle(song ?: station.name)
                .setSubtitle(artist ?: station.genre)
                .setDescription(now?.raw ?: station.genre)
                .setStation(station.name)
                .setGenre(station.genre)
        }

        applyArtwork(b, station)
        return b.build()
    }

    private fun applyArtwork(b: MediaMetadata.Builder, station: Station) {
        when (prefs.artworkMode) {
            ArtworkMode.NONE -> Unit
            ArtworkMode.EMBEDDED_BYTES -> {
                val bytes = artworkBytes(station)
                if (bytes != null) {
                    b.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                } else {
                    b.setArtworkUri(logoUri(station))
                }
            }
            else -> b.setArtworkUri(logoUri(station))
        }
    }

    /** URI logotypu: wbudowany zasob albo zdalna grafika z listy M3U. */
    fun logoUri(station: Station): Uri {
        station.logoUrl?.let { return Uri.parse(it) }
        val resId = logoResId(station)
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    fun logoResId(station: Station): Int {
        val name = station.logo ?: return R.drawable.logo_placeholder
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else R.drawable.logo_placeholder
    }

    private fun artworkBytes(station: Station): ByteArray? =
        artworkBytesCache.getOrPut(station.id) {
            runCatching {
                val drawable = ContextCompat.getDrawable(context, logoResId(station)) ?: return@runCatching null
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
                    val size = ART_SIZE
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                        val c = Canvas(bmp)
                        drawable.setBounds(0, 0, size, size)
                        drawable.draw(c)
                    }
                }
                val scaled = if (bitmap.width > ART_SIZE || bitmap.height > ART_SIZE) {
                    Bitmap.createScaledBitmap(bitmap, ART_SIZE, ART_SIZE, true)
                } else {
                    bitmap
                }
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }.getOrNull()
        }

    private companion object {
        /** 512 px to rozsadny kompromis: HU dostaje ostry obrazek, a Binder nie puchnie. */
        const val ART_SIZE = 512
    }
}

/** To, co przyszlo w metadanych ICY (Icecast/SHOUTcast) dla biezacego strumienia. */
data class NowPlaying(
    val raw: String,
    val artist: String?,
    val songTitle: String?
) {
    companion object {
        /**
         * Typowy StreamTitle to "Wykonawca - Tytul". Reklamy wstrzykiwane przez
         * niektore rozglosnie (RMF) przychodza jako pusty tytul z adw_ad='true' -
         * takie wpisy odrzucamy, zeby na desce nie migala pustka.
         */
        fun parse(streamTitle: String?): NowPlaying? {
            val raw = streamTitle?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val dash = raw.indexOf(" - ")
            return if (dash > 0) {
                NowPlaying(raw, raw.substring(0, dash).trim(), raw.substring(dash + 3).trim())
            } else {
                NowPlaying(raw, null, raw)
            }
        }
    }
}
