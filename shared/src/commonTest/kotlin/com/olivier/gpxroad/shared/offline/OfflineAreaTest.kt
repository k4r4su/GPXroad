package com.olivier.gpxroad.shared.offline

import com.olivier.gpxroad.shared.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineAreaTest {
    @Test
    fun tileMathLikeIOS() {
        assertEquals(Tile(0, 0, 0), OfflineArea.covering(47.5, 7.5, 0))
        // Ferrette au niveau 14 (tuile OSM de référence).
        assertEquals(Tile(14, 8524, 5729), OfflineArea.covering(47.49, 7.31, 14))
        val box = OfflineArea.boxAround(LatLon(47.5, 7.5), 5_000.0)
        assertEquals(OfflineArea.tiles(box, 12).size.toLong(), OfflineArea.tileCount(box, 12))
        assertNull(OfflineArea.circleTileCount(LatLon(47.5, 7.5), 200_000.0, 5, 16), "trop grand : plafond")
        assertTrue(OfflineArea.circleTileCount(LatLon(47.5, 7.5), 15_000.0, 5, 14)!! in 300..1_500)
    }

    @Test
    fun corridorAroundATrack() {
        // ~11 km plein nord : 45 carrés (un tous les ~256 m) de ±1 km, quelques dizaines de tuiles vectorielles du 10 au 14.
        val track = (0..1000).map { LatLon(47.0 + it * 0.0001, 7.0) }
        val boxes = OfflineArea.corridorBoxes(track)
        assertEquals(45, boxes.size)
        val count = OfflineArea.tileCount(boxes, 10, 14)
        assertTrue(count in 20..100, "$count")
        val ring = OfflineArea.circle(LatLon(47.0, 7.0), 1_000.0)
        assertEquals(37, ring.size)
        assertEquals(ring.first(), ring.last())
    }
}
