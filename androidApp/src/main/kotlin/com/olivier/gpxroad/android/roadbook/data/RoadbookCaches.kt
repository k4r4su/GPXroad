package com.olivier.gpxroad.android.roadbook.data

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.BuiltUpArea
import com.olivier.gpxroad.shared.roadbook.CoveredRange
import com.olivier.gpxroad.shared.roadbook.LandmarkCandidate
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.roadbook.LandmarkData
import com.olivier.gpxroad.shared.roadbook.LandmarkOrientation
import com.olivier.gpxroad.shared.roadbook.MapMatchedManeuver
import com.olivier.gpxroad.shared.roadbook.OsmMiniRoundabout
import com.olivier.gpxroad.shared.roadbook.OsmRoad
import com.olivier.gpxroad.shared.roadbook.Place
import com.olivier.gpxroad.shared.roadbook.PlaceKind
import com.olivier.gpxroad.shared.roadbook.RoundaboutMapData
import com.olivier.gpxroad.shared.roadbook.ValhallaManeuverType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Candidat repère avec son identifiant OSM : un élément renvoyé par deux tronçons n'est gardé qu'une fois. */
data class StoredCandidate(val candidate: LandmarkCandidate, val osmId: String?)
data class StoredArea(val area: BuiltUpArea, val osmId: String?)
data class StoredPlace(val place: Place, val osmId: String?)

/** Repères connus d'une trace (géométrie seule, valable dans les deux sens) et catégories déjà téléchargées. */
data class StoredLandmarkData(
    val candidates: List<StoredCandidate>,
    val areas: List<StoredArea>,
    val places: List<StoredPlace>,
    val fetchedCategories: Set<LandmarkCategory>,
) {
    val shared: LandmarkData
        get() = LandmarkData(candidates.map { it.candidate }, areas.map { it.area }, places.map { it.place })

    val elementCount: Int get() = candidates.size + areas.size + places.size

    fun adding(other: StoredLandmarkData, markingFetched: Set<LandmarkCategory>): StoredLandmarkData {
        val known = candidates.mapNotNull { it.osmId }.toMutableSet()
        val merged = candidates + other.candidates.filter { it.osmId == null || known.add(it.osmId) }
        return StoredLandmarkData(
            merged,
            deduplicated(areas + other.areas) { it.osmId },
            deduplicated(places + other.places) { it.osmId },
            fetchedCategories + markingFetched,
        )
    }

    fun filtered(categories: Set<LandmarkCategory>): StoredLandmarkData {
        val withCityEntries = LandmarkCategory.CITY_SIGN in categories
        return StoredLandmarkData(
            candidates.filter { it.candidate.category in categories },
            if (withCityEntries) areas else emptyList(),
            if (withCityEntries) places else emptyList(),
            emptySet(),
        )
    }

    companion object {
        val EMPTY = StoredLandmarkData(emptyList(), emptyList(), emptyList(), emptySet())

        private fun <T> deduplicated(items: List<T>, id: (T) -> String?): List<T> {
            val seen = mutableSetOf<String>()
            return items.filter { item -> id(item)?.let { seen.add(it) } ?: true }
        }
    }
}

/** Manœuvres Valhalla et portions recalées d'un PARCOURS (trace + sens). */
data class MapMatchEntry(val maneuvers: List<MapMatchedManeuver>, val coverage: List<CoveredRange>)

/**
 * Caches disque du Road Book (`files/Roadbook*Cache/`), comme l'iPhone : map matching par parcours
 * (`traversalKey` : les types gauche/droite dépendent du sens), ronds-points et repères par trace
 * (géométrie seule). Un fichier illisible est ignoré : la donnée est simplement retéléchargée.
 */
class RoadbookCaches(filesDir: File) {
    private val mapMatchDir = File(filesDir, "RoadbookMapMatchCache").apply { mkdirs() }
    private val roundaboutDir = File(filesDir, "RoadbookRoundaboutCache").apply { mkdirs() }
    private val landmarkDir = File(filesDir, "RoadbookLandmarkCache").apply { mkdirs() }

    fun mapMatch(traversalKey: String): MapMatchEntry? = read(File(mapMatchDir, fileName(traversalKey))) { json ->
        MapMatchEntry(
            maneuvers = json.getJSONArray("maneuvers").objects().map { m ->
                MapMatchedManeuver(
                    coordinate = LatLon(m.getDouble("lat"), m.getDouble("lon")),
                    type = ValhallaManeuverType.fromRawValue(m.getInt("type")),
                    roundaboutExitCount = m.optIntOrNull("exitCount"),
                    routeProgressFraction = m.optDoubleOrNull("progress"),
                    streetNamesBefore = m.getJSONArray("before").strings(),
                    streetNamesAfter = m.getJSONArray("after").strings(),
                )
            },
            coverage = json.getJSONArray("coverage").objects().map { CoveredRange(it.getDouble("start"), it.getDouble("end")) },
        )
    }

    fun storeMapMatch(traversalKey: String, entry: MapMatchEntry) {
        val json = JSONObject()
            .put("maneuvers", JSONArray(entry.maneuvers.map { m ->
                JSONObject()
                    .put("lat", m.coordinate.latitude).put("lon", m.coordinate.longitude)
                    .put("type", m.type.rawValue)
                    .putOpt("exitCount", m.roundaboutExitCount)
                    .putOpt("progress", m.routeProgressFraction)
                    .put("before", JSONArray(m.streetNamesBefore))
                    .put("after", JSONArray(m.streetNamesAfter))
            }))
            .put("coverage", JSONArray(entry.coverage.map { JSONObject().put("start", it.startMeters).put("end", it.endMeters) }))
        write(File(mapMatchDir, fileName(traversalKey)), json)
    }

    fun roundabouts(trackId: String): RoundaboutMapData? = read(File(roundaboutDir, fileName(trackId))) { json ->
        RoundaboutMapData(
            roads = json.getJSONArray("roads").objects().map { r ->
                OsmRoad(
                    id = r.getLong("id"),
                    nodeIds = r.getJSONArray("nodes").let { a -> (0 until a.length()).map { a.getLong(it) } },
                    geometry = r.getJSONArray("geometry").latLons(),
                    highway = r.getString("highway"),
                    junction = r.optStringOrNull("junction"),
                    oneway = r.optStringOrNull("oneway"),
                    access = r.optStringOrNull("access"),
                    motorVehicle = r.optStringOrNull("motorVehicle"),
                    name = r.optStringOrNull("name"),
                    ref = r.optStringOrNull("ref"),
                    destination = r.optStringOrNull("destination"),
                )
            },
            miniRoundabouts = json.getJSONArray("minis").objects().map {
                OsmMiniRoundabout(it.getLong("id"), LatLon(it.getDouble("lat"), it.getDouble("lon")), it.getBoolean("clockwise"))
            },
        )
    }

    fun storeRoundabouts(trackId: String, data: RoundaboutMapData) {
        val json = JSONObject()
            .put("roads", JSONArray(data.roads.map { r ->
                JSONObject()
                    .put("id", r.id).put("nodes", JSONArray(r.nodeIds)).put("geometry", r.geometry.toJson()).put("highway", r.highway)
                    .putOpt("junction", r.junction).putOpt("oneway", r.oneway).putOpt("access", r.access)
                    .putOpt("motorVehicle", r.motorVehicle).putOpt("name", r.name).putOpt("ref", r.ref).putOpt("destination", r.destination)
            }))
            .put("minis", JSONArray(data.miniRoundabouts.map {
                JSONObject().put("id", it.nodeId).put("lat", it.coordinate.latitude).put("lon", it.coordinate.longitude).put("clockwise", it.clockwise)
            }))
        write(File(roundaboutDir, fileName(trackId)), json)
    }

    fun landmarks(trackId: String): StoredLandmarkData? = read(File(landmarkDir, fileName(trackId))) { json ->
        StoredLandmarkData(
            candidates = json.getJSONArray("candidates").objects().mapNotNull { c ->
                val category = LandmarkCategory.fromKey(c.getString("category")) ?: return@mapNotNull null
                StoredCandidate(
                    LandmarkCandidate(
                        category = category,
                        label = c.getString("label"),
                        coordinate = LatLon(c.getDouble("lat"), c.getDouble("lon")),
                        orientation = when {
                            c.has("appliesTo") -> LandmarkOrientation.AppliesToTravelBearing(c.getDouble("appliesTo"))
                            c.has("faces") -> LandmarkOrientation.Faces(c.getDouble("faces"))
                            else -> null
                        },
                        roadAxes = c.optJSONArray("axes")?.let { a -> (0 until a.length()).map { a.getDouble(it) } },
                    ),
                    c.optStringOrNull("osm"),
                )
            },
            areas = json.getJSONArray("areas").objects().map { a ->
                StoredArea(BuiltUpArea(a.optStringOrNull("name"), a.getJSONArray("rings").let { r -> (0 until r.length()).map { r.getJSONArray(it).latLons() } }), a.optStringOrNull("osm"))
            },
            places = json.getJSONArray("places").objects().mapNotNull { p ->
                val kind = runCatching { PlaceKind.valueOf(p.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                StoredPlace(Place(p.getString("name"), kind, LatLon(p.getDouble("lat"), p.getDouble("lon"))), p.optStringOrNull("osm"))
            },
            fetchedCategories = json.getJSONArray("fetched").strings().mapNotNull { LandmarkCategory.fromKey(it) }.toSet(),
        )
    }

    fun storeLandmarks(trackId: String, data: StoredLandmarkData) {
        val json = JSONObject()
            .put("candidates", JSONArray(data.candidates.map { stored ->
                val c = stored.candidate
                JSONObject()
                    .put("category", c.category.key).put("label", c.label)
                    .put("lat", c.coordinate.latitude).put("lon", c.coordinate.longitude)
                    .putOpt("appliesTo", (c.orientation as? LandmarkOrientation.AppliesToTravelBearing)?.bearing)
                    .putOpt("faces", (c.orientation as? LandmarkOrientation.Faces)?.bearing)
                    .putOpt("axes", c.roadAxes?.let { JSONArray(it) })
                    .putOpt("osm", stored.osmId)
            }))
            .put("areas", JSONArray(data.areas.map { a ->
                JSONObject().putOpt("name", a.area.name).put("rings", JSONArray(a.area.rings.map { it.toJson() })).putOpt("osm", a.osmId)
            }))
            .put("places", JSONArray(data.places.map { p ->
                JSONObject().put("name", p.place.name).put("kind", p.place.kind.name)
                    .put("lat", p.place.coordinate.latitude).put("lon", p.place.coordinate.longitude).putOpt("osm", p.osmId)
            }))
            .put("fetched", JSONArray(data.fetchedCategories.map { it.key }))
        write(File(landmarkDir, fileName(trackId)), json)
    }

    private fun <T> read(file: File, decode: (JSONObject) -> T): T? =
        if (!file.exists()) null else runCatching { decode(JSONObject(file.readText())) }.getOrNull()

    /** Écriture atomique : jamais un cache à moitié écrit. */
    private fun write(file: File, json: JSONObject) {
        runCatching {
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.toString())
            temporary.renameTo(file)
        }
    }

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
private fun JSONArray.latLons(): List<LatLon> = (0 until length()).map { getJSONArray(it).let { p -> LatLon(p.getDouble(0), p.getDouble(1)) } }
private fun List<LatLon>.toJson(): JSONArray = JSONArray(map { JSONArray().put(it.latitude).put(it.longitude) })
private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
