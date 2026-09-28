package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.abs
import kotlin.math.max

/** Point de retour sur la trace : son rang dans les points de la trace et sa position le long d'elle. */
data class RejoinTarget(val pointIndex: Int, val coordinate: LatLon, val cumulativeDistanceMeters: Double)

/**
 * Chemin de reprise calculé (routage OSRM/Valhalla vers [target]) et ses virages — détectés par le
 * MÊME moteur que la trace ([RoadbookExtractor]) : mêmes paliers, mêmes pictogrammes, et ça marche
 * avec un routeur qui ne renvoie que la géométrie (OSRM).
 */
data class RejoinPlan(
    val target: RejoinTarget,
    val routePoints: List<LatLon>,
    val maneuvers: List<RoadbookManeuver>,
    val routeLengthMeters: Double,
)

/**
 * Où en est-on sur le chemin de reprise : prochain virage du chemin (`null` : plus de virage, tout
 * droit jusqu'à la trace) et distance restante jusqu'à la trace le long du chemin.
 */
data class RejoinProgress(
    val routeCumulativeDistanceMeters: Double,
    val nextManeuverIndex: Int?,
    val distanceToNextManeuverMeters: Double?,
    val remainingToTrackMeters: Double,
)

/** Réglages de la reprise (it33). */
object RejoinConstants {
    /** Cible dépassée : derrière soi (écart de cap au-delà de ça)… */
    const val PASSED_BEHIND_ANGLE_DEGREES = 90.0

    /** …en roulant (au moins cette vitesse : à l'arrêt, le cap GPS n'a pas de sens)… */
    const val PASSED_MIN_SPEED_MPS = 2.8

    /** …pendant au moins ce temps d'affilée (un lacet peut faire passer la cible derrière un instant). */
    const val PASSED_DELAY_SECONDS = 10.0

    /** Un virage du chemin de reprise dans ses premiers mètres est la position de départ elle-même, bruitée. */
    const val ROUTE_START_IGNORED_METERS = 20.0
}

/**
 * Reprise de la trace après une sortie (it33, demande terrain : « le point le plus proche devant
 * moi, en utilisant les routes, on ne coupe pas dans les champs ; si on le dépasse, après 10 s
 * l'app le recalcule »). Pur : le routage lui-même (réseau) reste natif.
 */
object RejoinPlanner {

    /**
     * Point de la trace le plus proche à VOL D'OISEAU parmi ceux situés DEVANT
     * [fromCumulativeMeters] (dernière position connue sur la trace, ou au-delà d'une cible déjà
     * dépassée) — jamais un point déjà parcouru. Toute la suite de la trace compte, pas seulement le
     * point suivant : une boucle qui repasse près de la position actuelle est bien prise en compte.
     */
    fun nearestAhead(position: LatLon, points: List<LatLon>, cumulativeDistances: DoubleArray, fromCumulativeMeters: Double): RejoinTarget? {
        if (points.isEmpty() || points.size != cumulativeDistances.size) return null
        var best = -1
        var bestDistance = Double.MAX_VALUE
        for (i in points.indices) {
            if (cumulativeDistances[i] < fromCumulativeMeters) continue
            val distance = geodesicDistanceMeters(position, points[i])
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        if (best < 0) return null
        return RejoinTarget(best, points[best], cumulativeDistances[best])
    }

    /** Virages du chemin de reprise (mêmes règles que la trace), hors départ bruité. */
    fun plan(target: RejoinTarget, routePoints: List<LatLon>, settings: RoadbookSettings): RejoinPlan {
        val cumulative = TrackGeometry.cumulativeDistances(routePoints)
        val maneuvers = RoadbookExtractor.maneuvers(routePoints, settings)
            .filter { it.cumulativeDistanceMeters >= RejoinConstants.ROUTE_START_IGNORED_METERS }
        return RejoinPlan(target, routePoints, maneuvers, cumulative.lastOrNull() ?: 0.0)
    }

    /** Progression le long du chemin : même compte à rebours et même maintien que le Road Book. */
    fun progress(plan: RejoinPlan, position: LatLon): RejoinProgress {
        val cumulative = TrackGeometry.cumulativeDistances(plan.routePoints)
        val along = TrackGeometry.project(position, plan.routePoints, cumulative)?.cumulativeDistanceMeters ?: 0.0
        val next = RoadbookLiveProgress.next(plan.maneuvers.map { it.cumulativeDistanceMeters }, along)
        return RejoinProgress(
            routeCumulativeDistanceMeters = along,
            nextManeuverIndex = next?.index,
            distanceToNextManeuverMeters = next?.distanceRemainingMeters,
            remainingToTrackMeters = max(plan.routeLengthMeters - along, 0.0),
        )
    }
}

/**
 * Détecte une cible de reprise DÉPASSÉE : derrière soi (écart entre le cap suivi et la direction de
 * la cible au-delà de [RejoinConstants.PASSED_BEHIND_ANGLE_DEGREES]), en roulant, pendant
 * [RejoinConstants.PASSED_DELAY_SECONDS] d'affilée. Une position à l'arrêt ou sans cap ne compte pas
 * (ni pour, ni contre : le chrono est remis à zéro).
 */
class RejoinPassedDetector {
    private var behindSinceSeconds: Double? = null

    /** @param courseDegrees cap suivi (0-360°), `null` si inconnu. @return `true` : cible dépassée, à recalculer. */
    fun update(position: LatLon, courseDegrees: Double?, speedMetersPerSecond: Double, timestampSeconds: Double, target: LatLon): Boolean {
        if (courseDegrees == null || speedMetersPerSecond < RejoinConstants.PASSED_MIN_SPEED_MPS) {
            behindSinceSeconds = null
            return false
        }
        val toTarget = RoadbookAnalyzer.bearing(position, target)
        val isBehind = abs(RoadbookAnalyzer.signedAngleDifference(courseDegrees, toTarget)) > RejoinConstants.PASSED_BEHIND_ANGLE_DEGREES
        if (!isBehind) {
            behindSinceSeconds = null
            return false
        }
        val since = behindSinceSeconds ?: timestampSeconds.also { behindSinceSeconds = it }
        if (timestampSeconds - since >= RejoinConstants.PASSED_DELAY_SECONDS) {
            behindSinceSeconds = null
            return true
        }
        return false
    }

    fun reset() {
        behindSinceSeconds = null
    }
}
