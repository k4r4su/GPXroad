package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTrackMatcherTest {
    /** Aller-retour sur la même route : 1 km vers le nord, puis retour (écart de 5 m). */
    private val points = (0..100).map { LatLon(47.0 + it * 0.00009, 7.0) } + (100 downTo 0).map { LatLon(47.0 + it * 0.00009, 7.00007) }
    private val cumulative = TrackGeometry.cumulativeDistances(points)

    @Test
    fun followsTheOutboundLegThenTheReturnLegWithoutJumping() {
        val matcher = LiveTrackMatcher()
        val first = matcher.update(LatLon(47.0045, 7.0), points, cumulative)
        assertEquals(500.0, assertNotNull(first.cumulativeDistanceMeters), 10.0)
        assertFalse(first.isOffTrack)
        // Au bout, puis sur le retour : l'avancement continue au-delà de 1 km, jamais un retour à 500 m.
        matcher.update(LatLon(47.0090, 7.00003), points, cumulative)
        val back = matcher.update(LatLon(47.0045, 7.00007), points, cumulative)
        assertEquals(1500.0, assertNotNull(back.cumulativeDistanceMeters), 15.0)
    }

    @Test
    fun offTrackUsesTheSharedHysteresisAndFreezesProgress() {
        val matcher = LiveTrackMatcher()
        matcher.update(LatLon(47.0045, 7.0), points, cumulative)
        val away = matcher.update(LatLon(47.0050, 7.0006), points, cumulative) // ~45 m à l'est
        assertTrue(away.isOffTrack)
        assertEquals(500.0, assertNotNull(away.cumulativeDistanceMeters), 10.0, "avancement figé hors trace")
        val between = matcher.update(LatLon(47.0050, 7.00043), points, cumulative) // ~28 m : entre 25 et 30
        assertTrue(between.isOffTrack, "hystérésis : pas de retour avant 25 m")
        assertFalse(matcher.update(LatLon(47.0050, 7.00007), points, cumulative).isOffTrack)
    }

    @Test
    fun farFromTheTrackFromTheStart() {
        val far = LiveTrackMatcher().update(LatLon(46.9, 7.0), points, cumulative)
        assertTrue(far.isOffTrack)
        assertNull(far.cumulativeDistanceMeters)
    }
}
