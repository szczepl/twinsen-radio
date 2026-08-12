package net.mspanc.twinsenradio.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A simple information channel between the playback service and the phone
 * UI. Both live in the same process, so there's no point routing this
 * through a Binder.
 */
object PlaybackStatusBus {

    private val _stationId = MutableStateFlow<String?>(null)
    val stationId: StateFlow<String?> = _stationId

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    /**
     * The cover art of the current track, regardless of what was sent to
     * Android Auto. The phone should always show the cover art or the
     * station logo - the clock replacing the cover art is a feature meant
     * only for the screen in the car.
     */
    private val _coverArtUrl = MutableStateFlow<String?>(null)
    val coverArtUrl: StateFlow<String?> = _coverArtUrl

    fun setCoverArt(url: String?) {
        _coverArtUrl.value = url
    }

    /** What the catalog knows about the track - label and year, needed on the phone too. */
    private val _trackInfo = MutableStateFlow<CoverArtLookup.TrackInfo?>(null)
    val trackInfo: StateFlow<CoverArtLookup.TrackInfo?> = _trackInfo

    fun setTrackInfo(info: CoverArtLookup.TrackInfo?) {
        _trackInfo.value = info
    }

    /**
     * A description of the current stream's quality - codec, bitrate, sample
     * rate. For the phone only; in the car there's no field to display it in.
     */
    private val _quality = MutableStateFlow<String?>(null)
    val quality: StateFlow<String?> = _quality

    /**
     * The actual bitrate from the decoder, in kb/s - for stations that don't
     * declare it in the catalog (a single, "bare" stream URL with no
     * variants). In that case the quality button shows this number instead
     * of a placeholder.
     */
    private val _qualityKbps = MutableStateFlow<Int?>(null)
    val qualityKbps: StateFlow<Int?> = _qualityKbps

    fun setQuality(text: String?, kbps: Int? = null) {
        _quality.value = text
        _qualityKbps.value = kbps
    }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    enum class Status {
        IDLE, CONNECTING, BUFFERING, PLAYING, RECONNECTING, WAITING_FOR_NETWORK,

        /** The network is up, but the station is silent - usually a decommissioned stream URL. */
        STATION_UNREACHABLE
    }

    fun setStation(id: String?) {
        if (_stationId.value != id) {
            _stationId.value = id
            _nowPlaying.value = null
            _coverArtUrl.value = null
            _trackInfo.value = null
            // A new station means a new stream - the old quality stops being
            // valid immediately, and we'll only learn the new one after the
            // first frame from the decoder.
            _quality.value = null
            _qualityKbps.value = null
        }
    }

    fun setNowPlaying(np: NowPlaying?) {
        _nowPlaying.value = np
    }

    fun setStatus(s: Status) {
        _status.value = s
    }
}
