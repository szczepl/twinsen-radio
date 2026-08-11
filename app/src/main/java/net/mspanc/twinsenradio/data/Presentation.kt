package net.mspanc.twinsenradio.data

/**
 * Co ma stac w kolejnych liniach opisu na desce.
 *
 * Podzial wierszy wynika wprost z pomiaru w Passacie (2026-08-11, patrz
 * BADANIA.md). Active Info Display czyta trzy pola metadanych:
 *
 *   gorna linia   <- subtitle
 *   srodkowa      <- description
 *   dolna         <- displayTitle
 *
 * Te same pola czyta ekran centralny Android Auto, tyle ze pokazuje z nich
 * dwa: duza linia to displayTitle, mala to subtitle. Opisu nie pokazuje wcale.
 *
 * Wniosek, ktory rzadzi cala ta klasa: **nie da sie sterowac AID niezaleznie od
 * ekranu centralnego**. Cokolwiek wstawimy w gorny albo dolny wiersz, pojawi sie
 * w obu miejscach. Zegar w linii tekstu bedzie wiec widoczny takze na ekranie
 * centralnym i jest to swiadomy kompromis, a nie usterka. Srodkowy wiersz jest
 * jedynym, ktory AID ma na wylacznosc.
 */
enum class LineContent(val label: String) {
    TITLE("Tytuł utworu"),
    ARTIST("Wykonawca"),
    ARTIST_ALBUM("Wykonawca · album [rok]"),
    TRACK_FULL("Wykonawca — tytuł"),
    STATION("Nazwa stacji"),
    CLOCK("Zegar"),
    EMPTY("Puste");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { TITLE }
    }
}

/**
 * Ktory wiersz na desce. Kolejnosc jak na AID, od gory.
 */
enum class Line(val label: String, val hint: String) {
    TOP(
        "Wiersz 1 — górny",
        "Na AID linia górna. Na ekranie centralnym mała linia pod tytułem."
    ),
    MIDDLE(
        "Wiersz 2 — środkowy",
        "Widoczny wyłącznie na AID. ReplaIO wstawia tu nazwę stacji, aplikacja RNŚ zostawia pusty."
    ),
    BOTTOM(
        "Wiersz 3 — dolny",
        "Na AID linia pogrubiona. Na ekranie centralnym duża linia."
    )
}

/** Czy okladke zastepuje zegar, a jesli tak - w jakiej postaci. */
enum class ClockFace(val label: String) {
    NONE("Okładka utworu albo logo stacji"),
    DIGITAL("Zegar cyfrowy"),
    ANALOG("Zegar analogowy");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { NONE }
    }
}

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
 * Komplet ustawien opisu: co w ktorym wierszu i czy zamiast okladki ma byc zegar.
 */
data class Presentation(
    val top: LineContent,
    val middle: LineContent,
    val bottom: LineContent,
    val clockFace: ClockFace
) {
    fun contentFor(line: Line): LineContent = when (line) {
        Line.TOP -> top
        Line.MIDDLE -> middle
        Line.BOTTOM -> bottom
    }

    /** Czy uklad w ogole potrzebuje odswiezania co minute. */
    val needsClock: Boolean
        get() = clockFace != ClockFace.NONE ||
            top == LineContent.CLOCK ||
            middle == LineContent.CLOCK ||
            bottom == LineContent.CLOCK

    companion object {
        /**
         * Domyslnie: wykonawca z plyta na gorze, nazwa stacji w srodku, tytul
         * na dole. Srodkowy wiersz dostaje nazwe stacji, bo to jedyne miejsce
         * widoczne wylacznie na AID - wpisanie tam czegokolwiek, co juz stoi
         * w pozostalych liniach, byloby marnowaniem jedynej wolnej linii.
         */
        val DEFAULT = Presentation(
            top = LineContent.ARTIST_ALBUM,
            middle = LineContent.STATION,
            bottom = LineContent.TITLE,
            clockFace = ClockFace.NONE
        )
    }
}
