package com.olivier.gpxroad.shared.offline

import com.olivier.gpxroad.shared.LatLon

/** Ce qu'il faut avoir en local pour rouler une trace sans réseau. */
enum class ReadinessPart { MAP, LANDMARKS, ROUTE_MATCH, ROUNDABOUTS }

enum class ReadinessLevel { READY, PARTIAL, NONE }

data class TrackReadiness(val level: ReadinessLevel, val missing: List<ReadinessPart>)

/**
 * Préparation complète d'une trace (idée du 06/10) : carte du couloir, repères, recalage Valhalla et
 * ronds-points. Règles communes iOS/Android ; chaque plateforme dit seulement ce qu'elle a en local.
 */
object TrackPreparation {
    /** Couloir de toute la trace : mêmes boîtes (±1 km tous les 250 m) que le téléchargement manuel. */
    fun mapRings(points: List<LatLon>): List<List<LatLon>> =
        if (points.size < 2) emptyList() else OfflineArea.corridorBoxes(points).map { box ->
            listOf(
                LatLon(box.minLat, box.minLon), LatLon(box.minLat, box.maxLon),
                LatLon(box.maxLat, box.maxLon), LatLon(box.maxLat, box.minLon), LatLon(box.minLat, box.minLon),
            )
        }

    /**
     * Chaque élément : `true` = présent en local, `false` = manquant, `null` = sans objet (fonction désactivée :
     * aucune catégorie de repère, Valhalla non configuré…). Prête = tout ce qui s'applique est présent.
     */
    fun evaluate(map: Boolean, landmarks: Boolean?, routeMatch: Boolean?, roundabouts: Boolean?): TrackReadiness {
        val applicable = listOf(
            ReadinessPart.MAP to map,
            ReadinessPart.LANDMARKS to landmarks,
            ReadinessPart.ROUTE_MATCH to routeMatch,
            ReadinessPart.ROUNDABOUTS to roundabouts,
        ).mapNotNull { (part, present) -> present?.let { part to it } }
        val missing = applicable.filter { !it.second }.map { it.first }
        val level = when {
            missing.isEmpty() -> ReadinessLevel.READY
            missing.size == applicable.size -> ReadinessLevel.NONE
            else -> ReadinessLevel.PARTIAL
        }
        return TrackReadiness(level, missing)
    }
}

/** Alerte « plus de carte devant » (idée du 06/10) : distance jusqu'au premier trou de couverture sur la trace. */
object CoverageGap {
    const val LOOK_AHEAD_METERS = 20_000.0
    const val SAMPLE_STEP_METERS = 250.0

    /** Seuils d'alerte « plus de carte dans X km » : une seule alerte par seuil franchi. */
    val ALERT_THRESHOLDS_METERS = listOf(15_000.0, 5_000.0)

    /**
     * Seuil à annoncer maintenant, ou `null`. `distance` = trou devant (`null` : pas de trou) ; `lastAlerted` = dernier seuil
     * annoncé (`null` : aucun). Retourne le plus petit seuil atteint s'il est plus petit que le dernier annoncé.
     */
    fun nextAlert(distance: Double?, lastAlerted: Double?): Double? {
        if (distance == null) return null
        val reached = ALERT_THRESHOLDS_METERS.filter { distance <= it }.minOrNull() ?: return null
        return if (lastAlerted == null || reached < lastAlerted) reached else null
    }

    /** Point dans un anneau (lancer de rayon, plan lat/lon : les zones font quelques dizaines de km). */
    fun inRing(point: LatLon, ring: List<LatLon>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
            ) inside = !inside
            j = i
        }
        return inside
    }

    fun covered(point: LatLon, rings: List<List<LatLon>>): Boolean = rings.any { inRing(point, it) }

    /**
     * Distance (m) devant `fromCumulativeMeters` jusqu'au premier échantillon hors de toute zone, ou `null` si
     * tout ce qui est devant (sur `lookAheadMeters`) est couvert. Une trace sans zone du tout : trou immédiat (0).
     */
    fun distanceToGap(
        points: List<LatLon>,
        cumulative: DoubleArray,
        fromCumulativeMeters: Double,
        rings: List<List<LatLon>>,
        lookAheadMeters: Double = LOOK_AHEAD_METERS,
    ): Double? {
        if (points.size != cumulative.size || points.isEmpty()) return null
        val end = fromCumulativeMeters + lookAheadMeters
        var next = fromCumulativeMeters
        var index = 0
        while (next <= end && index < points.size) {
            while (index < points.size - 1 && cumulative[index] < next) index++
            if (!covered(points[index], rings)) return maxOf(0.0, cumulative[index] - fromCumulativeMeters)
            if (cumulative[index] >= cumulative.last()) break
            next += SAMPLE_STEP_METERS
        }
        return null
    }
}
