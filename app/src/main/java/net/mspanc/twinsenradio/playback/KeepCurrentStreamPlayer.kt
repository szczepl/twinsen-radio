package net.mspanc.twinsenradio.playback

import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Nakladka, ktora ignoruje prosbe o wlaczenie stacji, ktora wlasnie gra.
 *
 * Bez tego wybranie tej samej pozycji z listy - czy to w aplikacji, czy w Android
 * Auto - powodowalo `setMediaItems` + `prepare`, czyli rozlaczenie i ponowne
 * podlaczenie do strumienia. Slychac to jako sekundowa przerwe, a przy radiu na
 * zywo nie ma to zadnego sensu: to ten sam, nieprzerwany strumien.
 *
 * Nakladka siedzi w warstwie odtwarzacza celowo - dzieki temu obowiazuje tak samo
 * dla telefonu, dla glowicy i dla sterowania glosem, bez powtarzania warunku
 * w kazdym z tych miejsc.
 */
@UnstableApi
class KeepCurrentStreamPlayer(player: Player) : ForwardingPlayer(player) {

    override fun setMediaItem(mediaItem: MediaItem) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    /**
     * Pojedyncza pozycja o tym samym mediaId co grajaca, przy zywym odtwarzaniu.
     * Stan IDLE albo ENDED oznacza, ze strumien i tak trzeba podniesc od nowa.
     */
    private fun isAlreadyPlaying(mediaItems: List<MediaItem>): Boolean {
        if (mediaItems.size != 1) return false
        val requested = mediaItems.first().mediaId
        val current = currentMediaItem?.mediaId ?: return false
        if (requested != current) return false
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return false

        Log.i(TAG, "stacja $requested juz gra - nie przerywam strumienia")
        if (!playWhenReady) play()
        return true
    }

    private companion object {
        const val TAG = "KeepCurrentStream"
    }
}
