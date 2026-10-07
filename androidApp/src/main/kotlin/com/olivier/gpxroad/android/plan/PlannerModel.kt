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
    private val valhalla: () -> ValhallaConfiguration?,
    private val scope: CoroutineScope,
) {
    enum class Status { IDLE, COMPUTING, NOT_CONFIGURED, FAILED }

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
        waypoints = emptyList()
        route = null
        status = Status.IDLE
        fitToken++
    }

    fun updateOptions(value: PlanOptions) {
        options = value
    }

    /** Fenêtre d'options fermée : on recalcule avec les nouveaux réglages. */
    fun optionsChanged() = recompute()

    private fun recompute() {
        job?.cancel()
        if (waypoints.size < RoutePlanner.MIN_WAYPOINTS) {
            route = null
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
        job = scope.launch {
            try {
                delay(350)   // plusieurs points posés d'affilée ne font qu'un calcul
                val planned = withContext(Dispatchers.IO) { routing.plan(points, chosen, configuration) }
                route = planned
                status = Status.IDLE
                if (first) fitToken++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                route = null
                status = Status.FAILED
            }
        }
    }
}
