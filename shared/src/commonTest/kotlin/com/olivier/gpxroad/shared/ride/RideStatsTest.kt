package com.olivier.gpxroad.shared.ride

import com.olivier.gpxroad.shared.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RideStatsTest {
    @Test
    fun averageMaxAndArrival() {
        val stats = RideStats()
        // 10 fixs à 1 s d'intervalle, ~11,1 m chacun (40 km/h), puis 10 s d'arrêt (gigue ignorée).
        for (i in 0..10) stats.update(LatLon(47.0 + i * 0.0001, 7.0), 40.0, i.toDouble())
        for (i in 11..20) stats.update(LatLon(47.001 + (i % 2) * 0.00001, 7.0), 0.0, i.toDouble())
        assertEquals(40.0, stats.maxSpeedKmh)
        assertEquals(111.2, stats.traveledMeters, 0.5)
        assertEquals(20.0, stats.averageSpeedKmh, 0.1) // 111 m en 20 s
        val progress = stats.progress(10_000.0, 2_500.0)
        assertEquals(7_500.0, progress.remainingMeters)
        assertEquals(25.0, progress.percentComplete)
        // Vitesse moyenne des 5 dernières minutes : 11 × 40 / 21 fixs ≈ 20,95 km/h.
        assertEquals(7.5 / (440.0 / 21) * 3600, progress.remainingSeconds!!, 1.0)
        stats.reset()
        stats.update(LatLon(47.0, 7.0), 0.0, 0.0)
        assertNull(stats.progress(1000.0, 0.0).remainingSeconds)
    }
}
