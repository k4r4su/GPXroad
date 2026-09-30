package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Priorité au virage sur les repères de décor (retour terrain du 30/09, Ferrette). */
class RoadbookFocusRuleTest {
    private fun landmark(at: Double, category: LandmarkCategory) =
        RoadbookEntry.Landmark(LandmarkCheckpoint(LandmarkInfo(category, category.genericLabel), LatLon(47.0, 7.0), at))

    private fun turn(at: Double, index: Int = 0) = RoadbookEntry.Maneuver(
        RoadbookManeuver(Checkpoint(LatLon(47.0, 7.0), 90.0, TurnDirection.LEFT, RoadbookTier.HARD, 1, 0, trackCumulativeDistanceMeters = at), at, at, 0.0),
        index,
    )

    private fun focus(entries: List<RoadbookEntry>, position: Double, speed: Double? = null) =
        RoadbookFocusRule.focus(entries, RoadbookLiveProgress.next(entries.map { it.cumulativeDistanceMeters }, position), position, speed)

    /** Ferrette : église (4 449 m), borne (4 508 m), station (4 604 m), virage (4 607 m). */
    private val ferrette = listOf(
        landmark(4_449.0, LandmarkCategory.CHURCH),
        landmark(4_508.0, LandmarkCategory.CHARGING_STATION),
        landmark(4_604.0, LandmarkCategory.FUEL),
        turn(4_607.0),
    )

    @Test
    fun theTurnTakesTheBigCardAndTheChurchBecomesItsLandmark() {
        val f = focus(ferrette, 4_400.0)!!
        assertEquals(3, f.heroIndex, "le virage")
        assertEquals(207.0, f.distanceRemainingMeters, 1e-6)
        assertEquals(0, f.leadingLandmarkIndex, "l'église, repère le plus proche")
        assertEquals(49.0, f.leadingLandmarkDistanceMeters!!, 1e-6)
    }

    @Test
    fun aDistantTurnLeavesTheLandmarkAlone() {
        val f = focus(ferrette, 4_000.0)!!
        assertEquals(0, f.heroIndex, "virage à 607 m : l'église garde sa carte")
        assertNull(f.leadingLandmarkIndex)
    }

    @Test
    fun theWindowGrowsWithSpeed() {
        // 90 km/h = 25 m/s → 15 s = 375 m.
        assertEquals(3, focus(ferrette, 4_250.0, speed = 25.0)!!.heroIndex, "virage à 357 m, sous 375 m")
        assertEquals(0, focus(ferrette, 4_250.0, speed = 10.0)!!.heroIndex, "à 36 km/h, 300 m seulement")
    }

    @Test
    fun actionLandmarksKeepTheirCard() {
        val withStop = listOf(landmark(1_000.0, LandmarkCategory.STOP_SIGN), turn(1_050.0))
        assertEquals(0, focus(withStop, 900.0)!!.heroIndex, "un stop demande une action")
        val stopBetween = listOf(landmark(1_000.0, LandmarkCategory.CHURCH), landmark(1_020.0, LandmarkCategory.GIVE_WAY_SIGN), turn(1_050.0))
        assertEquals(0, focus(stopBetween, 900.0)!!.heroIndex, "un cédez-le-passage entre les deux : ordre normal")
        assertFalse(RoadbookFocusRule.isDecor(LandmarkCategory.CITY_SIGN))
        assertFalse(RoadbookFocusRule.isDecor(LandmarkCategory.SPEED_BUMP))
        assertTrue(RoadbookFocusRule.isDecor(LandmarkCategory.BRIDGE))
    }

    @Test
    fun aTurnStaysATurn() {
        val entries = listOf(turn(500.0), landmark(520.0, LandmarkCategory.CHURCH))
        assertEquals(RoadbookFocus(0, 100.0), focus(entries, 400.0))
        assertNull(focus(emptyList(), 0.0))
    }
}
