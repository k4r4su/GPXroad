package com.olivier.gpxroad.shared.recording

import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Densité d'enregistrement (équivalent de `RecordingDensityPreset` iOS, it19) : un point est gardé dès
 * que l'UN des deux seuils est atteint depuis le dernier point gardé. `PRECIS` = défaut ; ×2 à chaque
 * palier pour alléger le fichier GPX.
 */
enum class RecordingDensity(val minIntervalSeconds: Int, val minDistanceMeters: Int) {
    PRECIS(5, 15),
    LEGER(10, 30),
    TRES_LEGER(20, 60),
    ULTRA_LEGER(40, 120),
}

/** Point enregistré : [timeMillis] = heure du fix GPS (UTC, ms depuis 1970). */
data class RecordedPoint(val latitude: Double, val longitude: Double, val elevation: Double?, val timeMillis: Long)

object RecordingConstants {
    /** Copie de secours (« Sorties non enregistrées ») réécrite tous les N points enregistrés. */
    const val UNSAVED_CHECKPOINT_EVERY_N_POINTS = 10
    const val UNSAVED_RETENTION_DEFAULT = 10
    val UNSAVED_RETENTION_OPTIONS = listOf(5, 10, 20, 50)
}

object RecordingSampler {
    /** Garde-t-on [candidate] ? Toujours le premier point ; ensuite l'intervalle OU la distance. */
    fun shouldRecord(last: RecordedPoint?, candidate: RecordedPoint, density: RecordingDensity): Boolean {
        if (last == null) return true
        val elapsedSeconds = (candidate.timeMillis - last.timeMillis) / 1000.0
        if (elapsedSeconds >= density.minIntervalSeconds) return true
        val distance = geodesicDistanceMeters(last.latitude, last.longitude, candidate.latitude, candidate.longitude)
        return distance >= density.minDistanceMeters
    }

    /** Distance parcourue le long des points (Vincenty, comme toutes les distances de trace). */
    fun lengthMeters(points: List<RecordedPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> geodesicDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude) }
}

/**
 * GPX 1.1 d'une sortie enregistrée — même document que `GPXExporter.export` iOS : `<metadata>` avec
 * nom et commentaire (`<desc>`), puis un `<trk>` d'un seul segment ; altitude et heure si connues.
 */
object GpxWriter {
    /**
     * GPX d'un itinéraire CRÉÉ (pas enregistré) : mêmes balises, mais ni heure ni altitude — il n'y a pas eu de passage, donc
     * aucune vitesse ou durée réelle à en tirer (les statistiques de fiche restent « indisponibles », comme pour un import sans temps).
     */
    fun writeRoute(name: String, points: List<com.olivier.gpxroad.shared.LatLon>, comment: String? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GPXroad\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata>\n    <name>").append(escape(name)).append("</name>\n")
        if (!comment.isNullOrBlank()) append("    <desc>").append(escape(comment)).append("</desc>\n")
        append("  </metadata>\n")
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) append("      <trkpt lat=\"").append(plain(p.latitude)).append("\" lon=\"").append(plain(p.longitude)).append("\"></trkpt>\n")
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    fun write(name: String, points: List<RecordedPoint>, comment: String? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GPXroad\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata>\n    <name>").append(escape(name)).append("</name>\n")
        if (!comment.isNullOrBlank()) append("    <desc>").append(escape(comment)).append("</desc>\n")
        append("  </metadata>\n")
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            append("      <trkpt lat=\"").append(plain(p.latitude)).append("\" lon=\"").append(plain(p.longitude)).append("\">")
            p.elevation?.let { append("<ele>").append(plain(it)).append("</ele>") }
            append("<time>").append(IsoTime.format(p.timeMillis)).append("</time>")
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Nombre décimal sans notation scientifique (interdite dans un attribut GPX) : 9 décimales au plus. */
    internal fun plain(value: Double): String {
        val text = value.toString()
        if (!text.contains('E') && !text.contains('e')) return text
        val scaled = (value * 1_000_000_000).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val absolute = abs(scaled)
        val fraction = (absolute % 1_000_000_000).toString().padStart(9, '0').trimEnd('0')
        return sign + (absolute / 1_000_000_000) + if (fraction.isEmpty()) "" else ".$fraction"
    }
}

/** Date ISO 8601 UTC à la seconde (`2026-09-28T08:31:05Z`), comme `ISO8601DateFormatter` iOS. */
object IsoTime {
    fun format(epochMillis: Long): String {
        val totalSeconds = floorDiv(epochMillis, 1000)
        val days = floorDiv(totalSeconds, 86_400)
        val secondsOfDay = totalSeconds - days * 86_400
        // Jour civil depuis le nombre de jours (algorithme de H. Hinnant).
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}T${two(secondsOfDay / 3600)}:${two(secondsOfDay % 3600 / 60)}:${two(secondsOfDay % 60)}Z"
    }

    /**
     * Lecture d'une date ISO 8601 (`2026-09-28T08:31:05Z`, fraction de seconde et décalage `+02:00`
     * acceptés), en ms depuis 1970 UTC ; `null` si le texte n'en est pas une.
     */
    fun parse(text: String): Long? {
        val match = ISO.matchEntire(text.trim()) ?: return null
        val g = match.groupValues
        val year = g[1].toLong()
        val month = g[2].toLong()
        val day = g[3].toLong()
        if (month !in 1..12 || day !in 1..31) return null
        val hour = g[4].toLong()
        val minute = g[5].toLong()
        val second = g[6].toLong()
        val millis = g[7].takeIf { it.isNotEmpty() }?.drop(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
        val offsetSeconds = when {
            g[8].isEmpty() || g[8] == "Z" || g[8] == "z" -> 0L
            else -> (if (g[8][0] == '-') -1 else 1) * (g[9].toLong() * 3600 + g[10].toLong() * 60)
        }
        // Jours depuis 1970 (inverse de l'algorithme de H. Hinnant).
        val y = if (month <= 2) year - 1 else year
        val era = floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097 + doe - 719_468
        return ((days * 86_400 + hour * 3600 + minute * 60 + second) - offsetSeconds) * 1000 + millis
    }

    private val ISO = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?(Z|z|[+-](\\d{2}):?(\\d{2}))?")

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }
}
