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
    fun label(): String = buildList {
        if (codec.isNotBlank()) add(codec)
        if (bitrateKbps > 0) add("$bitrateKbps kb/s")
        if (sampleRateHz > 0) add(khz(sampleRateHz))
        when (channels) {
            1 -> add("mono")
            2 -> add("stereo")
            in 3..Int.MAX_VALUE -> add("$channels kan.")
        }
    }.joinToString(" · ")

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
            return StreamQuality(
                codec = codecName(format),
                bitrateKbps = bitrate,
                sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: 0,
                channels = format.channelCount.takeIf { it > 0 } ?: 0
            )
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
