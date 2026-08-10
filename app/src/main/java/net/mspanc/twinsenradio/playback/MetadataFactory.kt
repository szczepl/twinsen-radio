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
import net.mspanc.twinsenradio.data.ClockColors
import net.mspanc.twinsenradio.data.ClockFace
import net.mspanc.twinsenradio.data.Presentation
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Slot
import net.mspanc.twinsenradio.data.Station
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
    fun forPlayback(
        station: Station,
        now: NowPlaying?,
        coverArtUrl: String? = null,
        trackInfo: CoverArtLookup.TrackInfo? = null
    ): MediaMetadata {
        val b = MediaMetadata.Builder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)

        if (prefs.diagnosticMode) {
            val withApi = prefs.diagnosticShowApiName
            val clock = clockText()

            // Kazde pole niesie swoja nazwe ORAZ zegar, np. "TYT.WYSW 16:44".
            // Jeden wyjazd daje wtedy odpowiedz na dwa pytania naraz: ktore pole
            // glowica pokazuje i czy odswieza je w trakcie odtwarzania, czy
            // zamraza na wartosci z chwili rozpoczecia utworu.
            fun v(api: String): String {
                val field = DiagnosticFields.TEXT.first { it.api == api }
                return DiagnosticFields.value(field, withApi) + " " + clock
            }

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
            val p = Presentation.at(prefs.presentationMode)
            val top = textFor(p.top, station, now, trackInfo)
            val middle = textFor(p.middle, station, now, trackInfo)
            val bottom = textFor(p.bottom, station, now, trackInfo)

            // Srodkowa linia idzie w albumTitle - to najbardziej prawdopodobne
            // zrodlo srodkowej linii na desce. `station` zostawiamy zawsze na
            // nazwie rozglosni, bo to pole ma znaczenie semantyczne i inne
            // aplikacje moga na nim polegac.
            // Nazwa stacji tylko raz. Gdy stoi juz w srodkowej linii, nie powtarzamy
            // jej w albumArtist - inaczej glowica potrafi pokazac "RMF FM" dwa razy
            // pod rzad, co widac zwlaszcza podczas reklam i serwisow.
            val albumArtist = if (middle == station.name) "" else station.name

            b.setArtist(top)
                .setAlbumTitle(middle)
                .setStation(station.name)
                .setTitle(bottom)
                .setAlbumArtist(albumArtist)
                // Android Auto pokazuje na duzym ekranie wlasnie te dwa pola
                .setDisplayTitle(bottom)
                .setSubtitle(top)
                .setDescription(describe(station, now))
                .setGenre(station.genre)
        }

        applyArtwork(b, station, coverArtUrl)
        return b.build()
    }

    /**
     * Tresc pojedynczej linii. Celowo zwraca pusty napis zamiast nazwy stacji,
     * gdy nie ma utworu - powielanie nazwy w kilku liniach to dokladnie ta wada,
     * ktora widac w ReplaIO i w oficjalnej aplikacji RNS.
     */
    private fun textFor(
        slot: Slot,
        station: Station,
        now: NowPlaying?,
        trackInfo: CoverArtLookup.TrackInfo? = null
    ): String = when (effectiveSlot(slot)) {
        Slot.CLOCK -> clockText()
        Slot.STATION -> station.name
        Slot.EMPTY -> ""
        // Etykieta reklamy trafia wylacznie w linie tytulu - powtorzona w dwoch
        // liniach wygladalaby dokladnie tak, jak zdublowana nazwa stacji.
        Slot.ARTIST -> if (now?.isRealSong == true) artistLine(now, trackInfo) else ""
        Slot.TITLE -> when {
            now?.isRealSong == true -> now.songTitle.orEmpty()
            now?.isNews == true -> "${now.slogan} — serwis informacyjny"
            now?.slogan != null -> now.slogan!!
            now?.isAd == true -> adText(now)
            else -> ""
        }
    }

    /** Zamiana tytulu z wykonawca dziala na poziomie tresci, nie ukladu pol. */
    private fun effectiveSlot(slot: Slot): Slot {
        if (!prefs.swapTitleArtist) return slot
        return when (slot) {
            Slot.ARTIST -> Slot.TITLE
            Slot.TITLE -> Slot.ARTIST
            else -> slot
        }
    }

    /**
     * Linia wykonawcy, w razie potrzeby uzupelniona o wydawnictwo.
     *
     * RMF sam podaje juz gotowe "Wiktoria Kida / Księga" - takiego zapisu nie
     * ruszamy. Stacje, ktore daja sam nazwisko, uzupelniamy o to, co wie katalog
     * iTunes: "Kaeyra / singiel [2024]".
     */
    private fun artistLine(now: NowPlaying, info: CoverArtLookup.TrackInfo?): String =
        composeArtistLine(now, info, prefs.enrichWithAlbum)

    private fun adText(now: NowPlaying): String {
        val seconds = now.adDurationMs / 1000
        return if (seconds > 0) "$AD_LABEL · ${seconds}s" else AD_LABEL
    }

    private fun describe(station: Station, now: NowPlaying?): String = when {
        now?.isRealSong == true -> now.raw
        now?.slogan != null -> now.slogan!!
        now?.isAd == true -> adText(now)
        else -> station.genre
    }

    private fun applyArtwork(b: MediaMetadata.Builder, station: Station, coverArtUrl: String?) {
        // Zegar zamiast okladki - rysowany w locie, wiec nie ma adresu i musi
        // pojechac jako bajty.
        val p = Presentation.at(prefs.presentationMode)
        if (p.clockFace != ClockFace.NONE) {
            val useClock = prefs.clockCoverAlways || coverArtUrl == null
            if (useClock) {
                val bg = ClockColors.background(prefs.clockBackground)
                val fg = ClockColors.foreground(prefs.clockForeground, bg)
                ClockArt.pngBytes(p.clockFace, bg, fg)?.let {
                    b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    return
                }
            }
        }
        // Doszukana okladka utworu ma pierwszenstwo przed logo stacji.
        // Celowo niezalezne od trybu diagnostycznego: tryb podmienia wylacznie
        // pola tekstowe na etykiety, grafika ma zachowywac sie zawsze tak samo.
        if (coverArtUrl != null) {
            b.setArtworkUri(Uri.parse(coverArtUrl))
            return
        }
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

    /**
     * URI logotypu: wbudowany zasob albo zdalna grafika z listy M3U.
     *
     * Wbudowane logo idzie przez [LogoProvider], a nie jako android.resource://
     * z numerem zasobu - patrz komentarz w tamtej klasie: numery zmieniaja sie
     * miedzy wersjami, a Android Auto cache'uje grafike po adresie.
     */
    fun logoUri(station: Station): Uri {
        station.logoUrl?.let { return Uri.parse(it) }
        return LogoProvider.uriFor(context, station, logoResId(station))
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

    companion object {
        /** 512 px to rozsadny kompromis: HU dostaje ostry obrazek, a Binder nie puchnie. */
        private const val ART_SIZE = 512

        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        const val AD_LABEL = "Reklama"

        /**
         * Linia wykonawcy, w razie potrzeby uzupelniona o wydawnictwo.
         *
         * Wspoldzielona przez metadane dla auta i ekran odtwarzania w telefonie -
         * inaczej na telefonie brakowaloby nazwy plyty, ktora widac w aucie.
         *
         * RMF sam podaje gotowe "Wiktoria Kida / Księga" - takiego zapisu nie
         * ruszamy. Stacjom, ktore daja samego wykonawce, dokladamy to, co wie
         * katalog: "Kaeyra · singiel [2024]".
         */
        fun composeArtistLine(
            now: NowPlaying,
            info: CoverArtLookup.TrackInfo?,
            enrich: Boolean
        ): String {
            val artist = now.artist.orEmpty()
            if (artist.isBlank()) return ""
            if (!enrich || info == null) return artist

            // Odetnij koncowke tylko wtedy, gdy katalog potwierdza, ze to nazwa
            // plyty. Inaczej "Shimza / AR/CO / Kasango" stracilby trzeciego wykonawce.
            val parts = artist.split(Regex("\\s+[/,]\\s+"))
            val artistsOnly = if (parts.size > 1 && info.tailIsAlbum(parts.last())) {
                parts.dropLast(1).joinToString(" / ")
            } else {
                artist
            }

            val album = info.albumLabel() ?: return artistsOnly
            // Wydawnictwo oddzielamy kropka srodkowa, a nie ukosnikiem - przy kilku
            // wykonawcach po ukosnikach nie dalo by sie ich odroznic od plyty.
            return "$artistsOnly · $album"
        }

        /** Zawsze dwucyfrowa godzina i minuta, np. "09:07". */
        fun clockText(): String = LocalTime.now().format(CLOCK_FORMAT)
    }
}

/**
 * To, co przyszlo w metadanych ICY (Icecast/SHOUTcast) dla biezacego strumienia.
 *
 * [isStationSelfTitle] oznacza przypadek, w ktorym rozglosnia wpisala w to samo
 * pole nie utwor, tylko wlasna nazwe i slogan albo nazwe audycji - np.
 * "Radio Nowy Świat - Pion i poziom!". Naiwny podzial po " - " robi wtedy
 * "wykonawce" rownego nazwie stacji i nazwa laduje na ekranie dwa razy.
 */
data class NowPlaying(
    val raw: String,
    val artist: String?,
    val songTitle: String?,
    val isStationSelfTitle: Boolean = false,
    val isAd: Boolean = false,
    val adDurationMs: Long = 0,
    /** Znacznik sterujacy rozglosni, np. STOP_AD_BREAK - nie jest trescia. */
    val isControlMarker: Boolean = false
) {
    /** Slogan albo nazwa audycji - to, co stacja wpisala zamiast utworu. */
    val slogan: String? get() = if (isStationSelfTitle) songTitle else null

    /**
     * Serwis informacyjny. RMF oznacza go jako "RMF FM - FAKTY", inne stacje
     * uzywaja slow "wiadomosci", "informacje", "serwis". To nie jest utwor, ale
     * warto to nazwac po imieniu zamiast pokazywac pusta linie.
     */
    val isNews: Boolean
        get() = slogan?.let { s -> NEWS_WORDS.any { s.contains(it, ignoreCase = true) } } == true

    /** Czy naprawde leci utwor, a nie reklama, znacznik ani wlasna zapowiedz stacji. */
    val isRealSong: Boolean
        get() = !isAd && !isStationSelfTitle && !isControlMarker && !songTitle.isNullOrBlank()

    companion object {
        /**
         * Typowy StreamTitle to "Wykonawca - Tytul".
         *
         * @param stationName nazwa stacji, potrzebna do rozpoznania wlasnego sloganu
         * @param rawBlock caly blok ICY - stad rozpoznajemy reklamy. RMF wysyla
         *   pusty StreamTitle wraz z adw_ad='true', adId i durationMilliseconds.
         */
        fun parse(
            streamTitle: String?,
            stationName: String? = null,
            rawBlock: String? = null
        ): NowPlaying? {
            val block = rawBlock.orEmpty()
            val isAd = Regex("""adw_ad='true'""").containsMatchIn(block) ||
                Regex("""adId='[^']+'""").containsMatchIn(block)
            val adMs = Regex("""durationMilliseconds='(\d+)'""")
                .find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0

            val raw = streamTitle?.trim().orEmpty()

            // Rozglosnie oznaczaja reklamy na dwa sposoby i potrafia je zmieniac:
            // RMF wysylal blok z adw_ad='true', a teraz przysyla po prostu
            // StreamTitle='Reklama'. Bez tego wykazu slowo "Reklama" trafialo do
            // wyszukiwarki okladek i wracalo z okladka uzbeckiej piosenki o tym
            // tytule.
            if (raw.isNotEmpty() && AD_WORDS.any { it.equals(raw, ignoreCase = true) }) {
                return NowPlaying(raw, null, null, isAd = true, adDurationMs = adMs)
            }

            if (raw.isEmpty()) {
                // Pusty tytul sam w sobie nie niesie nic, ale jesli towarzyszy mu
                // znacznik reklamy, to jest konkretna informacja warta pokazania.
                return if (isAd) NowPlaying("", null, null, isAd = true, adDurationMs = adMs) else null
            }

            // Znaczniki sterujace: RMF wysyla w StreamTitle np. STOP_AD_BREAK,
            // zeby oznaczyc koniec bloku reklamowego. To nie jest tytul utworu -
            // potraktowany doslownie zostawal na ekranie razem z okladka
            // poprzedniej piosenki.
            if (CONTROL_MARKER.matches(raw)) {
                val upper = raw.uppercase()
                val breakStarts = upper.contains("START") && upper.contains("AD")
                return NowPlaying(
                    raw = raw,
                    artist = null,
                    songTitle = null,
                    isAd = isAd || breakStarts,
                    adDurationMs = adMs,
                    isControlMarker = true
                )
            }

            val dash = raw.indexOf(" - ")
            if (dash <= 0) {
                val selfTitled = stationName != null && similar(raw, stationName)
                return NowPlaying(raw, null, raw, selfTitled, isAd, adMs)
            }

            val left = raw.substring(0, dash).trim()
            val right = raw.substring(dash + 3).trim()
            val selfTitled = stationName != null && similar(left, stationName)
            return NowPlaying(raw, left, right, selfTitled, isAd, adMs)
        }

        /**
         * Same wielkie litery, cyfry i podkreslniki, bez spacji - tak wygladaja
         * znaczniki sterujace rozglosni, a nie tytuly utworow.
         */
        private val CONTROL_MARKER = Regex("^[A-Z0-9][A-Z0-9_]{3,}$")

        /** Slowa, po ktorych rozpoznajemy serwis informacyjny. */
        private val NEWS_WORDS = listOf(
            "fakty", "wiadomosci", "wiadomości", "informacje", "serwis", "news"
        )

        /** Tytuly, ktore w istocie oznaczaja blok reklamowy, a nie utwor. */
        private val AD_WORDS = listOf(
            "reklama", "reklamy", "reklamа",
            "spot reklamowy", "blok reklamowy",
            "advertisement", "advert", "commercial", "ad break", "ads"
        )

        /** Porownanie odporne na diakrytyki, wielkosc liter i slowo "radio". */
        private fun similar(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase()
                .replace("ł", "l")
                .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]"), "")
                .replace("radio", "")
            val na = norm(a)
            val nb = norm(b)
            return na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
        }
    }
}
