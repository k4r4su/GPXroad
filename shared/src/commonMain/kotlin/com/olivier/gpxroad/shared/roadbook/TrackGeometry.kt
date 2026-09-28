package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Portage it32 (pilote KMP) de la partie de `TrackProjector` (Swift) utilisée par la géométrie du
 * Road Book. Même ordre d'opérations que l'original ; parité vérifiée côté iOS par
 * `SharedRoadbookParityTests` (distances : voir `geodesicDistanceMeters`).
 */
object TrackGeometry {

    data class Projection(
        val nearestSegmentIndex: Int,
        val distanceToTrackMeters: Double,
        val cumulativeDistanceMeters: Double,
    )

    /** Distances cumulées depuis le départ, un élément par point de la trace. */
    fun cumulativeDistances(points: List<LatLon>): DoubleArray {
        if (points.isEmpty()) return DoubleArray(0)
        val result = DoubleArray(points.size)
        for (i in 1 until points.size) {
            result[i] = result[i - 1] + geodesicDistanceMeters(points[i - 1], points[i])
        }
        return result
    }

    fun project(coordinate: LatLon, points: List<LatLon>, cumulativeDistances: DoubleArray): Projection? {
        if (points.size <= 1 || points.size != cumulativeDistances.size) return null

        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        var bestCumulative = 0.0

        for (i in 0 until points.size - 1) {
            val (distance, t) = distanceFromPointToSegment(coordinate, points[i], points[i + 1])
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
                val segmentLength = cumulativeDistances[i + 1] - cumulativeDistances[i]
                bestCumulative = cumulativeDistances[i] + segmentLength * t
            }
        }
        return Projection(bestIndex, bestDistance, bestCumulative)
    }

    /** Position INTERPOLÉE sur la trace à une distance cumulée donnée. `null` hors de la trace. */
    fun interpolatedCoordinate(target: Double, points: List<LatLon>, cumulativeDistances: DoubleArray): LatLon? {
        if (points.size != cumulativeDistances.size || cumulativeDistances.isEmpty()) return null
        val total = cumulativeDistances.last()
        if (target < 0 || target > total) return null
        var low = 0
        var high = cumulativeDistances.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (cumulativeDistances[mid] < target) low = mid + 1 else high = mid
        }
        val upper = low
        if (upper <= 0) return points[0]
        val lower = upper - 1
        val length = cumulativeDistances[upper] - cumulativeDistances[lower]
        val t = if (length > 0) (target - cumulativeDistances[lower]) / length else 0.0
        val a = points[lower]
        val b = points[upper]
        return LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    /** Distance (m) point → segment et position relative `t`, projection locale équirectangulaire. */
    private fun distanceFromPointToSegment(p: LatLon, a: LatLon, b: LatLon, minT: Double = 0.0): Pair<Double, Double> {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = 111_320.0 * cos(a.latitude * PI / 180)

        val px = (p.longitude - a.longitude) * metersPerDegreeLon
        val py = (p.latitude - a.latitude) * metersPerDegreeLat
        val bx = (b.longitude - a.longitude) * metersPerDegreeLon
        val by = (b.latitude - a.latitude) * metersPerDegreeLat
        val abLenSq = bx * bx + by * by
        val t = if (abLenSq > 0) max(min(max(minT, 0.0), 1.0), min(1.0, (px * bx + py * by) / abLenSq)) else 0.0
        val dx = px - bx * t
        val dy = py - by * t
        return Pair(sqrt(dx * dx + dy * dy), t)
    }
}
