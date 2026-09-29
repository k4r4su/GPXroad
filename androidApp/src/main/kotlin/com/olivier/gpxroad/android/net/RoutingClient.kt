package com.olivier.gpxroad.android.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.MapMatchResult
import com.olivier.gpxroad.shared.roadbook.ValhallaLeg
import com.olivier.gpxroad.shared.roadbook.ValhallaManeuverRecord
import com.olivier.gpxroad.shared.roadbook.ValhallaMapMatching
import org.json.JSONArray
import org.json.JSONObject

/** Service de routage qui a répondu en dernier (indicateur des Réglages, comme l'iPhone). */
enum class RoutingProvider { VALHALLA, OSRM }

/**
 * Valhalla (portage de `ValhallaRoutingService`/`ValhallaMapMatchingService` iOS) et OSRM public en
 * secours du seul chemin de retour. Appels bloquants : hors du fil principal.
 */
class RoutingClient {
    var lastSuccess by mutableStateOf<Pair<RoutingProvider, Long>?>(null)
        private set

    /** Recale la trace ENTIÈRE (déjà dans son sens de parcours) sur le réseau routier. */
    fun mapMatch(points: List<LatLon>, configuration: ValhallaConfiguration): MapMatchResult {
        if (points.size < 2) return MapMatchResult(emptyList(), emptyList())
        val body = JSONObject()
            .put("shape", JSONArray(ValhallaMapMatching.downsampled(points).map { JSONObject().put("lat", it.latitude).put("lon", it.longitude) }))
            .put("costing", "auto")
            .put("shape_match", "map_snap")
            .put("costing_options", autoCosting())
        val data = post(configuration, "trace_route", body, MAP_MATCHING_TIMEOUT_MILLIS)
        val legs = JSONObject(data.toString(Charsets.UTF_8)).getJSONObject("trip").getJSONArray("legs")
        val decoded = (0 until legs.length()).map { index ->
            val leg = legs.getJSONObject(index)
            val maneuvers = leg.optJSONArray("maneuvers") ?: JSONArray()
            ValhallaLeg(
                maneuvers = (0 until maneuvers.length()).map { maneuver(maneuvers.getJSONObject(it)) },
                shape = ValhallaMapMatching.decodePolyline6(leg.getString("shape")),
            )
        }
        lastSuccess = RoutingProvider.VALHALLA to System.currentTimeMillis()
        return MapMatchResult(ValhallaMapMatching.matchedManeuvers(decoded), decoded.map { it.shape })
    }

    /**
     * Itinéraire routier (costing « auto », autoroutes/péages évités) : Valhalla s'il est configuré,
     * sinon ou en cas d'échec OSRM public — le chemin de retour n'est jamais bloqué par Valhalla.
     */
    fun route(from: LatLon, to: LatLon, valhalla: ValhallaConfiguration?): List<LatLon> {
        if (valhalla != null) {
            runCatching { valhallaRoute(from, to, valhalla) }.getOrNull()?.takeIf { it.size > 1 }?.let {
                lastSuccess = RoutingProvider.VALHALLA to System.currentTimeMillis()
                return it
            }
        }
        val url = "$OSRM_BASE_URL/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson"
        val json = JSONObject(Http.request(url, timeoutMillis = OSRM_TIMEOUT_MILLIS).toString(Charsets.UTF_8))
        val coordinates = json.getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        val route = (0 until coordinates.length()).map { coordinates.getJSONArray(it).let { c -> LatLon(c.getDouble(1), c.getDouble(0)) } }
        if (route.size < 2) throw HttpException("aucun itinéraire")
        lastSuccess = RoutingProvider.OSRM to System.currentTimeMillis()
        return route
    }

    /** Test de connexion (Réglages) : `/status`, adresse et identifiants tels que saisis. */
    fun status(configuration: ValhallaConfiguration): String {
        val data = Http.request(endpoint(configuration.endpoint, "status"), authorization = authorization(configuration), timeoutMillis = REQUEST_TIMEOUT_MILLIS)
        return runCatching { JSONObject(data.toString(Charsets.UTF_8)).optString("version").takeIf { it.isNotEmpty() } }.getOrNull() ?: "OK"
    }

    private fun valhallaRoute(from: LatLon, to: LatLon, configuration: ValhallaConfiguration): List<LatLon> {
        val body = JSONObject()
            .put("locations", JSONArray().put(JSONObject().put("lat", from.latitude).put("lon", from.longitude)).put(JSONObject().put("lat", to.latitude).put("lon", to.longitude)))
            .put("costing", "auto")
            .put("units", "kilometers")
            .put("costing_options", autoCosting())
        val legs = JSONObject(post(configuration, "route", body, REQUEST_TIMEOUT_MILLIS).toString(Charsets.UTF_8)).getJSONObject("trip").getJSONArray("legs")
        return (0 until legs.length()).flatMap { ValhallaMapMatching.decodePolyline6(legs.getJSONObject(it).getString("shape")) }
    }

    private fun post(configuration: ValhallaConfiguration, path: String, body: JSONObject, timeoutMillis: Int): ByteArray {
        val url = endpoint(configuration.endpoint, path)
        if (!url.startsWith("https://")) throw HttpException("HTTPS requis")
        return Http.request(url, "POST", body.toString().toByteArray(Charsets.UTF_8), "application/json", authorization(configuration), timeoutMillis)
    }

    private fun maneuver(json: JSONObject): ValhallaManeuverRecord {
        val names = json.optJSONArray("street_names")
        return ValhallaManeuverRecord(
            type = json.optInt("type", 0),
            beginShapeIndex = json.getInt("begin_shape_index"),
            roundaboutExitCount = if (json.has("roundabout_exit_count")) json.getInt("roundabout_exit_count") else null,
            streetNames = names?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
        )
    }

    private fun autoCosting() = JSONObject().put(
        "auto",
        JSONObject().put("use_highways", ValhallaMapMatching.AUTO_COSTING_USE_HIGHWAYS).put("use_tolls", ValhallaMapMatching.AUTO_COSTING_USE_TOLLS),
    )

    private fun authorization(configuration: ValhallaConfiguration) = Http.basicAuthorization(configuration.username, configuration.password)

    private fun endpoint(base: String, path: String) = base.trim().trimEnd('/') + "/" + path

    private companion object {
        const val OSRM_BASE_URL = "https://router.project-osrm.org"
        const val REQUEST_TIMEOUT_MILLIS = 20_000
        const val MAP_MATCHING_TIMEOUT_MILLIS = 45_000
        const val OSRM_TIMEOUT_MILLIS = 12_000
    }
}
