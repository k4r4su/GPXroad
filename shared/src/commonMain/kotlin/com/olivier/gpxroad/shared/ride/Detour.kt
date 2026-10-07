package com.olivier.gpxroad.shared.ride

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.roadbook.TrackGeometry

/** « Chemin bloqué » (portage de la partie détour de `RideSessionManager` iOS). */
object DetourConstants {
    /** Points de retour essayés devant soi sur la trace : 500, 1 000, 1 500 puis 2 000 m. */
    const val AHEAD_MIN_METERS = 500.0
    const val AHEAD_MAX_METERS = 2_000.0
    const val AHEAD_STEP_METERS = 500.0
    /** Détour fini : à 20 m du point de retour, ou de nouveau sur la trace après l'avoir quittée. */
    const val CLEAR_RADIUS_METERS = 20.0
    /** « Portion bloquée ? » : hors trace (> 50 m) depuis 30 s ou sur 200 m. */
    const val OFF_TRACK_METERS = 50.0
    const val STAGNANT_SECONDS = 30.0
    const val STAGNANT_METERS = 200.0
    /** Signalements partagés : alerte si un point connu est à moins de 300 m de la trace. */
    const val SHARED_ALERT_RADIUS_METERS = 300.0
    const val SHARED_FADE_AFTER_DAYS = 90.0
    const val SHARED_EXPIRE_AFTER_DAYS = 180.0
}

object DetourPlanner {
    /** Points de retour candidats devant [fromCumulativeMeters], du plus proche au plus lointain. */
    fun candidates(points: List<LatLon>, cumulative: DoubleArray, fromCumulativeMeters: Double): List<LatLon> {
        val total = cumulative.lastOrNull() ?: return emptyList()
        val result = ArrayList<LatLon>()
        var ahead = DetourConstants.AHEAD_MIN_METERS
        while (ahead <= DetourConstants.AHEAD_MAX_METERS) {
            val target = fromCumulativeMeters + ahead
            if (target > total) break
            TrackGeometry.interpolatedCoordinate(target, points, cumulative)?.let { result += it }
            ahead += DetourConstants.AHEAD_STEP_METERS
        }
        return result
    }
}

/**
 * Suivi d'un détour : fini au point de retour, ou dès le retour sur la trace — mais seulement après
 * l'avoir QUITTÉE (on demande souvent le détour depuis la trace, devant le chemin bloqué : l'iPhone
 * l'effaçait alors au fix suivant).
 */
class DetourTracker(val target: LatLon) {
    private var hasLeftTrack = false

    /** @return `true` quand le détour est terminé. */
    fun update(position: LatLon, distanceToTrackMeters: Double?): Boolean {
        if (geodesicDistanceMeters(position, target) <= DetourConstants.CLEAR_RADIUS_METERS) return true
        val distance = distanceToTrackMeters ?: return false
        if (distance > DetourConstants.CLEAR_RADIUS_METERS) hasLeftTrack = true
        return hasLeftTrack && distance <= DetourConstants.CLEAR_RADIUS_METERS
    }
}

/** « Portion bloquée ? » : hors trace depuis 30 s, ou 200 m parcourus hors trace. */
class BlockedPathDetector {
    private var since: Double? = null
    private var last: LatLon? = null
    private var accumulated = 0.0

    fun update(position: LatLon, distanceToTrackMeters: Double, timestampSeconds: Double): Boolean {
        if (distanceToTrackMeters <= DetourConstants.OFF_TRACK_METERS) {
            reset()
            return false
        }
        val start = since ?: timestampSeconds.also { since = it }
        last?.let { accumulated += geodesicDistanceMeters(it, position) }
        last = position
        return timestampSeconds - start >= DetourConstants.STAGNANT_SECONDS || accumulated >= DetourConstants.STAGNANT_METERS
    }

    fun reset() {
        since = null
        last = null
        accumulated = 0.0
    }
}

/**
 * `BLOCKED` : chemin impraticable (arbre, barrière…), montré dans le Ride ; `FORBIDDEN` : chemin interdit aux véhicules,
 * signalé depuis le planificateur d'itinéraire — jamais montré comme obstacle, mais évité par les itinéraires créés.
 */
enum class BlockageKind { BLOCKED, FORBIDDEN }

/** Point bloqué partagé (serveur auto-hébergé, anonyme). [wayId] : identifiant OpenStreetMap du chemin, s'il est connu. */
data class SharedBlockage(
    val id: String,
    val coordinate: LatLon,
    val note: String?,
    val lastConfirmedMillis: Long,
    val kind: BlockageKind = BlockageKind.BLOCKED,
    val wayId: Long? = null,
) {
    fun ageDays(nowMillis: Long): Double = (nowMillis - lastConfirmedMillis) / 86_400_000.0
    fun isFaded(nowMillis: Long): Boolean = ageDays(nowMillis) > DetourConstants.SHARED_FADE_AFTER_DAYS
    fun isExpired(nowMillis: Long): Boolean = ageDays(nowMillis) > DetourConstants.SHARED_EXPIRE_AFTER_DAYS
}

object SharedBlockages {
    /** Le point connu le plus proche de la trace, à moins de 300 m d'elle ; `null` sinon. */
    fun nearestAlongTrack(blockages: List<SharedBlockage>, points: List<LatLon>): SharedBlockage? {
        if (points.isEmpty()) return null
        var best: Pair<SharedBlockage, Double>? = null
        for (blockage in blockages) {
            var min = Double.MAX_VALUE
            for (point in points) {
                val d = geodesicDistanceMeters(blockage.coordinate, point)
                if (d < min) min = d
                if (min <= DetourConstants.SHARED_ALERT_RADIUS_METERS) break
            }
            if (min <= DetourConstants.SHARED_ALERT_RADIUS_METERS && (best == null || min < best.second)) best = blockage to min
        }
        return best?.first
    }
}
