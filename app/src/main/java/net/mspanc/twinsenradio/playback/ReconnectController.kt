package net.mspanc.twinsenradio.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Utrzymuje odtwarzanie przy zyciu, gdy LTE znika w tunelu albo na wsi.
 *
 * Zasada: uzytkownik nie ma zobaczyc bledu. Jesli chcial sluchac, to gramy -
 * a jak sie nie da, to czekamy i probujemy dalej. Dwa niezalezne wyzwalacze:
 *
 *  1. `ConnectivityManager` - gdy telefon odzyska sensowna siec, wznawiamy
 *     natychmiast, bez czekania na kolejny krok backoffu;
 *  2. backoff czasowy - na wypadek gdy siec formalnie jest, ale nadajnik lub
 *     CDN nie odpowiada.
 *
 * Wiekszosc przerw obsluguje jeszcze nizej [InfiniteLoadErrorHandlingPolicy] -
 * tam ExoPlayer ponawia pobranie bez zglaszania bledu w ogole. Ten kontroler
 * lapie dopiero to, co sie przez tamto przebilo.
 */
@UnstableApi
class ReconnectController(
    context: Context,
    private val player: Player,
    private val onStatusChanged: (Status) -> Unit
) : Player.Listener {

    /**
     * [STATION_UNREACHABLE] to nie to samo co [RECONNECTING]. Oznacza: siec masz,
     * probowalismy juz kilka razy i stacja milczy. Zdarza sie, gdy rozglosnia
     * wycofa serwer, a katalog wciaz podaje stary adres - tak wlasnie bylo
     * z Triple M Melbourne. Ponawiamy dalej, ale uczciwie mowimy, ze problem
     * jest po drugiej stronie, a nie z zasiegiem.
     */
    enum class Status { OK, RECONNECTING, WAITING_FOR_NETWORK, STATION_UNREACHABLE }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private var attempt = 0
    private var pendingRetry: Runnable? = null
    private var stuckCheck: Runnable? = null
    private var registered = false

    var status: Status = Status.OK
        private set(value) {
            if (field != value) {
                field = value
                onStatusChanged(value)
            }
        }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { if (needsRevive()) retryNow("siec wrocila") }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            handler.post { if (needsRevive()) retryNow("siec zwalidowana") }
        }
    }

    /**
     * Czy jest co ratowac. Celowo NIE pytamy o `status`, tylko o stan odtwarzacza.
     *
     * Powod: nasza polityka ponawiania jest cicha - ExoPlayer probuje pobrac
     * strumien w nieskonczonosc i nie zglasza bledu wyzej, wiec `onPlayerError`
     * moze sie nigdy nie odpalic. Odtwarzacz wisi wtedy w BUFFERING ze statusem
     * OK, a warunek "status != OK" nie przepuszczal powrotu sieci. Dokladnie tak
     * zachowuja sie ReplaIO i TuneIn, ktore potrafia wisiec godzine po wyjezdzie
     * z garazu i dopiero potem zorientowac sie, ze siec wrocila.
     */
    private fun needsRevive(): Boolean =
        player.playWhenReady && player.playbackState != Player.STATE_READY

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity?.registerNetworkCallback(request, networkCallback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "Nie udalo sie zarejestrowac nasluchu sieci", it) }
        player.addListener(this)
    }

    fun stop() {
        cancelPending()
        cancelStuckCheck()
        player.removeListener(this)
        if (registered) {
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            registered = false
        }
    }

    // --- Player.Listener -----------------------------------------------------

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "Blad odtwarzania: ${error.errorCodeName}", error)
        if (!player.playWhenReady) {
            // Uzytkownik i tak nie chcial grac - nie ma czego ratowac.
            status = Status.OK
            return
        }
        status = currentStatus()
        scheduleRetry()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                attempt = 0
                cancelPending()
                cancelStuckCheck()
                status = Status.OK
            }
            // Buforowanie samo w sobie jest normalne. Buforowanie, ktore nie
            // konczy sie przez [STUCK_MS], oznacza, ze cichy ponawiacz nizej
            // krazy w kolko - wtedy nazywamy rzecz po imieniu i zaczynamy
            // probowac sami.
            Player.STATE_BUFFERING -> scheduleStuckCheck()
            else -> cancelStuckCheck()
        }
    }

    // --- wewnetrzne ----------------------------------------------------------

    private fun scheduleRetry() {
        cancelPending()
        val delay = BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.last() }
        attempt = (attempt + 1).coerceAtMost(BACKOFF_MS.size - 1)
        val runnable = Runnable { retryNow("backoff") }
        pendingRetry = runnable
        handler.postDelayed(runnable, delay)
        Log.i(TAG, "Ponowie za ${delay}ms (proba $attempt)")
    }

    private fun retryNow(reason: String) {
        cancelPending()
        if (!player.playWhenReady) return
        Log.i(TAG, "Wznawiam odtwarzanie ($reason)")
        status = currentStatus()
        runCatching {
            player.prepare()
            player.play()
        }.onFailure { Log.w(TAG, "prepare() nie wyszlo", it) }
        // Jesli sie nie uda, onPlayerError zaplanuje kolejna probe.
        scheduleRetry()
    }

    private fun cancelPending() {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun scheduleStuckCheck() {
        cancelStuckCheck()
        if (!player.playWhenReady) return
        val runnable = Runnable {
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@Runnable
            status = currentStatus()
            Log.i(TAG, "Buforowanie ciagnie sie ponad ${STUCK_MS}ms - $status")
            scheduleRetry()
        }
        stuckCheck = runnable
        handler.postDelayed(runnable, STUCK_MS)
    }

    private fun cancelStuckCheck() {
        stuckCheck?.let { handler.removeCallbacks(it) }
        stuckCheck = null
    }

    /**
     * Bez sieci czekamy na siec. Z siecia najpierw ponawiamy, a po
     * [ATTEMPTS_BEFORE_UNREACHABLE] nieudanych probach nazywamy rzecz po imieniu.
     */
    private fun currentStatus(): Status = when {
        !hasUsableNetwork() -> Status.WAITING_FOR_NETWORK
        attempt >= ATTEMPTS_BEFORE_UNREACHABLE -> Status.STATION_UNREACHABLE
        else -> Status.RECONNECTING
    }

    private fun hasUsableNetwork(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "ReconnectController"
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

        /**
         * Po tylu milisekundach nieprzerwanego buforowania uznajemy, ze to nie
         * jest zwykle napelnianie bufora, tylko brak polaczenia. Wartosc z
         * zapasem wzgledem najwiekszego profilu bufora (start ~4 s).
         */
        const val STUCK_MS = 12_000L

        /**
         * Po tylu nieudanych probach przy dzialajacej sieci przestajemy udawac,
         * ze to chwilowe. Cztery proby to okolo 15 s narastajacego backoffu.
         */
        const val ATTEMPTS_BEFORE_UNREACHABLE = 4
    }
}
