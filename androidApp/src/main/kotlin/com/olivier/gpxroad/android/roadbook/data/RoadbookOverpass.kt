package com.olivier.gpxroad.android.roadbook.data

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.BuiltUpArea
import com.olivier.gpxroad.shared.roadbook.LandmarkCandidate
import com.olivier.gpxroad.shared.roadbook.LandmarkCatalog
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.roadbook.LandmarkConstants
import com.olivier.gpxroad.shared.roadbook.LandmarkOrientation
import com.olivier.gpxroad.shared.roadbook.OsmMiniRoundabout
import com.olivier.gpxroad.shared.roadbook.OsmRoad
import com.olivier.gpxroad.shared.roadbook.Place
import com.olivier.gpxroad.shared.roadbook.PlaceKind
import com.olivier.gpxroad.shared.roadbook.RoadbookAnalyzer
import com.olivier.gpxroad.shared.roadbook.RoundaboutMapData
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Requêtes et décodage Overpass du Road Book — portage FIDÈLE de `RoadbookRoundaboutOverpass` et
 * `RoadbookLandmarkOverpassService` (iOS) : mêmes requêtes au caractère près, même découpage en
 * tronçons, mêmes règles de décodage. L'analyse (ronds-points, sélection des repères) est partagée.
 */
object RoadbookOverpass {
    const val REQUEST_TIMEOUT_SECONDS = 90
    val RETRY_DELAYS_SECONDS = listOf(5.0, 15.0, 30.0)
    private const val CHUNK_METERS = 8000.0
    private const val LANDMARK_SAMPLE_SPACING_METERS = 250.0
    private const val MAX_POLYLINE_POINTS = 600
    private const val ROUNDABOUT_SAMPLE_SPACING_METERS = 40.0
    private const val ROUNDABOUT_AROUND_METERS = 40

    /** Routes non carrossables : jamais la chaussée d'un repère « sur la route ». */
    private val NON_VEHICLE_HIGHWAYS = setOf("footway", "path", "cycleway", "pedestrian", "steps", "bridleway", "corridor", "elevator", "platform")

    /** Tronçons consécutifs d'environ 8 km (le point de jonction appartient aux deux). */
    fun chunks(points: List<LatLon>, chunkMeters: Double = CHUNK_METERS): List<List<LatLon>> {
        val cumulative = TrackGeometry.cumulativeDistances(points)
        if (points.size <= 1 || (cumulative.lastOrNull() ?: 0.0) <= 0) return emptyList()
        val chunks = mutableListOf<List<LatLon>>()
        var start = 0
        for (index in 1 until points.size) {
            if (cumulative[index] - cumulative[start] >= chunkMeters || index == points.size - 1) {
                chunks += points.subList(start, index + 1).toList()
                start = index
            }
        }
        return chunks
    }

    fun length(points: List<LatLon>): Double = TrackGeometry.cumulativeDistances(points).lastOrNull() ?: 0.0

    /** Polyligne `around:` échantillonnée et son pas, ou `null` si la trace est trop courte. */
    private fun polyline(points: List<LatLon>, minSpacing: Double): Pair<String, Double>? {
        val cumulative = TrackGeometry.cumulativeDistances(points)
        val total = cumulative.lastOrNull() ?: return null
        if (points.size <= 1 || total <= 0) return null
        val spacing = max(minSpacing, total / (MAX_POLYLINE_POINTS - 1))
        val sampleCount = ceil(total / spacing).toInt()
        val text = (0..sampleCount)
            .mapNotNull { TrackGeometry.interpolatedCoordinate(minOf(it * spacing, total), points, cumulative) }
            .joinToString(",") { String.format(Locale.US, "%.6f,%.6f", it.latitude, it.longitude) }
        return text to spacing
    }

    // MARK: Ronds-points

    fun roundaboutQuery(points: List<LatLon>): String? {
        val (polyline, spacing) = polyline(points, ROUNDABOUT_SAMPLE_SPACING_METERS) ?: return null
        val around = ceil(spacing / 2).toInt() + ROUNDABOUT_AROUND_METERS
        return """
            [out:json][timeout:$REQUEST_TIMEOUT_SECONDS];
            way["highway"]["junction"~"^(roundabout|circular)$"](around:$around,$polyline)->.r;
            .r out geom;
            node(w.r)->.rn;
            (way(bn.rn)["highway"]; - .r;)->.c;
            .c out geom;
            node["highway"="mini_roundabout"](around:$around,$polyline)->.m;
            .m out;
            way(bn.m)["highway"]->.mc;
            .mc out geom;
        """.trimIndent()
    }

    fun parseRoundabouts(data: ByteArray): RoundaboutMapData? {
        val elements = elements(data) ?: return null
        val roads = mutableListOf<OsmRoad>()
        val minis = mutableListOf<OsmMiniRoundabout>()
        val seen = mutableSetOf<Long>()
        for (element in elements) {
            val tags = tags(element)
            val type = element.optString("type")
            if (type == "node" && tags["highway"] == "mini_roundabout" && element.has("lat") && element.has("lon")) {
                minis += OsmMiniRoundabout(element.getLong("id"), LatLon(element.getDouble("lat"), element.getDouble("lon")), tags["direction"] == "clockwise")
                continue
            }
            val highway = tags["highway"] ?: continue
            val nodes = element.optJSONArray("nodes") ?: continue
            val geometry = element.optJSONArray("geometry") ?: continue
            if (type != "way" || nodes.length() != geometry.length() || nodes.length() <= 1) continue
            val id = element.getLong("id")
            if (!seen.add(id)) continue
            val coordinates = coordinates(geometry)
            // Nœud manquant dans la géométrie (hors de la zone renvoyée) : route inutilisable.
            if (coordinates.size != nodes.length()) continue
            roads += OsmRoad(
                id = id,
                nodeIds = (0 until nodes.length()).map { nodes.getLong(it) },
                geometry = coordinates,
                highway = highway,
                junction = tags["junction"],
                oneway = tags["oneway"],
                access = tags["access"] ?: tags["vehicle"],
                motorVehicle = tags["motor_vehicle"] ?: tags["motorcar"],
                name = tags["name"],
                ref = tags["ref"],
                destination = tags["destination"],
            )
        }
        return RoundaboutMapData(roads, minis)
    }

    // MARK: Repères

    /** Sélecteurs Overpass par catégorie — ceux de `RoadbookLandmarkCategory.definition` (iOS). */
    fun selectors(category: LandmarkCategory): List<String> = when (category) {
        LandmarkCategory.CITY_SIGN -> {
            val values = LandmarkCatalog.CITY_SIGN_VALUES.joinToString("|")
            listOf(
                "node[\"traffic_sign\"~\"$values\",i]", "node[\"traffic_sign:forward\"~\"$values\",i]",
                "node[\"traffic_sign:backward\"~\"$values\",i]", "node[\"highway\"=\"city_limit\"]",
                "node[\"city_limit\"~\"^(begin|both)$\"]",
            )
        }
        LandmarkCategory.STOP_SIGN -> listOf("node[\"highway\"=\"stop\"]")
        LandmarkCategory.GIVE_WAY_SIGN -> listOf("node[\"highway\"=\"give_way\"]")
        LandmarkCategory.TRAFFIC_SIGNALS -> listOf("node[\"highway\"=\"traffic_signals\"]")
        LandmarkCategory.LEVEL_CROSSING -> listOf("node[\"railway\"=\"level_crossing\"]")
        LandmarkCategory.SPEED_BUMP -> listOf("node[\"traffic_calming\"~\"^(bump|hump|table|cushion)$\"]")
        LandmarkCategory.BRIDGE -> listOf("way[\"highway\"][\"bridge\"~\"^(yes|viaduct)$\"]")
        LandmarkCategory.TUNNEL -> listOf("way[\"highway\"][\"tunnel\"=\"yes\"]")
        LandmarkCategory.CHURCH -> listOf("nwr[\"amenity\"=\"place_of_worship\"]", "nwr[\"building\"~\"^(church|chapel)$\"]", "nwr[\"man_made\"=\"bell_tower\"]")
        LandmarkCategory.TOWN_HALL -> listOf("nwr[\"amenity\"=\"townhall\"]")
        LandmarkCategory.WATER_TOWER -> listOf("nwr[\"man_made\"=\"water_tower\"]")
        LandmarkCategory.MILL -> listOf("nwr[\"man_made\"~\"^(windmill|watermill)$\"]")
        LandmarkCategory.WAYSIDE_CROSS -> listOf("nwr[\"historic\"~\"^(wayside_cross|wayside_shrine)$\"]")
        LandmarkCategory.CASTLE -> listOf("nwr[\"historic\"=\"castle\"]", "nwr[\"building\"=\"castle\"]")
        LandmarkCategory.FUEL -> listOf("nwr[\"amenity\"=\"fuel\"]")
        LandmarkCategory.CHARGING_STATION -> listOf("nwr[\"amenity\"=\"charging_station\"]")
        LandmarkCategory.PARKING -> listOf("nwr[\"amenity\"=\"parking\"]")
        LandmarkCategory.REST_AREA -> listOf("nwr[\"highway\"~\"^(rest_area|services)$\"]")
        LandmarkCategory.DRINKING_WATER -> listOf("node[\"amenity\"~\"^(drinking_water|water_point)$\"]")
        LandmarkCategory.RESTAURANT -> listOf("nwr[\"amenity\"=\"restaurant\"]")
        LandmarkCategory.CAFE -> listOf("nwr[\"amenity\"=\"cafe\"]")
        LandmarkCategory.BAKERY -> listOf("nwr[\"shop\"=\"bakery\"]")
        LandmarkCategory.SUPERMARKET -> listOf("nwr[\"shop\"=\"supermarket\"]")
        LandmarkCategory.PHARMACY -> listOf("nwr[\"amenity\"=\"pharmacy\"]")
        LandmarkCategory.HOTEL -> listOf("nwr[\"tourism\"~\"^(hotel|motel)$\"]")
        LandmarkCategory.CAMPSITE -> listOf("nwr[\"tourism\"=\"camp_site\"]")
        LandmarkCategory.TRAIN_STATION -> listOf("nwr[\"railway\"~\"^(station|halt)$\"]")
        LandmarkCategory.SCHOOL -> listOf("nwr[\"amenity\"=\"school\"]")
        LandmarkCategory.CEMETERY -> listOf("nwr[\"landuse\"=\"cemetery\"]", "nwr[\"amenity\"=\"grave_yard\"]")
        LandmarkCategory.MEMORIAL -> listOf("nwr[\"historic\"~\"^(memorial|monument)$\"]")
        LandmarkCategory.WIND_TURBINE -> listOf("nwr[\"generator:source\"=\"wind\"]")
        LandmarkCategory.ANTENNA -> listOf("nwr[\"man_made\"~\"^(mast|communications_tower)$\"]", "nwr[\"man_made\"=\"tower\"][\"tower:type\"=\"communication\"]")
        LandmarkCategory.LIGHTHOUSE -> listOf("nwr[\"man_made\"=\"lighthouse\"]")
        LandmarkCategory.TOWER -> listOf("nwr[\"man_made\"=\"tower\"]")
    }

    /**
     * Requête des repères d'un tronçon : UNE ligne par sélecteur des seules [categories] (ordre du
     * catalogue), routes porteuses si une catégorie posée sur la chaussée en a besoin, zones bâties et
     * localités pour le repli « Entrée de <localité> ».
     */
    fun landmarkQuery(points: List<LatLon>, categories: Set<LandmarkCategory>, includeCityEntryAreas: Boolean = LandmarkConstants.CITY_ENTRY_FALLBACK_ENABLED): String? {
        if (categories.isEmpty()) return null
        val (polyline, spacing) = polyline(points, LANDMARK_SAMPLE_SPACING_METERS) ?: return null
        val ordered = LandmarkCategory.entries.filter { it in categories }
        val statements = ordered.flatMap { category ->
            val radius = ceil(spacing + category.visibilityRadiusMeters).toInt()
            selectors(category).map { "  $it(around:$radius,$polyline);" }
        }
        val carriageways = if (ordered.any { it.requiresRoadAlignment || it.isDirectional }) {
            """
            (
              node.candidates["highway"];
              node.candidates["railway"="level_crossing"];
              node.candidates["traffic_calming"];
              node.candidates["traffic_sign"];
              node.candidates["traffic_sign:forward"];
              node.candidates["traffic_sign:backward"];
            )->.onroad;
            way(bn.onroad)["highway"];
            out geom;
            """.trimIndent()
        } else ""
        val areaRadius = ceil(spacing + 30).toInt()
        fun placeRadius(kinds: List<PlaceKind>) = ceil(spacing + (kinds.maxOfOrNull { LandmarkConstants.placeReachMeters(it) } ?: 0.0)).toInt()
        val cityEntryAreas = if (includeCityEntryAreas && LandmarkCategory.CITY_SIGN in categories) {
            """
            (
              way(around:$areaRadius,$polyline)["landuse"="residential"];
              relation(around:$areaRadius,$polyline)["landuse"="residential"];
              way(around:$areaRadius,$polyline)["place"~"^(village|town|city)$"];
              relation(around:$areaRadius,$polyline)["place"~"^(village|town|city)$"];
            );
            out geom;
            (
              node(around:${placeRadius(listOf(PlaceKind.VILLAGE, PlaceKind.SUBURB))},$polyline)["place"~"^(village|town|city|suburb)$"]["name"];
              node(around:${placeRadius(listOf(PlaceKind.CITY, PlaceKind.TOWN))},$polyline)["place"~"^(town|city)$"]["name"];
            );
            out body;
            """.trimIndent()
        } else ""
        return "[out:json][timeout:$REQUEST_TIMEOUT_SECONDS];\n(\n${statements.joinToString("\n")}\n)->.candidates;\n.candidates out tags center;\n$carriageways\n$cityEntryAreas"
    }

    /** Décode une réponse : repères du catalogue (sens des panneaux sur leur route porteuse), zones bâties, localités. */
    fun parseLandmarks(data: ByteArray): StoredLandmarkData? {
        val elements = elements(data) ?: return null
        val ways = elements.filter { it.optString("type") == "way" && it.has("geometry") && it.has("nodes") }
        val vehicleWays = ways.filter { way -> tags(way)["highway"]?.let { it !in NON_VEHICLE_HIGHWAYS } ?: false }

        val candidates = mutableListOf<StoredCandidate>()
        for (element in elements) {
            if (!element.has("tags")) continue
            val tags = tags(element)
            val coordinate = candidateCoordinate(element) ?: continue
            val (category, label) = LandmarkCatalog.classify(tags) ?: continue
            val id = if (element.has("id")) element.getLong("id") else null
            val type = element.optString("type")
            candidates += StoredCandidate(
                LandmarkCandidate(
                    category = category,
                    label = label,
                    coordinate = coordinate,
                    orientation = orientation(id, tags, vehicleWays),
                    roadAxes = if (type == "node" && id != null) vehicleWays.mapNotNull { bearing(id, it) } else null,
                ),
                osmId = id?.let { "$type/$it" },
            )
        }

        val areas = mutableListOf<StoredArea>()
        val places = mutableListOf<StoredPlace>()
        for (element in elements) {
            val tags = tags(element)
            val type = element.optString("type")
            val osmId = if (element.has("id")) "$type/${element.getLong("id")}" else null
            val placeKind = placeKind(tags["place"])
            val name = tags["name"]
            if (type == "node" && placeKind != null && name != null && element.has("lat") && element.has("lon")) {
                places += StoredPlace(Place(name, placeKind, LatLon(element.getDouble("lat"), element.getDouble("lon"))), osmId)
                continue
            }
            val isResidential = tags["landuse"] == "residential"
            val isPlaceArea = placeKind != null && placeKind != PlaceKind.SUBURB
            if (type == "node" || !(isResidential || isPlaceArea)) continue
            val rings = outerRings(element)
            if (rings.isEmpty()) continue
            areas += StoredArea(BuiltUpArea(if (isPlaceArea) name else null, rings), osmId)
        }
        return StoredLandmarkData(candidates, areas, places, emptySet())
    }

    private fun placeKind(value: String?): PlaceKind? = when (value) {
        "city" -> PlaceKind.CITY
        "town" -> PlaceKind.TOWN
        "village" -> PlaceKind.VILLAGE
        "suburb" -> PlaceKind.SUBURB
        else -> null
    }

    /** Contour(s) extérieur(s) : le chemin fermé, ou les membres `outer` d'une relation. */
    private fun outerRings(element: JSONObject): List<List<LatLon>> {
        if (element.optString("type") == "way") {
            val ring = coordinates(element.optJSONArray("geometry"))
            return if (ring.size >= 3) listOf(ring) else emptyList()
        }
        val members = element.optJSONArray("members") ?: return emptyList()
        return (0 until members.length()).map { members.getJSONObject(it) }
            .filter { it.optString("type") == "way" && (if (it.isNull("role")) "outer" else it.optString("role", "outer")) != "inner" }
            .map { coordinates(it.optJSONArray("geometry")) }
            .filter { it.size >= 3 }
    }

    private fun candidateCoordinate(element: JSONObject): LatLon? = when {
        element.has("lat") && element.has("lon") -> LatLon(element.getDouble("lat"), element.getDouble("lon"))
        element.has("center") -> element.getJSONObject("center").let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
        else -> null
    }

    /** Cap de la chaussée au nœud (segment qui en part, ou qui y arrive en bout). */
    private fun bearing(nodeId: Long, way: JSONObject): Double? {
        val nodes = way.optJSONArray("nodes") ?: return null
        val geometry = way.optJSONArray("geometry") ?: return null
        if (nodes.length() != geometry.length() || nodes.length() <= 1) return null
        val index = (0 until nodes.length()).firstOrNull { nodes.getLong(it) == nodeId } ?: return null
        val from = if (index < nodes.length() - 1) index else index - 1
        val a = point(geometry, from) ?: return null
        val b = point(geometry, from + 1) ?: return null
        return RoadbookAnalyzer.bearing(a, b)
    }

    /** `forward`/`backward` résolu sur la route porteuse ; cap ou point cardinal = cap de face. */
    private fun orientation(id: Long?, tags: Map<String, String>, ways: List<JSONObject>): LandmarkOrientation? {
        val forwardSign = tags["traffic_sign:forward"] != null
        val backwardSign = tags["traffic_sign:backward"] != null
        val relative = when {
            forwardSign != backwardSign -> if (forwardSign) "forward" else "backward"
            forwardSign && backwardSign -> null
            else -> tags["direction"] ?: tags["traffic_signals:direction"]
        }
        if (relative == "forward" || relative == "backward") {
            if (id == null) return null
            val wayBearing = ways.firstNotNullOfOrNull { bearing(id, it) } ?: return null
            return LandmarkOrientation.AppliesToTravelBearing(if (relative == "forward") wayBearing else wayBearing + 180)
        }
        val facing = tags["direction"]?.let(::compassBearing) ?: return null
        return LandmarkOrientation.Faces(facing)
    }

    private val CARDINALS = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5, "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
        "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5, "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )

    private fun compassBearing(value: String): Double? = value.trim().toDoubleOrNull() ?: CARDINALS[value.uppercase(Locale.ROOT)]

    // MARK: JSON

    private fun elements(data: ByteArray): List<JSONObject>? = runCatching {
        val array = JSONObject(data.toString(Charsets.UTF_8)).getJSONArray("elements")
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrNull()

    private fun tags(element: JSONObject): Map<String, String> {
        val tags = element.optJSONObject("tags") ?: return emptyMap()
        return tags.keys().asSequence().associateWith { tags.optString(it) }
    }

    private fun point(geometry: JSONArray, index: Int): LatLon? =
        geometry.optJSONObject(index)?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }

    private fun coordinates(geometry: JSONArray?): List<LatLon> =
        if (geometry == null) emptyList() else (0 until geometry.length()).mapNotNull { point(geometry, it) }
}
