package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Portion de la trace (distances cumulées, m) réellement recalée sur la route par le map matching. */
data class CoveredRange(val startMeters: Double, val endMeters: Double) {
    fun contains(meters: Double): Boolean = meters in startMeters..endMeters
}

/**
 * Couverture du map matching (it33 bis, retour terrain : « maintenant ça me met juste les villages et
 * plus de changement de route ») — Valhalla ne recale parfois qu'une PARTIE de la trace (mesuré :
 * « Travail maison déviation », 3 manœuvres dans les premiers kilomètres, rien sur les 20 suivants).
 * La règle « seuls les vrais carrefours » ne vaut que là où la route est connue ; ailleurs, la
 * géométrie de la trace reprend la main. Une portion est couverte si la trace passe à moins de
 * [TOLERANCE_METERS] de la géométrie recalée ; petits trous comblés, bribes écartées.
 */
object MapMatchCoverage {
    const val TOLERANCE_METERS = 40.0
    const val GAP_FILL_METERS = 150.0
    const val MIN_RANGE_METERS = 200.0
    private const val DENSIFY_METERS = 15.0
    private const val CELL_DEGREES = 0.002

    fun coveredRanges(trackPoints: List<LatLon>, matchedShapes: List<List<LatLon>>): List<CoveredRange> {
        if (trackPoints.size < 2) return emptyList()
        val grid = HashMap<Long, MutableList<LatLon>>()
        for (shape in matchedShapes) {
            for (point in densified(shape)) grid.getOrPut(cellKey(point)) { mutableListOf() }.add(point)
        }
        if (grid.isEmpty()) return emptyList()

        val cumulative = TrackGeometry.cumulativeDistances(trackPoints)
        val ranges = mutableListOf<CoveredRange>()
        var runStart: Double? = null
        var runEnd = 0.0
        for ((index, point) in trackPoints.withIndex()) {
            val meters = cumulative[index]
            if (isNearShape(point, grid)) {
                val start = runStart
                if (start == null) {
                    runStart = meters
                } else if (meters - runEnd > GAP_FILL_METERS) {
                    ranges.add(CoveredRange(start, runEnd))
                    runStart = meters
                }
                runEnd = meters
            }
        }
        runStart?.let { ranges.add(CoveredRange(it, runEnd)) }
        return ranges.filter { it.endMeters - it.startMeters >= MIN_RANGE_METERS }
    }

    private fun isNearShape(point: LatLon, grid: Map<Long, List<LatLon>>): Boolean {
        val row = floor(point.latitude / CELL_DEGREES).toLong()
        val column = floor(point.longitude / CELL_DEGREES).toLong()
        for (dr in -1L..1L) for (dc in -1L..1L) {
            val candidates = grid[key(row + dr, column + dc)] ?: continue
            if (candidates.any { geodesicDistanceMeters(point, it) <= TOLERANCE_METERS }) return true
        }
        return false
    }

    /** Points de la géométrie recalée tous les [DENSIFY_METERS] au plus (segments longs d'une route droite). */
    private fun densified(shape: List<LatLon>): List<LatLon> {
        if (shape.size < 2) return shape
        val result = mutableListOf(shape.first())
        for (i in 1 until shape.size) {
            val a = shape[i - 1]
            val b = shape[i]
            val steps = max(ceil(geodesicDistanceMeters(a, b) / DENSIFY_METERS).toInt(), 1)
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                result.add(LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t))
            }
        }
        return result
    }

    private fun cellKey(point: LatLon): Long = key(floor(point.latitude / CELL_DEGREES).toLong(), floor(point.longitude / CELL_DEGREES).toLong())

    private fun key(row: Long, column: Long): Long = row * 1_000_003L + column
}
