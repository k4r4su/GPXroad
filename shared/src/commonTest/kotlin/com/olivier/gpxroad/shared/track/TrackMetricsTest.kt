package com.olivier.gpxroad.shared.track

import com.olivier.gpxroad.shared.gpx.GpxPoint
import com.olivier.gpxroad.shared.recording.IsoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackMetricsTest {
    @Test
    fun isoDatesAreReadBack() {
        assertEquals(1_790_584_265_000, IsoTime.parse("2026-09-28T08:31:05Z"))
        assertEquals(1_790_584_265_250, IsoTime.parse("2026-09-28T10:31:05.25+02:00"))
        assertEquals(951_868_799_000, IsoTime.parse("2000-02-29T23:59:59Z"))
        assertEquals(0, IsoTime.parse("1970-01-01T00:00:00"))
        assertNull(IsoTime.parse("hier"))
        for (millis in listOf(0L, 1_790_584_265_000, 4_102_444_800_000)) assertEquals(millis, IsoTime.parse(IsoTime.format(millis)))
    }

    private fun point(lat: Double, ele: Double?, seconds: Long?) =
        GpxPoint(lat, 7.0, ele, seconds?.let { IsoTime.format(1_790_000_000_000 + it * 1000) })

    @Test
    fun metricsLikeIOS() {
        // 3 × ~111 m à 10 s d'intervalle (40 km/h), puis 60 s d'arrêt, montée de 10 m sur le 2e segment.
        val points = listOf(point(47.0, 300.0, 0), point(47.001, 300.0, 10), point(47.002, 310.0, 20), point(47.003, 305.0, 30), point(47.003, 305.0, 90))
        val m = TrackMetricsCalculator.compute(points)!!
        assertEquals(90.0, m.durationSeconds)
        assertEquals(30.0, m.movingDurationSeconds, "l'arrêt ne compte pas")
        assertEquals(40.0, m.maxSpeedKmh, 0.2)
        assertEquals(10.0, m.elevationGainMeters)
        assertEquals(5.0, m.elevationLossMeters)
        assertEquals(300.0, m.minElevationMeters)
        assertEquals(310.0, m.maxElevationMeters)
        assertEquals(9.0, m.maxGradePercent, 0.1)
        assertEquals(40.0, m.averageMovingSpeedKmh, 0.2)
        assertEquals(13.3, m.averageSpeedKmh, 0.1)
    }

    @Test
    fun noRealTimesNoMetrics() {
        assertNull(TrackMetricsCalculator.compute(listOf(point(47.0, 1.0, null), point(47.1, 2.0, null))))
        val summary = TrackMetricsCalculator.summary(listOf(point(47.0, 100.0, null), point(47.001, 120.0, null), point(47.002, 110.0, null)))
        assertEquals(20.0, summary.elevationGainMeters)
        assertEquals(222.4, summary.lengthMeters, 0.5)
    }
}
