package com.olivier.gpxroad.android.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.net.PlannedRoute
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ValhallaConfiguration
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.plan.PlanOptions
import com.olivier.gpxroad.shared.plan.RoutePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** État de l'écran « Créer un itinéraire » (`RoutePlannerModel` iOS) : points posés, options, itinéraire calculé. */
class PlannerModel(
    private val routing: RoutingClient,
    private val checker: TrackAccessChecker,
    private val valhalla: () -> ValhallaConfiguration?,
    /** Points déjà signalés bloqués ou interdits dans la zone (minLat, minLon, maxLat, maxLon) : évités par Valhalla. */
    private val exclusions: (Double, Double, Double, Double) -> List<LatLon>,
    private val scope: CoroutineScope,
) {
    enum class Status { IDLE, COMPUTING, NOT_CONFIGURED, FAILED }

    /** Contrôle d'accès des pistes (seulement quand « Autoriser les pistes » est actif). */
    enum class AccessStatus { IDLE, CHECKING, CHECKED, UNAVAILABLE }

    var flagged by mutableStateOf<List<FlaggedSegment>>(emptyList())
        private set
    var accessStatus by mutableStateOf(AccessStatus.IDLE)
        private set
    private var accessJob: Job? = null

    var waypoints by mutableStateOf<List<LatLon>>(emptyList())
        private set
    var options by mutableStateOf(PlanOptions())
        private set
    var route by mutableStateOf<PlannedRoute?>(null)
        private set
    var status by mutableStateOf(Status.IDLE)
        private set
    /** Change quand l'itinéraire doit être recadré (premier calcul, ou après « Tout effacer »). */
    var fitToken by mutableIntStateOf(0)
        private set

    private var job: Job? = null

    val canSave: Boolean get() = route != null && status != Status.COMPUTING

    fun add(point: LatLon) {
        if (!RoutePlanner.canAdd(waypoints, point)) return
        waypoints = waypoints + point
        recompute()
    }

    fun remove(index: Int) {
        if (index !in waypoints.indices) return
        waypoints = waypoints.toMutableList().also { it.removeAt(index) }
        recompute()
    }

    fun removeLast() {
        if (waypoints.isEmpty()) return
        waypoints = waypoints.dropLast(1)
        recompute()
    }

    fun clear() {
        job?.cancel()
        accessJob?.cancel()
        waypoints = emptyList()
        route = null
        flagged = emptyList()
        accessStatus = AccessStatus.IDLE
        status = Status.IDLE
        fitToken++
    }

    fun updateOptions(value: PlanOptions) {
        options = value
    }

    /** Fenêtre d'options fermée : on recalcule avec les nouveaux réglages. */
    fun optionsChanged() = recompute()

    fun recompute() {
        job?.cancel()
        accessJob?.cancel()
        if (waypoints.size < RoutePlanner.MIN_WAYPOINTS) {
            route = null
            flagged = emptyList()
            accessStatus = AccessStatus.IDLE
            status = Status.IDLE
            return
        }
        val configuration = valhalla()
        if (configuration == null) {
            status = Status.NOT_CONFIGURED
            return
        }
        status = Status.COMPUTING
        val points = waypoints
        val chosen = options
        val first = route == null
        val padding = 0.5   // degrés autour des points posés
        val excluded = exclusions(points.minOf { it.latitude } - padding, points.minOf { it.longitude } - padding, points.maxOf { it.latitude } + padding, points.maxOf { it.longitude } + padding)
        job = scope.launch {
            try {
                delay(350)   // plusieurs points posés d'affilée ne font qu'un calcul
                val planned = withContext(Dispatchers.IO) { routing.plan(points, chosen, excluded, configuration) }
                route = planned
                status = Status.IDLE
                if (first) fitToken++
                checkAccess(planned, chosen, configuration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                route = null
                flagged = emptyList()
                accessStatus = AccessStatus.IDLE
                status = Status.FAILED
            }
        }
    }

    /** Pistes autorisées ? Seulement si l'utilisateur les a permises : on lit alors l'accès de chaque chemin non goudronné. */
    private fun checkAccess(planned: PlannedRoute, chosen: PlanOptions, configuration: ValhallaConfiguration) {
        accessJob?.cancel()
        flagged = emptyList()
        if (!chosen.allowTracks) {
            accessStatus = AccessStatus.IDLE
            return
        }
        accessStatus = AccessStatus.CHECKING
        accessJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { checker.check(planned, chosen.vehicle, configuration) }.getOrNull() }
            if (result != null) {
                flagged = result
                accessStatus = AccessStatus.CHECKED
            } else {
                accessStatus = AccessStatus.UNAVAILABLE
            }
        }
    }
}
