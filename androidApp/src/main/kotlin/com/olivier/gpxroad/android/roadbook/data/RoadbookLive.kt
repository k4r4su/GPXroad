package com.olivier.gpxroad.android.roadbook.data

import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ValhallaConfiguration
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.roadbook.OffTrackDetector
import com.olivier.gpxroad.shared.roadbook.RejoinPassedDetector
import com.olivier.gpxroad.shared.roadbook.RejoinPlan
import com.olivier.gpxroad.shared.roadbook.RejoinPlanner
import com.olivier.gpxroad.shared.roadbook.RejoinTarget
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import com.olivier.gpxroad.shared.roadbook.RoadbookSettings
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Location.latLon() = LatLon(latitude, longitude)

/**
 * Statut « Hors trace » du Road Book (portage de `RoadbookOffTrackState` iOS) — même règle que le
 * Ride ([OffTrackDetector], hystérésis 30/25 m). La distance de reprise vise le point de la trace le
 * plus proche DEVANT la dernière position sur la trace, affichée après [REJOIN_DISTANCE_DELAY_MILLIS].
 */
data class OffTrackState(
    val isOffTrack: Boolean = false,
    val sinceMillis: Long? = null,
    val distanceToTrackMeters: Double? = null,
    val rejoinDistanceMeters: Double? = null,
    val lastOnTrackCumulativeMeters: Double? = null,
) {
    fun updated(location: Location, points: List<LatLon>, cumulative: DoubleArray): OffTrackState {
        val projection = TrackGeometry.project(location.latLon(), points, cumulative) ?: return this
        val offTrack = OffTrackDetector.isOffTrack(isOffTrack, projection.distanceToTrackMeters)
        if (!offTrack) return OffTrackState(false, null, projection.distanceToTrackMeters, null, projection.cumulativeDistanceMeters)
        val rejoin = RejoinPlanner.nearestAhead(location.latLon(), points, cumulative, lastOnTrackCumulativeMeters ?: 0.0)
            ?.let { geodesicDistanceMeters(location.latLon(), it.coordinate) }
        return copy(
            isOffTrack = true,
            sinceMillis = if (isOffTrack) sinceMillis else location.time,
            distanceToTrackMeters = projection.distanceToTrackMeters,
            rejoinDistanceMeters = rejoin,
        )
    }

    /** Discret tant que ça peut se résorber : distance de reprise après le même délai que le Ride. */
    fun showsRejoinDistance(nowMillis: Long): Boolean =
        isOffTrack && sinceMillis != null && nowMillis - sinceMillis >= REJOIN_DISTANCE_DELAY_MILLIS

    companion object {
        const val REJOIN_DISTANCE_DELAY_MILLIS = 30_000L
    }
}

/** Ce que l'écran affiche hors trace : le chemin pour rejoindre la trace et où l'on en est dessus. */
data class RejoinDisplay(
    val status: Status,
    val maneuvers: List<RoadbookManeuver> = emptyList(),
    val nextManeuverIndex: Int? = null,
    val distanceToNextManeuverMeters: Double? = null,
    val remainingToTrackMeters: Double? = null,
    val routeCumulativeDistanceMeters: Double = 0.0,
    val targetCumulativeDistanceMeters: Double? = null,
) {
    enum class Status { WAITING, COMPUTING, ROUTED, UNAVAILABLE }
}

/**
 * Chemin de reprise du Road Book (portage de `RoadbookRejoinController` iOS) : hors trace depuis 2 s,
 * point de retour = le plus proche DEVANT soi, rejoint par les routes (Valhalla, sinon OSRM) ;
 * point dépassé (derrière soi, en roulant, 10 s) → nouveau point au-delà ; réévaluation toutes les
 * 60 s si le point a bougé de plus de 10 m ; retour sur la trace → tout est effacé.
 */
class RejoinController(private val routing: RoutingClient) {
    var display by mutableStateOf<RejoinDisplay?>(null)
        private set

    private val scope = MainScope()
    private var lastOnTrackCumulative: Double? = null
    private var floorCumulative: Double? = null
    private var offTrackSince: Long? = null
    private var target: RejoinTarget? = null
    private var plan: RejoinPlan? = null
    private var lastEvaluation: Long? = null
    private var routingJob: Job? = null
    private val passedDetector = RejoinPassedDetector()

    fun update(
        location: Location,
        points: List<LatLon>,
        cumulative: DoubleArray,
        isOffTrack: Boolean,
        onTrackCumulative: Double?,
        settings: RoadbookSettings,
        valhalla: ValhallaConfiguration?,
    ) {
        if (!isOffTrack) {
            if (onTrackCumulative != null) lastOnTrackCumulative = onTrackCumulative
            clear()
            return
        }
        val now = location.time
        val since = offTrackSince ?: now
        offTrackSince = since
        if (now - since < DIVERGENCE_DURATION_MILLIS) {
            display = RejoinDisplay(RejoinDisplay.Status.WAITING)
            return
        }
        val current = target
        if (current != null) {
            val passed = passedDetector.update(
                position = location.latLon(),
                courseDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
                speedMetersPerSecond = if (location.hasSpeed()) maxOf(location.speed.toDouble(), 0.0) else 0.0,
                timestampSeconds = location.time / 1000.0,
                target = current.coordinate,
            )
            if (passed) {
                floorCumulative = current.cumulativeDistanceMeters + 1
                retarget(location, points, cumulative, settings, valhalla, force = true)
            } else if (lastEvaluation?.let { now - it >= REEVALUATION_INTERVAL_MILLIS } == true) {
                retarget(location, points, cumulative, settings, valhalla, force = false)
            }
        } else {
            retarget(location, points, cumulative, settings, valhalla, force = true)
        }
        refreshProgress(location.latLon())
    }

    fun reset() {
        lastOnTrackCumulative = null
        clear()
    }

    private fun clear() {
        routingJob?.cancel()
        routingJob = null
        offTrackSince = null
        floorCumulative = null
        target = null
        plan = null
        lastEvaluation = null
        passedDetector.reset()
        display = null
    }

    private fun retarget(location: Location, points: List<LatLon>, cumulative: DoubleArray, settings: RoadbookSettings, valhalla: ValhallaConfiguration?, force: Boolean) {
        lastEvaluation = location.time
        val from = maxOf(floorCumulative ?: 0.0, lastOnTrackCumulative ?: 0.0)
        val newTarget = RejoinPlanner.nearestAhead(location.latLon(), points, cumulative, from)
        if (newTarget == null) {
            display = RejoinDisplay(RejoinDisplay.Status.UNAVAILABLE)
            return
        }
        val current = target
        if (!force && current != null && display?.status == RejoinDisplay.Status.ROUTED &&
            geodesicDistanceMeters(current.coordinate, newTarget.coordinate) <= RETARGET_MIN_DISTANCE_METERS
        ) return
        target = newTarget
        plan = null
        passedDetector.reset()
        display = RejoinDisplay(RejoinDisplay.Status.COMPUTING, targetCumulativeDistanceMeters = newTarget.cumulativeDistanceMeters)

        routingJob?.cancel()
        val origin = location.latLon()
        routingJob = scope.launch {
            val route = withContext(Dispatchers.IO) { runCatching { routing.route(origin, newTarget.coordinate, valhalla) }.getOrNull() }
            if (target != newTarget) return@launch
            if (route == null || route.size < 2) {
                display = RejoinDisplay(RejoinDisplay.Status.UNAVAILABLE, targetCumulativeDistanceMeters = newTarget.cumulativeDistanceMeters)
                return@launch
            }
            plan = withContext(Dispatchers.Default) { RejoinPlanner.plan(newTarget, route, settings) }
            refreshProgress(origin)
        }
    }

    private fun refreshProgress(position: LatLon) {
        val plan = plan ?: return
        val progress = RejoinPlanner.progress(plan, position)
        display = RejoinDisplay(
            status = RejoinDisplay.Status.ROUTED,
            maneuvers = plan.maneuvers,
            nextManeuverIndex = progress.nextManeuverIndex,
            distanceToNextManeuverMeters = progress.distanceToNextManeuverMeters,
            remainingToTrackMeters = progress.remainingToTrackMeters,
            routeCumulativeDistanceMeters = progress.routeCumulativeDistanceMeters,
            targetCumulativeDistanceMeters = plan.target.cumulativeDistanceMeters,
        )
    }

    private companion object {
        const val DIVERGENCE_DURATION_MILLIS = 2_000L
        const val REEVALUATION_INTERVAL_MILLIS = 60_000L
        const val RETARGET_MIN_DISTANCE_METERS = 10.0
    }
}
