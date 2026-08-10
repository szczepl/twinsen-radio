package net.mspanc.twinsenradio.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Polityka ponawiania skrojona pod radio internetowe.
 *
 * Chwilowa utrata LTE nie jest bledem, tylko przerwa - ExoPlayer ma po prostu
 * probowac dalej, w nieskonczonosc, a odtwarzacz zostaje w stanie BUFFERING.
 * Dzieki temu do Android Auto nie leci zaden komunikat o bledzie.
 *
 * Wyjatek: trwale odpowiedzi 4xx (np. 404 na wycofanym adresie strumienia) nie
 * naprawia sie same, wiec te przepuszczamy dalej - lepiej, zeby uzytkownik
 * zobaczyl, ze stacja wymaga poprawki, niz zeby aplikacja cicho mielila.
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
