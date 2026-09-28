package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Géométrie d'une trace (portage de `TrackProjector`, it32-it33) : distances cumulées, projection
 * d'une position, passages près d'un point, interpolation. Distances : [geodesicDistanceMeters].
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

    /**
     * Tous les PASSAGES de la trace à moins de [maxDistanceMeters] de [coordinate], dans l'ordre du
     * trajet — un passage = segments consécutifs tous à portée, représenté par sa meilleure
     * projection. Une boucle ou un aller-retour qui repasse au même endroit en produit plusieurs.
     */
    fun passes(
        coordinate: LatLon,
        points: List<LatLon>,
        cumulativeDistances: DoubleArray,
        maxDistanceMeters: Double,
        minimumCumulativeDistanceMeters: Double = 0.0,
    ): List<Projection> {
        if (points.size <= 1 || points.size != cumulativeDistances.size) return emptyList()
        val result = mutableListOf<Projection>()
        var currentPassBest: Projection? = null
        for (i in 0 until points.size - 1) {
            if (cumulativeDistances[i + 1] < minimumCumulativeDistanceMeters) continue
            val segmentLength = cumulativeDistances[i + 1] - cumulativeDistances[i]
            val minT = if (segmentLength > 0) max((minimumCumulativeDistanceMeters - cumulativeDistances[i]) / segmentLength, 0.0) else 0.0
            val (distance, t) = distanceFromPointToSegment(coordinate, points[i], points[i + 1], minT)
            if (distance > maxDistanceMeters) {
                currentPassBest?.let { result.add(it) }
                currentPassBest = null
                continue
            }
            val candidate = Projection(i, distance, cumulativeDistances[i] + segmentLength * t)
            if (distance < (currentPassBest?.distanceToTrackMeters ?: Double.MAX_VALUE)) currentPassBest = candidate
        }
        currentPassBest?.let { result.add(it) }
        return result
    }

    /** Point de la trace le plus proche à VOL D'OISEAU de [coordinate], parmi TOUS ses points. */
    fun nearestPointByAirDistance(coordinate: LatLon, points: List<LatLon>, cumulativeDistances: DoubleArray): Int? {
        if (points.isEmpty() || points.size != cumulativeDistances.size) return null
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for ((index, point) in points.withIndex()) {
            val distance = geodesicDistanceMeters(coordinate, point)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
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
