package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RejoinPlannerTest {
    private fun destination(from: LatLon, bearingDegrees: Double, distanceMeters: Double): LatLon {
        val r = 6_371_000.0
        val b = bearingDegrees * PI / 180
        val lat1 = from.latitude * PI / 180
        val lon1 = from.longitude * PI / 180
        val d = distanceMeters / r
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(lat2 * 180 / PI, lon2 * 180 / PI)
    }

    private val start = LatLon(47.6, 7.4)

    /** Ligne droite vers le nord, un point tous les 10 m. */
    private fun northLine(lengthMeters: Double): List<LatLon> =
        (0..(lengthMeters / 10).toInt()).map { destination(start, 0.0, it * 10.0) }

    @Test
    fun theRejoinPointIsNeverBehindTheLastPositionOnTheTrack() {
        val points = northLine(2000.0)
        val cumulative = TrackGeometry.cumulativeDistances(points)
        // À 200 m à l'est du km 0,3 : le plus proche à vol d'oiseau est derrière (≈ 300 m)…
        val rider = destination(destination(start, 0.0, 300.0), 90.0, 200.0)
        assertEquals(300.0, RejoinPlanner.nearestAhead(rider, points, cumulative, 0.0)!!.cumulativeDistanceMeters, 10.0)
        // …mais on avait déjà roulé jusqu'au km 0,8 : le point de retour est devant, au km 0,8.
        val target = RejoinPlanner.nearestAhead(rider, points, cumulative, 800.0)
        assertNotNull(target)
        assertEquals(800.0, target.cumulativeDistanceMeters, 10.0)
    }

    @Test
    fun aLoopPassingBackNearbyIsAValidRejoinPointAhead() {
        // Aller 1 km au nord, retour 1 km au sud à 50 m à l'est : le passage retour est "devant".
        val out = northLine(1000.0)
        val back = (0..100).map { destination(destination(out.last(), 90.0, 50.0), 180.0, it * 10.0) }
        val points = out + back
        val cumulative = TrackGeometry.cumulativeDistances(points)
        val rider = destination(destination(start, 0.0, 200.0), 90.0, 120.0)
        val target = RejoinPlanner.nearestAhead(rider, points, cumulative, 600.0)!!
        assertTrue(target.cumulativeDistanceMeters > 1000.0, "le passage retour, plus proche que le km 0,6 de l'aller")
    }

    @Test
    fun theRejoinRouteTurnsAreDetectedLikeTheTraceOnes() {
        val corner = destination(start, 0.0, 400.0)
        val route = (0..40).map { destination(start, 0.0, it * 10.0) } + (1..40).map { destination(corner, 90.0, it * 10.0) }
        val target = RejoinTarget(0, route.last(), 0.0)
        val plan = RejoinPlanner.plan(target, route, RoadbookSettings())
        assertEquals(1, plan.maneuvers.size)
        assertEquals(RoadbookTier.HARD, plan.maneuvers[0].checkpoint.tier)
        assertEquals(TurnDirection.RIGHT, plan.maneuvers[0].checkpoint.direction)
        assertEquals(800.0, plan.routeLengthMeters, 5.0)

        val atStart = RejoinPlanner.progress(plan, start)
        assertEquals(0, atStart.nextManeuverIndex)
        assertEquals(400.0, atStart.distanceToNextManeuverMeters!!, 5.0)
        assertEquals(800.0, atStart.remainingToTrackMeters, 5.0)

        val afterCorner = RejoinPlanner.progress(plan, destination(corner, 90.0, 200.0))
        assertNull(afterCorner.nextManeuverIndex, "plus de virage : tout droit jusqu'à la trace")
        assertEquals(200.0, afterCorner.remainingToTrackMeters, 5.0)
    }

    @Test
    fun aPassedTargetIsConfirmedOnlyAfterTenSecondsBehindWhileMoving() {
        val detector = RejoinPassedDetector()
        val target = destination(start, 0.0, 100.0) // au nord
        val south = 180.0
        for (t in 0..9) assertFalse(detector.update(start, south, 10.0, t.toDouble(), target), "t=$t")
        assertTrue(detector.update(start, south, 10.0, 10.0, target))
    }

    @Test
    fun stoppingOrHeadingTowardsTheTargetResetsTheChrono() {
        val detector = RejoinPassedDetector()
        val target = destination(start, 0.0, 100.0)
        for (t in 0..8) detector.update(start, 180.0, 10.0, t.toDouble(), target)
        assertFalse(detector.update(start, 180.0, 0.5, 9.0, target), "arrêt : pas de cap fiable")
        assertFalse(detector.update(start, 180.0, 10.0, 12.0, target), "chrono reparti de zéro")
        for (t in 13..30) assertFalse(detector.update(start, 10.0, 10.0, t.toDouble(), target), "cible devant")
    }
}
