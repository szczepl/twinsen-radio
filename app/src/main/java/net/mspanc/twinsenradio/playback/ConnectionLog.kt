package net.mspanc.twinsenradio.playback

import android.content.Context
import android.os.Bundle
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Trwaly dziennik podlaczen do sesji medialnej.
 *
 * Po co: logcat to bufor pierscieniowy - po godzinie jazdy najciekawsze wpisy
 * dawno z niego wypadna. Tutaj zapisujemy na dysk wszystko, co Android Auto
 * powiedzialo nam przy podlaczeniu, zeby dalo sie to odczytac po powrocie.
 *
 * Czego tu NIE ma i nie bedzie: tresci handshake'u miedzy Android Auto a
 * odbiornikiem w aucie. Ta rozmowa toczy sie poza nami i zadna aplikacja
 * trzecia jej nie widzi. To, co mamy, to podpowiedzi (root hints), ktore
 * Android Auto przekazuje aplikacjom - a one odbijaja ograniczenia glowicy,
 * na przyklad zadany rozmiar okladki albo limit akcji przy pozycjach listy.
 *
 * Odczyt:
 *   adb shell run-as net.mspanc.twinsenradio cat files/polaczenia.log
 * albo tools\pull-log.ps1
 */
object ConnectionLog {

    private const val FILE_NAME = "polaczenia.log"
    private const val MAX_BYTES = 512 * 1024
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun connected(
        context: Context,
        packageName: String,
        uid: Int,
        controllerVersion: Int,
        interfaceVersion: Int,
        connectionHints: Bundle
    ) {
        write(context, buildString {
            appendLine("== podlaczenie: $packageName (uid=$uid)")
            appendLine("   wersja kontrolera=$controllerVersion, interfejsu=$interfaceVersion")
            appendBundle(connectionHints, "   hint: ")
        })
    }

    /**
     * Podpowiedzi przekazane przy pytaniu o korzen. Tu ladują ograniczenia
     * glowicy - rozmiar grafiki, limit pozycji, limit akcji.
     */
    fun libraryRoot(context: Context, packageName: String, rootHints: Bundle?) {
        write(context, buildString {
            appendLine("== korzen biblioteki dla $packageName")
            if (rootHints == null || rootHints.isEmpty) {
                appendLine("   (brak podpowiedzi)")
            } else {
                appendBundle(rootHints, "   ")
            }
        })
    }

    private fun StringBuilder.appendBundle(bundle: Bundle, prefix: String) {
        for (key in bundle.keySet().sorted()) {
            @Suppress("DEPRECATION")
            val value = bundle.get(key)
            appendLine("$prefix$key = $value")
        }
    }

    private fun write(context: Context, text: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            // Prosty limit, zeby dziennik nie rosl w nieskonczonosc przez miesiace
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText("[${LocalDateTime.now().format(STAMP)}] $text")
        }.onFailure { Log.w("ConnectionLog", "nie udalo sie zapisac: ${it.message}") }
    }
}
