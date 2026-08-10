package net.mspanc.twinsenradio.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Nakladka na zrodlo danych, ktora potrafi calkowicie odciac metadane ICY.
 *
 * Po co: w trybie diagnostycznym ExoPlayer normalnie nadpisuje pola `title`,
 * `station` i `genre` tym, co przyszlo w naglowkach icy-* oraz w bloku
 * StreamTitle. To zepsulo by caly eksperyment - na AID zobaczylbys nazwe
 * rozglosni zamiast etykiety pola. Usuwamy wiec naglowek zadania `Icy-MetaData`
 * (serwer przestaje wysylac blok w strumieniu) i odfiltrowujemy naglowki
 * odpowiedzi `icy-*` (ExoPlayer nie zbuduje z nich obiektu IcyHeaders).
 */
@UnstableApi
class IcyFilteringDataSource(
    private val upstream: DataSource,
    private val stripIcy: Boolean
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!stripIcy) return upstream.open(dataSpec)
        val headers = HashMap(dataSpec.httpRequestHeaders)
        headers.keys.removeAll { it.equals(ICY_REQUEST_HEADER, ignoreCase = true) }
        return upstream.open(dataSpec.buildUpon().setHttpRequestHeaders(headers).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        val headers = upstream.responseHeaders
        if (!stripIcy) return headers
        return headers.filterKeys { !it.lowercase().startsWith("icy-") }
    }

    override fun close() {
        upstream.close()
    }

    @UnstableApi
    class Factory(
        private val delegate: DataSource.Factory,
        private val stripIcy: () -> Boolean
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            IcyFilteringDataSource(delegate.createDataSource(), stripIcy())
    }

    private companion object {
        const val ICY_REQUEST_HEADER = "Icy-MetaData"
    }
}
