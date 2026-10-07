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

class TrackAccessTest {
    private fun edge(way: Long, use: String = "track", unpaved: Boolean = true, length: Double = 100.0, begin: Int = 0, end: Int = 1, name: String? = null) =
        AccessEdge(way, use, unpaved, length, begin, end, name)

    private val moto = PlanVehicle.MOTORCYCLE

    @Test
    fun explicitAccessDecidesAndMostSpecificKeyWins() {
        assertEquals(AccessVerdict.OK, TrackAccess.classify(mapOf("highway" to "track", "motorcycle" to "yes", "access" to "no"), moto), "motorcycle=yes prime sur access=no")
        assertEquals(AccessVerdict.FORBIDDEN, TrackAccess.classify(mapOf("highway" to "track", "motor_vehicle" to "no"), moto))
        assertEquals(AccessVerdict.FORBIDDEN, TrackAccess.classify(mapOf("highway" to "track", "access" to "private"), moto))
        assertEquals(AccessVerdict.FORBIDDEN, TrackAccess.classify(mapOf("highway" to "track", "motor_vehicle" to "forestry"), moto))
        // Vraie étiquette relevée dans les Vosges : accès « destination » (riverains).
        assertEquals(AccessVerdict.RESTRICTED, TrackAccess.classify(mapOf("highway" to "track", "foot" to "yes", "motor_vehicle" to "destination"), moto))
    }

    @Test
    fun aTrackWithoutAnyAccessTagIsNeverSaidToBeAllowed() {
        assertEquals(AccessVerdict.TO_VERIFY, TrackAccess.classify(mapOf("highway" to "track", "surface" to "gravel", "tracktype" to "grade2"), moto))
        assertEquals(AccessVerdict.TO_VERIFY, TrackAccess.classify(mapOf("highway" to "track"), PlanVehicle.CAR))
    }

    @Test
    fun footPathsAreForbiddenToMotorsButNotToBicycles() {
        assertEquals(AccessVerdict.FORBIDDEN, TrackAccess.classify(mapOf("highway" to "path"), moto))
        assertEquals(AccessVerdict.FORBIDDEN, TrackAccess.classify(mapOf("highway" to "footway"), PlanVehicle.CAR))
        assertEquals(AccessVerdict.OK, TrackAccess.classify(mapOf("highway" to "path"), PlanVehicle.BICYCLE))
        assertEquals(AccessVerdict.TO_VERIFY, TrackAccess.classify(mapOf("highway" to "footway"), PlanVehicle.BICYCLE))
        assertEquals(AccessVerdict.FORBIDDEN, TrackAccess.classify(mapOf("highway" to "track", "bicycle" to "no"), PlanVehicle.BICYCLE))
    }

    @Test
    fun ordinaryRoadsAreOk() {
        assertEquals(AccessVerdict.OK, TrackAccess.classify(mapOf("highway" to "secondary"), moto))
        assertEquals(AccessVerdict.OK, TrackAccess.classify(mapOf("highway" to "service"), moto))
    }

    @Test
    fun onlyEdgesWorthCheckingAreLookedUp() {
        val edges = listOf(edge(1, use = "road", unpaved = false), edge(2), edge(2), edge(3, use = "road", unpaved = true), edge(4, use = "service_road", unpaved = false))
        assertEquals(listOf(2L, 3L, 4L), TrackAccess.checkedWayIds(edges), "route goudronnée ignorée, doublons retirés")
        assertEquals(TrackAccess.MAX_WAYS_PER_CHECK, TrackAccess.checkedWayIds((1L..1000L).map { edge(it) }).size)
    }

    @Test
    fun consecutiveEdgesWithTheSameVerdictAreMergedIntoOneSegment() {
        val edges = listOf(
            edge(10, use = "road", unpaved = false, length = 500.0, begin = 0, end = 5),
            edge(11, length = 300.0, begin = 5, end = 9, name = "Chemin du Sattel"),
            edge(12, length = 200.0, begin = 9, end = 12),
            edge(13, use = "road", unpaved = false, length = 400.0, begin = 12, end = 20),
            edge(14, length = 150.0, begin = 20, end = 25),
        )
        val tags = mapOf(
            11L to mapOf("highway" to "track", "motor_vehicle" to "destination"),
            12L to mapOf("highway" to "track", "motor_vehicle" to "destination"),
            14L to mapOf("highway" to "track", "motorcycle" to "no"),
        )
        val segments = TrackAccess.segments(edges, tags, moto)
        assertEquals(2, segments.size)
        assertEquals(AccessSegment(AccessVerdict.RESTRICTED, listOf(11, 12), "Chemin du Sattel", 500.0, 5, 12), segments[0])
        assertEquals(AccessVerdict.FORBIDDEN, segments[1].verdict)
        assertEquals(20, segments[1].beginShapeIndex)
    }

    @Test
    fun unreadableTagsMeanToVerifyNeverAllowed() {
        val segments = TrackAccess.segments(listOf(edge(7, length = 800.0)), emptyMap(), moto)
        assertEquals(AccessVerdict.TO_VERIFY, segments.single().verdict)
    }

    @Test
    fun excludedLocationsAreAddedToTheRequest() {
        val a = LatLon(47.5, 7.5)
        val b = LatLon(47.6, 7.6)
        val body = RoutePlanner.requestBody(listOf(a, b), PlanOptions(), listOf(LatLon(47.55, 7.55)))
        assertTrue(""""exclude_locations":[{"lat":47.55,"lon":7.55}]""" in body)
        assertFalse("exclude_locations" in RoutePlanner.requestBody(listOf(a, b), PlanOptions()))
        val many = (0 until 80).map { LatLon(47.0 + it * 0.001, 7.0) }
        assertEquals(RoutePlanner.MAX_EXCLUDED_LOCATIONS, Regex(""""lat":""").findAll(RoutePlanner.requestBody(listOf(a, b), PlanOptions(), many).substringAfter("exclude_locations")).count())
    }

    @Test
    fun attributesRequestFollowsTheRouteShapeAsIs() {
        val body = RoutePlanner.attributesRequestBody("abc\\def\"", PlanVehicle.MOTORCYCLE)
        assertTrue(""""shape_match":"edge_walk"""" in body)
        assertTrue(""""costing":"motorcycle"""" in body)
        assertTrue("""abc\\def\"""" in body, "caractères spéciaux de la polyline échappés")
        assertTrue(""""edge.way_id"""" in body && """"shape"]""" in body)
    }
}
