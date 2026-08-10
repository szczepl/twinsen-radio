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

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    enum class Status { IDLE, CONNECTING, BUFFERING, PLAYING, RECONNECTING, WAITING_FOR_NETWORK }

    fun setStation(id: String?) {
        if (_stationId.value != id) {
            _stationId.value = id
            _nowPlaying.value = null
        }
    }

    fun setNowPlaying(np: NowPlaying?) {
        _nowPlaying.value = np
    }

    fun setStatus(s: Status) {
        _status.value = s
    }
}
