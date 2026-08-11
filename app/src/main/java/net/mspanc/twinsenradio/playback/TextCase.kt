package net.mspanc.twinsenradio.playback

/**
 * Porzadkowanie napisow przysylanych przez rozglosnie.
 *
 * Problem jest realny i widoczny w aucie: Jacaranda FM podaje wszystko
 * WERSALIKAMI, a wytwornie wpisuja tak tytuly do katalogow ("NO ME ARREPIENTO
 * DE SENTIR TANTO"). Na waskim ekranie taki napis zjada dwie linie i krzyczy.
 *
 * Kolejnosc postepowania jest wazna i celowo taka:
 *
 *  1. Jesli napis nie jest krzykliwy - nie ruszamy go wcale. Rozglosnia wie
 *     lepiej, jak zapisac wlasny repertuar.
 *  2. Jesli jest krzykliwy, a katalog zna ten sam napis w porzadnym zapisie -
 *     bierzemy wersje z katalogu. To najlepsze zrodlo, bo pochodzi od wydawcy,
 *     a nie z automatu rozglosni.
 *  3. Dopiero gdy katalog tez krzyczy albo nic nie wie - normalizujemy sami.
 */
object TextCase {

    /** Samogloski razem z polskimi - do rozpoznawania skrotowcow. */
    private const val VOWELS = "aeiouyąęioóuAEIOUYĄĘIOÓU"

    /**
     * Czy napis jest "krzykliwy", czyli zapisany w calosci wersalikami.
     *
     * Wymagamy spacji, wiec pojedyncze wyrazy - "ABBA", "U2", "AC/DC", "MGMT" -
     * nigdy tu nie trafiaja. Napisow wielowyrazowych nie ograniczamy dlugoscia:
     * "I TRY" tez krzyczy, a przy progu pieciu liter przechodzilo bokiem.
     * Skrotowce wewnatrz zdania chroni osobna regula w [tame].
     */
    fun isShouty(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 2) return false
        if (!text.contains(' ')) return false
        return letters.none { it.isLowerCase() }
    }

    /**
     * Sprowadza napis wersalikowy do zapisu z wielkiej litery.
     *
     * Krotkie wyrazy bez samoglosek zostawiamy w spokoju - to prawie zawsze
     * skrotowce (DJ, MC, FM, RMF), ktore po "znormalizowaniu" wygladalyby
     * glupio ("Dj Snake").
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
     * @param raw napis z rozglosni
     * @param fromCatalogue ten sam napis wedlug katalogu (iTunes / MusicBrainz)
     */
    fun tidy(raw: String?, fromCatalogue: String? = null): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (!isShouty(text)) return text

        val catalogue = fromCatalogue?.trim()
        if (!catalogue.isNullOrEmpty() && !isShouty(catalogue) && sameText(text, catalogue)) {
            return catalogue
        }
        return tame(text)
    }

    /** Porownanie z pominieciem wielkosci liter i znakow niealfanumerycznych. */
    private fun sameText(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
