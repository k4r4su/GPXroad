package com.olivier.gpxroad.android.nav

import android.content.Context
import android.location.Location
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.shared.ride.SpeedSmoother
import org.json.JSONObject
import com.olivier.gpxroad.android.net.ValhallaConfiguration
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.nav.GoToGuidance
import com.olivier.gpxroad.shared.nav.GoToProfile
import com.olivier.gpxroad.shared.nav.NavGuidanceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Destination d'« Aller à » : libellé, position, profil. */
data class NavDestination(val label: String, val coordinate: LatLon, val profile: GoToProfile)

/**
 * Guidage « Aller à » du Ride (partie Nav de `RideSessionManager` iOS), UN SEUL guidage à la fois :
 * - riche (profil Itinéraire + Valhalla configuré) : manœuvres, annonces vocales, recalcul hors
 *   itinéraire ([NavGuidanceTracker], règles partagées) ;
 * - simple (Piste, Mixte, ou sans Valhalla) : un chemin, la distance restante, une durée estimée.
 * Pendant un guidage, le Road Book et la reprise de trace se taisent ; la trace reste affichée.
 */
class NavSession(context: Context, private val routing: RoutingClient, private val overpass: OverpassClient) {
    private val scope = MainScope()
    private var job: Job? = null
    private val voice = VoiceAnnouncer(context)

    var destination by mutableStateOf<NavDestination?>(null)
        private set
    var tracker by mutableStateOf<NavGuidanceTracker?>(null)
        private set
    /** Change à chaque fix : la vue relit l'état du [tracker] (objet non observable). */
    var tick by mutableStateOf(0)
        private set
    var goTo by mutableStateOf<GoToGuidance?>(null)
        private set
    var goToRemainingMeters by mutableStateOf<Double?>(null)
        private set
    var isRequesting by mutableStateOf(false)
        private set
    var isRecomputing by mutableStateOf(false)
        private set
    /** Guidage simple de secours (itinéraire impossible) : ligne droite. */
    var failed by mutableStateOf(false)
        private set

    /**
     * Limitation de vitesse de la route (`SpeedLimitService` iOS) : Overpass `maxspeed` à 25 m,
     * au plus toutes les 20 s, seulement pendant un guidage détaillé ; `null` = inconnue.
     */
    var speedLimitKmh by mutableStateOf<Int?>(null)
        private set
    var isOverSpeedLimit by mutableStateOf(false)
        private set
    private var lastSpeedLimitLookup = 0L
    private val smoother = SpeedSmoother()

    val isActive: Boolean get() = destination != null

    fun start(target: NavDestination, origin: Location?, valhalla: ValhallaConfiguration?) {
        stop()
        destination = target
        val from = origin?.let { LatLon(it.latitude, it.longitude) } ?: run {
            failed = true
            goTo = GoToGuidance(listOf(target.coordinate), target.profile, target.coordinate, target.label)
            return
        }
        request(from, target, valhalla, recompute = false)
    }

    fun stop() {
        speedLimitKmh = null
        isOverSpeedLimit = false
        lastSpeedLimitLookup = 0
        job?.cancel()
        job = null
        destination = null
        tracker = null
        goTo = null
        goToRemainingMeters = null
        isRequesting = false
        isRecomputing = false
        failed = false
        voice.stop()
    }

    fun onLocation(fix: Location, voiceEnabled: Boolean, voiceVolume: Float, valhalla: ValhallaConfiguration?, overSpeedMarginKmh: Int = SPEED_MARGIN_DEFAULT_KMH) {
        val target = destination ?: return
        val position = LatLon(fix.latitude, fix.longitude)
        val smoothed = smoother.add(if (fix.hasSpeed()) fix.speed * 3.6 else 0.0, fix.time / 1000.0)
        tracker?.let { t ->
            updateSpeedLimit(position)
            isOverSpeedLimit = speedLimitKmh?.let { smoothed > it + overSpeedMarginKmh } ?: false
            val update = t.update(position, fix.time / 1000.0, voiceEnabled, isRecomputing || isRequesting)
            update.announcements.forEach { voice.say(it, voiceVolume) }
            if (update.shouldRecompute) request(position, target, valhalla, recompute = true)
            if (t.currentManeuver == null) stop() else tick++
            return
        }
        goTo?.let { g ->
            goToRemainingMeters = if (failed) geodesicDistanceMeters(position, target.coordinate) else g.remainingMeters(position)
            if (g.isArrived(position)) stop()
        }
    }

    private fun request(from: LatLon, target: NavDestination, valhalla: ValhallaConfiguration?, recompute: Boolean) {
        job?.cancel()
        if (recompute) isRecomputing = true else isRequesting = true
        job = scope.launch {
            val rich = target.profile == GoToProfile.ROUTE && valhalla != null
            if (rich) {
                val route = withContext(Dispatchers.IO) {
                    runCatching { routing.navRoute(from, target.coordinate, target.label, valhalla!!, Locale.getDefault().toLanguageTag()) }.getOrNull()
                }
                if (destination != target) return@launch
                if (route != null) {
                    val current = tracker
                    if (current != null) current.replaceRoute(route) else tracker = NavGuidanceTracker(route)
                    isRequesting = false
                    isRecomputing = false
                    tick++
                    return@launch
                }
                if (recompute) {
                    isRecomputing = false
                    return@launch
                }
            }
            // Guidage simple : routes (Itinéraire), vélo (Piste), ou routes puis piste pour la fin (Mixte).
            val points = withContext(Dispatchers.IO) {
                runCatching {
                    when (target.profile) {
                        GoToProfile.OFFROAD -> routing.route(from, target.coordinate, valhalla, offroad = true)
                        GoToProfile.ROUTE -> routing.route(from, target.coordinate, valhalla)
                        GoToProfile.MIXED -> {
                            val road = routing.route(from, target.coordinate, valhalla)
                            val end = road.last()
                            if (geodesicDistanceMeters(end, target.coordinate) > MIXED_TAIL_METERS) road + routing.route(end, target.coordinate, valhalla, offroad = true).drop(1) else road
                        }
                    }
                }.getOrNull()
            }
            if (destination != target) return@launch
            failed = points == null
            goTo = GoToGuidance(points ?: listOf(from, target.coordinate), if (points == null) GoToProfile.OFFROAD else target.profile, target.coordinate, target.label)
            goToRemainingMeters = goTo?.remainingMeters(from)
            isRequesting = false
            isRecomputing = false
        }
    }

    private fun updateSpeedLimit(position: LatLon) {
        val now = System.currentTimeMillis()
        if (now - lastSpeedLimitLookup < SPEED_LIMIT_INTERVAL_MILLIS) return
        lastSpeedLimitLookup = now
        val query = "[out:json][timeout:8];way(around:25,${position.latitude},${position.longitude})[highway][maxspeed];out tags 1;"
        scope.launch {
            val limit = withContext(Dispatchers.IO) {
                overpass.fetchOnce(query, 10_000) { data -> runCatching { Optional(parseMaxSpeed(data)) }.getOrNull() }
            }
            if (destination != null && limit != null) speedLimitKmh = limit.value
        }
    }

    /** Réponse lue (limite éventuellement inconnue), distincte d'un échec réseau (`null`). */
    private class Optional<T>(val value: T?)

    companion object {
        /**
         * `maxspeed` de la première route de la réponse Overpass, en km/h ; `null` si absente ou non
         * numérique (« FR:urban », « signals »…), comme l'iPhone. Lève une exception si ce n'est pas du JSON.
         */
        fun parseMaxSpeed(data: ByteArray): Int? =
            JSONObject(data.toString(Charsets.UTF_8)).getJSONArray("elements").optJSONObject(0)
                ?.optJSONObject("tags")?.optString("maxspeed")?.trim()?.toIntOrNull()

        const val SPEED_MARGIN_DEFAULT_KMH = 10
        val SPEED_MARGIN_OPTIONS = listOf(5, 10, 15)
        private const val MIXED_TAIL_METERS = 20.0
        private const val SPEED_LIMIT_INTERVAL_MILLIS = 20_000L
    }
}

/** Annonces vocales (`NavVoiceAnnouncer` iOS) : synthèse vocale du téléphone, langue du téléphone. */
private class VoiceAnnouncer(context: Context) {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    fun say(text: String, volume: Float) {
        if (!ready || text.isBlank()) return
        tts.language = Locale.getDefault()
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, text.hashCode().toString())
    }

    fun stop() {
        if (ready) tts.stop()
    }
}
