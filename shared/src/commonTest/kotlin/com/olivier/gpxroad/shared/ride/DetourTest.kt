package com.olivier.gpxroad.shared.ride

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetourTest {
    /** Plein nord sur ~2,2 km (un point tous les ~11,1 m). */
    private val points = (0..200).map { LatLon(47.0 + it * 0.0001, 7.0) }
    private val cumulative = TrackGeometry.cumulativeDistances(points)

    @Test
    fun candidatesAheadEveryFiveHundredMeters() {
        val candidates = DetourPlanner.candidates(points, cumulative, 100.0)
        assertEquals(4, candidates.size)
        assertEquals(47.0 + 600 / 111_195.0, candidates[0].latitude, 1e-5)
        assertEquals(2, DetourPlanner.candidates(points, cumulative, 1_000.0).size)
    }

    @Test
    fun detourStartedOnTheTrackIsNotClearedAtOnce() {
        val tracker = DetourTracker(points[100])
        assertFalse(tracker.update(points[10], 0.0), "encore sur la trace, devant le chemin bloqué")
        assertFalse(tracker.update(LatLon(47.002, 7.002), 150.0))
        assertTrue(tracker.update(points[60], 5.0), "de retour sur la trace")
        assertTrue(DetourTracker(points[100]).update(points[100], 0.0), "au point de retour")
    }

    @Test
    fun blockedPathAfterThirtySecondsOrTwoHundredMeters() {
        val detector = BlockedPathDetector()
        val off = LatLon(47.0, 7.001)
        assertFalse(detector.update(off, 80.0, 0.0))
        assertFalse(detector.update(off, 80.0, 29.0))
        assertTrue(detector.update(off, 80.0, 30.0))
        detector.update(points[0], 10.0, 31.0)
        assertFalse(detector.update(off, 80.0, 32.0), "revenu sur la trace : tout repart de zéro")
        assertTrue(detector.update(LatLon(47.002, 7.001), 80.0, 40.0), "222 m parcourus hors trace")
    }

    @Test
    fun sharedBlockageNearTheTrack() {
        val now = 1_800_000_000_000
        val near = SharedBlockage("a", LatLon(47.01, 7.003), null, now)
        val far = SharedBlockage("b", LatLon(47.01, 7.01), "Arbre", now)
        assertEquals("a", SharedBlockages.nearestAlongTrack(listOf(far, near), points)?.id)
        assertNull(SharedBlockages.nearestAlongTrack(listOf(far), points))
        assertTrue(near.copy(lastConfirmedMillis = now - 100L * 86_400_000).isFaded(now))
        assertFalse(near.isExpired(now))
    }
}
