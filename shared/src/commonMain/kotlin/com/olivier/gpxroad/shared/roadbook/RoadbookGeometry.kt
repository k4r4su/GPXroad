package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Palier d'un événement GÉOMÉTRIQUE — sous-ensemble de `RoadbookTier` (Swift) détectable par l'angle seul. */
enum class GeometricTier { LIGHT, MARKED, HARD, VERY_HARD, U_TURN }

enum class GeometricDirection { LEFT, RIGHT, U_TURN }

/** Équivalent d'un `Checkpoint` Swift produit par la partie géométrique de `RoadbookAnalyzer`. */
data class GeometricEvent(
    val coordinate: LatLon,
    val turnAngleDegrees: Double,
    val direction: GeometricDirection,
    val tier: GeometricTier,
    val sequenceIndex: Int,
    val sourcePointIndex: Int,
    val trackCumulativeDistanceMeters: Double,
)

/** Paliers d'angle — SEUL endroit où un angle devient un palier ; sous `light`, jamais un virage. */
data class TierThresholds(val light: Double, val marked: Double, val hard: Double, val veryHard: Double) {
    fun tier(absoluteAngle: Double): GeometricTier = when {
        absoluteAngle >= veryHard -> GeometricTier.VERY_HARD
        absoluteAngle >= hard -> GeometricTier.HARD
        absoluteAngle >= marked -> GeometricTier.MARKED
        else -> GeometricTier.LIGHT
    }

    companion object {
        val DEFAULT = TierThresholds(
            RoadbookConstants.LIGHT_THRESHOLD_DEGREES_DEFAULT,
            RoadbookConstants.MARKED_THRESHOLD_DEGREES_DEFAULT,
            RoadbookConstants.HARD_THRESHOLD_DEGREES_DEFAULT,
            RoadbookConstants.VERY_HARD_THRESHOLD_DEGREES_DEFAULT,
        )
    }
}

/** Seuils du Road Book — copie de `NavigationConstants` (Swift), à garder synchronisée (test de parité). */
object RoadbookConstants {
    const val WINDOW_BEFORE_METERS_DEFAULT = 40.0
    const val WINDOW_AFTER_METERS_DEFAULT = 40.0
    const val LIGHT_THRESHOLD_DEGREES_DEFAULT = 25.0
    const val MARKED_THRESHOLD_DEGREES_DEFAULT = 45.0
    const val HARD_THRESHOLD_DEGREES_DEFAULT = 90.0
    const val VERY_HARD_THRESHOLD_DEGREES_DEFAULT = 135.0
    const val TURN_CLUSTER_METERS = 50.0
    const val U_TURN_MIN_DEGREES = 175.0
    const val U_TURN_SAME_PATH_MAX_METERS = 12.0
    const val U_TURN_ENDPOINT_GUARD_METERS = 200.0
}

/**
 * Portage it32 (pilote KMP) de la partie GÉOMÉTRIQUE de `RoadbookAnalyzer.buildRoadbookEvents`
 * (Swift) : changement de cap par cordes avant/après, regroupement des sommets rapprochés, vrai
 * demi-tour sur la même route, paliers, fusion des événements proches. La fusion des manœuvres
 * Valhalla reste en Swift (elle s'applique au résultat). Voir les commentaires de l'original pour
 * l'historique de chaque règle — ce fichier en reprend le calcul à l'identique.
 */
object RoadbookGeometry {

    fun buildGeometricEvents(
        points: List<LatLon>,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        thresholds: TierThresholds,
        mergeMinDistanceMeters: Double,
    ): List<GeometricEvent> {
        if (points.size <= 2 || windowBeforeMeters <= 0 || windowAfterMeters <= 0) return emptyList()

        val cumulative = TrackGeometry.cumulativeDistances(points)

        val candidates = mutableListOf<Candidate>()
        for (i in 1 until points.size - 1) {
            val angle = headingChange(cumulative[i], points, cumulative, windowBeforeMeters, windowAfterMeters) ?: continue
            if (abs(angle) < thresholds.light) continue
            candidates.add(Candidate(i, angle))
        }

        val raw = mutableListOf<RawEvent>()
        for (candidate in clustered(candidates, points, cumulative, windowBeforeMeters, windowAfterMeters, thresholds.light)) {
            val i = candidate.pointIndex
            val absAngle = abs(candidate.angle)

            val reversesOnSamePath = absAngle >= RoadbookConstants.U_TURN_MIN_DEGREES &&
                returnsOnSamePath(i, points, cumulative, min(windowBeforeMeters, windowAfterMeters))
            if (reversesOnSamePath && isNearTrackEndpoint(cumulative[i], cumulative)) continue

            val tier = if (reversesOnSamePath) GeometricTier.U_TURN else thresholds.tier(absAngle)
            val direction = when {
                tier == GeometricTier.U_TURN -> GeometricDirection.U_TURN
                candidate.angle > 0 -> GeometricDirection.RIGHT
                else -> GeometricDirection.LEFT
            }
            raw.add(RawEvent(points[i], absAngle, direction, tier, i))
        }

        return mergeNearby(raw, mergeMinDistanceMeters, cumulative)
    }

    /** Surcharge pour Swift : tableaux de latitudes/longitudes plutôt qu'une liste d'objets. */
    fun buildGeometricEvents(
        latitudes: DoubleArray,
        longitudes: DoubleArray,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        thresholds: TierThresholds,
        mergeMinDistanceMeters: Double,
    ): List<GeometricEvent> {
        require(latitudes.size == longitudes.size)
        val points = List(latitudes.size) { LatLon(latitudes[it], longitudes[it]) }
        return buildGeometricEvents(points, windowBeforeMeters, windowAfterMeters, thresholds, mergeMinDistanceMeters)
    }

    /** Changement de cap signé (droite > 0) à la distance cumulée `c`. `null` si la trace est trop courte autour. */
    fun headingChange(
        c: Double,
        points: List<LatLon>,
        cumulativeDistances: DoubleArray,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
    ): Double? = headingChange(c, c, points, cumulativeDistances, windowBeforeMeters, windowAfterMeters)

    private fun headingChange(
        start: Double,
        end: Double,
        points: List<LatLon>,
        cumulativeDistances: DoubleArray,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
    ): Double? {
        if (cumulativeDistances.isEmpty()) return null
        val total = cumulativeDistances.last()
        val approachStart = max(start - windowBeforeMeters, 0.0)
        val exitEnd = min(end + windowAfterMeters, total)
        if (start - approachStart < windowBeforeMeters / 2 || exitEnd - end < windowAfterMeters / 2) return null
        val a = TrackGeometry.interpolatedCoordinate(approachStart, points, cumulativeDistances) ?: return null
        val s = TrackGeometry.interpolatedCoordinate(start, points, cumulativeDistances) ?: return null
        val e = TrackGeometry.interpolatedCoordinate(end, points, cumulativeDistances) ?: return null
        val b = TrackGeometry.interpolatedCoordinate(exitEnd, points, cumulativeDistances) ?: return null
        return signedAngleDifference(bearing(a, s), bearing(e, b))
    }

    /** Cap absolu (0-360°) à suivre APRÈS la distance cumulée `c`. */
    fun outgoingHeading(c: Double, points: List<LatLon>, cumulativeDistances: DoubleArray, windowAfterMeters: Double): Double {
        if (cumulativeDistances.isEmpty()) return 0.0
        val total = cumulativeDistances.last()
        if (total <= 0) return 0.0
        val m = TrackGeometry.interpolatedCoordinate(c, points, cumulativeDistances) ?: return 0.0
        val forward = if (total - c >= 1) TrackGeometry.interpolatedCoordinate(min(c + windowAfterMeters, total), points, cumulativeDistances) else null
        val heading = if (forward != null) {
            bearing(m, forward)
        } else {
            val back = TrackGeometry.interpolatedCoordinate(max(c - windowAfterMeters, 0.0), points, cumulativeDistances) ?: return 0.0
            bearing(back, m)
        }
        return (heading + 360) % 360
    }

    fun bearing(from: LatLon, to: LatLon): Double {
        val lat1 = from.latitude * PI / 180
        val lat2 = to.latitude * PI / 180
        val deltaLon = (to.longitude - from.longitude) * PI / 180

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        val radians = atan2(y, x)
        return (radians * 180 / PI) % 360
    }

    /** Différence signée entre deux caps, normalisée dans (-180, 180]. Positif = droite. */
    fun signedAngleDifference(from: Double, to: Double): Double {
        var diff = (to - from) % 360
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }

    private data class Candidate(val pointIndex: Int, val angle: Double)

    private class RawEvent(
        val coordinate: LatLon,
        val angle: Double,
        val direction: GeometricDirection,
        val tier: GeometricTier,
        val pointIndex: Int,
    )

    /** Vrai demi-tour : le point `probeMeters` APRÈS le virage retombe sur le tracé des `probeMeters` PRÉCÉDENTS. */
    private fun returnsOnSamePath(i: Int, points: List<LatLon>, cumulativeDistances: DoubleArray, probeMeters: Double): Boolean {
        val c = cumulativeDistances[i]
        val probe = TrackGeometry.interpolatedCoordinate(c + probeMeters, points, cumulativeDistances) ?: return false
        val approachStart = TrackGeometry.interpolatedCoordinate(c - probeMeters, points, cumulativeDistances) ?: return false

        val approach = mutableListOf(approachStart)
        for (k in 0..i) {
            if (cumulativeDistances[k] > c - probeMeters) approach.add(points[k])
        }
        if (approach.size <= 1) return false
        val projection = TrackGeometry.project(probe, approach, TrackGeometry.cumulativeDistances(approach)) ?: return false
        return projection.distanceToTrackMeters <= RoadbookConstants.U_TURN_SAME_PATH_MAX_METERS
    }

    private fun isNearTrackEndpoint(cumulative: Double, cumulativeDistances: DoubleArray): Boolean {
        val total = if (cumulativeDistances.isEmpty()) 0.0 else cumulativeDistances.last()
        val guardMeters = RoadbookConstants.U_TURN_ENDPOINT_GUARD_METERS
        return cumulative < guardMeters || cumulative > total - guardMeters
    }

    /** Un événement par grappe de sommets rapprochés, portant le virage NET de la grappe. */
    private fun clustered(
        candidates: List<Candidate>,
        points: List<LatLon>,
        cumulativeDistances: DoubleArray,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        minimumTurnDegrees: Double,
    ): List<Candidate> {
        val groups = mutableListOf<MutableList<Candidate>>()
        for (candidate in candidates) {
            val last = groups.lastOrNull()?.lastOrNull()
            if (last != null &&
                cumulativeDistances[candidate.pointIndex] - cumulativeDistances[last.pointIndex] <= RoadbookConstants.TURN_CLUSTER_METERS
            ) {
                groups.last().add(candidate)
            } else {
                groups.add(mutableListOf(candidate))
            }
        }

        return groups.mapNotNull { group ->
            if (group.size <= 1) return@mapNotNull group.firstOrNull()
            val first = group.first()
            val last = group.last()
            val net = headingChange(
                cumulativeDistances[first.pointIndex],
                cumulativeDistances[last.pointIndex],
                points,
                cumulativeDistances,
                windowBeforeMeters,
                windowAfterMeters,
            ) ?: group.fold(0.0) { sum, candidate -> sum + candidate.angle }
            if (abs(net) < minimumTurnDegrees) return@mapNotNull null
            val apex = group.filter { (it.angle > 0) == (net > 0) }.firstMaxByAbsAngle()
            if (apex != null) return@mapNotNull Candidate(apex.pointIndex, net)
            // Grappe de plus de 180° : la différence de caps s'est enroulée (voir l'original).
            val wrapped = group.firstMaxByAbsAngle() ?: return@mapNotNull null
            Candidate(wrapped.pointIndex, (if (wrapped.angle > 0) 1 else -1) * (360 - abs(net)))
        }
    }

    /** Premier élément d'angle absolu maximal — même choix que `max(by:)` en Swift en cas d'égalité. */
    private fun List<Candidate>.firstMaxByAbsAngle(): Candidate? {
        var best: Candidate? = null
        for (candidate in this) {
            if (best == null || abs(best.angle) < abs(candidate.angle)) best = candidate
        }
        return best
    }

    /** Fusionne les événements trop rapprochés en gardant l'angle le plus marqué. */
    private fun mergeNearby(raw: List<RawEvent>, minDistanceMeters: Double, cumulativeDistances: DoubleArray): List<GeometricEvent> {
        val merged = mutableListOf<RawEvent>()
        for (candidate in raw) {
            val lastIndex = merged.lastIndex
            if (lastIndex >= 0 && geodesicDistanceMeters(merged[lastIndex].coordinate, candidate.coordinate) < minDistanceMeters) {
                if (candidate.angle > merged[lastIndex].angle) merged[lastIndex] = candidate
            } else {
                merged.add(candidate)
            }
        }
        return merged.mapIndexed { index, item ->
            GeometricEvent(
                coordinate = item.coordinate,
                turnAngleDegrees = item.angle,
                direction = item.direction,
                tier = item.tier,
                sequenceIndex = index + 1,
                sourcePointIndex = item.pointIndex,
                trackCumulativeDistanceMeters = cumulativeDistances[item.pointIndex],
            )
        }
    }
}
