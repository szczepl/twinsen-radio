package net.mspanc.twinsenradio.data

/**
 * What the end of a track is predicted from - the moment the description
 * comes off the screen when the station never says the track is over.
 *
 * It was one fixed rule (the catalog's length, five minutes without one, plus
 * half a minute) until the user drove on it for a weekend (03.11.2026): it
 * doesn't always hold, and whether to trust it is the listener's call, not
 * ours. So it is a choice now, down to not predicting at all.
 *
 * Stored as an ordinal; new entries go on the end.
 */
enum class TrackEnd(val label: String) {
    /** The old rule, and the default. */
    CATALOG_OR_GUESS("Długość z katalogu, a bez niej stały czas"),

    /**
     * Without a length from the catalog - or with one that says nothing,
     * because we joined the track half way - the description waits for the
     * station to say something new.
     */
    CATALOG_ONLY("Tylko długość z katalogu, bez niej czekaj na zmianę"),

    /** The catalog is ignored; every track gets the same time. */
    GUESS_ONLY("Zawsze stały czas, bez katalogu"),

    /** The description stays until the station says something new. */
    OFF("Nie przewiduj — opis zostaje do następnej zmiany");

    /** Whether the fixed time is used at all - the settings grey it out otherwise. */
    val usesGuess: Boolean get() = this == CATALOG_OR_GUESS || this == GUESS_ONLY

    companion object {
        val LABELS get() = entries.map { it.label }

        fun at(index: Int) = entries.getOrElse(index) { CATALOG_OR_GUESS }
    }
}
