package com.olivier.gpxroad.shared.plan

import com.olivier.gpxroad.shared.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePlannerTest {
    private val a = LatLon(47.5, 7.5)
    private val b = LatLon(47.6, 7.6)
    private val c = LatLon(47.7, 7.5)

    @Test
    fun defaultsAreFunRoadsOnAMotorcycle() {
        val body = RoutePlanner.requestBody(listOf(a, b, c), PlanOptions())
        assertTrue(""""costing":"motorcycle"""" in body)
        assertTrue(""""use_highways":0.0""" in body, "autoroutes évitées par défaut")
        assertTrue(""""use_tolls":0.0""" in body)
        assertTrue(""""use_ferry":0.0""" in body)
        assertTrue(""""use_trails":0.0""" in body, "pistes interdites par défaut")
        // Les trois points, dans l'ordre, tous en arrêt.
        assertEquals(3, Regex(""""type":"break"""").findAll(body).count())
        assertTrue(body.indexOf("47.5") < body.indexOf("47.6") && body.indexOf("47.6") < body.indexOf("47.7"))
    }

    @Test
    fun optionsChangeTheCostingOptions() {
        val relaxed = RoutePlanner.requestBody(listOf(a, b), PlanOptions(PlanVehicle.CAR, avoidHighways = false, avoidTolls = false, allowTracks = true))
        assertTrue(""""costing":"auto"""" in relaxed)
        assertTrue(""""use_highways":0.5""" in relaxed)
        assertTrue(""""use_tracks":0.5""" in relaxed)
        val bike = RoutePlanner.requestBody(listOf(a, b), PlanOptions(PlanVehicle.BICYCLE))
        assertTrue(""""costing":"bicycle"""" in bike)
        assertFalse("use_highways" in bike, "sans objet à vélo")
        val trails = RoutePlanner.requestBody(listOf(a, b), PlanOptions(allowTracks = true))
        assertTrue(""""use_trails":0.6""" in trails)
    }

    @Test
    fun legsAreJoinedWithoutDuplicatedJunctions() {
        val merged = RoutePlanner.mergeLegs(listOf(listOf(a, b), listOf(b, c)))
        assertEquals(listOf(a, b, c), merged)
        assertEquals(emptyList(), RoutePlanner.mergeLegs(emptyList()))
    }

    @Test
    fun lengthIsTheGeodesicSum() {
        val km = RoutePlanner.lengthMeters(listOf(a, b)) / 1000
        assertTrue(km in 13.0..14.5, "≈ 13,6 km, trouvé $km")
        assertEquals(0.0, RoutePlanner.lengthMeters(listOf(a)))
    }

    @Test
    fun pointsAreRejectedWhenTooCloseOrTooMany() {
        assertTrue(RoutePlanner.canAdd(emptyList(), a))
        assertFalse(RoutePlanner.canAdd(listOf(a), LatLon(47.50005, 7.5)), "à 5 m du précédent")
        assertTrue(RoutePlanner.canAdd(listOf(a), b))
        val many = (0 until RoutePlanner.MAX_WAYPOINTS).map { LatLon(47.0 + it * 0.01, 7.0) }
        assertFalse(RoutePlanner.canAdd(many, LatLon(48.0, 7.0)))
    }
}

class RouteGpxTest {
    @Test
    fun writesATimelessTrackThatTheParserReads() {
        val gpx = com.olivier.gpxroad.shared.recording.GpxWriter.writeRoute("Col & lac <test>", listOf(LatLon(47.5, 7.5), LatLon(47.51, 7.52)), "Itinéraire créé")
        assertTrue("<name>Col &amp; lac &lt;test&gt;</name>" in gpx)
        assertFalse("<time>" in gpx, "pas d'heure inventée")
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        val parsed = com.olivier.gpxroad.shared.gpx.GpxParser.parse(gpx)
        assertEquals(2, parsed.points.size)
        assertEquals("Col & lac <test>", parsed.name)
    }
}
