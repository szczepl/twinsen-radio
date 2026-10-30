package net.mspanc.twinsenradio.playback

/**
 * Triton Digital's public "now playing" for stations streamed through
 * StreamTheWorld: `np.tritondigital.com/public/nowplaying?mountName=...`.
 *
 * Smooth FM 91.5 Melbourne sends nothing in ICY during its ad breaks - the
 * screen stayed on a bare logo through a whole break on 30.10.2026 - while
 * Triton listed every spot on its own, with the advertiser, the start to the
 * millisecond and the length: National Tiles 30 s from 16:31:47, GWM 17 s,
 * Industry Super 30 s, Shaver Shop 30 s - the break beginning in the second
 * "Tainted Love" ended. Tracks come the same way: artist, title, album, start
 * and length. No key, and no cache in the way: a spot 24 s old was there.
 *
 * Pure, so it can be checked against the answer as it really is. Newest event
 * first in the answer.
 */
object TritonNowPlaying {

    sealed class Event {
        abstract val startMs: Long
        abstract val durationMs: Long
        val endMs: Long get() = startMs + durationMs

        data class Track(
            val artist: String,
            val title: String,
            override val startMs: Long,
            override val durationMs: Long
        ) : Event() {
            /** As ICY would put it, so it goes down the same road. */
            val streamTitle: String get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
        }

        data class Ad(
            /** "LMG2126VICR130 - Liquor Marketing Group": the spot's code and who paid for it. */
            val title: String,
            override val startMs: Long,
            override val durationMs: Long
        ) : Event() {
            /**
             * The station's own link, not an ad: Triton files those under "ad"
             * too, titled "(Link) - ..." - "SWP-MMALT SMOOTH 915 COLD", the way
             * out of a break. Measured over an hour of Smooth FM (30.10.2026):
             * a 3-6 s one between nearly every two songs, which shown as an ad
             * would put the logo up between two covers.
             */
            val isLink: Boolean get() = title.trimStart().startsWith("(Link)")

            /**
             * An ICY block of the kind RMF sends for its inserted spots - an
             * empty title, `adw_ad`, and how long is left - which NowPlaying
             * already reads as an ad with its length. The start makes each
             * spot a block of its own, so two 30 s spots in a row aren't taken
             * for one repeated.
             */
            fun icyBlock(leftMs: Long): String =
                "StreamTitle='';adw_ad='true';durationMilliseconds='${leftMs.coerceAtLeast(0)}';" +
                    "triton='$startMs';"
        }
    }

    /** The mount a StreamTheWorld address plays - "SMOOTH915" for `.../SMOOTH915_AAC320.aac`. */
    fun mountOf(streamUrl: String?): String? =
        MOUNT.find(streamUrl.orEmpty())?.groupValues?.get(1)

    fun url(mount: String) = "https://np.tritondigital.com/public/nowplaying?mountName=$mount&numberToFetch=1"

    /**
     * The newest event, if it is still on at [nowMs] as we hear it - [lagMs]
     * after Triton's clock; null once it has ended, nothing logged since.
     */
    fun current(xml: String, nowMs: Long, lagMs: Long = 0): Event? =
        latest(xml)?.takeIf { nowMs < it.endMs + lagMs + END_GRACE_MS }

    /** The newest event in the answer, whether or not it is still on. */
    fun latest(xml: String): Event? {
        val block = INFO.find(xml) ?: return null
        val type = block.groupValues[1]
        val p = PROPERTY.findAll(block.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
        val start = p["cue_time_start"]?.toLongOrNull() ?: return null
        val length = p["cue_time_duration"]?.toDoubleOrNull()?.toLong() ?: return null
        return when (type) {
            "track" -> Event.Track(p["track_artist_name"].orEmpty(), p["cue_title"].orEmpty(), start, length)
            "ad" -> Event.Ad(p["cue_title"].orEmpty(), start, length)
            else -> null
        }
    }

    /**
     * When to ask next: just after the current event ends, which is when the
     * next one is due - within [maxMs], so a talk segment nobody logs doesn't
     * leave us asking never.
     */
    fun nextAskInMs(xml: String, nowMs: Long, maxMs: Long): Long {
        val end = latest(xml)?.endMs ?: return maxMs
        return (end - nowMs + ASK_AFTER_END_MS).coerceIn(MIN_ASK_MS, maxMs)
    }

    private val MOUNT = Regex(
        """streamtheworld\.com/(?:api/livestream-redirect/)?([A-Z0-9_]+?)(?:_?(?:AAC|MP3)\d*)?\.(?:aac|mp3)""",
        RegexOption.IGNORE_CASE
    )
    private val INFO = Regex("""<nowplaying-info\b[^>]*\btype="(\w+)"[^>]*>(.*?)</nowplaying-info>""", RegexOption.DOT_MATCHES_ALL)
    private val PROPERTY = Regex("""<property name="(\w+)"><!\[CDATA\[(.*?)]]></property>""", RegexOption.DOT_MATCHES_ALL)

    /** An event's end is the next one's start; a little slack before calling it over. */
    private const val END_GRACE_MS = 2_000L
    private const val ASK_AFTER_END_MS = 700L
    private const val MIN_ASK_MS = 1_000L
}
