package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portage it32 des tests Swift de la géométrie du Road Book — mêmes cas, mêmes attendus :
 * `RoadbookInflectionTests` (10 tests, tous) et la partie purement géométrique de
 * `RoadbookCheckpointReliabilityTests` (8 tests sans manœuvre Valhalla). Les attendus portant sur
 * `RoadbookExtractor` (distance partielle) restent côté Swift. La parité numérique avec
 * l'implémentation Swift, elle, est vérifiée côté iOS par `SharedRoadbookParityTests`, qui compare
 * les deux moteurs sur des milliers d'événements.
 */
class RoadbookGeometryTest {

    // MARK: outils communs aux deux suites Swift

    private fun destination(from: LatLon, bearingDegrees: Double, distanceMeters: Double): LatLon {
        val earthRadius = 6_371_000.0
        val bearing = bearingDegrees * PI / 180
        val lat1 = from.latitude * PI / 180
        val lon1 = from.longitude * PI / 180
        val angularDistance = distanceMeters / earthRadius
        val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(angularDistance) * cos(lat1), cos(angularDistance) - sin(lat1) * sin(lat2))
        return LatLon(lat2 * 180 / PI, lon2 * 180 / PI)
    }

    private fun events(points: List<LatLon>, mergeMinDistanceMeters: Double = 50.0): List<GeometricEvent> =
        RoadbookGeometry.buildGeometricEvents(
            points,
            RoadbookConstants.WINDOW_BEFORE_METERS_DEFAULT,
            RoadbookConstants.WINDOW_AFTER_METERS_DEFAULT,
            TierThresholds.DEFAULT,
            mergeMinDistanceMeters,
        )

    // MARK: RoadbookInflectionTests

    private fun curvingTrack(segmentCount: Int, segmentLengthMeters: Double, segmentTurnDegrees: Double): List<LatLon> {
        val points = mutableListOf(LatLon(45.0, 5.0))
        var bearing = 0.0
        var coordinate = points[0]
        repeat(segmentCount) {
            coordinate = destination(coordinate, bearing, segmentLengthMeters)
            points.add(coordinate)
            bearing += segmentTurnDegrees
        }
        return points
    }

    private fun inflectionTrack(segments: List<Pair<Double, Double>>): List<LatLon> {
        val points = mutableListOf(LatLon(45.0, 5.0))
        var bearing = 0.0
        var coordinate = points[0]
        for ((length, turnAfter) in segments) {
            coordinate = destination(coordinate, bearing, length)
            points.add(coordinate)
            bearing += turnAfter
        }
        return points
    }

    @Test
    fun aGradualCurveIsNotACheckpoint() {
        assertTrue(events(curvingTrack(10, 20.0, 8.0)).isEmpty())
    }

    @Test
    fun sharpSplitAlsoTriggersAnEvent() {
        assertFalse(events(curvingTrack(4, 30.0, 70.0)).isEmpty())
    }

    @Test
    fun straightTrackProducesNoEvents() {
        assertTrue(events(curvingTrack(10, 50.0, 0.0)).isEmpty())
    }

    @Test
    fun belowLightThresholdProducesNoEvent() {
        assertTrue(events(curvingTrack(2, 100.0, 20.0)).isEmpty())
    }

    @Test
    fun nearbyEventsMergeIntoOne() {
        val result = events(curvingTrack(10, 15.0, 15.0), mergeMinDistanceMeters = 150.0)
        assertFalse(result.isEmpty(), "précondition : la courbe est bien détectée")
        for (i in 1 until result.size) {
            val distance = geodesicDistanceMeters(result[i - 1].coordinate, result[i].coordinate)
            assertTrue(distance >= 150, "deux événements retenus ne doivent jamais être plus proches que mergeMinDistanceMeters")
        }
    }

    @Test
    fun angleBucketsMapToExpectedTiers() {
        assertEquals(GeometricTier.LIGHT, events(curvingTrack(2, 100.0, 35.0)).firstOrNull()?.tier)
        assertEquals(GeometricTier.MARKED, events(curvingTrack(2, 100.0, 60.0)).firstOrNull()?.tier)
        assertEquals(GeometricTier.HARD, events(curvingTrack(2, 100.0, 100.0)).firstOrNull()?.tier)
        val veryHard = events(curvingTrack(2, 100.0, 150.0))
        assertEquals(GeometricTier.VERY_HARD, veryHard.firstOrNull()?.tier)
        assertEquals(GeometricDirection.RIGHT, veryHard.firstOrNull()?.direction)
    }

    @Test
    fun a160DegreeTurnIsAVeryTightTurnWithItsSideNeverAUTurn() {
        val right = events(curvingTrack(2, 100.0, 160.0))
        assertEquals(GeometricTier.VERY_HARD, right.firstOrNull()?.tier)
        assertEquals(GeometricDirection.RIGHT, right.firstOrNull()?.direction)

        val left = events(curvingTrack(2, 100.0, -160.0))
        assertEquals(GeometricTier.VERY_HARD, left.firstOrNull()?.tier)
        assertEquals(GeometricDirection.LEFT, left.firstOrNull()?.direction)
    }

    @Test
    fun aHairpinBeyond175DegreesThatLeavesOnAnotherBranchIsAVeryTightTurn() {
        val result = events(inflectionTrack(listOf(300.0 to 90.0, 40.0 to 90.0, 300.0 to 0.0)))
        assertEquals(1, result.size)
        assertTrue((result.firstOrNull()?.turnAngleDegrees ?: 0.0) >= RoadbookConstants.U_TURN_MIN_DEGREES, "précondition : angle cumulé au-delà du seuil demi-tour")
        assertEquals(GeometricTier.VERY_HARD, result.firstOrNull()?.tier)
        assertEquals(GeometricDirection.RIGHT, result.firstOrNull()?.direction)
    }

    @Test
    fun aReversalOnTheSamePathIsAUTurn() {
        val result = events(inflectionTrack(listOf(400.0 to 180.0, 400.0 to 0.0)))
        assertEquals(1, result.size)
        assertEquals(GeometricTier.U_TURN, result.firstOrNull()?.tier)
        assertEquals(GeometricDirection.U_TURN, result.firstOrNull()?.direction)
    }

    @Test
    fun aReversalRightAfterTheStartIsIgnoredAsAParkingManeuver() {
        assertFalse(events(inflectionTrack(listOf(100.0 to 180.0, 400.0 to 0.0))).any { it.tier == GeometricTier.U_TURN })
    }

    // MARK: RoadbookCheckpointReliabilityTests (partie géométrique)

    private fun reliabilityTrack(segments: List<Pair<Double, Double>>, pointSpacing: Double = 0.0): MutableList<LatLon> {
        var coordinate = LatLon(47.6, 7.4)
        val points = mutableListOf(coordinate)
        var bearing = 0.0
        for ((length, turnAfter) in segments) {
            val steps = if (pointSpacing > 0) max((length / pointSpacing).roundToInt(), 1) else 1
            repeat(steps) {
                coordinate = destination(coordinate, bearing, length / steps)
                points.add(coordinate)
            }
            bearing += turnAfter
        }
        return points
    }

    /** Réglages de `RoadbookExtractor.maneuvers` dans ces tests : fusion à 150 m (`RideConstants`). */
    private fun maneuvers(points: List<LatLon>) = events(points, mergeMinDistanceMeters = 150.0)

    private fun outgoingHeading(points: List<LatLon>, event: GeometricEvent): Double =
        RoadbookGeometry.outgoingHeading(
            event.trackCumulativeDistanceMeters,
            points,
            TrackGeometry.cumulativeDistances(points),
            RoadbookConstants.WINDOW_AFTER_METERS_DEFAULT,
        )

    @Test
    fun aDuplicatedPointOnAStraightSparseTraceIsNotACheckpoint() {
        val points = reliabilityTrack(listOf(300.0 to 0.0, 0.0001 to 0.0, 239.0 to 0.0, 300.0 to 0.0))
        points.add(2, points[2])
        assertTrue(maneuvers(points).isEmpty())
    }

    @Test
    fun aGradualCurveOnTheSameRoadHasNoCheckpoint() {
        assertTrue(maneuvers(reliabilityTrack(List(30) { 20.0 to 3.0 }, pointSpacing = 20.0)).isEmpty())
    }

    @Test
    fun aRealRightAngleTurnIsOneCoherentCheckpoint() {
        val points = reliabilityTrack(listOf(400.0 to -100.0, 400.0 to 0.0), pointSpacing = 20.0)
        val result = maneuvers(points)
        assertEquals(1, result.size)
        assertEquals(GeometricTier.HARD, result[0].tier)
        assertEquals(GeometricDirection.LEFT, result[0].direction)
        assertEquals(100.0, result[0].turnAngleDegrees, 3.0)
        assertEquals(400.0, result[0].trackCumulativeDistanceMeters, 25.0)
        assertEquals(260.0, outgoingHeading(points, result[0]), 3.0, "cap vers l'ouest, jamais négatif")
    }

    @Test
    fun theFieldReportGeometryHasOneCheckpointAtTheRealTurnOnly() {
        val points = reliabilityTrack(listOf(400.0 to -102.0, 31.0 to 0.0, 0.0001 to 0.0, 239.0 to 0.0, 58.0 to 0.0, 302.0 to 0.0))
        points.add(2, points[2])
        val result = maneuvers(points)
        assertEquals(1, result.size, "plus de 'Virage fort' fantôme sur la ligne droite qui suit")
        assertEquals(400.0, result[0].trackCumulativeDistanceMeters, 1.0, "au vrai sommet, pas sur le point dupliqué 31 m plus loin")
        assertEquals(GeometricDirection.LEFT, result[0].direction)
        assertEquals(102.0, result[0].turnAngleDegrees, 5.0)
        assertEquals(GeometricTier.HARD, result[0].tier)
        assertEquals(258.0, outgoingHeading(points, result[0]), 5.0, "cap moyen après le virage, jamais le 0° d'un segment de 0 m")
    }

    @Test
    fun twoCloseSameSideTurnsMergeIntoOneWithTheNetAngle() {
        val result = maneuvers(reliabilityTrack(listOf(300.0 to 90.0, 35.0 to 90.0, 300.0 to 0.0), pointSpacing = 5.0))
        assertEquals(1, result.size)
        assertEquals(GeometricDirection.RIGHT, result[0].direction)
        assertEquals(GeometricTier.VERY_HARD, result[0].tier)
    }

    @Test
    fun aZigzagArtifactIsIgnored() {
        assertTrue(maneuvers(reliabilityTrack(listOf(300.0 to -40.0, 15.0 to 40.0, 300.0 to 0.0), pointSpacing = 5.0)).isEmpty())
    }

    @Test
    fun twoSeparatedTurnsAreBothKept() {
        val result = maneuvers(reliabilityTrack(listOf(300.0 to 0.0, 0.0001 to 0.0, 300.0 to 90.0, 500.0 to -90.0, 300.0 to 0.0), pointSpacing = 20.0))
        assertEquals(listOf(GeometricDirection.RIGHT, GeometricDirection.LEFT), result.map { it.direction })
        assertEquals(600.0, result[0].trackCumulativeDistanceMeters, 25.0)
        assertEquals(1100.0, result[1].trackCumulativeDistanceMeters, 25.0)
    }

    @Test
    fun noTurnLabelBelowTheMinimalAngle() {
        val wiggly = reliabilityTrack(List(60) { (if (it % 2 == 0) 12.0 else 18.0) to (if (it % 3 == 0) 14.0 else -9.0) })
        for (event in maneuvers(wiggly)) {
            if (event.tier != GeometricTier.U_TURN) {
                assertTrue(event.turnAngleDegrees >= RoadbookConstants.LIGHT_THRESHOLD_DEGREES_DEFAULT)
            }
        }
    }

    // MARK: fonctions élémentaires

    @Test
    fun signedAngleDifferenceIsNormalizedAndSigned() {
        assertEquals(20.0, RoadbookGeometry.signedAngleDifference(350.0, 10.0), 1e-12)
        assertEquals(-20.0, RoadbookGeometry.signedAngleDifference(10.0, 350.0), 1e-12)
        assertEquals(180.0, RoadbookGeometry.signedAngleDifference(0.0, 180.0), 1e-12)
    }

    @Test
    fun geodesicDistanceMatchesAKnownValue() {
        // 1° de longitude à l'équateur : 111 319,49 m (Vincenty ; CLLocation donne la même valeur à 10⁻⁷ m).
        assertEquals(111_319.490793, geodesicDistanceMeters(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-3)
        assertTrue(abs(geodesicDistanceMeters(LatLon(48.5, 7.5), LatLon(48.5001, 7.5002)) - 18.4962375) < 1e-6)
    }
}
