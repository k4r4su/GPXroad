package com.olivier.gpxroad.android

import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.shared.nav.NavGuidanceTracker
import com.olivier.gpxroad.shared.roadbook.ValhallaManeuverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guidage « Aller à » sur une VRAIE réponse Valhalla `/route` (serveur public FOSSGIS, 01/10, Hégenheim → Ferrette, fr-FR). */
class NavRouteParsingTest {
    private val data = requireNotNull(javaClass.getResource("/valhalla/route-hegenheim-ferrette.json")).readBytes()

    @Test
    fun decodesManeuversAndGuidesAlongThem() {
        val route = RoutingClient.parseNavRoute(data, "Ferrette")
        assertEquals(22_123.0, route.totalDistanceMeters, 0.5)
        assertEquals(16, route.maneuvers.size)
        assertEquals(ValhallaManeuverType.START_RIGHT, route.maneuvers[0].type)
        assertEquals(ValhallaManeuverType.RIGHT, route.maneuvers[1].type)
        assertEquals("Tournez à droite dans Rue de Bourgfelden.", route.maneuvers[1].verbalAlert)
        assertEquals(listOf("Rue de Bourgfelden"), route.maneuvers[1].streetNames)
        assertTrue(route.maneuvers.last().isArrival)
        assertTrue(route.points.size > 100)
        assertEquals(route.points.size - 1, route.maneuvers.last().beginShapeIndex)

        // Rejouer l'itinéraire point par point : chaque manœuvre est passée, dans l'ordre, jusqu'à l'arrivée.
        val tracker = NavGuidanceTracker(route)
        var spoken = 0
        route.points.forEachIndexed { index, point ->
            spoken += tracker.update(point, index.toDouble(), voiceEnabled = true, isRecomputing = false).announcements.size
        }
        assertEquals(route.maneuvers.size, tracker.currentIndex)
        assertTrue(spoken >= route.maneuvers.size, "$spoken annonces")
        assertEquals(0.0, tracker.remainingMeters, 30.0)
    }
}
