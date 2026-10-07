package com.olivier.gpxroad.android

import com.olivier.gpxroad.android.plan.TrackAccessChecker
import com.olivier.gpxroad.shared.plan.AccessVerdict
import com.olivier.gpxroad.shared.plan.PlanVehicle
import com.olivier.gpxroad.shared.plan.TrackAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vraies réponses : `trace_attributes` (Valhalla, Vosges, moto) et étiquettes OSM de ces chemins (Overpass). */
class TrackAccessCheckerTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/valhalla/$name")!!.readBytes()
    private val attributes = TrackAccessChecker.parseAttributes(resource("trace-attributes-vosges.json"))
    private val tags = TrackAccessChecker.parseWayTags(resource("overpass-way-tags-vosges.json"))!!

    @Test
    fun edgesAreDecodedWithTheirOsmWayAndShape() {
        val (edges, shape) = attributes
        assertEquals(9, edges.size)
        assertTrue("des pistes ET des routes", edges.any { it.use == "track" } && edges.any { it.use == "road" })
        assertTrue("longueur en mètres (Valhalla donne des km)", edges.all { it.lengthMeters in 0.0..20_000.0 } && edges.any { it.lengthMeters > 100 })
        assertTrue("indices dans la forme", edges.all { it.endShapeIndex < shape.size })
    }

    @Test
    fun wayTagsAreReadByOsmId() {
        assertEquals("destination", tags.getValue(50316888L)["motor_vehicle"])
        assertEquals("track", tags.getValue(204998511L)["highway"])
    }

    @Test
    fun realTracksAreFlaggedNeverCalledAllowed() {
        val segments = TrackAccess.segments(attributes.first, tags, PlanVehicle.MOTORCYCLE)
        assertTrue("des portions signalées", segments.isNotEmpty())
        assertTrue("jamais « OK » dans une liste de signalements", segments.none { it.verdict == AccessVerdict.OK })
        // Le Neuer Kastelbergweg est étiqueté « destination » : accès riverains.
        assertTrue(segments.any { it.verdict == AccessVerdict.RESTRICTED && it.name == "Neuer Kastelbergweg" })
        // Les pistes sans aucune étiquette d'accès sont « à vérifier ».
        assertTrue(segments.any { it.verdict == AccessVerdict.TO_VERIFY })
    }

    @Test
    fun withoutOverpassEveryTrackIsToVerify() {
        val segments = TrackAccess.segments(attributes.first, emptyMap(), PlanVehicle.MOTORCYCLE)
        assertTrue(segments.isNotEmpty() && segments.all { it.verdict == AccessVerdict.TO_VERIFY })
    }

    @Test
    fun onlyUnpavedWaysAreLookedUp() {
        val ids = TrackAccess.checkedWayIds(attributes.first)
        assertEquals("exactement les chemins dont Overpass a renvoyé les étiquettes", tags.keys, ids.toSet())
    }
}
