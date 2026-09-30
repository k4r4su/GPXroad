package com.olivier.gpxroad.shared.recording

import com.olivier.gpxroad.shared.gpx.GpxParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingTest {
    private fun point(lat: Double, seconds: Long, ele: Double? = null) = RecordedPoint(lat, 7.0, ele, 1_790_000_000_000 + seconds * 1000)

    @Test
    fun densityPresetsMatchIOS() {
        assertEquals(listOf(5 to 15, 10 to 30, 20 to 60, 40 to 120), RecordingDensity.entries.map { it.minIntervalSeconds to it.minDistanceMeters })
    }

    @Test
    fun aPointIsKeptWhenEitherThresholdIsReached() {
        val first = point(47.0, 0)
        assertTrue(RecordingSampler.shouldRecord(null, first, RecordingDensity.PRECIS), "toujours le premier point")
        assertFalse(RecordingSampler.shouldRecord(first, point(47.0001, 2), RecordingDensity.PRECIS), "11 m en 2 s : rien")
        assertTrue(RecordingSampler.shouldRecord(first, point(47.0002, 2), RecordingDensity.PRECIS), "22 m : distance atteinte")
        assertTrue(RecordingSampler.shouldRecord(first, point(47.0, 5), RecordingDensity.PRECIS), "5 s à l'arrêt : intervalle atteint")
        assertFalse(RecordingSampler.shouldRecord(first, point(47.0002, 5), RecordingDensity.ULTRA_LEGER), "ultra léger : 40 s ou 120 m")
    }

    @Test
    fun isoTimeIsUtcToTheSecond() {
        assertEquals("1970-01-01T00:00:00Z", IsoTime.format(0))
        assertEquals("2026-09-28T08:31:05Z", IsoTime.format(1_790_584_265_000))
        assertEquals("2000-02-29T23:59:59Z", IsoTime.format(951_868_799_999))
        assertEquals("1969-12-31T23:59:59Z", IsoTime.format(-1))
    }

    @Test
    fun writtenGpxIsReadBackIdentically() {
        val points = listOf(point(47.123456789, 0, 312.5), point(47.1236, 6), RecordedPoint(0.00001, -0.00002, null, 0))
        val gpx = GpxWriter.write("Sortie <test> & co", points, "Beau temps \"sec\"")
        assertTrue(gpx.contains("<desc>Beau temps &quot;sec&quot;</desc>"))
        assertFalse(gpx.contains("E-"), "jamais de notation scientifique : $gpx")
        val doc = GpxParser.parse(gpx)
        assertEquals("Sortie <test> & co", doc.name)
        assertEquals(3, doc.points.size)
        assertEquals(47.123456789, doc.points[0].latitude)
        assertEquals(312.5, doc.points[0].elevation)
        assertEquals(IsoTime.format(points[1].timeMillis), doc.points[1].timeIso)
        assertEquals(0.00001, doc.points[2].latitude)
        assertEquals(-0.00002, doc.points[2].longitude)
        assertEquals(0.0, RecordingSampler.lengthMeters(points.take(1)))
    }
}
