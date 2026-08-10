package net.mspanc.twinsenradio.playback

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * Polityka ponawiania celowo nie zglasza bledow sieci wyzej, zeby w aucie nie
 * migaly komunikaty. Efekt uboczny jest taki, ze zerwany strumien wyglada
 * dokladnie jak wolne buforowanie. Ten sluchacz zapisuje w logu, co sie
 * naprawde dzieje - inaczej diagnoza w terenie jest wrozeniem z fusow.
 *
 *   adb logcat -s LoadDiag
 */
@UnstableApi
class LoadDiagnostics : AnalyticsListener {

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(TAG, "start pobierania: ${loadEventInfo.dataSpec.uri}")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(
            TAG,
            "zakonczone: ${loadEventInfo.bytesLoaded} B w ${loadEventInfo.loadDurationMs} ms " +
                "(${loadEventInfo.uri})"
        )
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        Log.w(
            TAG,
            "BLAD pobierania (${loadEventInfo.uri}) po ${loadEventInfo.loadDurationMs} ms, " +
                "pobrano ${loadEventInfo.bytesLoaded} B, anulowane=$wasCanceled: " +
                "${error.javaClass.simpleName}: ${error.message}"
        )
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(TAG, "anulowano pobieranie: ${loadEventInfo.uri}")
    }

    private companion object {
        const val TAG = "LoadDiag"
    }
}
