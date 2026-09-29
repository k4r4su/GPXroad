package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValhallaMapMatchingTest {
    private fun encode(coordinates: List<LatLon>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        fun value(v: Long) {
            var shifted = if (v < 0) (v shl 1).inv() else v shl 1
            while (shifted >= 0x20) {
                out.append(((0x20 or (shifted and 0x1f).toInt()) + 63).toChar())
                shifted = shifted shr 5
            }
            out.append((shifted.toInt() + 63).toChar())
        }
        for (c in coordinates) {
            val lat = (c.latitude * 1e6).roundToLong()
            val lon = (c.longitude * 1e6).roundToLong()
            value(lat - lastLat)
            value(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }

    @Test
    fun polyline6RoundTrip() {
        val original = listOf(LatLon(47.567081, 7.536442), LatLon(47.56630, 7.53283), LatLon(-33.868820, 151.209296), LatLon(0.0, -0.000001))
        val decoded = ValhallaMapMatching.decodePolyline6(encode(original))
        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (a, b) ->
            assertTrue(abs(a.latitude - b.latitude) < 1e-9 && abs(a.longitude - b.longitude) < 1e-9, "$a != $b")
        }
    }

    @Test
    fun keepsOnlyIntermediateDrivingDecisions() {
        val shape = (0..10).map { LatLon(47.0 + it * 0.001, 7.0) }
        val maneuvers = listOf(
            ValhallaManeuverRecord(ValhallaManeuverType.START.rawValue, 0, streetNames = listOf("Rue A")),
            ValhallaManeuverRecord(ValhallaManeuverType.CONTINUE_STRAIGHT.rawValue, 2, streetNames = listOf("Rue B")),
            ValhallaManeuverRecord(ValhallaManeuverType.RIGHT.rawValue, 4, streetNames = listOf("Rue C")),
            ValhallaManeuverRecord(ValhallaManeuverType.ROUNDABOUT_ENTER.rawValue, 6, roundaboutExitCount = 2),
            ValhallaManeuverRecord(ValhallaManeuverType.LEFT.rawValue, 99),
            ValhallaManeuverRecord(ValhallaManeuverType.DESTINATION.rawValue, 10),
        )
        val result = ValhallaMapMatching.matchedManeuvers(listOf(ValhallaLeg(maneuvers, shape)))
        assertEquals(listOf(ValhallaManeuverType.RIGHT, ValhallaManeuverType.ROUNDABOUT_ENTER), result.map { it.type })
        assertEquals(shape[4], result[0].coordinate)
        assertEquals(listOf("Rue B"), result[0].streetNamesBefore)
        assertEquals(listOf("Rue C"), result[0].streetNamesAfter)
        assertEquals(2, result[1].roundaboutExitCount)
        assertEquals(0.4, result[0].routeProgressFraction!!, 1e-6)
    }

    @Test
    fun downsamplesUniformlyKeepingBothEnds() {
        val points = (0 until 5000).toList()
        val sampled = ValhallaMapMatching.downsampled(points, 2000)
        assertEquals(2000, sampled.size)
        assertEquals(0, sampled.first())
        assertEquals(4999, sampled.last())
        assertEquals(points.take(10), ValhallaMapMatching.downsampled(points.take(10), 2000))
    }
}
