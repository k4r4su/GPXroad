package com.olivier.gpxroad.shared.offline

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPrefetchTest {
    private val here = LatLon(47.5, 7.5)
    private val radius = 15_000.0

    @Test
    fun refreshesOnlyWithGoodNetworkAfterMovingAndNotTooOften() {
        val now = 10_000_000L
        // Première fois : on télécharge dès que le réseau est bon.
        assertTrue(AutoPrefetch.shouldRefresh(here, null, radius, now, null, networkGood = true, busy = false))
        assertFalse(AutoPrefetch.shouldRefresh(here, null, radius, now, null, networkGood = false, busy = false), "réseau faible : jamais")
        assertFalse(AutoPrefetch.shouldRefresh(here, null, radius, now, null, networkGood = true, busy = true), "déjà en cours")
        // Presque sur place : pas de renouvellement ; 6 km plus loin (> 5 km = rayon / 3) : oui.
        val near = LatLon(47.51, 7.5) // ~1,1 km
        val far = LatLon(47.56, 7.5) // ~6,7 km
        assertFalse(AutoPrefetch.shouldRefresh(near, here, radius, now, now - 3_600_000, true, false))
        assertTrue(AutoPrefetch.shouldRefresh(far, here, radius, now, now - 3_600_000, true, false))
        // Même éloigné, jamais deux essais à moins de 10 minutes d'écart.
        assertFalse(AutoPrefetch.shouldRefresh(far, here, radius, now, now - 5 * 60_000, true, false))
        assertTrue(AutoPrefetch.shouldRefresh(far, here, radius, now, now - 11 * 60_000, true, false))
    }

    @Test
    fun zoneIsADiscPlusTheTrackAhead() {
        // Trace plein nord de ~40 km ; on est au km 10 : seuls les km 10 à 40 comptent.
        val track = (0..3600).map { LatLon(47.0 + it * 0.0001, 7.0) }
        val cumulative = TrackGeometry.cumulativeDistances(track)
        val ahead = AutoPrefetch.trackAhead(track, cumulative, 10_000.0)
        assertTrue(ahead.first().latitude > 47.08 && ahead.last().latitude < 47.37, "${ahead.first()} ${ahead.last()}")
        val rings = AutoPrefetch.rings(here, radius, ahead)
        assertEquals(37, rings.first().size)
        assertTrue(rings.size > 50, "un carré par ~250 m de trace : ${rings.size}")
        assertEquals(1, AutoPrefetch.rings(here, radius, emptyList()).size, "sans trace : le disque seul")
        // Poids raisonnable : disque de 15 km + 30 km de trace ≈ quelques centaines de tuiles.
        val boxes = rings.drop(1).map { r -> Box(r.minOf { it.latitude }, r.maxOf { it.latitude }, r.minOf { it.longitude }, r.maxOf { it.longitude }) }
        val total = OfflineArea.circleTileCount(here, radius, OfflineConstants.REGION_MIN_ZOOM, OfflineConstants.VECTOR_MAX_ZOOM)!! +
            OfflineArea.tileCount(boxes, OfflineConstants.CORRIDOR_MIN_ZOOM, OfflineConstants.VECTOR_MAX_ZOOM)
        assertTrue(total in 400..1_500, "$total tuiles")
    }
}
