package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** It33 bis — la règle « seuls les vrais carrefours » ne vaut que là où Valhalla a recalé la trace. */
class MapMatchCoverageTest {
    private fun destination(from: LatLon, bearingDegrees: Double, meters: Double): LatLon {
        val r = 6_371_000.0
        val b = bearingDegrees * PI / 180
        val lat1 = from.latitude * PI / 180
        val lon1 = from.longitude * PI / 180
        val d = meters / r
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(lat2 * 180 / PI, lon2 * 180 / PI)
    }

    private val start = LatLon(47.6, 7.4)

    /** Nord 1 km (courbe de 60° sur la même route à 600 m), virage à droite à 1 km, est 1 km, virage à gauche à 2 km, nord 1 km. */
    private val track: List<LatLon> by lazy {
        val points = mutableListOf(start)
        var bearing = 0.0
        var current = start
        for (meters in 10..3000 step 10) {
            when (meters) {
                600 -> bearing += 30.0
                620 -> bearing -= 30.0
                1000 -> bearing = 90.0
                2000 -> bearing = 0.0
            }
            current = destination(current, bearing, 10.0)
            points.add(current)
        }
        points
    }

    @Test
    fun coveredRangesFollowTheMatchedShapeOnly() {
        val shape = track.take(120) // ~1,2 km recalés
        val ranges = MapMatchCoverage.coveredRanges(track, listOf(shape))
        assertEquals(1, ranges.size)
        assertEquals(0.0, ranges[0].startMeters, 1.0)
        assertTrue(ranges[0].endMeters in 1150.0..1250.0, "${ranges[0]}")
        assertTrue(MapMatchCoverage.coveredRanges(track, emptyList()).isEmpty())
    }

    @Test
    fun aPartialMatchNoLongerHidesTheTurnsOfTheRestOfTheTrack() {
        val settings = RoadbookSettings()
        val cumulative = TrackGeometry.cumulativeDistances(track)
        val rightTurn = MapMatchedManeuver(track[100], ValhallaManeuverType.RIGHT, null, streetNamesBefore = listOf("D 1"), streetNamesAfter = listOf("D 2"))
        val coverage = MapMatchCoverage.coveredRanges(track, listOf(track.take(120)))

        val events = RoadbookAnalyzer.buildRoadbookEvents(track, settings, listOf(rightTurn), coverage)
        val positions = events.map { it.cumulativeDistanceMeters(cumulative)!! }

        assertEquals(2, events.size, "virage Valhalla dans la partie recalée + virage géométrique au-delà ; ${events.map { it.tier }} @ $positions")
        assertEquals(1000.0, positions[0], 15.0)
        assertEquals(TurnDirection.RIGHT, events[0].direction)
        assertEquals(2000.0, positions[1], 30.0, "le virage à 2 km, hors de la partie recalée, reste annoncé")
        assertEquals(TurnDirection.LEFT, events[1].direction)
        assertEquals(listOf(1, 2), events.map { it.sequenceIndex })
    }

    @Test
    fun withAFullMatchTheRoadBendIsStillNotAnnounced() {
        val settings = RoadbookSettings()
        val rightTurn = MapMatchedManeuver(track[100], ValhallaManeuverType.RIGHT, null, streetNamesBefore = listOf("D 1"), streetNamesAfter = listOf("D 2"))
        val leftTurn = MapMatchedManeuver(track[200], ValhallaManeuverType.LEFT, null, streetNamesBefore = listOf("D 2"), streetNamesAfter = listOf("D 3"))
        val coverage = MapMatchCoverage.coveredRanges(track, listOf(track))
        val events = RoadbookAnalyzer.buildRoadbookEvents(track, settings, listOf(rightTurn, leftTurn), coverage)
        assertEquals(listOf(TurnDirection.RIGHT, TurnDirection.LEFT), events.map { it.direction }, "la courbe de 600 m (même route) n'est pas annoncée")
    }

    @Test
    fun noCoverageAtAllMeansGeometryAsBefore() {
        val settings = RoadbookSettings()
        val geometric = RoadbookAnalyzer.buildRoadbookEvents(track, settings)
        assertEquals(geometric, RoadbookAnalyzer.buildRoadbookEvents(track, settings, emptyList(), emptyList()))
    }
}
