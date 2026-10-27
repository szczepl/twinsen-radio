package net.mspanc.twinsenradio.playback

import java.text.Normalizer

/**
 * Decides which of two sources gets to describe the track, for a station that
 * has both a stream and a title API.
 *
 * **The stream has to earn the say.** What it sends on its own proves
 * nothing: on 27.10.2026 RNS's mounts went in one afternoon from Revma's
 * placeholder to a fixed "Radio Nowy Swiat - Pion i poziom!" sent once on
 * connect while Albert Hammond played. That slogan is also exactly what a
 * working stream sends during a talk show, so it cannot tell "talking" from
 * "broken". A real track title can: no fixed string in an encoder names a
 * different song every few minutes. So the stream is **trusted** from the
 * first real track it names, and until then the API has the say.
 *
 * Once trusted, the stream wins - slogans included, because now a slogan
 * means talk - and the API is only compared against it. Trust is lost when
 * the stream stops following: the API moves on to a new track and the stream
 * hasn't named it within [followMs]. The placeholder loses it at once.
 *
 * Only a *change* from the API counts as news. Their web page can keep
 * showing a track long after it ended; a title that just stands there never
 * reaches here again, and the stale deadline takes it off screen in its own
 * time.
 *
 * A source repeating what the other one already put up is not published a
 * second time - to the rest of the service every published title is a track
 * change, and the same song twice would arm the stale deadline from the
 * wrong moment.
 *
 * All times are on one monotonic clock, passed in, so this is pure logic.
 */
class TitleArbiter(private val followMs: Long = FOLLOW_MS) {

    enum class Source { ICY, API }

    enum class Verdict {
        /** Goes on through the usual path. */
        PUBLISH,

        /** Already on screen - the other source said the same. */
        SAME,

        /** The trusted stream has the say; the API is only compared. */
        STREAM_RULES,

        /** The stream isn't trusted and the API names a track - the API has the say. */
        API_RULES
    }

    /**
     * @param agrees whether this matches what the other source last said;
     *   null when the other one has said nothing on this connection yet.
     * @param apartMs how long after the other source this one said it - only
     *   when they agree.
     */
    data class Result(val verdict: Verdict, val agrees: Boolean?, val apartMs: Long?)

    private class Said(val key: String, val atMs: Long, val realSong: Boolean)

    private var lastIcy: Said? = null
    private var lastApi: Said? = null
    private var shownKey: String? = null

    /** Whether the stream has proven it names tracks - see the class comment. */
    var streamTrusted = false
        private set

    /** When the API moved to a track the trusted stream hasn't named yet; null when none. */
    private var unfollowedSinceMs: Long? = null

    /**
     * Whether the stream still has the say at [atMs]. This is also where trust
     * is lost to a stream that stopped following - it is called on every poll,
     * so the loss shows up within one.
     */
    fun streamRules(atMs: Long): Boolean {
        val since = unfollowedSinceMs
        if (streamTrusted && since != null && atMs - since >= followMs) {
            streamTrusted = false
            unfollowedSinceMs = null
        }
        return streamTrusted
    }

    /**
     * A block from the stream, already parsed. Null means it said nothing - an
     * empty title (a pause, changes nothing) or the platform's placeholder
     * (the stream saying outright it has nothing, which ends its trust).
     */
    fun fromIcy(now: NowPlaying?, placeholder: Boolean, atMs: Long): Result {
        if (now == null) {
            if (placeholder) {
                streamTrusted = false
                unfollowedSinceMs = null
            }
            // Let the usual path record it; it changes nothing on screen.
            return Result(Verdict.PUBLISH, null, null)
        }
        val key = key(now)
        lastIcy = Said(key, atMs, now.isRealSong)
        if (now.isRealSong) {
            streamTrusted = true
            unfollowedSinceMs = null
        }
        val other = lastApi
        val agrees = other?.let { it.key == key }
        val apart = if (agrees == true) atMs - other.atMs else null
        if (key == shownKey) return Result(Verdict.SAME, agrees, apart)
        // An untrusted stream's slogan against a track the API names: the
        // slogan may be a fixed string, the track can't be.
        if (!streamRules(atMs) && other != null && other.realSong) {
            return Result(Verdict.API_RULES, agrees, apart)
        }
        shownKey = key
        return Result(Verdict.PUBLISH, agrees, apart)
    }

    /** A changed answer from the title API, already parsed. */
    fun fromApi(now: NowPlaying, atMs: Long): Result {
        val key = key(now)
        lastApi = Said(key, atMs, now.isRealSong)
        val other = lastIcy
        val agrees = other?.let { it.key == key }
        val apart = if (agrees == true) atMs - other.atMs else null
        if (streamRules(atMs)) {
            // Start the clock on a track the stream hasn't named - unless it
            // already has, or this is not a track at all.
            if (agrees != true && now.isRealSong) {
                if (unfollowedSinceMs == null) unfollowedSinceMs = atMs
            } else {
                unfollowedSinceMs = null
            }
            return Result(if (key == shownKey) Verdict.SAME else Verdict.STREAM_RULES, agrees, apart)
        }
        if (key == shownKey) return Result(Verdict.SAME, agrees, apart)
        shownKey = key
        return Result(Verdict.PUBLISH, agrees, apart)
    }

    /** A new station - nothing either source said still holds, trust included. */
    fun reset() {
        lastIcy = null
        lastApi = null
        shownKey = null
        streamTrusted = false
        unfollowedSinceMs = null
    }

    companion object {
        /**
         * How long a trusted stream may lag the API on a new track before it
         * counts as having stopped. Stations announce every track (FINDINGS
         * section 7) and the API is polled every 10 s, so a working stream
         * names it within seconds either side. A judgement, not a measurement -
         * the `zgodnosc` trace records the real gap once both are alive.
         */
        const val FOLLOW_MS = 60_000L

        /**
         * What has to match for two sources to mean the same thing: artist and
         * title, without case, accents or punctuation - the API and the stream
         * need not agree on "Swiat" against "Świat", or on a stray dot.
         */
        fun key(now: NowPlaying): String =
            fold(now.artist) + "|" + fold(now.songTitle) + "|" + now.isAd

        private fun fold(s: String?): String =
            Normalizer.normalize(s.orEmpty().lowercase().replace("ł", "l"), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]"), "")
    }
}
