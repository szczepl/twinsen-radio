package net.mspanc.twinsenradio.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * Krotki opis tego, co faktycznie plynie ze strumienia - kodek, przeplywnosc,
 * czestotliwosc probkowania i liczba kanalow.
 *
 * Dane bierzemy z formatu ustalonego przez dekoder, a nie z opisu stacji: to,
 * co rozglosnia deklaruje w naglowku icy-br, bywa nieaktualne, a przy AAC+
 * potrafi opisywac tylko warstwe bazowa. Przeplywnosc z naglowka sluzy wylacznie
 * jako zapas, gdy dekoder jej nie poda (typowe dla AAC w kontenerze ADTS).
 *
 * Pokazujemy to tylko na telefonie - w Android Auto nie mamy wlasnego pola,
 * w ktorym daloby sie to napisac, a doklejanie do tytulu psuloby metadane.
 */
@UnstableApi
data class StreamQuality(
    val codec: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val channels: Int
) {
    /** np. "AAC+ · 48 kb/s · 44,1 kHz · stereo". */
    /**
     * Bez czestotliwosci probkowania - ta niewiele mowi, a przeplywnosc
     * uzytkownik wybiera teraz sam z listy obok. Zostaje kodek i liczba kanalow.
     */
    fun label(): String = buildList {
        if (codec.isNotBlank()) add(codec)
        channelsLabel().takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")

    /** Sama liczba kanalow, bez kodeka - ten juz widac na przycisku jakosci obok. */
    fun channelsLabel(): String = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        in 3..Int.MAX_VALUE -> "$channels kan."
        else -> ""
    }

    private fun khz(hz: Int): String {
        val tenths = (hz + 50) / 100
        return if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10},${tenths % 10} kHz"
    }

    companion object {
        /**
         * @param icyBitrateKbps przeplywnosc z naglowka icy-br; uzywana tylko
         *   wtedy, gdy sam format jej nie niesie.
         */
        fun of(format: Format, icyBitrateKbps: Int): StreamQuality {
            val bitrate = when {
                format.bitrate > 0 -> (format.bitrate + 500) / 1000
                format.averageBitrate > 0 -> (format.averageBitrate + 500) / 1000
                else -> icyBitrateKbps
            }
            val sbr = looksLikeSbr(format)
            val rate = format.sampleRate.takeIf { it > 0 } ?: 0
            return StreamQuality(
                codec = if (sbr) "AAC+" else codecName(format),
                bitrateKbps = bitrate,
                // Przy SBR dekoder oddaje dwa razy wiecej, niz deklaruje naglowek
                sampleRateHz = if (sbr) rate * 2 else rate,
                channels = format.channelCount.takeIf { it > 0 } ?: 0
            )
        }

        /**
         * Czy to HE-AAC sygnalizowany niejawnie.
         *
         * Przy niejawnej sygnalizacji SBR naglowek ADTS klamie dwa razy: podaje
         * profil AAC-LC i **polowe** docelowej czestotliwosci, bo gorne pasmo
         * dokleja dopiero dekoder. Radio Nowy Swiat wyglada wtedy tak:
         *
         *     audio/mp4a-latm codecs=mp4a.40.2 sr=22050 ch=2
         *
         * i bez tej poprawki pokazywalibysmy "AAC 22,1 kHz", czyli wartosc
         * brzmiaca na duzo gorsza jakosc, niz slychac naprawde.
         *
         * Rozpoznajemy to po zestawie: profil LC, stereo i czestotliwosc rzedu
         * 24 kHz lub mniej. Prawdziwy AAC-LC 22 kHz stereo w muzycznym strumieniu
         * internetowym praktycznie nie wystepuje - taka przeplywnosc idzie dzis
         * zawsze przez HE-AAC.
         */
        private fun looksLikeSbr(format: Format): Boolean {
            val isAac = format.sampleMimeType in setOf("audio/mp4a-latm", "audio/aac", "audio/aacp")
            if (!isAac) return false
            val profile = format.codecs?.substringAfterLast('.')?.toIntOrNull()
            if (profile != null && profile != 2) return false
            return format.sampleRate in 1..24_000 && format.channelCount >= 2
        }

        /**
         * Nazwa kodeka w postaci, ktora cos znaczy dla czlowieka. Sam typ MIME
         * nie wystarcza: AAC-LC, HE-AAC i HE-AACv2 maja ten sam typ i roznia sie
         * dopiero profilem zapisanym w polu codecs (mp4a.40.<profil>).
         */
        private fun codecName(format: Format): String {
            val profile = format.codecs?.substringAfterLast('.')?.toIntOrNull()
            return when (format.sampleMimeType) {
                "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2" -> "MP3"
                "audio/mp4a-latm", "audio/aac", "audio/aacp" -> when (profile) {
                    5 -> "AAC+"
                    29 -> "AAC++"
                    2 -> "AAC"
                    else -> "AAC"
                }
                "audio/opus" -> "Opus"
                "audio/vorbis" -> "Vorbis"
                "audio/flac" -> "FLAC"
                "audio/ac3" -> "AC-3"
                null -> ""
                else -> format.sampleMimeType!!.substringAfter('/').uppercase()
            }
        }
    }
}
