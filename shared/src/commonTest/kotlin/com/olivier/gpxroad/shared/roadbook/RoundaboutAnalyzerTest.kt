package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * It34 — ronds-points analysés sur OSM (données de synthèse : repère local en mètres, x = est,
 * y = nord). Anneau de 16 nœuds, rayon 15 m ; branche k au nœud k, angle mathématique
 * −90° + k × 22,5° (k = 0 : sud, 4 : est, 8 : nord, 12 : ouest).
 */
class RoundaboutAnalyzerTest {
    private val origin = LatLon(47.6, 7.3)
    private fun at(x: Double, y: Double) = LatLon(
        origin.latitude + y / 111_320.0,
        origin.longitude + x / (111_320.0 * cos(origin.latitude * PI / 180)),
    )

    private val radius = 15.0
    private fun ringAngle(k: Int) = (-90.0 + k * 22.5) * PI / 180
    private fun ringPoint(k: Int, r: Double = radius) = Pair(r * cos(ringAngle(k)), r * sin(ringAngle(k)))

    /** Anneau dessiné dans le sens inverse des aiguilles d'une montre (circulation à droite). */
    private fun ring(clockwise: Boolean = false, r: Double = radius): OsmRoad {
        val ks = (0..16).map { it % 16 }.let { if (clockwise) it.reversed() else it }
        return OsmRoad(
            id = 1, nodeIds = ks.map { 100L + it }, geometry = ks.map { val (x, y) = ringPoint(it, r); at(x, y) },
            highway = "primary", junction = "roundabout",
        )
    }

    /** Branche droite à double sens (ou sens unique `oneway`) du nœud k vers l'extérieur, 200 m. */
    private fun arm(k: Int, name: String?, highway: String = "secondary", ref: String? = null, oneway: String? = null, towardRing: Boolean = false): OsmRoad {
        val (x, y) = ringPoint(k)
        val (fx, fy) = ringPoint(k, 215.0)
        val nodes = listOf(100L + k, 200L + k)
        val geometry = listOf(at(x, y), at(fx, fy))
        return OsmRoad(
            id = 10L + k, nodeIds = if (towardRing) nodes.reversed() else nodes, geometry = if (towardRing) geometry.reversed() else geometry,
            highway = highway, oneway = oneway, name = name, ref = ref,
        )
    }

    private val standardArms = listOf(
        arm(0, "Rue Sud"), arm(4, "Rue Est"), arm(8, "Rue Nord", ref = "D 2"), arm(12, null, ref = "D 419"),
    )

    /** Trace : bras d'entrée k=0 depuis 200 m, anneau dans le sens de circulation, sortie par le bras `exitK`. */
    private fun track(exitK: Int, clockwise: Boolean = false, entryK: Int = 0): List<LatLon> {
        val points = mutableListOf<LatLon>()
        for (d in 200 downTo 20 step 5) { val (x, y) = ringPoint(entryK, radius + d); points.add(at(x, y)) }
        var k = entryK
        val stepSign = if (clockwise) -1 else 1
        // Anneau à pas de 1/2 nœud.
        var t = entryK.toDouble()
        val target = if (clockwise) { var e = exitK.toDouble(); while (e >= t) e -= 16; e } else { var e = exitK.toDouble(); while (e <= t) e += 16; e }
        while (if (clockwise) t > target else t < target) {
            val a = (-90.0 + t * 22.5) * PI / 180
            points.add(at(radius * cos(a), radius * sin(a)))
            t += stepSign * 0.5
        }
        for (d in 20..200 step 5) { val (x, y) = ringPoint(((exitK % 16) + 16) % 16, radius + d); points.add(at(x, y)) }
        k = 0
        return points
    }

    private fun passage(exitK: Int, roads: List<OsmRoad> = listOf(ring()) + standardArms, clockwise: Boolean = false): RoundaboutPassage {
        val passages = RoundaboutAnalyzer.passages(track(exitK, clockwise), RoundaboutMapData(roads))
        assertEquals(1, passages.size, "un seul passage")
        return passages.single()
    }

    @Test
    fun leftTurnIsTheThirdExitInRightHandTraffic() {
        val p = passage(exitK = 12)
        assertEquals(3, p.exitNumber)
        assertEquals(-90.0, p.exitAngleDegrees)
        assertEquals(TurnDirection.LEFT, p.direction)
        assertEquals("D 419", p.exitRoadName)
        assertEquals(false, p.clockwise)
        assertEquals(
            listOf(180.0 to RoundaboutBranchKind.ENTRY, 90.0 to RoundaboutBranchKind.COUNTED_EXIT, 0.0 to RoundaboutBranchKind.COUNTED_EXIT, -90.0 to RoundaboutBranchKind.TAKEN_EXIT),
            p.branches.map { it.pictureAngleDegrees to it.kind },
        )
    }

    @Test
    fun rightIsFirstAndStraightIsSecond() {
        assertEquals(1, passage(exitK = 4).exitNumber)
        assertEquals(TurnDirection.RIGHT, passage(exitK = 4).direction)
        val straight = passage(exitK = 8)
        assertEquals(2, straight.exitNumber)
        assertEquals(TurnDirection.STRAIGHT, straight.direction)
        assertEquals("D 2", straight.exitRoadName)
    }

    @Test
    fun serviceRoadIsDrawnButNotCounted() {
        val p = passage(exitK = 8, roads = listOf(ring()) + standardArms + arm(2, null, highway = "service"))
        assertEquals(2, p.exitNumber, "la voie de service ne compte pas")
        assertTrue(p.branches.any { it.kind == RoundaboutBranchKind.MINOR }, "mais elle est dessinée")
    }

    @Test
    fun privateAccessIsNotCountedEither() {
        val p = passage(exitK = 8, roads = listOf(ring()) + standardArms + arm(2, "Allée", highway = "residential").copy(access = "private"))
        assertEquals(2, p.exitNumber)
    }

    @Test
    fun takenServiceExitIsCounted() {
        val p = passage(exitK = 2, roads = listOf(ring()) + standardArms + arm(2, "Parking", highway = "service"))
        assertEquals(1, p.exitNumber)
    }

    @Test
    fun incompleteRingGivesTheDirectionWithoutNumber() {
        val half = ring().let { it.copy(nodeIds = it.nodeIds.take(10), geometry = it.geometry.take(10)) }
        val p = passage(exitK = 12, roads = listOf(half) + standardArms)
        assertNull(p.exitNumber, "jamais un numéro douteux")
        assertEquals(-90.0, p.exitAngleDegrees, "direction de la trace")
        assertEquals(2, p.branches.size)
    }

    @Test
    fun leftHandTrafficCountsClockwise() {
        val p = passage(exitK = 12, roads = listOf(ring(clockwise = true)) + standardArms, clockwise = true)
        assertEquals(true, p.clockwise)
        assertEquals(1, p.exitNumber, "à gauche en circulation à gauche : première sortie")
        assertEquals(-90.0, p.exitAngleDegrees)
    }

    @Test
    fun entryOnlyOneWayIsNotAnExit() {
        // D 419 à chaussées séparées : entrée seule au nœud 11, sortie seule au nœud 12.
        val arms = standardArms.dropLast(1) + arm(11, null, ref = "D 419", oneway = "yes", towardRing = true) + arm(12, null, ref = "D 419", oneway = "yes")
        val p = passage(exitK = 12, roads = listOf(ring()) + arms)
        assertEquals(3, p.exitNumber)
        assertEquals(1, p.branches.count { it.pictureAngleDegrees < -45 && it.pictureAngleDegrees > -135 }, "une seule route dessinée à gauche")
    }

    @Test
    fun bridgeOverALargeRoundaboutIsNotAPassage() {
        val big = ring(r = 60.0)
        val straight = (-300..300 step 10).map { at(0.0, it.toDouble()) }
        assertTrue(RoundaboutAnalyzer.passages(straight, RoundaboutMapData(listOf(big))).isEmpty())
    }

    @Test
    fun miniRoundaboutCountsByAngle() {
        val node = OsmMiniRoundabout(nodeId = 500, coordinate = at(0.0, 0.0))
        fun spoke(id: Long, x: Double, y: Double, name: String) = OsmRoad(id, listOf(500L, id), listOf(at(0.0, 0.0), at(x, y)), "residential", name = name)
        val roads = listOf(spoke(501, 0.0, -200.0, "Sud"), spoke(502, 200.0, 0.0, "Est"), spoke(503, 0.0, 200.0, "Nord"))
        val straight = (-200..200 step 5).map { at(0.0, it.toDouble()) }
        val p = RoundaboutAnalyzer.passages(straight, RoundaboutMapData(roads, listOf(node))).single()
        assertEquals(2, p.exitNumber)
        assertEquals(TurnDirection.STRAIGHT, p.direction)
        assertEquals("Nord", p.exitRoadName)
    }

    @Test
    fun roundaboutReplacesValhallaAndGeometricEvents() {
        val points = track(exitK = 12)
        val passages = RoundaboutAnalyzer.passages(points, RoundaboutMapData(listOf(ring()) + standardArms))
        val valhalla = listOf(
            MapMatchedManeuver(at(0.0, -15.0), ValhallaManeuverType.ROUNDABOUT_ENTER, roundaboutExitCount = 2),
            MapMatchedManeuver(at(-15.0, 0.0), ValhallaManeuverType.ROUNDABOUT_EXIT),
        )
        val events = RoadbookAnalyzer.buildRoadbookEvents(points, RoadbookSettings(), valhalla, roundabouts = passages)
        val roundabout = events.single { it.tier == RoadbookTier.ROUNDABOUT }
        assertEquals(3, roundabout.roundaboutExitCount, "numéro OSM, jamais le « 2 » Valhalla")
        assertNotNull(roundabout.roundabout)
        assertEquals(1, events.size, "rien d'autre autour du rond-point")

        val withoutOsm = RoadbookAnalyzer.buildRoadbookEvents(points, RoadbookSettings(), valhalla).single { it.tier == RoadbookTier.ROUNDABOUT }
        assertNull(withoutOsm.roundaboutExitCount, "sans OSM : direction seule")
    }

    @Test
    fun geometryOnlyRoadbookGetsTheRoundaboutToo() {
        val points = track(exitK = 12)
        val passages = RoundaboutAnalyzer.passages(points, RoundaboutMapData(listOf(ring()) + standardArms))
        val events = RoadbookAnalyzer.buildRoadbookEvents(points, RoadbookSettings(), roundabouts = passages)
        assertEquals(listOf(RoadbookTier.ROUNDABOUT), events.map { it.tier })
        val maneuver = RoadbookExtractor.maneuvers(points, RoadbookSettings(), roundabouts = passages).single()
        assertTrue(maneuver.headingDegrees in 250.0..290.0, "cap après la sortie (ouest), pas celui de l'entrée : ${maneuver.headingDegrees}")
    }

    @Test
    fun slotsKeepTheOrderOfCrowdedExits() {
        val slots = RoundaboutAnalyzer.assignSlots(listOf(80.0, 95.0, 100.0, 300.0))
        assertEquals(slots.sorted(), slots)
        assertEquals(slots.toSet().size, slots.size)
        assertEquals(7, slots.last())
        assertEquals(listOf(1, 2), RoundaboutAnalyzer.assignSlots(listOf(90.0, 100.0), 1, 2), "bornes respectées")
    }

    @Test
    fun closeRoundaboutsAreChained() {
        // Deux ronds-points identiques, le second 180 m au nord du premier, tout droit dans les deux.
        fun shifted(road: OsmRoad, dy: Double, idOffset: Long) = road.copy(
            id = road.id + idOffset,
            nodeIds = road.nodeIds.map { it + idOffset },
            geometry = road.geometry.map { LatLon(it.latitude + dy / 111_320.0, it.longitude) },
        )
        val first = listOf(ring(), arm(0, "A"), arm(4, "B"), arm(12, "C")) + arm(8, "Liaison").copy(geometry = listOf(at(0.0, 15.0), at(0.0, 180.0 - 15.0)), nodeIds = listOf(108L, 1108L))
        val second = listOf(ring(), arm(4, "E"), arm(8, "F"), arm(12, "G")).map { shifted(it, 180.0, 1000) }
        val points = (-200..-20 step 5).map { at(0.0, it.toDouble()) } +
            (0..16).map { val a = (-90.0 + it * 11.25) * PI / 180; at(radius * cos(a), radius * sin(a)) } +
            (20..160 step 5).map { at(0.0, it.toDouble()) } +
            (0..16).map { val a = (-90.0 + it * 11.25) * PI / 180; at(radius * cos(a), 180 + radius * sin(a)) } +
            (200..400 step 5).map { at(0.0, it.toDouble()) }
        val passages = RoundaboutAnalyzer.passages(points, RoundaboutMapData(first + second))
        assertEquals(2, passages.size)
        assertEquals(passages[1].exitNumber, passages[0].thenExitNumber)
        assertNotNull(passages[0].thenExitAngleDegrees)
        assertNull(passages[1].thenExitNumber)
    }

    @Test
    fun exitLeavingTangentiallyStraightAheadIsStraight() {
        // Sortie au nœud 3 (est-sud-est de l'anneau) par une route qui file vers le NORD : la
        // position de la branche autour du centre dirait « légèrement à droite », la route va tout droit.
        val (x3, y3) = ringPoint(3)
        val north = OsmRoad(40, listOf(103L, 403L), listOf(at(x3, y3), at(x3, y3 + 200)), "secondary", ref = "D 201")
        val roads = listOf(ring(), arm(0, "Sud"), arm(12, "Ouest"), north)
        val points = (200 downTo 20 step 5).map { val (x, y) = ringPoint(0, radius + it); at(x, y) } +
            (0..6).map { val a = (-90.0 + it * 11.25) * PI / 180; at(radius * cos(a), radius * sin(a)) } +
            (5..200 step 5).map { at(x3, y3 + it) }
        val p = RoundaboutAnalyzer.passages(points, RoundaboutMapData(roads)).single()
        assertEquals(1, p.exitNumber)
        assertEquals(0.0, p.exitAngleDegrees, "tout droit")
        assertEquals("D 201", p.exitRoadName)
        assertEquals(-90.0, p.branches.single { it.kind == RoundaboutBranchKind.COUNTED_EXIT }.pictureAngleDegrees, "la route de l'ouest reste à gauche")
    }
}
