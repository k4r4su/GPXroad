package com.olivier.gpxroad.android.roadbook.data

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.roadbook.LandmarkOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Décodage Overpass du Road Book Android sur de VRAIES réponses (instance publique, 29/09, autour de
 * Saint-Louis / Hégenheim) — mêmes règles que `RoadbookRoundaboutOverpass.parse` et
 * `RoadbookLandmarkOverpassService.parse` iOS.
 */
class RoadbookOverpassTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResource("/overpass/$name")) { name }.readBytes()

    @Test
    fun decodesRoundaboutRingsAndConnectedRoads() {
        val data = assertNotNull(RoadbookOverpass.parseRoundabouts(fixture("roundabouts-saint-louis.json")))
        assertEquals(16, data.roads.size)
        assertEquals(7, data.roads.count { it.junction == "roundabout" })
        assertTrue(data.roads.all { it.geometry.size == it.nodeIds.size && it.nodeIds.size > 1 })
        assertTrue(data.miniRoundabouts.isEmpty())
        assertNull(RoadbookOverpass.parseRoundabouts("<html>504</html>".toByteArray()))
    }

    @Test
    fun decodesLandmarksWithSignDirectionRoadAxesAndPlaces() {
        val data = assertNotNull(RoadbookOverpass.parseLandmarks(fixture("landmarks-saint-louis.json")))
        // Stop orienté `direction=forward` : sens résolu sur sa route porteuse, axes connus.
        val stop = data.candidates.first { it.osmId == "node/281352770" }
        assertEquals(LandmarkCategory.STOP_SIGN, stop.candidate.category)
        assertTrue(stop.candidate.orientation is LandmarkOrientation.AppliesToTravelBearing)
        assertTrue(stop.candidate.roadAxes!!.isNotEmpty())
        // Église (chemin résumé par son centre) : nom OSM gardé, pas d'axe de chaussée.
        val church = data.candidates.first { it.osmId == "way/46581144" }
        assertEquals(LandmarkCategory.CHURCH, church.candidate.category)
        assertEquals("Église Saint-Rémy", church.candidate.label)
        assertNull(church.candidate.roadAxes)
        // Repli « Entrée de » : localités nommées et zones bâties.
        assertEquals(
            setOf("Saint-Louis", "Hégenheim", "Basel", "Buschwiller", "Hésingue", "Binningen", "Allschwil", "Bourgfelden", "Iselin"),
            data.places.map { it.place.name }.toSet(),
        )
        assertTrue(data.areas.isNotEmpty() && data.areas.all { area -> area.area.rings.all { it.size >= 3 } })
        // Les routes porteuses (géométrie complète) ne sont jamais des candidats.
        assertTrue(data.candidates.all { it.osmId != null })
    }

    @Test
    fun mergingChunksKeepsEachOsmElementOnce() {
        val data = assertNotNull(RoadbookOverpass.parseLandmarks(fixture("landmarks-saint-louis.json")))
        val merged = data.adding(data, setOf(LandmarkCategory.STOP_SIGN))
        assertEquals(data.candidates.size, merged.candidates.size)
        assertEquals(data.places.size, merged.places.size)
        assertEquals(setOf(LandmarkCategory.STOP_SIGN), merged.fetchedCategories)
        val withoutCityEntries = data.filtered(setOf(LandmarkCategory.STOP_SIGN))
        assertTrue(withoutCityEntries.candidates.all { it.candidate.category == LandmarkCategory.STOP_SIGN })
        assertTrue(withoutCityEntries.places.isEmpty() && withoutCityEntries.areas.isEmpty())
    }

    @Test
    fun chunksShareTheirJunctionPointAndCoverTheTrack() {
        val points = (0..400).map { LatLon(47.0 + it * 0.0005, 7.0) } // ~22 km plein nord
        val chunks = RoadbookOverpass.chunks(points)
        assertEquals(3, chunks.size)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.last(), b.first()) }
        assertEquals(points.first(), chunks.first().first())
        assertEquals(points.last(), chunks.last().last())
    }

    @Test
    fun queriesMatchTheIphoneFormat() {
        val points = (0..40).map { LatLon(47.5 + it * 0.0005, 7.5) }
        val roundabout = assertNotNull(RoadbookOverpass.roundaboutQuery(points))
        assertTrue(roundabout.startsWith("[out:json][timeout:90];\nway[\"highway\"][\"junction\"~\"^(roundabout|circular)$\"](around:"))
        assertTrue(roundabout.contains("47.500000,7.500000"))
        val landmarks = assertNotNull(RoadbookOverpass.landmarkQuery(points, setOf(LandmarkCategory.CITY_SIGN, LandmarkCategory.CHURCH)))
        assertTrue(landmarks.startsWith("[out:json][timeout:90];\n(\n  node[\"traffic_sign\"~\"city_limit|FR:EB10|DE:310\",i](around:"))
        assertTrue(landmarks.contains(")->.candidates;\n.candidates out tags center;\n(\n  node.candidates[\"highway\"];"))
        assertTrue(landmarks.contains("[\"landuse\"=\"residential\"]") && landmarks.contains("out body;"))
        assertNull(RoadbookOverpass.landmarkQuery(points, emptySet()))
    }
}
