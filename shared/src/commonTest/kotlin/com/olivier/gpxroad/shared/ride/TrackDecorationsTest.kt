package com.olivier.gpxroad.shared.ride

import com.olivier.gpxroad.shared.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackDecorationsTest {
    /** Ligne plein nord, un point tous les ~11,1 m. */
    private val north = (0..100).map { LatLon(47.0 + it * 0.0001, 7.0) }

    @Test
    fun chevronsEveryHundredMetersPointingNorth() {
        val chevrons = DirectionChevrons.chevrons(north, 100.0)
        assertEquals(11, chevrons.size) // ~1 112 m de trace
        assertTrue(chevrons.all { it.bearingDegrees < 0.01 || it.bearingDegrees > 359.99 })
        assertEquals(500.0, DirectionChevrons.adaptiveSpacingMeters(100.0, 12.5))
        assertEquals(200.0, DirectionChevrons.adaptiveSpacingMeters(200.0, 15.0))
        assertEquals(20_000.0, DirectionChevrons.adaptiveSpacingMeters(100.0, 3.0))
    }

    @Test
    fun slopeWarningsAreSpacedAndSigned() {
        // Montée régulière de 12 % sur toute la ligne : fenêtres de ~111 m, panneau tous les ≥ 300 m.
        val climbing = north.indices.map { it * 11.12 * 0.12 }
        val warnings = SlopeAnalyzer.steepGradeWarnings(north, climbing, 10.0)
        assertTrue(warnings.size in 3..4, "${warnings.size}")
        assertTrue(warnings.all { it.isClimbing && it.roundedPercent == 12 })
        assertTrue(SlopeAnalyzer.steepGradeWarnings(north, climbing, 15.0).isEmpty())
        val descending = climbing.map { -it }
        assertTrue(SlopeAnalyzer.steepGradeWarnings(north, descending, 10.0).all { !it.isClimbing })
        assertTrue(SlopeAnalyzer.steepGradeWarnings(north, north.map { null }, 10.0).isEmpty())
    }
}
