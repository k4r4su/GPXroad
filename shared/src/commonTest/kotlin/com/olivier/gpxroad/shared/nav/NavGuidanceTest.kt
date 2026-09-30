package com.olivier.gpxroad.shared.nav

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.ValhallaManeuverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavGuidanceTest {
    /** Itinéraire plein nord, un point tous les ~11,1 m, sur ~1,1 km ; virage à droite au point 60. */
    private val points = (0..100).map { LatLon(47.0 + it * 0.0001, 7.0) }
    private val route = NavRoute(
        points,
        listOf(
            NavManeuver(ValhallaManeuverType.START, "Partez", beginShapeIndex = 0),
            NavManeuver(ValhallaManeuverType.RIGHT, "Tournez à droite", "Dans 500 m, tournez à droite", "Tournez à droite", "Continuez 2 km", beginShapeIndex = 60),
            NavManeuver(ValhallaManeuverType.DESTINATION, "Vous êtes arrivé", beginShapeIndex = 100),
        ),
        1_112.0, 60.0, "Maison",
    )

    @Test
    fun maneuversAdvanceAndVoiceAnnouncesOnce() {
        val tracker = NavGuidanceTracker(route)
        // Au départ : « Partez » dit UNE fois (les deux seuils sont franchis d'un coup), puis passée.
        assertEquals(listOf("Partez"), tracker.update(points[0], 0.0, voiceEnabled = true, isRecomputing = false).announcements)
        assertEquals(1, tracker.currentIndex)
        // À ~445 m du virage : annonce lointaine, une seule fois.
        assertEquals(listOf("Dans 500 m, tournez à droite"), tracker.update(points[20], 10.0, true, false).announcements)
        assertEquals(emptyList(), tracker.update(points[21], 11.0, true, false).announcements)
        // À ~89 m : annonce proche ; au virage : texte d'après-manœuvre et manœuvre suivante.
        assertEquals(listOf("Tournez à droite"), tracker.update(points[52], 20.0, true, false).announcements)
        assertEquals(listOf("Continuez 2 km"), tracker.update(points[60], 25.0, true, false).announcements)
        assertEquals(2, tracker.currentIndex)
        assertEquals(60.0, tracker.percentComplete, 1.0)
        assertEquals(445.0, tracker.remainingMeters, 2.0)
    }

    @Test
    fun offRouteRecomputesAfterEightSecondsWithCooldown() {
        val tracker = NavGuidanceTracker(route)
        val off = LatLon(47.005, 7.001) // ~76 m à l'est
        assertFalse(tracker.update(off, 0.0, false, false).shouldRecompute)
        assertFalse(tracker.update(off, 7.0, false, false).shouldRecompute)
        assertTrue(tracker.update(off, 8.0, false, false).shouldRecompute)
        // Recalcul échoué : pas de nouvelle requête avant 12 s.
        assertFalse(tracker.update(off, 15.0, false, false).shouldRecompute)
        assertTrue(tracker.update(off, 20.0, false, false).shouldRecompute)
        // De retour sur l'itinéraire : le compteur repart de zéro.
        assertFalse(tracker.update(points[50], 21.0, false, false).shouldRecompute)
        assertFalse(tracker.update(off, 40.0, false, false).shouldRecompute)
    }

    @Test
    fun goToRemainingAndHistory() {
        val goTo = GoToGuidance(points, GoToProfile.OFFROAD, points.last(), "Col")
        assertEquals(1_112.0, goTo.remainingMeters(points[0]), 2.0)
        assertEquals(133.4, goTo.estimatedSeconds(1_112.0), 0.5)
        assertTrue(goTo.isArrived(points[99]))
        val history = listOf("A", "B", "C", "D", "E")
        assertEquals(listOf("C", "A", "B", "D", "E"), SearchHistory.record(history, "C") { it })
        assertEquals(listOf("F", "A", "B", "C", "D"), SearchHistory.record(history, "F") { it })
        assertEquals("6.7,47.3,7.3,46.7", nominatimViewbox(LatLon(47.0, 7.0)).split(",").joinToString(",") { it.toDouble().let { v -> ((v * 10).toLong() / 10.0).toString() } })
    }
}
