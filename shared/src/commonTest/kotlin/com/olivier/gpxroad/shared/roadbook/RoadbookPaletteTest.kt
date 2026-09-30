package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.recording.IsoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoadbookPaletteTest {
    private val ferrette = LatLon(47.49, 7.31)

    @Test
    fun sunriseAndSunsetAtFerrette() {
        // 1er octobre à Ferrette : lever ~05:4x UTC, coucher ~17:1x UTC (formule approchée).
        val (sunrise, sunset) = SolarTime.sunriseSunset(IsoTime.parse("2026-10-01T12:00:00Z")!!, ferrette)!!
        assertEquals("2026-10-01T05:4", IsoTime.format(sunrise).take(15))
        assertEquals("2026-10-01T17:1", IsoTime.format(sunset).take(15))
        assertNull(SolarTime.sunriseSunset(IsoTime.parse("2026-12-21T12:00:00Z")!!, LatLon(80.0, 0.0)), "nuit polaire")
    }

    @Test
    fun paletteFollowsTheSunOrTheSetting() {
        val noon = IsoTime.parse("2026-10-01T12:00:00Z")!!
        val night = IsoTime.parse("2026-10-01T21:00:00Z")!!
        assertEquals(RoadbookPalette.PAPER, RoadbookPaletteResolver.resolve(RoadbookPaletteSetting.AUTOMATIC, noon, ferrette, 14))
        assertEquals(RoadbookPalette.NIGHT, RoadbookPaletteResolver.resolve(RoadbookPaletteSetting.AUTOMATIC, night, ferrette, 23))
        assertEquals(RoadbookPalette.NIGHT, RoadbookPaletteResolver.resolve(RoadbookPaletteSetting.NIGHT, noon, ferrette, 14))
        assertEquals(RoadbookPalette.PAPER, RoadbookPaletteResolver.resolve(RoadbookPaletteSetting.AUTOMATIC, night, null, 10), "sans position : l'heure locale")
    }
}
