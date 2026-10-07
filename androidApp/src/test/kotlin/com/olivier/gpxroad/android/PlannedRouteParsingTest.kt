package com.olivier.gpxroad.android

import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.shared.plan.RoutePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vraie réponse `/route` (moto, 3 points, autoroutes évitées) d'une instance publique de Valhalla. */
class PlannedRouteParsingTest {
    private val planned = RoutingClient.parsePlannedRoute(javaClass.getResourceAsStream("/valhalla/plan-route-basel.json")!!.readBytes())

    @Test
    fun legsAreJoinedIntoOneContinuousRoute() {
        assertTrue("des centaines de points attendus, trouvé ${planned.points.size}", planned.points.size > 200)
        assertEquals("aucun point en double aux jonctions", planned.points.size, planned.points.zipWithNext().count { (a, b) -> a != b } + 1)
    }

    @Test
    fun lengthAndDurationComeFromValhalla() {
        assertEquals(68_032.0, planned.distanceMeters, 1.0)
        assertEquals(7_250.8, planned.durationSeconds, 1.0)
        // La longueur du tracé décodé est cohérente avec celle annoncée (à 3 % près).
        val computed = RoutePlanner.lengthMeters(planned.points)
        assertTrue("tracé $computed m pour ${planned.distanceMeters} m", kotlin.math.abs(computed - planned.distanceMeters) / planned.distanceMeters < 0.03)
    }

    @Test
    fun routeStartsAndEndsNearTheRequestedPoints() {
        assertEquals(47.5, planned.points.first().latitude, 0.01)
        assertEquals(7.5, planned.points.first().longitude, 0.02)
        assertEquals(47.55, planned.points.last().latitude, 0.01)
        assertEquals(7.9, planned.points.last().longitude, 0.02)
    }
}
