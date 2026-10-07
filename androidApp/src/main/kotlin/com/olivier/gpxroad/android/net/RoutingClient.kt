package com.olivier.gpxroad.android.net

import com.olivier.gpxroad.shared.plan.PlanOptions
import com.olivier.gpxroad.shared.plan.RoutePlanner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.nav.NavManeuver
import com.olivier.gpxroad.shared.nav.NavRoute
import com.olivier.gpxroad.shared.roadbook.ValhallaManeuverType
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
    fun route(from: LatLon, to: LatLon, valhalla: ValhallaConfiguration?, offroad: Boolean = false): List<LatLon> {
        if (valhalla != null) {
            runCatching { valhallaRoute(from, to, valhalla, offroad) }.getOrNull()?.takeIf { it.size > 1 }?.let {
                lastSuccess = RoutingProvider.VALHALLA to System.currentTimeMillis()
                return it
            }
        }
        val profile = if (offroad) "cycling" else "driving"
        val url = "$OSRM_BASE_URL/route/v1/$profile/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson"
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

    /**
     * Guidage « Aller à » riche (`ValhallaNavigationService` iOS) : `/route` avec ses manœuvres, textes
     * rédigés par Valhalla dans [language] (BCP 47). Autoroutes permises : c'est un GPS classique.
     * Pas de repli OSRM (pas de manœuvres détaillées) : l'appelant bascule sur le guidage simple.
     */
    fun navRoute(from: LatLon, to: LatLon, label: String, configuration: ValhallaConfiguration, language: String): NavRoute {
        val body = JSONObject()
            .put("locations", locations(from, to))
            .put("costing", "auto")
            .put("units", "kilometers")
            .put("language", language)
        val route = parseNavRoute(post(configuration, "route", body, REQUEST_TIMEOUT_MILLIS), label)
        lastSuccess = RoutingProvider.VALHALLA to System.currentTimeMillis()
        return route
    }

    private fun locations(from: LatLon, to: LatLon) =
        JSONArray().put(JSONObject().put("lat", from.latitude).put("lon", from.longitude)).put(JSONObject().put("lat", to.latitude).put("lon", to.longitude))

    /** Costing « auto » (autoroutes/péages évités, chemin de retour) ou « bicycle » (profil Piste). */
    private fun valhallaRoute(from: LatLon, to: LatLon, configuration: ValhallaConfiguration, offroad: Boolean): List<LatLon> {
        val body = JSONObject()
            .put("locations", locations(from, to))
            .put("costing", if (offroad) "bicycle" else "auto")
            .put("units", "kilometers")
        if (!offroad) body.put("costing_options", autoCosting())
        val legs = JSONObject(post(configuration, "route", body, REQUEST_TIMEOUT_MILLIS).toString(Charsets.UTF_8)).getJSONObject("trip").getJSONArray("legs")
        return (0 until legs.length()).flatMap { ValhallaMapMatching.decodePolyline6(legs.getJSONObject(it).getString("shape")) }
    }

    /**
     * Itinéraire créé à la volée (06/10) : `/route` pour tous les points posés en un seul appel, corps construit par la règle
     * commune ([RoutePlanner], identique sur iPhone). Valhalla recale chaque tronçon sur les routes existantes.
     */
    fun plan(points: List<LatLon>, options: PlanOptions, configuration: ValhallaConfiguration): PlannedRoute {
        val body = JSONObject(RoutePlanner.requestBody(points, options))
        val planned = parsePlannedRoute(post(configuration, "route", body, REQUEST_TIMEOUT_MILLIS))
        lastSuccess = RoutingProvider.VALHALLA to System.currentTimeMillis()
        return planned
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


    companion object {
        /** Réponse `/route` de Valhalla → tracé (tronçons mis bout à bout), longueur et durée (`PlannedRoute`). */
        fun parsePlannedRoute(data: ByteArray): PlannedRoute {
            val trip = JSONObject(data.toString(Charsets.UTF_8)).getJSONObject("trip")
            val legs = trip.getJSONArray("legs")
            val merged = RoutePlanner.mergeLegs((0 until legs.length()).map { ValhallaMapMatching.decodePolyline6(legs.getJSONObject(it).getString("shape")) })
            if (merged.size < 2) throw HttpException("aucun itinéraire")
            val summary = trip.optJSONObject("summary")
            val kilometers = summary?.optDouble("length", Double.NaN)?.takeIf { !it.isNaN() } ?: (RoutePlanner.lengthMeters(merged) / 1000)
            return PlannedRoute(merged, kilometers * 1000, summary?.optDouble("time", 0.0) ?: 0.0)
        }

        /** Réponse `/route` de Valhalla → itinéraire et manœuvres (textes tels que Valhalla les rédige). */
        fun parseNavRoute(data: ByteArray, label: String): NavRoute {
            val trip = JSONObject(data.toString(Charsets.UTF_8)).getJSONObject("trip")
            val leg = trip.getJSONArray("legs").getJSONObject(0)
            val points = ValhallaMapMatching.decodePolyline6(leg.getString("shape"))
            if (points.size < 2) throw HttpException("aucun itinéraire")
            val maneuvers = leg.getJSONArray("maneuvers")
            val summary = trip.getJSONObject("summary")
            return NavRoute(
                points = points,
                maneuvers = (0 until maneuvers.length()).map { index ->
                    val m = maneuvers.getJSONObject(index)
                    val names = m.optJSONArray("street_names")
                    NavManeuver(
                        type = ValhallaManeuverType.fromRawValue(m.optInt("type", 0)),
                        instruction = m.optString("instruction"),
                        verbalAlert = m.optString("verbal_transition_alert_instruction").ifEmpty { null },
                        verbalPre = m.optString("verbal_pre_transition_instruction").ifEmpty { null },
                        verbalPost = m.optString("verbal_post_transition_instruction").ifEmpty { null },
                        streetNames = names?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
                        beginShapeIndex = m.optInt("begin_shape_index", 0),
                        isMultiCue = m.optBoolean("verbal_multi_cue", false),
                        roundaboutExitCount = if (m.has("roundabout_exit_count")) m.getInt("roundabout_exit_count") else null,
                    )
                },
                totalDistanceMeters = summary.optDouble("length", 0.0) * 1000,
                totalDurationSeconds = summary.optDouble("time", 0.0),
                destinationLabel = label,
            )
        }
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
        private const val REQUEST_TIMEOUT_MILLIS = 20_000
        private const val MAP_MATCHING_TIMEOUT_MILLIS = 45_000
        private const val OSRM_TIMEOUT_MILLIS = 12_000
    }
}

/** Itinéraire calculé pour la création d'une trace à la volée : tracé, longueur et durée estimées par Valhalla. */
data class PlannedRoute(val points: List<LatLon>, val distanceMeters: Double, val durationSeconds: Double)
