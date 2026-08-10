package net.mspanc.twinsenradio.data

/**
 * Minimalny parser rozszerzonego M3U (#EXTM3U / #EXTINF). Wyciaga nazwe, URL,
 * oraz atrybuty tvg-logo i group-title, ktore w praktyce niosa logo i gatunek.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(body: String, sourceTag: String): List<Station> {
        val out = mutableListOf<Station>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var seq = 0

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val afterComma = line.substringAfter(',', "").trim()
                    pendingName = afterComma.ifBlank { null }
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    pendingLogo = attrs["tvg-logo"]?.ifBlank { null }
                    pendingGroup = attrs["group-title"]?.ifBlank { null }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName ?: line.substringAfterLast('/').ifBlank { line }
                    out += Station(
                        id = "u_${sourceTag}_${seq++}",
                        name = name,
                        genre = pendingGroup ?: "Z listy M3U",
                        stream = line,
                        logoUrl = pendingLogo,
                        source = Station.Source.USER_M3U
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }
        return out
    }
}
