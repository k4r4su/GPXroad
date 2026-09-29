package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.roundToInt

/**
 * Manœuvre Valhalla telle que décodée d'une réponse `/trace_route` (champs utiles seulement) — le
 * décodage JSON reste natif (Android : `org.json` ; iOS : `ValhallaManeuver`, Decodable).
 */
data class ValhallaManeuverRecord(
    val type: Int,
    val beginShapeIndex: Int,
    val roundaboutExitCount: Int? = null,
    val streetNames: List<String> = emptyList(),
)

/** Un tronçon (`trip.legs[]`) : ses manœuvres et sa géométrie recalée décodée. */
data class ValhallaLeg(val maneuvers: List<ValhallaManeuverRecord>, val shape: List<LatLon>)

/** Résultat du map matching : manœuvres retenues ET tracés réellement recalés (un par tronçon). */
data class MapMatchResult(val maneuvers: List<MapMatchedManeuver>, val matchedShapes: List<List<LatLon>>)

/**
 * Map matching Valhalla (`/trace_route`) — partie PURE, portage de `ValhallaMapMatchingService.swift`
 * et de `ValhallaRoutingService.decodePolyline6` : mêmes règles, même filtrage.
 */
object ValhallaMapMatching {
    /** Au-delà, la trace est sous-échantillonnée uniformément avant l'envoi (`mapMatchingMaxTracePoints`). */
    const val MAX_TRACE_POINTS = 2000

    /** Réduction de la préférence autoroute/péage, identique au routage (it20). */
    const val AUTO_COSTING_USE_HIGHWAYS = 0.3
    const val AUTO_COSTING_USE_TOLLS = 0.1

    /** Sous-échantillonnage UNIFORME (mêmes indices que `downsampledForMapMatching` iOS). */
    fun <T> downsampled(points: List<T>, maxPoints: Int = MAX_TRACE_POINTS): List<T> {
        if (points.size <= maxPoints || maxPoints <= 1) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1).toDouble()
        // Valeurs positives : `roundToInt` arrondit comme `rounded()` de Swift.
        return (0 until maxPoints).map { points[(it * step).roundToInt()] }
    }

    /**
     * Manœuvres retenues de TOUS les tronçons, chacune avec sa progression le long de la route
     * recalée ENTIÈRE (tronçons précédents inclus).
     */
    fun matchedManeuvers(legs: List<ValhallaLeg>): List<MapMatchedManeuver> {
        val legCumulative = legs.map { TrackGeometry.cumulativeDistances(it.shape) }
        val totalRouteMeters = legCumulative.sumOf { it.lastOrNull() ?: 0.0 }
        var legStartMeters = 0.0
        val result = mutableListOf<MapMatchedManeuver>()
        legs.forEachIndexed { index, leg ->
            val cumulative = legCumulative[index]
            val offset = legStartMeters
            result += intermediateManeuvers(leg.maneuvers, leg.shape) { shapeIndex ->
                if (totalRouteMeters > 0 && shapeIndex in cumulative.indices) (offset + cumulative[shapeIndex]) / totalRouteMeters else null
            }
            legStartMeters += cumulative.lastOrNull() ?: 0.0
        }
        return result
    }

    /**
     * Exclut la première ET la dernière manœuvre (départ/arrivée) ; ne garde que les types dont
     * [ValhallaManeuverType.roadbookTier] n'est pas nul (écarte « continuer », « devient »…).
     */
    fun intermediateManeuvers(
        maneuvers: List<ValhallaManeuverRecord>,
        legCoordinates: List<LatLon>,
        routeProgressFractionAtShapeIndex: (Int) -> Double? = { null },
    ): List<MapMatchedManeuver> {
        if (maneuvers.size <= 2) return emptyList()
        return (1 until maneuvers.size - 1).mapNotNull { index ->
            val maneuver = maneuvers[index]
            if (maneuver.beginShapeIndex !in legCoordinates.indices) return@mapNotNull null
            val type = ValhallaManeuverType.fromRawValue(maneuver.type)
            if (type.roadbookTier == null) return@mapNotNull null
            MapMatchedManeuver(
                coordinate = legCoordinates[maneuver.beginShapeIndex],
                type = type,
                roundaboutExitCount = maneuver.roundaboutExitCount,
                routeProgressFraction = routeProgressFractionAtShapeIndex(maneuver.beginShapeIndex),
                streetNamesBefore = maneuvers[index - 1].streetNames,
                streetNamesAfter = maneuver.streetNames,
            )
        }
    }

    /** Polyline PRÉCISION 6 (facteur 1e6, format Valhalla — pas 1e5 comme OSRM/Google). */
    fun decodePolyline6(encoded: String): List<LatLon> {
        val coordinates = mutableListOf<LatLon>()
        var index = 0
        var latitude = 0L
        var longitude = 0L
        while (index < encoded.length) {
            val lat = nextValue(encoded, index)
            index = lat.second
            val lon = nextValue(encoded, index)
            index = lon.second
            latitude += lat.first
            longitude += lon.first
            coordinates += LatLon(latitude / 1e6, longitude / 1e6)
        }
        return coordinates
    }

    private fun nextValue(encoded: String, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var result = 0L
        var byte: Int
        do {
            byte = encoded[index].code - 63
            index += 1
            result = result or ((byte and 0x1f).toLong() shl shift)
            shift += 5
        } while (byte >= 0x20)
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to index
    }
}
