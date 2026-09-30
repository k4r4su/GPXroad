package com.olivier.gpxroad.android.ride

import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ValhallaConfiguration
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
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

    fun onLocation(fix: Location, trackKey: String?, trackLengthMeters: Double?, cumulativeMeters: Double?) {
        if (trackKey != statsTrackKey) {
            statsTrackKey = trackKey
            stats.reset()
            cancelResume()
        }
        val speedKmh = if (fix.hasSpeed()) fix.speed * 3.6 else 0.0
        stats.update(LatLon(fix.latitude, fix.longitude), speedKmh, fix.time / 1000.0)
        maxSpeedKmh = stats.maxSpeedKmh
        averageSpeedKmh = stats.averageSpeedKmh
        progress = if (trackLengthMeters != null && cumulativeMeters != null) stats.progress(trackLengthMeters, cumulativeMeters) else null
    }

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
