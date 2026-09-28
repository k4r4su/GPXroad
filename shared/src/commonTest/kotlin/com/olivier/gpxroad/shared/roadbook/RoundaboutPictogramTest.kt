package com.olivier.gpxroad.shared.roadbook

import kotlin.test.Test
import kotlin.test.assertEquals

class RoundaboutPictogramTest {
    @Test
    fun theExitIsDrawnWhereTheTraceReallyLeaves() {
        assertEquals(90.0, RoundaboutPictogram.layout(90.0, 1).exitAngleDegrees, "à droite")
        assertEquals(0.0, RoundaboutPictogram.layout(0.0, 2).exitAngleDegrees, "tout droit, en haut")
        assertEquals(-90.0, RoundaboutPictogram.layout(-90.0, 3).exitAngleDegrees, "à gauche")
        assertEquals(-160.0, RoundaboutPictogram.layout(178.0, 4).exitAngleDegrees, "demi-tour : juste à gauche de l'entrée")
        assertEquals(-160.0, RoundaboutPictogram.layout(-179.0, 4).exitAngleDegrees)
    }

    @Test
    fun thePathTurnsCounterClockwiseLikeTheTraffic() {
        assertEquals(90.0, RoundaboutPictogram.layout(90.0, 1).pathSweepDegrees, "1re sortie à droite : quart de tour")
        assertEquals(180.0, RoundaboutPictogram.layout(0.0, 2).pathSweepDegrees)
        assertEquals(270.0, RoundaboutPictogram.layout(-90.0, 3).pathSweepDegrees, "à gauche : trois quarts du tour")
    }

    @Test
    fun skippedExitsAreSpreadAlongThePath() {
        assertEquals(listOf(), RoundaboutPictogram.layout(90.0, 1).intermediateExitAngles)
        assertEquals(listOf(90.0), RoundaboutPictogram.layout(0.0, 2).intermediateExitAngles, "2e sortie tout droit : une sortie passée à droite")
        assertEquals(listOf(90.0, 0.0), RoundaboutPictogram.layout(-90.0, 3).intermediateExitAngles)
        assertEquals(listOf(), RoundaboutPictogram.layout(0.0, null).intermediateExitAngles, "rang inconnu : aucune sortie inventée")
    }
}
