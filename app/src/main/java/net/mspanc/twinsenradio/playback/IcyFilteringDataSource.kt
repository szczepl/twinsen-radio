package net.mspanc.twinsenradio.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A wrapper around a data source that can completely strip ICY metadata.
 *
 * Why: in diagnostic mode ExoPlayer normally overwrites the `title`,
 * `station` and `genre` fields with whatever came in the icy-* headers and
 * the StreamTitle block. That would ruin the whole experiment - on the AID
 * you'd see the station's name instead of the field label. So we remove the
 * `Icy-MetaData` request header (the server then stops sending the block in
 * the stream) and filter out the `icy-*` response headers (so ExoPlayer
 * won't build an IcyHeaders object from them).
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
