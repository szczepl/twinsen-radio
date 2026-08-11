package net.mspanc.twinsenradio.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Prosty kanal informacyjny miedzy usluga odtwarzania a UI telefonu.
 * Oba zyja w tym samym procesie, wiec nie ma sensu ciagnac tego przez Bindera.
 */
object PlaybackStatusBus {

    private val _stationId = MutableStateFlow<String?>(null)
    val stationId: StateFlow<String?> = _stationId

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    /**
     * Okladka biezacego utworu, niezaleznie od tego, co poszlo do Android Auto.
     * Telefon ma zawsze pokazywac okladke albo logo stacji - zegar zamiast
     * okladki jest ficzerem wylacznie dla ekranu w aucie.
     */
    private val _coverArtUrl = MutableStateFlow<String?>(null)
    val coverArtUrl: StateFlow<String?> = _coverArtUrl

    fun setCoverArt(url: String?) {
        _coverArtUrl.value = url
    }

    /** Co katalog wie o utworze - wydawnictwo i rok, potrzebne takze na telefonie. */
    private val _trackInfo = MutableStateFlow<CoverArtLookup.TrackInfo?>(null)
    val trackInfo: StateFlow<CoverArtLookup.TrackInfo?> = _trackInfo

    fun setTrackInfo(info: CoverArtLookup.TrackInfo?) {
        _trackInfo.value = info
    }

    /**
     * Opis jakosci biezacego strumienia - kodek, przeplywnosc, probkowanie.
     * Tylko dla telefonu; w aucie nie ma pola, w ktorym daloby sie to napisac.
     */
    private val _quality = MutableStateFlow<String?>(null)
    val quality: StateFlow<String?> = _quality

    fun setQuality(text: String?) {
        _quality.value = text
    }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    enum class Status {
        IDLE, CONNECTING, BUFFERING, PLAYING, RECONNECTING, WAITING_FOR_NETWORK,

        /** Siec jest, ale stacja milczy - najczesciej wycofany adres strumienia. */
        STATION_UNREACHABLE
    }

    fun setStation(id: String?) {
        if (_stationId.value != id) {
            _stationId.value = id
            _nowPlaying.value = null
            _coverArtUrl.value = null
            _trackInfo.value = null
            // Nowa stacja to nowy strumien - stara jakosc przestaje obowiazywac
            // od razu, a nowa poznamy dopiero po pierwszej ramce z dekodera.
            _quality.value = null
        }
    }

    fun setNowPlaying(np: NowPlaying?) {
        _nowPlaying.value = np
    }

    fun setStatus(s: Status) {
        _status.value = s
    }
}
