package com.olivier.gpxroad.shared.nav

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import com.olivier.gpxroad.shared.roadbook.ValhallaManeuverType
import kotlin.math.max
import kotlin.math.min

/** Réglages du guidage « Aller à » (`NavConstants` iOS). */
object NavConstants {
    const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
    const val NOMINATIM_MIN_INTERVAL_SECONDS = 1.0
    const val NOMINATIM_RESULT_LIMIT = 6
    /** Biais de proximité (±0,3° ≈ 33 km autour de la position) — jamais une restriction. */
    const val NOMINATIM_PROXIMITY_BIAS_DEGREES = 0.3
    /** Annonce lointaine (texte « alerte ») puis proche (texte « pré-transition »). */
    val VOICE_ANNOUNCE_DISTANCES_METERS = listOf(500.0, 100.0)
    const val MANEUVER_PASSED_RADIUS_METERS = 25.0
    const val OFF_ROUTE_DISTANCE_METERS = 30.0
    const val OFF_ROUTE_TOLERANCE_SECONDS = 8.0
    /** Un recalcul qui échoue ne relance pas une requête à chaque fix. */
    const val RECOMPUTE_COOLDOWN_SECONDS = 12.0
    const val MAX_SEARCH_HISTORY = 5
}

/** Profil « Aller à » : routes (voitures), piste (vélo, hors grands axes) ou mixte. */
enum class GoToProfile(val averageSpeedKmh: Double) { ROUTE(70.0), OFFROAD(30.0), MIXED(50.0) }

/** Manœuvre Valhalla `/route` : textes DÉJÀ rédigés par Valhalla dans la langue demandée. */
data class NavManeuver(
    val type: ValhallaManeuverType,
    val instruction: String,
    val verbalAlert: String? = null,
    val verbalPre: String? = null,
    val verbalPost: String? = null,
    val streetNames: List<String> = emptyList(),
    val beginShapeIndex: Int,
    val isMultiCue: Boolean = false,
    val roundaboutExitCount: Int? = null,
) {
    val isRoundabout: Boolean get() = type == ValhallaManeuverType.ROUNDABOUT_ENTER || type == ValhallaManeuverType.ROUNDABOUT_EXIT
    val isArrival: Boolean
        get() = type == ValhallaManeuverType.DESTINATION || type == ValhallaManeuverType.DESTINATION_LEFT || type == ValhallaManeuverType.DESTINATION_RIGHT
}

data class NavRoute(
    val points: List<LatLon>,
    val maneuvers: List<NavManeuver>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val destinationLabel: String,
) {
    val cumulative: DoubleArray = TrackGeometry.cumulativeDistances(points)
}

/** Ce qu'un fix change au guidage : textes à dire, et s'il faut recalculer l'itinéraire. */
data class NavUpdate(
    val announcements: List<String>,
    val shouldRecompute: Boolean,
)

/**
 * Suivi d'un guidage « Aller à » riche (portage de `RideSessionManager.updateNavProgress` iOS) :
 * manœuvre courante (passée à 25 m de son point de départ), annonces vocales à 500 m (texte
 * d'alerte) et 100 m (texte proche) puis après la manœuvre, restant/progression le long de
 * l'itinéraire, et recalcul hors itinéraire (30 m pendant 8 s, au plus une fois toutes les 12 s).
 */
class NavGuidanceTracker(route: NavRoute) {
    var route: NavRoute = route
        private set
    var currentIndex = 0
        private set
    var distanceToManeuverMeters: Double? = null
        private set
    var remainingMeters: Double = route.totalDistanceMeters
        private set
    var percentComplete: Double = 0.0
        private set
    /** Nombre de points de l'itinéraire déjà parcourus (tracé parcouru/restant). */
    var traveledPointCount: Int = 0
        private set
    private val announced = HashMap<Int, MutableSet<Double>>()
    private var offRouteSince: Double? = null
    private var lastRecompute: Double? = null

    val currentManeuver: NavManeuver? get() = route.maneuvers.getOrNull(currentIndex)
    /** « Puis… » : seulement si Valhalla signale un enchaînement trop rapproché. */
    val nextManeuverIfChained: NavManeuver? get() = currentManeuver?.takeIf { it.isMultiCue }?.let { route.maneuvers.getOrNull(currentIndex + 1) }

    /** Nouvel itinéraire (recalcul réussi) : on repart de sa première manœuvre. */
    fun replaceRoute(newRoute: NavRoute) {
        route = newRoute
        currentIndex = 0
        announced.clear()
        offRouteSince = null
        traveledPointCount = 0
        remainingMeters = newRoute.totalDistanceMeters
        percentComplete = 0.0
    }

    fun update(position: LatLon, timestampSeconds: Double, voiceEnabled: Boolean, isRecomputing: Boolean): NavUpdate {
        val texts = ArrayList<String>()
        val maneuver = currentManeuver
        if (maneuver != null) {
            val point = route.points.getOrNull(maneuver.beginShapeIndex) ?: position
            val distance = geodesicDistanceMeters(position, point)
            distanceToManeuverMeters = distance
            if (voiceEnabled) {
                // Plusieurs seuils franchis d'un coup (départ, manœuvres rapprochées) : seule l'annonce
                // la plus proche est dite — l'iPhone disait la même consigne deux fois de suite.
                val far = NavConstants.VOICE_ANNOUNCE_DISTANCES_METERS.max()
                val done = announced.getOrPut(currentIndex) { mutableSetOf() }
                val crossed = NavConstants.VOICE_ANNOUNCE_DISTANCES_METERS.filter { distance <= it && done.add(it) }
                crossed.minOrNull()?.let { threshold ->
                    texts += if (threshold == far) maneuver.verbalAlert ?: maneuver.instruction else maneuver.verbalPre ?: maneuver.instruction
                }
            }
            if (distance <= NavConstants.MANEUVER_PASSED_RADIUS_METERS) {
                if (voiceEnabled) maneuver.verbalPost?.let { texts += it }
                currentIndex += 1
            }
        } else {
            distanceToManeuverMeters = null
        }

        var recompute = false
        val projection = TrackGeometry.project(position, route.points, route.cumulative)
        if (projection != null) {
            traveledPointCount = TrackGeometry.nearestPointByAirDistance(position, route.points, route.cumulative)?.plus(1) ?: 0
            remainingMeters = max(route.totalDistanceMeters - projection.cumulativeDistanceMeters, 0.0)
            percentComplete = if (route.totalDistanceMeters > 0) min(100.0, max(0.0, projection.cumulativeDistanceMeters / route.totalDistanceMeters * 100)) else 0.0
            if (projection.distanceToTrackMeters > NavConstants.OFF_ROUTE_DISTANCE_METERS) {
                val since = offRouteSince ?: timestampSeconds.also { offRouteSince = it }
                val cooled = lastRecompute?.let { timestampSeconds - it >= NavConstants.RECOMPUTE_COOLDOWN_SECONDS } ?: true
                if (timestampSeconds - since >= NavConstants.OFF_ROUTE_TOLERANCE_SECONDS && cooled && !isRecomputing) {
                    lastRecompute = timestampSeconds
                    recompute = true
                }
            } else {
                offRouteSince = null
            }
        }
        return NavUpdate(texts, recompute)
    }
}

/**
 * Guidage « Aller à » simple (sans Valhalla, ou profils Piste/Mixte) : un chemin, la distance
 * restante le long de lui et une durée estimée à la vitesse moyenne du profil.
 */
data class GoToGuidance(val points: List<LatLon>, val profile: GoToProfile, val destination: LatLon, val label: String) {
    val cumulative: DoubleArray = TrackGeometry.cumulativeDistances(points)
    val lengthMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    fun remainingMeters(position: LatLon): Double {
        val projection = TrackGeometry.project(position, points, cumulative) ?: return geodesicDistanceMeters(position, destination)
        return max(lengthMeters - projection.cumulativeDistanceMeters, 0.0) + projection.distanceToTrackMeters
    }

    fun estimatedSeconds(remainingMeters: Double): Double = remainingMeters / 1000 / profile.averageSpeedKmh * 3600

    /** Arrivée : à 30 m de la destination. */
    fun isArrived(position: LatLon): Boolean = geodesicDistanceMeters(position, destination) <= ARRIVAL_METERS

    companion object {
        const val ARRIVAL_METERS = 30.0
    }
}

/** Historique des recherches (5 dernières, la plus récente d'abord, sans doublon de libellé). */
object SearchHistory {
    fun <T> record(entries: List<T>, entry: T, label: (T) -> String): List<T> =
        (listOf(entry) + entries.filter { label(it) != label(entry) }).take(NavConstants.MAX_SEARCH_HISTORY)
}

/** `viewbox` Nominatim (biais de proximité autour de la position). */
fun nominatimViewbox(center: LatLon, delta: Double = NavConstants.NOMINATIM_PROXIMITY_BIAS_DEGREES): String =
    listOf(center.longitude - delta, center.latitude + delta, center.longitude + delta, center.latitude - delta).joinToString(",")
