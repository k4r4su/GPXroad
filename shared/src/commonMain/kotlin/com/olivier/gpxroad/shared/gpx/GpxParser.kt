package com.olivier.gpxroad.shared.gpx

/**
 * Point d'un fichier GPX. [timeIso] : texte ISO 8601 tel qu'écrit dans le fichier (la conversion en
 * date reste native), `null` si absent.
 */
data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val timeIso: String? = null,
)

/**
 * Contenu utile d'un fichier GPX : nom (premier `<name>` hors des points), date `<metadata><time>`,
 * points de la trace (`<trkpt>`, repli sur `<rtept>` s'il n'y en a aucun) et `<wpt>`.
 */
data class GpxDocument(
    val name: String?,
    val metadataTimeIso: String?,
    val points: List<GpxPoint>,
    val waypoints: List<GpxPoint>,
)

class GpxParseException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { INVALID_XML, NO_TRACK_DATA }
}

/**
 * Lecteur GPX partagé (Android d'abord ; même comportement que `GPXParser.swift` d'iOS, qui reste
 * en place jusqu'à un test de parité sur les traces réelles) : mêmes règles — `<trkpt>` sinon
 * `<rtept>`, `<wpt>` à part, nom = premier `<name>` hors d'un point, date = `<time>` de
 * `<metadata>`. Pas de bibliothèque XML (aucune n'existe en Kotlin commun) : un lecteur de balises
 * tolérant, suffisant pour le GPX (préfixes d'espace de noms ignorés, entités et CDATA gérés).
 */
object GpxParser {
    fun parse(text: String): GpxDocument {
        if (!text.contains('<')) throw GpxParseException(GpxParseException.Reason.INVALID_XML)
        val trackPoints = mutableListOf<GpxPoint>()
        val routePoints = mutableListOf<GpxPoint>()
        val waypoints = mutableListOf<GpxPoint>()
        var name: String? = null
        var metadataTime: String? = null
        var inMetadata = false
        var sawGpx = false

        var context: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var ele: String? = null
        var time: String? = null
        val textBuffer = StringBuilder()

        fun endPoint() {
            val la = lat
            val lo = lon
            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                val point = GpxPoint(la, lo, ele?.toDoubleOrNull(), time?.takeIf { it.isNotEmpty() })
                when (context) {
                    "trkpt" -> trackPoints.add(point)
                    "rtept" -> routePoints.add(point)
                    "wpt" -> waypoints.add(point)
                }
            }
            context = null
        }

        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c != '<') {
                val next = text.indexOf('<', i).let { if (it < 0) n else it }
                textBuffer.append(text, i, next)
                i = next
                continue
            }
            when {
                text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i + 4)
                    if (end < 0) throw GpxParseException(GpxParseException.Reason.INVALID_XML)
                    i = end + 3
                }
                text.startsWith("<![CDATA[", i) -> {
                    val end = text.indexOf("]]>", i + 9)
                    if (end < 0) throw GpxParseException(GpxParseException.Reason.INVALID_XML)
                    textBuffer.append("\u0000CDATA").append(text, i + 9, end).append("\u0000")
                    i = end + 3
                }
                text.startsWith("<?", i) || text.startsWith("<!", i) -> {
                    val end = text.indexOf('>', i)
                    if (end < 0) throw GpxParseException(GpxParseException.Reason.INVALID_XML)
                    i = end + 1
                }
                else -> {
                    val end = tagEnd(text, i)
                    if (end < 0) throw GpxParseException(GpxParseException.Reason.INVALID_XML)
                    val raw = text.substring(i + 1, end)
                    i = end + 1
                    val closing = raw.startsWith("/")
                    val selfClosing = raw.endsWith("/")
                    val body = raw.removePrefix("/").removeSuffix("/").trim()
                    val tagName = body.takeWhile { !it.isWhitespace() }.substringAfter(':')
                    if (closing) {
                        val value = decode(textBuffer.toString()).trim()
                        when (tagName) {
                            "ele" -> ele = value
                            "time" -> {
                                time = value
                                if (inMetadata && context == null && metadataTime == null && value.isNotEmpty()) metadataTime = value
                            }
                            "name" -> if (context == null && name == null && value.isNotEmpty()) name = value
                            "metadata" -> inMetadata = false
                            "trkpt", "rtept", "wpt" -> endPoint()
                        }
                        textBuffer.clear()
                    } else {
                        textBuffer.clear()
                        when (tagName) {
                            "gpx" -> sawGpx = true
                            "metadata" -> if (!selfClosing) inMetadata = true
                            "trkpt", "rtept", "wpt" -> {
                                context = tagName
                                lat = attribute(body, "lat")?.toDoubleOrNull()
                                lon = attribute(body, "lon")?.toDoubleOrNull()
                                ele = null
                                time = null
                                if (selfClosing) endPoint()
                            }
                        }
                    }
                }
            }
        }
        if (!sawGpx) throw GpxParseException(GpxParseException.Reason.INVALID_XML)
        val points = trackPoints.ifEmpty { routePoints }
        if (points.isEmpty() && waypoints.isEmpty()) throw GpxParseException(GpxParseException.Reason.NO_TRACK_DATA)
        return GpxDocument(name, metadataTime, points, waypoints)
    }

    /** Fin de balise en ignorant les `>` à l'intérieur des valeurs d'attributs. */
    private fun tagEnd(text: String, start: Int): Int {
        var quote: Char? = null
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return -1
    }

    private fun attribute(body: String, key: String): String? {
        val match = Regex("""(?:^|\s)(?:[\w-]+:)?$key\s*=\s*(["'])(.*?)\1""").find(body) ?: return null
        return decode(match.groupValues[2]).trim()
    }

    /** Entités XML et sections CDATA (marquées par le lecteur, jamais décodées). */
    private fun decode(value: String): String {
        if (!value.contains('&') && !value.contains('\u0000')) return value
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\u0000' && value.startsWith("\u0000CDATA", i)) {
                val end = value.indexOf('\u0000', i + 6)
                out.append(value, i + 6, if (end < 0) value.length else end)
                i = if (end < 0) value.length else end + 1
                continue
            }
            if (c == '&') {
                val end = value.indexOf(';', i)
                if (end > i) {
                    val entity = value.substring(i + 1, end)
                    val decoded = when {
                        entity == "amp" -> "&"
                        entity == "lt" -> "<"
                        entity == "gt" -> ">"
                        entity == "quot" -> "\""
                        entity == "apos" -> "'"
                        entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)?.let(::codePoint)
                        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
                        else -> null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String =
        if (value in 0..0xFFFF) value.toChar().toString()
        else {
            val v = value - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
}

/**
 * Sens de parcours et point de départ d'une trace (équivalent de `GPXTrack.reordered(using:)`
 * iOS) : NOUVELLE liste, jamais une modification de la trace (règle « trace sacrée »).
 */
object TrackOrder {
    fun <T> reordered(points: List<T>, reversed: Boolean, startIndex: Int? = null): List<T> {
        var result = if (reversed) points.asReversed().toList() else points
        if (startIndex != null && startIndex in 1 until result.size) {
            result = result.subList(startIndex, result.size) + result.subList(0, startIndex)
        }
        return result
    }

    /** Clé d'un parcours (trace ET sens) : identifiant + deux premiers points, comme iOS. */
    fun traversalKey(id: String, points: List<GpxPoint>): String =
        id + "|" + points.take(2).joinToString(";") { "${format6(it.latitude)},${format6(it.longitude)}" }

    private fun format6(value: Double): String {
        val scaled = kotlin.math.round(value * 1_000_000).toLong()
        val sign = if (scaled < 0) "-" else ""
        val absolute = kotlin.math.abs(scaled)
        return "$sign${absolute / 1_000_000}.${(absolute % 1_000_000).toString().padStart(6, '0')}"
    }
}
