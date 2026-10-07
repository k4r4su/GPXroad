package com.olivier.gpxroad.shared.plan

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.TrackGeometry

/** Véhicule de l'itinéraire créé (profil de calcul Valhalla). */
enum class PlanVehicle { MOTORCYCLE, CAR, BICYCLE }

/**
 * Réglages de la fenêtre « Créer un itinéraire » : le but est de s'amuser, donc autoroutes, péages et ferries
 * sont évités par défaut, et les pistes ne sont permises que sur demande.
 */
data class PlanOptions(
    val vehicle: PlanVehicle = PlanVehicle.MOTORCYCLE,
    val avoidHighways: Boolean = true,
    val avoidTolls: Boolean = true,
    val avoidFerries: Boolean = true,
    val allowTracks: Boolean = false,
)

/**
 * Création d'un itinéraire à la volée (06/10) : règles communes iOS/Android. On pose des points, Valhalla recale chaque
 * tronçon sur les routes existantes ; l'itinéraire obtenu devient une trace comme une autre (Road Book, Ride, hors ligne).
 */
object RoutePlanner {
    const val MIN_WAYPOINTS = 2

    /** Valhalla refuse au-delà de 20 lieux par requête `/route` (réglage par défaut du serveur). */
    const val MAX_WAYPOINTS = 20

    /** Un point posé à moins de cette distance du précédent est ignoré (double tap). */
    const val MIN_SPACING_METERS = 30.0

    fun costing(vehicle: PlanVehicle): String = when (vehicle) {
        PlanVehicle.MOTORCYCLE -> "motorcycle"
        PlanVehicle.CAR -> "auto"
        PlanVehicle.BICYCLE -> "bicycle"
    }

    /**
     * Corps JSON de la requête `/route` : un seul appel pour tout l'itinéraire. `use_*` va de 0 (éviter autant que possible)
     * à 1 (préférer) ; 0,5 est la valeur neutre de Valhalla. Éviter n'est pas interdire : si aucune autre route n'existe,
     * Valhalla peut quand même emprunter l'autoroute.
     */
    fun requestBody(points: List<LatLon>, options: PlanOptions): String {
        val neutral = 0.5
        fun use(avoid: Boolean) = if (avoid) 0.0 else neutral
        val costing = costing(options.vehicle)
        val costingOptions = when (options.vehicle) {
            PlanVehicle.MOTORCYCLE -> listOf(
                "use_highways" to use(options.avoidHighways), "use_tolls" to use(options.avoidTolls),
                "use_ferry" to use(options.avoidFerries), "use_trails" to if (options.allowTracks) 0.6 else 0.0,
            )
            PlanVehicle.CAR -> listOf(
                "use_highways" to use(options.avoidHighways), "use_tolls" to use(options.avoidTolls),
                "use_ferry" to use(options.avoidFerries), "use_tracks" to if (options.allowTracks) 0.5 else 0.0,
            )
            PlanVehicle.BICYCLE -> listOf("use_ferry" to use(options.avoidFerries))
        }
        val locations = points.joinToString(",") { """{"lat":${it.latitude},"lon":${it.longitude},"type":"break"}""" }
        val values = costingOptions.joinToString(",") { "\"${it.first}\":${it.second}" }
        return """{"locations":[$locations],"costing":"$costing","costing_options":{"$costing":{$values}},"units":"kilometers"}"""
    }

    /** Met bout à bout les tronçons d'une réponse : le dernier point d'un tronçon est le premier du suivant (un seul gardé). */
    fun mergeLegs(legs: List<List<LatLon>>): List<LatLon> {
        val result = ArrayList<LatLon>()
        for (leg in legs) {
            for (point in leg) {
                if (result.lastOrNull() == point) continue
                result += point
            }
        }
        return result
    }

    fun lengthMeters(points: List<LatLon>): Double =
        if (points.size < 2) 0.0 else TrackGeometry.cumulativeDistances(points).last()

    /** Peut-on poser un point ici ? (assez loin du précédent, et pas plus que [MAX_WAYPOINTS]). */
    fun canAdd(waypoints: List<LatLon>, candidate: LatLon): Boolean {
        if (waypoints.size >= MAX_WAYPOINTS) return false
        val last = waypoints.lastOrNull() ?: return true
        return TrackGeometry.cumulativeDistances(listOf(last, candidate)).last() >= MIN_SPACING_METERS
    }
}
