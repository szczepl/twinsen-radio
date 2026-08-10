package net.mspanc.twinsenradio.data

/**
 * Co ma stac w kolejnych liniach opisu utworu.
 *
 * Active Info Display w Passacie pokazuje trzy linie tekstu i grafike, ekran
 * centralny Android Auto - dwie linie i grafike. Zestaw jest wiec wspolny,
 * a roznica polega tylko na tym, ile z niego glowica zdola pokazac.
 */
enum class Slot {
    /** Godzina w formacie HH:mm, odswiezana na granicy minuty. */
    CLOCK,
    ARTIST,
    TITLE,
    /** Nazwa rozglosni. */
    STATION,
    EMPTY
}

/** Czy okladke zastepuje zegar, a jesli tak - w jakiej postaci. */
enum class ClockFace { NONE, DIGITAL, ANALOG }

/**
 * Gotowy uklad do wyboru w Opcjach. Wzorowane na trybach wyswietlania
 * metadanych w ReplaIO, ale z jawnym miejscem na zegar.
 */
data class Presentation(
    val label: String,
    val top: Slot,
    val middle: Slot,
    val bottom: Slot,
    val clockFace: ClockFace = ClockFace.NONE
) {
    companion object {
        val ALL = listOf(
            Presentation(
                "Domyślny — wykonawca, stacja, tytuł",
                Slot.ARTIST, Slot.STATION, Slot.TITLE
            ),
            Presentation(
                "Zegar u góry — zegar, wykonawca, tytuł",
                Slot.CLOCK, Slot.ARTIST, Slot.TITLE
            ),
            Presentation(
                "Zegar w środku — wykonawca, zegar, tytuł",
                Slot.ARTIST, Slot.CLOCK, Slot.TITLE
            ),
            Presentation(
                "Zegar na dole — tytuł, wykonawca, zegar",
                Slot.TITLE, Slot.ARTIST, Slot.CLOCK
            ),
            Presentation(
                "Zegar cyfrowy zamiast okładki",
                Slot.ARTIST, Slot.EMPTY, Slot.TITLE, ClockFace.DIGITAL
            ),
            Presentation(
                "Zegar analogowy zamiast okładki",
                Slot.ARTIST, Slot.EMPTY, Slot.TITLE, ClockFace.ANALOG
            )
        )

        fun at(index: Int) = ALL.getOrElse(index) { ALL[0] }

        val LABELS get() = ALL.map { it.label }
    }

    /** Czy uklad w ogole potrzebuje odswiezania co minute. */
    val needsClock: Boolean
        get() = clockFace != ClockFace.NONE ||
            top == Slot.CLOCK || middle == Slot.CLOCK || bottom == Slot.CLOCK
}
