package net.mspanc.twinsenradio.playback

/**
 * Cleans up text sent by stations.
 *
 * The problem is real and visible in the car: Jacaranda FM sends everything
 * in ALL CAPS, and labels enter titles into their catalogues the same way
 * ("NO ME ARREPIENTO DE SENTIR TANTO"). On a narrow screen a string like that
 * eats two lines and shouts at you.
 *
 * The order of operations matters and is deliberately this:
 *
 *  0. If it's all caps and the catalogue knows the same text in mixed case -
 *     take the catalogue's. This one goes first because it is the only case
 *     where somebody demonstrably knows the spelling better than we do, and
 *     because the rules below would otherwise leave a single word shouting.
 *  1. If the text isn't shouty - leave it alone entirely. The station knows
 *     best how to spell its own repertoire.
 *  2. If it's shouty, but the catalogue knows the same text in proper case -
 *     take the catalogue's version. That's the best source, since it comes
 *     from the publisher rather than the station's automation.
 *  3. Only when the catalogue is shouty too, or knows nothing - normalize it
 *     ourselves.
 */
object TextCase {

    /** Vowels including Polish ones - for recognizing abbreviations. */
    private const val VOWELS = "aeiouyąęioóuAEIOUYĄĘIOÓU"

    /**
     * Whether the text is "shouty", i.e. written entirely in all caps.
     *
     * We require a space, so single words - "ABBA", "U2", "AC/DC", "MGMT" -
     * never end up here. Multi-word text isn't limited by length: "I TRY" is
     * shouty too, and a five-letter threshold let it slip through. Standalone
     * abbreviations within a sentence are protected by a separate rule in
     * [tame].
     */
    fun isShouty(text: String): Boolean = text.contains(' ') && isAllCaps(text)

    /**
     * All caps whatever the length - "FUGEES" as much as "KILLING ME SOFTLY".
     *
     * Not the same question as [isShouty]: a single all-caps word is left alone
     * by our own tidying, because that is what ABBA and U2 look like. It is
     * still worth knowing, for the one case where somebody else knows better -
     * see [tidy].
     */
    fun isAllCaps(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 2) return false
        return letters.none { it.isLowerCase() }
    }

    /**
     * Brings an all-caps string down to title case.
     *
     * Short words without vowels are left alone - they're almost always
     * abbreviations (DJ, MC, FM, RMF), which would look silly after
     * "normalizing" ("Dj Snake").
     */
    fun tame(text: String): String = text.split(' ').joinToString(" ") { word ->
        val letters = word.filter { it.isLetter() }
        when {
            word.length <= 1 -> word
            letters.length <= 3 && letters.none { it in VOWELS } -> word
            else -> word.first() + word.drop(1).lowercase()
        }
    }

    /**
     * @param raw text from the station
     * @param fromCatalogue the same text according to the catalogue (iTunes / MusicBrainz)
     */
    fun tidy(raw: String?, fromCatalogue: String? = null): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val catalogue = fromCatalogue?.trim()

        // The catalogue's spelling of the same all-caps text wins - and this is
        // asked before the "a single word is left alone" rule, which is there
        // for ABBA and U2 and was also leaving Jacaranda's "FUGEES" shouting
        // next to a title the catalogue had already spelled properly.
        if (isAllCaps(text) && !catalogue.isNullOrEmpty() &&
            !isAllCaps(catalogue) && sameText(text, catalogue)
        ) {
            return catalogue
        }

        if (!isShouty(text)) return text
        if (!catalogue.isNullOrEmpty() && !isShouty(catalogue) && sameText(text, catalogue)) {
            return catalogue
        }
        return tame(text)
    }

    /** Comparison ignoring case and non-alphanumeric characters. */
    private fun sameText(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
