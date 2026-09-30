package com.olivier.gpxroad.shared.ride

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.roadbook.RoadbookAnalyzer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Chevron de sens posé sur la trace : cap boussole du segment (0 = nord). */
data class DirectionChevron(val coordinate: LatLon, val bearingDegrees: Double)

/**
 * Chevrons de sens le long de la trace (portage de `DirectionChevronComputer` iOS) : un tous les
 * [spacingMeters], jamais masqués au dézoom — seulement plus espacés ([adaptiveSpacingMeters]).
 */
object DirectionChevrons {
    const val SPACING_METERS_DEFAULT = 100.0
    val SPACING_OPTIONS = listOf(100.0, 200.0, 500.0, 1000.0)

    /** Palier de zoom (borne basse incluse) → espacement minimal (m). */
    private val ZOOM_SPACING = listOf(14.0 to 100.0, 12.0 to 500.0, 10.0 to 1_000.0, 8.0 to 5_000.0, 5.0 to 10_000.0, Double.NEGATIVE_INFINITY to 20_000.0)

    fun adaptiveSpacingMeters(configuredSpacingMeters: Double, zoomLevel: Double): Double =
        max(configuredSpacingMeters, ZOOM_SPACING.first { zoomLevel >= it.first }.second)

    fun chevrons(points: List<LatLon>, spacingMeters: Double): List<DirectionChevron> {
        if (points.size < 2 || spacingMeters <= 0) return emptyList()
        val result = ArrayList<DirectionChevron>()
        var accumulated = 0.0
        var nextTarget = spacingMeters
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val length = geodesicDistanceMeters(a, b)
            if (length <= 0) continue
            val bearing = RoadbookAnalyzer.bearing(a, b)
            while (accumulated + length >= nextTarget) {
                val t = (nextTarget - accumulated) / length
                result += DirectionChevron(LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t), bearing)
                nextTarget += spacingMeters
            }
            accumulated += length
        }
        return result
    }
}

/** Pente forte : symbole ponctuel (panneau), [gradePercent] signé (positif = montée). */
data class SlopeWarning(val coordinate: LatLon, val gradePercent: Double, val sourcePointIndex: Int) {
    val isClimbing: Boolean get() = gradePercent > 0
    val roundedPercent: Int get() = abs(gradePercent).roundToInt()
}

/**
 * Avertissements de pente (portage de `SlopeAnalyzer` iOS, it19) : fenêtres consécutives d'au moins
 * [MIN_SEGMENT_METERS] ; pente moyenne au-delà du seuil → un panneau au bout de la fenêtre, au moins
 * [MIN_MARKER_SPACING_METERS] après le précédent.
 */
object SlopeAnalyzer {
    const val THRESHOLD_PERCENT_DEFAULT = 10.0
    val THRESHOLD_OPTIONS = listOf(8.0, 10.0, 12.0, 15.0)
    const val MIN_SEGMENT_METERS = 100.0
    const val MIN_MARKER_SPACING_METERS = 300.0

    fun steepGradeWarnings(
        points: List<LatLon>,
        elevations: List<Double?>,
        thresholdPercent: Double,
        minSegmentMeters: Double = MIN_SEGMENT_METERS,
        minMarkerSpacingMeters: Double = MIN_MARKER_SPACING_METERS,
    ): List<SlopeWarning> {
        val count = min(points.size, elevations.size)
        if (count < 2 || thresholdPercent <= 0) return emptyList()
        val warnings = ArrayList<SlopeWarning>()
        var windowStart = 0
        var windowDistance = 0.0
        var cumulative = 0.0
        var lastMarker = -Double.MAX_VALUE
        for (i in 1 until count) {
            val segment = geodesicDistanceMeters(points[i - 1], points[i])
            cumulative += segment
            windowDistance += segment
            if (windowDistance < minSegmentMeters) continue
            val start = elevations[windowStart]
            val end = elevations[i]
            val distance = windowDistance
            windowStart = i
            windowDistance = 0.0
            if (start == null || end == null) continue
            val grade = (end - start) / distance * 100
            if (abs(grade) < thresholdPercent) continue
            if (cumulative - lastMarker < minMarkerSpacingMeters) continue
            lastMarker = cumulative
            warnings += SlopeWarning(points[i], grade, i)
        }
        return warnings
    }
}
