package com.olivier.gpxroad.android.ride

import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ValhallaConfiguration
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.ride.BlockedPathDetector
import com.olivier.gpxroad.shared.ride.DetourConstants
import com.olivier.gpxroad.shared.ride.DetourPlanner
import com.olivier.gpxroad.shared.ride.DetourTracker
import com.olivier.gpxroad.shared.ride.RideProgress
import com.olivier.gpxroad.shared.ride.RideStats
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * « Reprendre la trace ici » (`ResumeGuidance` iOS) : point touché sur la trace, d'abord en aperçu
 * (vol d'oiseau, itinéraire en cours de calcul) puis confirmé ; fini à 30 m du point, ou dès le
 * retour sur la trace.
 */
data class ManualResume(
    val target: LatLon,
    val targetCumulativeMeters: Double,
    val route: List<LatLon> = emptyList(),
    val routeLengthMeters: Double? = null,
    val isActive: Boolean = false,
    val isRequesting: Boolean = false,
    val routingFailed: Boolean = false,
)

/** Détour « Chemin bloqué » : par les routes, par les pistes, ou en direct (sans réseau). */
enum class DetourMode { ROAD, TRAIL, DIRECT }

data class Detour(val mode: DetourMode, val route: List<LatLon>, val target: LatLon)

/**
 * Session du Ride (partie mesures et reprise manuelle de `RideSessionManager` iOS). Vit le temps de
 * l'activité : changer d'onglet ne remet pas les mesures à zéro ; une nouvelle trace, si.
 */
class RideSession(private val routing: RoutingClient) {
    private val stats = RideStats()
    private var statsTrackKey: String? = null
    private val scope = MainScope()
    private var resumeJob: Job? = null

    var maxSpeedKmh by mutableStateOf(0.0)
        private set
    var averageSpeedKmh by mutableStateOf(0.0)
        private set
    var progress by mutableStateOf<RideProgress?>(null)
        private set
    var manualResume by mutableStateOf<ManualResume?>(null)
        private set
    var detour by mutableStateOf<Detour?>(null)
        private set
    var isRequestingDetour by mutableStateOf(false)
        private set
    /** « Portion bloquée ? » (hors trace depuis 30 s ou sur 200 m), ou bouton « Bloqué ». */
    var isBlockedBannerVisible by mutableStateOf(false)
        private set
    private var detourTracker: DetourTracker? = null
    private var detourJob: Job? = null
    private val blockedDetector = BlockedPathDetector()

    fun onLocation(fix: Location, trackKey: String?, trackLengthMeters: Double?, cumulativeMeters: Double?) {
        if (trackKey != statsTrackKey) {
            statsTrackKey = trackKey
            stats.reset()
            cancelResume()
            cancelDetour()
            dismissBlockedBanner()
        }
        val speedKmh = if (fix.hasSpeed()) fix.speed * 3.6 else 0.0
        stats.update(LatLon(fix.latitude, fix.longitude), speedKmh, fix.time / 1000.0)
        maxSpeedKmh = stats.maxSpeedKmh
        averageSpeedKmh = stats.averageSpeedKmh
        progress = if (trackLengthMeters != null && cumulativeMeters != null) stats.progress(trackLengthMeters, cumulativeMeters) else null
    }

    /** Restant / % / arrivée le long d'un autre parcours (itinéraire « Aller à »). */
    fun progressAlong(lengthMeters: Double, cumulativeMeters: Double): RideProgress = stats.progress(lengthMeters, cumulativeMeters)

    /** Tap sur la carte : sur la trace (à [toleranceMeters] près), propose d'y reprendre. */
    fun requestResume(tap: LatLon, points: List<LatLon>, cumulative: DoubleArray, toleranceMeters: Double, origin: Location?, valhalla: ValhallaConfiguration?) {
        if (manualResume != null || points.size < 2) return
        val projection = TrackGeometry.project(tap, points, cumulative) ?: return
        if (projection.distanceToTrackMeters > toleranceMeters) return
        val pin = TrackGeometry.interpolatedCoordinate(projection.cumulativeDistanceMeters, points, cumulative) ?: return
        manualResume = ManualResume(pin, projection.cumulativeDistanceMeters, isRequesting = origin != null)
        origin ?: return
        val from = LatLon(origin.latitude, origin.longitude)
        resumeJob = scope.launch {
            val route = withContext(Dispatchers.IO) { runCatching { routing.route(from, pin, valhalla) }.getOrNull() }
            val current = manualResume
            if (current?.target != pin) return@launch
            manualResume = if (route == null || route.size < 2) current.copy(isRequesting = false, routingFailed = true)
            else current.copy(route = route, routeLengthMeters = route.zipWithNext().sumOf { (a, b) -> geodesicDistanceMeters(a, b) }, isRequesting = false)
        }
    }

    /**
     * Contourne un chemin bloqué : itinéraire (routes ou pistes) vers le premier point de la trace
     * joignable à 500, 1 000, 1 500 ou 2 000 m devant soi ; sans réseau ou sans itinéraire, ligne
     * directe vers le point à 500 m. Signalé (anonymement, si activé) à la base partagée.
     */
    fun requestDetour(mode: DetourMode, fix: Location?, points: List<LatLon>, cumulative: DoubleArray, valhalla: ValhallaConfiguration?, onReport: (LatLon) -> Unit) {
        fix ?: return
        if (points.size < 2) return
        isBlockedBannerVisible = false
        detourJob?.cancel()
        val position = LatLon(fix.latitude, fix.longitude)
        val from = TrackGeometry.project(position, points, cumulative)?.cumulativeDistanceMeters ?: 0.0
        val candidates = DetourPlanner.candidates(points, cumulative, from)
        val direct = candidates.firstOrNull() ?: return
        onReport(position)
        if (mode == DetourMode.DIRECT) {
            start(Detour(DetourMode.DIRECT, listOf(position, direct), direct))
            return
        }
        isRequestingDetour = true
        detourJob = scope.launch {
            val found = withContext(Dispatchers.IO) {
                candidates.firstNotNullOfOrNull { candidate ->
                    runCatching { routing.route(position, candidate, valhalla, offroad = mode == DetourMode.TRAIL) }.getOrNull()
                        ?.takeIf { it.size > 1 }?.let { candidate to it }
                }
            }
            isRequestingDetour = false
            start(found?.let { (target, route) -> Detour(mode, route, target) } ?: Detour(DetourMode.DIRECT, listOf(position, direct), direct))
        }
    }

    private fun start(value: Detour) {
        detour = value
        detourTracker = DetourTracker(value.target)
    }

    fun cancelDetour() {
        detourJob?.cancel()
        detour = null
        detourTracker = null
        isRequestingDetour = false
    }

    fun showBlockedBanner() {
        isBlockedBannerVisible = true
    }

    fun dismissBlockedBanner() {
        isBlockedBannerVisible = false
        blockedDetector.reset()
    }

    /** À chaque fix, sur une trace : fin du détour, ou « Portion bloquée ? » hors trace qui s'éternise. */
    fun updateDetour(fix: Location, distanceToTrackMeters: Double?, guidingTrace: Boolean) {
        val position = LatLon(fix.latitude, fix.longitude)
        detourTracker?.let { if (it.update(position, distanceToTrackMeters)) cancelDetour() }
        if (detour != null || !guidingTrace || distanceToTrackMeters == null) return
        if (blockedDetector.update(position, distanceToTrackMeters, fix.time / 1000.0)) isBlockedBannerVisible = true
        else if (distanceToTrackMeters <= DetourConstants.OFF_TRACK_METERS) isBlockedBannerVisible = false
    }

    fun confirmResume() {
        manualResume = manualResume?.copy(isActive = true)
    }

    fun cancelResume() {
        resumeJob?.cancel()
        resumeJob = null
        manualResume = null
    }

    /** À chaque fix : fin de la reprise à 30 m du point, ou dès le retour sur la trace. */
    fun updateResume(fix: Location, distanceToTrackMeters: Double?) {
        val resume = manualResume ?: return
        val distance = geodesicDistanceMeters(LatLon(fix.latitude, fix.longitude), resume.target)
        if (distance <= JUNCTION_METERS || (distanceToTrackMeters != null && distanceToTrackMeters <= ON_TRACK_METERS)) cancelResume()
    }

    private companion object {
        const val JUNCTION_METERS = 30.0
        const val ON_TRACK_METERS = 30.0
    }
}
