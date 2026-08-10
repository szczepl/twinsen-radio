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

/** Kolorystyka zegara rysowanego zamiast okladki. */
object ClockColors {

    val BACKGROUNDS: List<Pair<String, Int>> = listOf(
        "Czarne" to 0xFF000000.toInt(),
        "Granatowe (motyw aplikacji)" to 0xFF0B3D91.toInt(),
        "Ciemnoszare" to 0xFF202124.toInt(),
        "Białe" to 0xFFFFFFFF.toInt()
    )

    /** null oznacza dobor automatyczny, kontrastowo do tla. */
    val FOREGROUNDS: List<Pair<String, Int?>> = listOf(
        "Auto — kontrastowo do tła" to null,
        "Białe" to 0xFFFFFFFF.toInt(),
        "Czarne" to 0xFF000000.toInt(),
        "Bursztynowe" to 0xFFF2A900.toInt()
    )

    fun background(index: Int) = BACKGROUNDS.getOrElse(index) { BACKGROUNDS[0] }.second

    /**
     * Kolor cyfr i wskazowek. Przy "auto" liczymy jasnosc tla wzorem luminancji
     * i wybieramy czarny albo bialy - to samo, co robi kazdy sensowny system
     * motywow, a unika nieczytelnego bialego na bialym.
     */
    fun foreground(index: Int, backgroundColor: Int): Int {
        FOREGROUNDS.getOrElse(index) { FOREGROUNDS[0] }.second?.let { return it }
        val r = (backgroundColor shr 16 and 0xFF) / 255.0
        val g = (backgroundColor shr 8 and 0xFF) / 255.0
        val b = (backgroundColor and 0xFF) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    val BACKGROUND_LABELS get() = BACKGROUNDS.map { it.first }
    val FOREGROUND_LABELS get() = FOREGROUNDS.map { it.first }
}

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
                "Góra — zegar, wykonawca, tytuł",
                Slot.CLOCK, Slot.ARTIST, Slot.TITLE
            ),
            Presentation(
                "Środek — wykonawca, zegar, tytuł",
                Slot.ARTIST, Slot.CLOCK, Slot.TITLE
            ),
            Presentation(
                "Dół — tytuł, wykonawca, zegar",
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
