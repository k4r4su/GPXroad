package com.olivier.gpxroad.shared.roadbook

import kotlin.test.Test
import kotlin.test.assertEquals

/** Paliers du compte à rebours demandés par le propriétaire (29/09). */
class DistanceCountdownTest {
    private fun shown(meters: Double) = DistanceCountdown.steppedMeters(meters)

    @Test
    fun approachingATurnGoesThroughTheRequestedStepsOnly() {
        val shownValues = (2_000 downTo 0).map { shown(it.toDouble()) }.distinct()
        val expected = listOf(2_000.0, 1_500.0, 1_000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 150.0, 100.0) +
            (9 downTo 0).map { it * 10.0 }
        assertEquals(expected, shownValues)
    }

    @Test
    fun theShownDistanceIsNeverShorterThanTheRealOne() {
        assertEquals(1_500.0, shown(1_001.0))
        assertEquals(1_000.0, shown(1_000.0))
        assertEquals(300.0, shown(201.0))
        assertEquals(200.0, shown(200.0))
        assertEquals(150.0, shown(101.0))
        assertEquals(10.0, shown(0.4))
        assertEquals(0.0, shown(0.0))
        assertEquals(0.0, shown(-3.0))
    }

    @Test
    fun longDistancesUseHalfThenWholeKilometres() {
        assertEquals(2_500.0, shown(2_010.0))
        assertEquals(10_000.0, shown(9_990.0))
        assertEquals(12_000.0, shown(11_200.0))
    }

    @Test
    fun milesUseTenthsBelowOneMile() {
        assertEquals(0.5, DistanceCountdown.steppedMiles(0.42), 1e-9)
        assertEquals(1.5, DistanceCountdown.steppedMiles(1.2), 1e-9)
        assertEquals(12.0, DistanceCountdown.steppedMiles(11.1), 1e-9)
    }
}
