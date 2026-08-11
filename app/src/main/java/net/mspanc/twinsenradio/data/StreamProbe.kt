package net.mspanc.twinsenradio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Sprawdza tu i teraz, czy strumien w ogole odpowiada.
 *
 * Po co, skoro katalog ma flage "dziala": ta flaga pochodzi z **ostatniego**
 * sprawdzenia, ktore bywa sprzed miesiecy. Triple M Melbourne mial
 * `lastcheckok = 1` z data 2026-01-15, a serwer `wz3drp.scahw.com.au` nie ma juz
 * nawet rekordu DNS. Bez wlasnej sondy taka pozycja ladowala na liscie i
 * buforowala sie w nieskonczonosc.
 *
 * Sonda celowo nie pobiera calego strumienia - wystarczy poczatek, zeby
 * odroznic dzialajacy serwer od martwego.
 */
object StreamProbe {

    enum class Result {
        /** Serwer odpowiedzial i oddaje dane. */
        OK,

        /** Nazwa hosta nie istnieje - serwer zostal wycofany. */
        NO_HOST,

        /** Serwer odpowiedzial bledem, np. 403 przy blokadzie regionalnej. */
        HTTP_ERROR,

        /** Nic nie przyszlo w zadanym czasie. */
        TIMEOUT,

        /** Cokolwiek innego - brak sieci, blad TLS. */
        FAILED
    }

    data class Report(val result: Result, val detail: String)

    suspend fun check(url: String): Report = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", StationRepository.USER_AGENT)
                // Prosimy o metadane ICY - przy okazji sprawdzamy, czy stacja je daje
                setRequestProperty("Icy-MetaData", "1")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val extra = if (code == 403) " (blokada regionalna?)" else ""
                    return@withContext Report(Result.HTTP_ERROR, "HTTP $code$extra")
                }
                // Czy naprawde cos plynie - sam kod 200 potrafi oddac strone bledu
                val buffer = ByteArray(PROBE_BYTES)
                val read = conn.inputStream.use { it.read(buffer) }
                if (read <= 0) {
                    return@withContext Report(Result.FAILED, "serwer nie przyslal danych")
                }
                val type = conn.contentType.orEmpty()
                val bitrate = conn.getHeaderField("icy-br")
                val detail = buildString {
                    append(type.substringBefore(';').ifBlank { "nieznany format" })
                    if (!bitrate.isNullOrBlank()) append(" · $bitrate kb/s")
                }
                Report(Result.OK, detail)
            } finally {
                conn.disconnect()
            }
        } catch (e: UnknownHostException) {
            Report(Result.NO_HOST, "serwer ${e.message} nie istnieje")
        } catch (e: java.net.SocketTimeoutException) {
            Report(Result.TIMEOUT, "serwer nie odpowiada")
        } catch (e: Exception) {
            Report(Result.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private const val TIMEOUT_MS = 8_000
    private const val PROBE_BYTES = 1024
}
