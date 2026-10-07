package com.olivier.gpxroad.shared.offline

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackPreparationTest {
    @Test
    fun readinessIgnoresWhatDoesNotApply() {
        assertEquals(ReadinessLevel.READY, TrackPreparation.evaluate(true, true, null, true).level)
        assertEquals(ReadinessLevel.READY, TrackPreparation.evaluate(true, null, null, null).level)
    }

    @Test
    fun readinessIsPartialOrNoneAndNamesWhatIsMissing() {
        val partial = TrackPreparation.evaluate(map = true, landmarks = false, routeMatch = true, roundabouts = false)
        assertEquals(ReadinessLevel.PARTIAL, partial.level)
        assertEquals(listOf(ReadinessPart.LANDMARKS, ReadinessPart.ROUNDABOUTS), partial.missing)
        val none = TrackPreparation.evaluate(map = false, landmarks = false, routeMatch = null, roundabouts = false)
        assertEquals(ReadinessLevel.NONE, none.level)
        assertEquals(3, none.missing.size)
    }

    // Trace de ~11 km vers l'est, de 47,5 N / 7,5 E.
    private val track = (0..100).map { LatLon(47.5, 7.5 + it * 0.0015) }
    private val cumulative = TrackGeometry.cumulativeDistances(track)

    @Test
    fun mapRingsCoverTheWholeTrack() {
        val rings = TrackPreparation.mapRings(track)
        assertTrue(rings.isNotEmpty())
        assertTrue(rings.all { it.size == 5 && it.first() == it.last() })
        assertNull(CoverageGap.distanceToGap(track, cumulative, 0.0, rings, lookAheadMeters = 50_000.0))
    }

    @Test
    fun noZoneMeansAnImmediateGap() {
        assertEquals(0.0, CoverageGap.distanceToGap(track, cumulative, 0.0, emptyList()))
    }

    @Test
    fun gapIsFoundAtTheEndOfACoveredStretch() {
        // Zone qui couvre les 4 premiers km seulement.
        val partial = TrackPreparation.mapRings(track.takeWhile { it.longitude < 7.5 + 0.0015 * 36 })
        val gap = CoverageGap.distanceToGap(track, cumulative, 0.0, partial)
        assertNotNull(gap)
        assertTrue(gap in 3_000.0..6_500.0, "trou vers 4-5 km, trouvé $gap")
        // Dans le trou même : distance nulle ; au-delà du regard (look-ahead) : rien.
        assertTrue((CoverageGap.distanceToGap(track, cumulative, 8_000.0, partial) ?: 1e9) < 150.0)
        assertNull(CoverageGap.distanceToGap(track, cumulative, 0.0, partial, lookAheadMeters = 1_000.0))
    }
}
