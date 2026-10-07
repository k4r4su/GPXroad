package com.olivier.gpxroad.android.plan

import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.PlannedRoute
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ValhallaConfiguration
import com.olivier.gpxroad.shared.roadbook.ValhallaMapMatching
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.plan.AccessEdge
import com.olivier.gpxroad.shared.plan.AccessVerdict
import com.olivier.gpxroad.shared.plan.PlanVehicle
import com.olivier.gpxroad.shared.plan.TrackAccess
import org.json.JSONArray
import org.json.JSONObject

/** Portion d'itinéraire dont l'accès est incertain ou interdit d'après OpenStreetMap (voir `shared/plan/TrackAccess`). */
data class FlaggedSegment(
    val verdict: AccessVerdict,
    val wayIds: List<Long>,
    val name: String?,
    val lengthMeters: Double,
    val coordinates: List<LatLon>,
) {
    /** Point du milieu : c'est là qu'un signalement « interdit » est posé. */
    val midpoint: LatLon? get() = coordinates.getOrNull(coordinates.size / 2)
}

/**
 * Contrôle d'accès des pistes d'un itinéraire créé (`TrackAccessChecker` iOS) : Valhalla `trace_attributes` donne les chemins
 * OSM suivis, Overpass leurs étiquettes d'accès, `TrackAccess` (commun) classe. `null` = vérification impossible (réseau,
 * serveur) : l'appelant le dit, il ne conclut jamais « tout est autorisé ». Appels bloquants : hors du fil principal.
 */
class TrackAccessChecker(private val routing: RoutingClient, private val overpass: OverpassClient) {
    fun check(route: PlannedRoute, vehicle: PlanVehicle, configuration: ValhallaConfiguration): List<FlaggedSegment>? {
        val legs = route.legShapes.map { encoded ->
            runCatching { parseAttributes(routing.traceAttributes(encoded, vehicle, configuration)) }.getOrNull() ?: return null
        }
        val ids = TrackAccess.checkedWayIds(legs.flatMap { it.first })
        val tags = if (ids.isEmpty()) emptyMap() else {
            val query = "[out:json][timeout:25];way(id:${ids.joinToString(",")});out tags;"
            overpass.fetchOnce(query, 30_000) { parseWayTags(it) } ?: return null
        }
        return legs.flatMap { (edges, shape) ->
            TrackAccess.segments(edges, tags, vehicle).mapNotNull { segment ->
                val begin = segment.beginShapeIndex.coerceAtLeast(0)
                val end = segment.endShapeIndex.coerceAtMost(shape.size - 1)
                if (begin >= end) null else FlaggedSegment(segment.verdict, segment.wayIds, segment.name, segment.lengthMeters, shape.subList(begin, end + 1))
            }
        }
    }

    companion object {
        /** Réponse `/trace_attributes` : arêtes (un chemin OSM chacune) et forme recalée dans laquelle elles sont indexées. */
        fun parseAttributes(data: ByteArray): Pair<List<AccessEdge>, List<LatLon>> {
            val root = JSONObject(data.toString(Charsets.UTF_8))
            val edges: JSONArray = root.getJSONArray("edges")
            val parsed = (0 until edges.length()).mapNotNull { index ->
                val edge = edges.getJSONObject(index)
                if (!edge.has("way_id") || !edge.has("begin_shape_index") || !edge.has("end_shape_index")) return@mapNotNull null
                val names = edge.optJSONArray("names")
                AccessEdge(
                    wayId = edge.getLong("way_id"),
                    use = edge.optString("use", "road"),
                    unpaved = edge.optBoolean("unpaved", false),
                    lengthMeters = edge.optDouble("length", 0.0) * 1000,   // Valhalla : kilomètres
                    beginShapeIndex = edge.getInt("begin_shape_index"),
                    endShapeIndex = edge.getInt("end_shape_index"),
                    name = names?.takeIf { it.length() > 0 }?.getString(0),
                )
            }
            return parsed to ValhallaMapMatching.decodePolyline6(root.getString("shape"))
        }

        /** Étiquettes OSM par identifiant de chemin (réponse Overpass `out tags`). */
        fun parseWayTags(data: ByteArray): Map<Long, Map<String, String>>? = runCatching {
            val elements = JSONObject(data.toString(Charsets.UTF_8)).getJSONArray("elements")
            (0 until elements.length()).mapNotNull { index ->
                val element = elements.getJSONObject(index)
                val tags = element.optJSONObject("tags") ?: return@mapNotNull null
                element.getLong("id") to tags.keys().asSequence().associateWith { tags.getString(it) }
            }.toMap()
        }.getOrNull()
    }
}
