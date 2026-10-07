package com.olivier.gpxroad.shared.plan

/** Ce que disent les données OpenStreetMap de l'accès d'un chemin à NOTRE véhicule (du plus sûr au plus grave). */
enum class AccessVerdict { OK, TO_VERIFY, RESTRICTED, FORBIDDEN }

/** Arête d'une réponse Valhalla `trace_attributes` : un tronçon d'un seul chemin OSM, avec ses indices dans la forme recalée. */
data class AccessEdge(
    val wayId: Long,
    val use: String,
    val unpaved: Boolean,
    val lengthMeters: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val name: String?,
)

/** Portion d'itinéraire à signaler : arêtes consécutives de même verdict (jamais [AccessVerdict.OK]). */
data class AccessSegment(
    val verdict: AccessVerdict,
    val wayIds: List<Long>,
    val name: String?,
    val lengthMeters: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
)

/**
 * Pistes autorisées ? (06/10) : règles communes iOS/Android. OpenStreetMap dit « accès interdit » ou « riverains » quand
 * c'est étiqueté, mais beaucoup de pistes n'ont AUCUNE étiquette : leur légalité dépend du pays (en France, la circulation des
 * véhicules à moteur hors des voies ouvertes à la circulation publique est interdite). Une piste sans étiquette est donc
 * « à vérifier », jamais « autorisée » : seul un accès explicite (`yes`, `designated`, `permissive`) vaut OK.
 */
object TrackAccess {
    /** Types de voies Valhalla qui méritent une vérification (le reste est de la route ordinaire). */
    val CHECKED_USES = setOf(
        "track", "path", "bridleway", "footway", "pedestrian", "steps", "cycleway", "mountain_bike",
        "service_road", "emergency_access", "driveway", "alley",
    )

    /** Une requête Overpass trop grosse échoue : au-delà, on ne vérifie que les premiers chemins. */
    const val MAX_WAYS_PER_CHECK = 300

    private val DENIED = setOf("no", "private", "forestry", "agricultural", "military")
    private val GRANTED = setOf("yes", "designated", "permissive")
    private val RESTRICTED = setOf("destination", "customers", "delivery", "permit")
    private val FOOT_ONLY_FOR_MOTORS = setOf("path", "footway", "pedestrian", "steps", "bridleway", "cycleway", "corridor")

    fun needsCheck(edge: AccessEdge): Boolean = edge.use in CHECKED_USES || edge.unpaved

    /** Chemins dont il faut lire les étiquettes (sans doublon, dans l'ordre de l'itinéraire). */
    fun checkedWayIds(edges: List<AccessEdge>): List<Long> =
        edges.filter(::needsCheck).map { it.wayId }.distinct().take(MAX_WAYS_PER_CHECK)

    /** Étiquettes d'accès, de la plus précise à la plus générale (la première présente décide). */
    private fun accessKeys(vehicle: PlanVehicle): List<String> = when (vehicle) {
        PlanVehicle.MOTORCYCLE -> listOf("motorcycle", "motor_vehicle", "vehicle", "access")
        PlanVehicle.CAR -> listOf("motor_car", "motor_vehicle", "vehicle", "access")
        PlanVehicle.BICYCLE -> listOf("bicycle", "vehicle", "access")
    }

    fun classify(tags: Map<String, String>, vehicle: PlanVehicle): AccessVerdict {
        for (key in accessKeys(vehicle)) {
            val value = tags[key]?.lowercase() ?: continue
            when (value) {
                in GRANTED -> return AccessVerdict.OK
                in DENIED -> return AccessVerdict.FORBIDDEN
                in RESTRICTED -> return AccessVerdict.RESTRICTED
            }
        }
        val highway = tags["highway"]
        return when (vehicle) {
            PlanVehicle.BICYCLE -> if (highway == "footway" || highway == "pedestrian" || highway == "steps") AccessVerdict.TO_VERIFY else AccessVerdict.OK
            else -> when {
                highway in FOOT_ONLY_FOR_MOTORS -> AccessVerdict.FORBIDDEN
                highway == "track" -> AccessVerdict.TO_VERIFY
                else -> AccessVerdict.OK
            }
        }
    }

    /**
     * Tronçons à signaler d'un itinéraire (ou d'un de ses morceaux). Un chemin dont les étiquettes n'ont pas pu être lues
     * (Overpass indisponible) est « à vérifier » : on ne conclut jamais « autorisé » faute de données.
     */
    fun segments(edges: List<AccessEdge>, tagsByWay: Map<Long, Map<String, String>>, vehicle: PlanVehicle): List<AccessSegment> {
        val result = ArrayList<AccessSegment>()
        var current: AccessSegment? = null
        for (edge in edges) {
            val verdict = if (!needsCheck(edge)) AccessVerdict.OK else tagsByWay[edge.wayId]?.let { classify(it, vehicle) } ?: AccessVerdict.TO_VERIFY
            if (verdict == AccessVerdict.OK) {
                current?.let(result::add)
                current = null
                continue
            }
            val open = current
            current = if (open != null && open.verdict == verdict) {
                open.copy(
                    wayIds = if (edge.wayId in open.wayIds) open.wayIds else open.wayIds + edge.wayId,
                    name = open.name ?: edge.name,
                    lengthMeters = open.lengthMeters + edge.lengthMeters,
                    endShapeIndex = edge.endShapeIndex,
                )
            } else {
                open?.let(result::add)
                AccessSegment(verdict, listOf(edge.wayId), edge.name, edge.lengthMeters, edge.beginShapeIndex, edge.endShapeIndex)
            }
        }
        current?.let(result::add)
        return result
    }
}
