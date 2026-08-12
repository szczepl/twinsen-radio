package net.mspanc.twinsenradio.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * A retry policy tailored for internet radio.
 *
 * A momentary loss of LTE is not an error, just an interruption - ExoPlayer
 * should simply keep trying, indefinitely, while the player stays in the
 * BUFFERING state. That way no error message ever reaches Android Auto.
 *
 * Exception: persistent 4xx responses (e.g. a 404 on a decommissioned stream
 * address) won't fix themselves, so we let those through - it's better for
 * the user to see that the station needs fixing than for the app to silently
 * grind away.
 */
@UnstableApi
class InfiniteLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        if (exception is HttpDataSource.InvalidResponseCodeException) {
            val code = exception.responseCode
            val transient = code == 408 || code == 429
            if (code in 400..499 && !transient) {
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        val step = (loadErrorInfo.errorCount - 1).coerceIn(0, BACKOFF_MS.size - 1)
        return BACKOFF_MS[step]
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
