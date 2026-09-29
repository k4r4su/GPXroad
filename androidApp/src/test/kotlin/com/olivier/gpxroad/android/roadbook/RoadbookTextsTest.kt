package com.olivier.gpxroad.android.roadbook

import com.olivier.gpxroad.android.data.DistanceUnit
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Distances du Road Book Android : mêmes chaînes que l'iPhone (`DistanceUnit` iOS). */
class RoadbookTextsTest {
    private val previous = Locale.getDefault()

    @BeforeTest
    fun french() = Locale.setDefault(Locale.FRANCE)

    @AfterTest
    fun restore() = Locale.setDefault(previous)

    @Test
    fun fixedDistances() {
        assertEquals("587 m", RoadbookTexts.distance(587.2, DistanceUnit.KM))
        assertEquals("1,5 km", RoadbookTexts.distance(1_480.0, DistanceUnit.KM))
        assertEquals("2,0 km", RoadbookTexts.distance(2_000.0, DistanceUnit.KM))
        assertEquals("0,4 mi", RoadbookTexts.distance(600.0, DistanceUnit.MI))
    }

    @Test
    fun countdownUsesTheSharedSteps() {
        assertEquals("2 km", RoadbookTexts.countdown(1_700.0, DistanceUnit.KM))
        assertEquals("1,5 km", RoadbookTexts.countdown(1_200.0, DistanceUnit.KM))
        assertEquals("900 m", RoadbookTexts.countdown(801.0, DistanceUnit.KM))
        assertEquals("150 m", RoadbookTexts.countdown(101.0, DistanceUnit.KM))
        assertEquals("10 m", RoadbookTexts.countdown(3.0, DistanceUnit.KM))
        assertEquals("0 m", RoadbookTexts.countdown(0.0, DistanceUnit.KM))
        assertEquals("1,5 mi", RoadbookTexts.countdown(2_000.0, DistanceUnit.MI))
    }
}
