package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Route OpenStreetMap utile à l'analyse d'un rond-point : anneau (`junction=roundabout|circular`)
 * ou route qui y aboutit. Tags déjà extraits côté natif (décodage Overpass).
 */
data class OsmRoad(
    val id: Long,
    val nodeIds: List<Long>,
    val geometry: List<LatLon>,
    val highway: String,
    val junction: String? = null,
    val oneway: String? = null,
    val access: String? = null,
    val motorVehicle: String? = null,
    val name: String? = null,
    val ref: String? = null,
    val destination: String? = null,
)

/** Mini-giratoire : un simple nœud `highway=mini_roundabout` (sens horaire si `direction=clockwise`). */
data class OsmMiniRoundabout(val nodeId: Long, val coordinate: LatLon, val clockwise: Boolean = false)

data class RoundaboutMapData(val roads: List<OsmRoad>, val miniRoundabouts: List<OsmMiniRoundabout> = emptyList())

/**
 * Nature d'une branche dessinée : l'entrée, la sortie prise, une sortie COMPTÉE (celles de la
 * signalisation), une petite voie non comptée (service, chemin, accès privé) ou une route où l'on
 * ne peut pas sortir (sens unique entrant).
 */
enum class RoundaboutBranchKind { ENTRY, TAKEN_EXIT, COUNTED_EXIT, MINOR, NO_EXIT }

/**
 * Branche du pictogramme. [pictureAngleDegrees] : 0 = en haut (tout droit), sens horaire positif,
 * entrée toujours à 180 (en bas) ; valeurs placées sur les 8 positions de 45°.
 */
data class RoundaboutBranch(val pictureAngleDegrees: Double, val kind: RoundaboutBranchKind)

/**
 * Passage de la trace dans un rond-point (it34, « jamais ambigu ») :
 * - [exitAngleDegrees] : position DESSINÉE de la sortie (8 positions) — la direction annoncée ;
 * - [exitNumber] : rang de la sortie en ne comptant que les sorties de la signalisation — `null`
 *   dès qu'un contrôle échoue (jamais un numéro douteux) ;
 * - [exitRoadName] : « D 419 », destination signalée, ou nom de la route de sortie ;
 * - [branches] : toutes les branches (entrée et sortie comprises), vides si seule la trace est connue ;
 * - [thenExitNumber]/[thenExitAngleDegrees] : rond-point suivant à moins de
 *   [RoundaboutAnalyzer.CHAIN_MAX_METERS] après la sortie (« puis 1re sortie »).
 */
data class RoundaboutPassage(
    val entryCumulativeMeters: Double,
    val exitCumulativeMeters: Double,
    val entryCoordinate: LatLon,
    val exitAngleDegrees: Double,
    val exitNumber: Int?,
    val exitRoadName: String?,
    val branches: List<RoundaboutBranch>,
    val clockwise: Boolean,
    val thenExitNumber: Int? = null,
    val thenExitAngleDegrees: Double? = null,
) {
    /** Sens de la sortie pour le Road Book (tout droit à ±22,5°, demi-tour à 180°). */
    val direction: TurnDirection
        get() = when {
            abs(exitAngleDegrees) < 22.5 -> TurnDirection.STRAIGHT
            abs(exitAngleDegrees) > 157.5 -> TurnDirection.U_TURN
            exitAngleDegrees > 0 -> TurnDirection.RIGHT
            else -> TurnDirection.LEFT
        }
}

/**
 * Analyse des ronds-points traversés par une trace (it34, retour terrain : numéro de sortie
 * Valhalla faux une fois sur deux — « 2 » partout —, pictogramme générique ambigu). Méthode (voir
 * RoadBook/CLAUDE.md) :
 * 1. anneau reconstitué depuis OSM, sens de circulation lu dans le sens de dessin de l'anneau ;
 * 2. passage de la trace ré-échantillonné tous les [SAMPLE_STEP_METERS] (traces peu denses) ;
 *    refusé s'il ne suit pas l'anneau (pont au-dessus d'un grand giratoire) ;
 * 3. direction = cap de la trace ~10 m avant l'anneau vs ~10 m après ;
 * 4. branches = routes carrossables reliées à l'anneau ; sorties comptées dans le sens de
 *    circulation, sans voies de service, chemins ni accès privés (convention de la signalisation),
 *    sauf si c'est la sortie prise ;
 * 5. numéro publié seulement si TOUS les contrôles passent (anneau fermé, entrée et sortie OSM
 *    retrouvées sur la trace, direction OSM cohérente avec la trace) ; sinon direction seule.
 */
object RoundaboutAnalyzer {
    const val SAMPLE_STEP_METERS = 2.0
    /** Marge autour de l'anneau pour considérer la trace « dans » le rond-point. */
    const val RING_MARGIN_METERS = 8.0
    /** Passage recherché à cette distance du centre au-delà du rayon. */
    const val SEARCH_MARGIN_METERS = 15.0
    /** Cap d'approche mesuré de −30 à −10 m avant l'anneau, cap de sortie de +10 à +30 m après. */
    const val HEADING_NEAR_METERS = 10.0
    const val HEADING_FAR_METERS = 30.0
    /** Branche OSM retrouvée si sa position autour du centre est à moins de ça de la trace. */
    const val BRANCH_MATCH_MAX_DEGREES = 40.0
    /** Cap d'une branche : route de 10 à 30 m hors de l'anneau (sens de la sortie). */
    const val BRANCH_HEADING_NEAR_METERS = 10.0
    const val BRANCH_HEADING_FAR_METERS = 30.0
    /** La trace doit longer la branche d'entrée / de sortie à moins de ça, 20 m hors de l'anneau. */
    const val BRANCH_TRACK_MAX_METERS = 20.0
    /** Direction OSM et direction de la trace : écart toléré (îlots déflecteurs). */
    const val DIRECTION_CONSISTENCY_MAX_DEGREES = 45.0
    /** Longueur de branche utilisée pour sa direction. */
    const val BRANCH_PROBE_METERS = 25.0
    /** Deux branches d'une même route (chaussées séparées) à moins de ça : un seul trait. */
    const val SAME_ROAD_MERGE_DEGREES = 40.0
    /** Chaussée de sens inverse de la route d'arrivée : repart à moins de ça de l'arrière. */
    const val SAME_ROAD_ENTRY_DEGREES = 60.0
    /** Rond-point suivant à moins de ça après la sortie : annoncé avec (« puis … »). */
    const val CHAIN_MAX_METERS = 200.0
    /** Au-delà, le numéro n'est plus crédible. */
    const val MAX_EXIT_NUMBER = 12
    /** Mini-giratoire : trace à moins de ça du nœud. */
    const val MINI_MATCH_METERS = 15.0
    /** Événements du Road Book supprimés autour d'un rond-point (courbes de l'approche, anneau). */
    const val EVENT_SUPPRESSION_MARGIN_METERS = 40.0

    private val NON_VEHICLE = setOf(
        "footway", "path", "cycleway", "pedestrian", "steps", "bridleway", "corridor", "elevator",
        "platform", "proposed", "construction", "abandoned", "razed", "bus_stop", "via_ferrata",
    )
    private val MINOR_HIGHWAYS = setOf("service", "track", "busway", "bus_guideway", "escape", "raceway")
    private val NO_CAR_ACCESS = setOf("no", "private")

    fun passages(points: List<LatLon>, data: RoundaboutMapData): List<RoundaboutPassage> {
        if (points.size < 2) return emptyList()
        val cumulative = TrackGeometry.cumulativeDistances(points)
        val rings = rings(data.roads)
        val branchRoads = data.roads.filter { it.junction != "roundabout" && it.junction != "circular" && it.highway !in NON_VEHICLE }
        val found = mutableListOf<RoundaboutPassage>()
        for (ring in rings) {
            for (pass in TrackGeometry.passes(ring.center, points, cumulative, ring.radius + SEARCH_MARGIN_METERS)) {
                analyzeRingPass(ring, pass.cumulativeDistanceMeters, branchRoads, points, cumulative)?.let(found::add)
            }
        }
        for (mini in data.miniRoundabouts) {
            val roads = data.roads.filter { mini.nodeId in it.nodeIds && it.highway !in NON_VEHICLE }
            for (pass in TrackGeometry.passes(mini.coordinate, points, cumulative, MINI_MATCH_METERS)) {
                analyzeMiniPass(mini, roads, pass.cumulativeDistanceMeters, points, cumulative)?.let(found::add)
            }
        }
        val sorted = found.sortedBy { it.entryCumulativeMeters }
            // Même passage trouvé deux fois (anneau découpé en plusieurs morceaux mal reliés) : le premier.
            .fold(mutableListOf<RoundaboutPassage>()) { acc, p ->
                if (acc.isEmpty() || p.entryCumulativeMeters >= acc.last().exitCumulativeMeters) acc.add(p)
                acc
            }
        return sorted.mapIndexed { index, passage ->
            val next = sorted.getOrNull(index + 1)
            if (next != null && next.entryCumulativeMeters - passage.exitCumulativeMeters <= CHAIN_MAX_METERS) {
                passage.copy(thenExitNumber = next.exitNumber, thenExitAngleDegrees = next.exitAngleDegrees)
            } else passage
        }
    }

    // MARK: anneaux

    private class Ring(
        val nodeOrder: List<Long>,
        val coordinates: Map<Long, LatLon>,
        val closed: Boolean,
        val center: LatLon,
        val radius: Double,
        val clockwise: Boolean,
    ) {
        val nodeSet = nodeOrder.toSet()
    }

    /** Morceaux d'anneau reliés par leurs nœuds communs → un anneau ordonné dans le sens de circulation. */
    private fun rings(roads: List<OsmRoad>): List<Ring> {
        val ringWays = roads.filter { it.junction == "roundabout" || it.junction == "circular" }
        val groups = mutableListOf<MutableList<OsmRoad>>()
        for (way in ringWays) {
            val nodes = way.nodeIds.toSet()
            val touching = groups.filter { group -> group.any { other -> other.nodeIds.any(nodes::contains) } }
            val merged = mutableListOf(way)
            for (group in touching) {
                merged.addAll(group)
                groups.remove(group)
            }
            groups.add(merged)
        }
        return groups.mapNotNull { group ->
            val coordinates = mutableMapOf<Long, LatLon>()
            val successor = mutableMapOf<Long, Long>()
            for (way in group) {
                val reversed = way.oneway == "-1"
                val ids = if (reversed) way.nodeIds.reversed() else way.nodeIds
                val geometry = if (reversed) way.geometry.reversed() else way.geometry
                ids.zip(geometry).forEach { (id, c) -> coordinates[id] = c }
                ids.zipWithNext().forEach { (a, b) -> if (a != b) successor[a] = b }
            }
            val start = successor.keys.firstOrNull() ?: return@mapNotNull null
            val order = mutableListOf(start)
            var next = successor[start]
            while (next != null && next != start && order.size <= successor.size) {
                order.add(next)
                next = successor[next]
            }
            val closed = next == start && order.toSet().size == coordinates.size
            val unique = coordinates.values.toList()
            val center = LatLon(unique.sumOf { it.latitude } / unique.size, unique.sumOf { it.longitude } / unique.size)
            val radius = unique.sumOf { geodesicDistanceMeters(center, it) } / unique.size
            Ring(order, coordinates, closed, center, radius, clockwise = signedArea(order.mapNotNull(coordinates::get)) < 0)
        }
    }

    /** Aire signée (lon/lat, repère local) : > 0 = sens inverse des aiguilles d'une montre vu du ciel. */
    private fun signedArea(ring: List<LatLon>): Double {
        if (ring.size < 3) return 0.0
        val scale = cos(ring[0].latitude * PI / 180)
        var area = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            area += a.longitude * scale * b.latitude - b.longitude * scale * a.latitude
        }
        return area / 2
    }

    // MARK: branches

    private class Branch(
        val nodeId: Long,
        val bearingFromCenter: Double,
        val far: List<LatLon>,
        val road: OsmRoad,
        val canExit: Boolean,
        val canEnter: Boolean,
    ) {
        /**
         * Cap en SORTANT par cette branche (route de 10 à 30 m hors de l'anneau) — la direction
         * ressentie. Une route qui longe l'anneau en tangente part « tout droit » même si elle s'en
         * détache sur le côté (retour planche it34 : W4/V1/V14/V22 annoncés « légèrement »).
         */
        val outHeading: Double = run {
            // Tronçon OSM court (bretelle de quelques mètres jusqu'au carrefour suivant) : cap sur
            // ce qui existe, jamais entre deux points confondus.
            val length = TrackGeometry.cumulativeDistances(far).lastOrNull() ?: 0.0
            val farEnd = min(BRANCH_HEADING_FAR_METERS, length)
            val near = min(BRANCH_HEADING_NEAR_METERS, farEnd / 3)
            RoadbookAnalyzer.bearing(pointAlong(far, near), pointAlong(far, farEnd))
        }
        /** Cap en ENTRANT par cette branche. */
        val inHeading: Double get() = (outHeading + 180) % 360
        val carAllowed: Boolean
            get() = road.access !in NO_CAR_ACCESS && road.motorVehicle !in NO_CAR_ACCESS
        /** Sortie de la signalisation : route carrossable ouverte, ni service ni chemin. */
        val isSignificant: Boolean get() = carAllowed && road.highway !in MINOR_HIGHWAYS
        val identity: String? get() = road.ref?.takeIf { it.isNotBlank() } ?: road.name?.takeIf { it.isNotBlank() }
        /** Tronçon assez long pour que son cap soit fiable. */
        val hasReliableHeading: Boolean get() = (TrackGeometry.cumulativeDistances(far).lastOrNull() ?: 0.0) >= BRANCH_HEADING_FAR_METERS
    }

    /** Branches qui partent des nœuds [nodes] vers l'extérieur (une route traversante en donne deux). */
    private fun branchesAt(nodes: Set<Long>, center: LatLon, roads: List<OsmRoad>): List<Branch> {
        val result = mutableListOf<Branch>()
        for (road in roads) {
            if (road.nodeIds.size != road.geometry.size) continue
            for ((k, node) in road.nodeIds.withIndex()) {
                if (node !in nodes) continue
                for (step in intArrayOf(1, -1)) {
                    val j = k + step
                    if (j !in road.nodeIds.indices || road.nodeIds[j] in nodes) continue
                    val far = mutableListOf(road.geometry[k])
                    var length = 0.0
                    var i = j
                    while (i in road.nodeIds.indices) {
                        length += geodesicDistanceMeters(road.geometry[i - step], road.geometry[i])
                        far.add(road.geometry[i])
                        if (length >= BRANCH_PROBE_METERS * 3 || road.nodeIds[i] in nodes) break
                        i += step
                    }
                    val probe = pointAlong(far, BRANCH_PROBE_METERS)
                    val oneway = road.oneway ?: if (road.highway == "motorway") "yes" else "no"
                    val forward = oneway == "yes" || oneway == "true" || oneway == "1"
                    val backward = oneway == "-1"
                    result.add(
                        Branch(
                            nodeId = node,
                            bearingFromCenter = RoadbookAnalyzer.bearing(center, probe),
                            far = far,
                            road = road,
                            canExit = !(forward && step == -1) && !(backward && step == 1),
                            canEnter = !(forward && step == 1) && !(backward && step == -1),
                        ),
                    )
                }
            }
        }
        return result
    }

    /** Angle du pictogramme d'une branche : son cap de sortie vs le cap d'arrivée (0 = tout droit, droite > 0). */
    private fun headingPicture(branch: Branch, entry: Branch): Double =
        normalize(RoadbookAnalyzer.signedAngleDifference(entry.inHeading, branch.outHeading))

    private fun pointAlong(line: List<LatLon>, meters: Double): LatLon {
        var remaining = meters
        for (i in 1 until line.size) {
            val d = geodesicDistanceMeters(line[i - 1], line[i])
            if (d >= remaining && d > 0) {
                val t = remaining / d
                return LatLon(
                    line[i - 1].latitude + (line[i].latitude - line[i - 1].latitude) * t,
                    line[i - 1].longitude + (line[i].longitude - line[i - 1].longitude) * t,
                )
            }
            remaining -= d
        }
        return line.last()
    }

    // MARK: passage dans un anneau

    private fun analyzeRingPass(
        ring: Ring,
        closestCumulative: Double,
        roads: List<OsmRoad>,
        points: List<LatLon>,
        cumulative: DoubleArray,
    ): RoundaboutPassage? {
        val total = cumulative.last()
        val inside = ring.radius + RING_MARGIN_METERS
        fun at(c: Double) = TrackGeometry.interpolatedCoordinate(c.coerceIn(0.0, total), points, cumulative)!!
        fun isInside(c: Double) = geodesicDistanceMeters(at(c), ring.center) <= inside
        if (!isInside(closestCumulative)) return null
        // Entrée / sortie : bornes du passage continu autour du point le plus proche.
        val maxSpan = 2 * PI * inside + 100
        var entry = closestCumulative
        while (entry - SAMPLE_STEP_METERS >= 0 && closestCumulative - entry < maxSpan && isInside(entry - SAMPLE_STEP_METERS)) entry -= SAMPLE_STEP_METERS
        var exit = closestCumulative
        while (exit + SAMPLE_STEP_METERS <= total && exit - closestCumulative < maxSpan && isInside(exit + SAMPLE_STEP_METERS)) exit += SAMPLE_STEP_METERS
        if (entry < HEADING_FAR_METERS || total - exit < HEADING_FAR_METERS) return null

        // La trace suit l'anneau (pas un pont qui passe au-dessus du centre d'un grand giratoire).
        val tolerance = max(RING_MARGIN_METERS, ring.radius * 0.35)
        var samples = 0
        var onRing = 0
        var c = entry
        while (c <= exit) {
            samples++
            if (abs(geodesicDistanceMeters(at(c), ring.center) - ring.radius) <= tolerance) onRing++
            c += SAMPLE_STEP_METERS
        }
        if (samples == 0 || onRing * 2 < samples) return null

        val trackTurn = trackTurn(entry, exit, points, cumulative) ?: return null
        val entryCoordinate = at(entry)
        val entryPosition = RoadbookAnalyzer.bearing(ring.center, at(entry - HEADING_NEAR_METERS))
        val exitPosition = RoadbookAnalyzer.bearing(ring.center, at(exit + HEADING_NEAR_METERS))

        val branches = branchesAt(ring.nodeSet, ring.center, roads)
        val entryBranch = branches.filter { it.canEnter }.minByOrNull { angleBetween(it.bearingFromCenter, entryPosition) }
        val exitBranch = branches.filter { it.canExit }.minByOrNull { angleBetween(it.bearingFromCenter, exitPosition) }

        val trackOnly = trackOnlyPassage(entry, exit, entryCoordinate, trackTurn, ring.clockwise)
        if (!ring.closed || entryBranch == null || exitBranch == null) return trackOnly
        if (angleBetween(entryBranch.bearingFromCenter, entryPosition) > BRANCH_MATCH_MAX_DEGREES ||
            angleBetween(exitBranch.bearingFromCenter, exitPosition) > BRANCH_MATCH_MAX_DEGREES
        ) return trackOnly
        if (distanceToLine(at(entry - BRANCH_TRACK_MAX_METERS), entryBranch.far) > BRANCH_TRACK_MAX_METERS ||
            distanceToLine(at(exit + BRANCH_TRACK_MAX_METERS), exitBranch.far) > BRANCH_TRACK_MAX_METERS
        ) return trackOnly
        if (exitBranch.hasReliableHeading && entryBranch.hasReliableHeading &&
            angleBetween(headingPicture(exitBranch, entryBranch), trackTurn) > DIRECTION_CONSISTENCY_MAX_DEGREES
        ) return trackOnly

        // Sorties rencontrées de l'entrée à la sortie prise, dans le sens de circulation.
        val order = ring.nodeOrder
        val entryIndex = order.indexOf(entryBranch.nodeId)
        val exitIndex = order.indexOf(exitBranch.nodeId)
        if (entryIndex < 0 || exitIndex < 0) return trackOnly
        val steps = ((exitIndex - entryIndex) % order.size + order.size) % order.size
        val travelled = if (steps == 0) order.size else steps
        var number = 0
        for (s in 1..travelled) {
            val node = order[(entryIndex + s) % order.size]
            if (node == exitBranch.nodeId) {
                number++
                break
            }
            number += branches.count { it.nodeId == node && it.canExit && it.isSignificant }
        }
        val exitNumber = number.takeIf { it in 1..MAX_EXIT_NUMBER }

        // Ordre de rencontre = position le long de l'anneau depuis l'entrée (branches du nœud
        // d'entrée en dernier : on ne les croise qu'après un tour complet).
        val encounter = { b: Branch ->
            val step = ((order.indexOf(b.nodeId) - entryIndex) % order.size + order.size) % order.size
            (if (step == 0) order.size else step).toDouble()
        }
        val drawn = drawnBranches(branches, entryBranch, exitBranch, ring.clockwise, encounter, trackTurn)
        val exitAngle = drawn.first { it.kind == RoundaboutBranchKind.TAKEN_EXIT }.pictureAngleDegrees
        return RoundaboutPassage(
            entryCumulativeMeters = entry,
            exitCumulativeMeters = exit,
            entryCoordinate = entryCoordinate,
            exitAngleDegrees = exitAngle,
            exitNumber = exitNumber,
            exitRoadName = roadLabel(exitBranch.road),
            branches = drawn,
            clockwise = ring.clockwise,
        )
    }

    // MARK: mini-giratoire

    private fun analyzeMiniPass(
        mini: OsmMiniRoundabout,
        roads: List<OsmRoad>,
        closestCumulative: Double,
        points: List<LatLon>,
        cumulative: DoubleArray,
    ): RoundaboutPassage? {
        val total = cumulative.last()
        if (closestCumulative < HEADING_FAR_METERS || total - closestCumulative < HEADING_FAR_METERS) return null
        fun at(c: Double) = TrackGeometry.interpolatedCoordinate(c.coerceIn(0.0, total), points, cumulative)!!
        val trackTurn = trackTurn(closestCumulative, closestCumulative, points, cumulative) ?: return null
        val entryCoordinate = at(closestCumulative)
        val trackOnly = trackOnlyPassage(closestCumulative, closestCumulative, entryCoordinate, trackTurn, mini.clockwise)
        val branches = branchesAt(setOf(mini.nodeId), mini.coordinate, roads)
        val entryPosition = RoadbookAnalyzer.bearing(mini.coordinate, at(closestCumulative - HEADING_FAR_METERS))
        val exitPosition = RoadbookAnalyzer.bearing(mini.coordinate, at(closestCumulative + HEADING_FAR_METERS))
        val entryBranch = branches.filter { it.canEnter }.minByOrNull { angleBetween(it.bearingFromCenter, entryPosition) } ?: return trackOnly
        val exitBranch = branches.filter { it.canExit && it !== entryBranch }.minByOrNull { angleBetween(it.bearingFromCenter, exitPosition) } ?: return trackOnly
        if (angleBetween(entryBranch.bearingFromCenter, entryPosition) > BRANCH_MATCH_MAX_DEGREES ||
            angleBetween(exitBranch.bearingFromCenter, exitPosition) > BRANCH_MATCH_MAX_DEGREES
        ) return trackOnly
        if (exitBranch.hasReliableHeading && entryBranch.hasReliableHeading &&
            angleBetween(headingPicture(exitBranch, entryBranch), trackTurn) > DIRECTION_CONSISTENCY_MAX_DEGREES
        ) return trackOnly
        // Mini-giratoire : ordre de rencontre = position des branches autour du nœud.
        val encounter = { b: Branch -> travel(picture(b.bearingFromCenter, entryBranch.bearingFromCenter), mini.clockwise) }
        val exitTravel = encounter(exitBranch)
        val number = 1 + branches.count { b ->
            b !== exitBranch && b.canExit && b.isSignificant && encounter(b).let { it > 0 && it < exitTravel }
        }
        val drawn = drawnBranches(branches, entryBranch, exitBranch, mini.clockwise, encounter, trackTurn)
        return RoundaboutPassage(
            entryCumulativeMeters = closestCumulative,
            exitCumulativeMeters = closestCumulative,
            entryCoordinate = entryCoordinate,
            exitAngleDegrees = drawn.first { it.kind == RoundaboutBranchKind.TAKEN_EXIT }.pictureAngleDegrees,
            exitNumber = number.takeIf { it in 1..MAX_EXIT_NUMBER },
            exitRoadName = roadLabel(exitBranch.road),
            branches = drawn,
            clockwise = mini.clockwise,
        )
    }

    // MARK: dessin sur 8 positions

    /**
     * Branches placées sur les 8 positions de 45° (entrée en bas). La sortie PRISE est placée d'après
     * la trace (sa vraie direction, qui est aussi celle annoncée) ; les autres branches se rangent
     * AVANT ou APRÈS elle selon leur ordre de rencontre sur l'anneau, au plus près de leur cap — deux
     * sorties ne peuvent jamais s'inverser, et le texte ne ment jamais (retour planche it34, V22 :
     * une chaussée en trop avait poussé une sortie à gauche en « tout droit »).
     */
    private fun drawnBranches(all: List<Branch>, entry: Branch, exit: Branch, clockwise: Boolean, encounter: (Branch) -> Double, trackTurn: Double): List<RoundaboutBranch> {
        /** [travel] : position visée (degrés parcourus depuis l'entrée, d'après le CAP de la branche). */
        class Item(val order: Double, val travel: Double, val kind: RoundaboutBranchKind, val identity: String?)
        fun kindOf(b: Branch): RoundaboutBranchKind = when {
            b === exit -> RoundaboutBranchKind.TAKEN_EXIT
            !b.isSignificant -> RoundaboutBranchKind.MINOR
            b.canExit -> RoundaboutBranchKind.COUNTED_EXIT
            else -> RoundaboutBranchKind.NO_EXIT
        }
        val rank = listOf(RoundaboutBranchKind.TAKEN_EXIT, RoundaboutBranchKind.ENTRY, RoundaboutBranchKind.COUNTED_EXIT, RoundaboutBranchKind.NO_EXIT, RoundaboutBranchKind.MINOR)
        val exitTravel = travel(snap(trackTurn), clockwise)
        var items = all.filter { it !== entry && it !== exit }.map { b ->
            Item(encounter(b), travel(headingPicture(b, entry), clockwise), kindOf(b), b.identity)
        }.sortedWith(compareBy<Item>({ it.order }, { it.travel }))
        // Chaussée de sens inverse de la route d'arrivée (même route, repart vers l'arrière) : fait
        // partie de l'entrée, jamais une branche à part.
        items = items.filterNot { it.identity != null && it.identity == entry.identity && (360 - it.travel) < SAME_ROAD_ENTRY_DEGREES }
        // Chaussées séparées d'une même route : un seul trait, le plus significatif.
        val merged = mutableListOf<Item>()
        for (item in items) {
            val last = merged.lastOrNull()
            if (last != null && item.identity != null && item.identity == last.identity && abs(item.travel - last.travel) < SAME_ROAD_MERGE_DEGREES) {
                merged[merged.lastIndex] = if (rank.indexOf(item.kind) < rank.indexOf(last.kind)) item else last
            } else {
                merged.add(item)
            }
        }
        val exitOrder = encounter(exit)
        val before = merged.filter { it.order < exitOrder || (it.order == exitOrder && it.travel < exitTravel) }.toMutableList()
        val after = merged.filter { it !in before }.toMutableList()
        // Sortie prise : position 1…7 (8 = demi-tour, juste avant l'entrée).
        val exitSlot = (exitTravel / 45).roundToInt().coerceIn(1, 8)
        fun fit(list: MutableList<Item>, capacity: Int) {
            while (list.size > capacity) {
                val removable = list.withIndex().filter { it.value.kind == RoundaboutBranchKind.MINOR || it.value.kind == RoundaboutBranchKind.NO_EXIT }
                    .maxByOrNull { abs(it.value.travel - exitTravel) } ?: list.withIndex().maxBy { abs(it.value.travel - exitTravel) }
                list.removeAt(removable.index)
            }
        }
        fit(before, exitSlot - 1)
        fit(after, 7 - exitSlot)
        val result = mutableListOf(RoundaboutBranch(180.0, RoundaboutBranchKind.ENTRY))
        fun place(travelled: Int, kind: RoundaboutBranchKind) {
            val degrees = travelled * 45.0
            result.add(RoundaboutBranch(normalize(if (clockwise) 180.0 + degrees else 180.0 - degrees), kind))
        }
        assignSlots(before.map { it.travel }, 1, exitSlot - 1).forEachIndexed { i, slot -> place(slot, before[i].kind) }
        place(exitSlot, RoundaboutBranchKind.TAKEN_EXIT)
        assignSlots(after.map { it.travel }, exitSlot + 1, 7).forEachIndexed { i, slot -> place(slot, after[i].kind) }
        return result
    }

    /**
     * Positions de [first] à [last] (×45° parcourus depuis l'entrée) strictement croissantes, au plus
     * près des angles réels (programmation dynamique, somme des écarts minimale).
     */
    internal fun assignSlots(travels: List<Double>, first: Int = 1, last: Int = 7): List<Int> {
        val n = travels.size
        if (n == 0 || last < first) return emptyList()
        val slots = (first..last).toList()
        val inf = Double.MAX_VALUE / 4
        val cost = Array(n) { DoubleArray(slots.size) { inf } }
        val from = Array(n) { IntArray(slots.size) }
        for (s in slots.indices) cost[0][s] = abs(travels[0] - 45.0 * slots[s])
        for (i in 1 until n) {
            for (s in slots.indices) {
                var best = inf
                var arg = 0
                for (p in 0 until s) if (cost[i - 1][p] < best) { best = cost[i - 1][p]; arg = p }
                if (best < inf) {
                    cost[i][s] = best + abs(travels[i] - 45.0 * slots[s])
                    from[i][s] = arg
                }
            }
        }
        var end = slots.indices.minByOrNull { cost[n - 1][it] }!!
        val result = IntArray(n)
        for (i in n - 1 downTo 0) {
            result[i] = slots[end]
            end = from[i][end]
        }
        return result.toList()
    }

    // MARK: outils

    /** Passage où seule la trace est fiable : entrée et sortie, direction de la trace, sans numéro. */
    private fun trackOnlyPassage(entry: Double, exit: Double, entryCoordinate: LatLon, trackTurn: Double, clockwise: Boolean) = RoundaboutPassage(
        entryCumulativeMeters = entry,
        exitCumulativeMeters = exit,
        entryCoordinate = entryCoordinate,
        exitAngleDegrees = snap(trackTurn),
        exitNumber = null,
        exitRoadName = null,
        branches = listOf(RoundaboutBranch(180.0, RoundaboutBranchKind.ENTRY), RoundaboutBranch(snap(trackTurn), RoundaboutBranchKind.TAKEN_EXIT)),
        clockwise = clockwise,
    )

    /** Virage de la trace : cap de −30 à −10 m avant l'entrée vs cap de +10 à +30 m après la sortie. */
    private fun trackTurn(entry: Double, exit: Double, points: List<LatLon>, cumulative: DoubleArray): Double? {
        val total = cumulative.last()
        if (entry - HEADING_FAR_METERS < 0 || exit + HEADING_FAR_METERS > total) return null
        val a = TrackGeometry.interpolatedCoordinate(entry - HEADING_FAR_METERS, points, cumulative) ?: return null
        val b = TrackGeometry.interpolatedCoordinate(entry - HEADING_NEAR_METERS, points, cumulative) ?: return null
        val c = TrackGeometry.interpolatedCoordinate(exit + HEADING_NEAR_METERS, points, cumulative) ?: return null
        val d = TrackGeometry.interpolatedCoordinate(exit + HEADING_FAR_METERS, points, cumulative) ?: return null
        return RoadbookAnalyzer.signedAngleDifference(RoadbookAnalyzer.bearing(a, b), RoadbookAnalyzer.bearing(c, d))
    }

    /** Angle dans le repère du pictogramme (entrée en bas = 180, en face = 0, droite = 90). */
    private fun picture(bearingFromCenter: Double, entryBearingFromCenter: Double): Double =
        normalize(bearingFromCenter - entryBearingFromCenter - 180)

    /** Degrés parcourus depuis l'entrée dans le sens de circulation (0 exclu, 360 = retour à l'entrée). */
    private fun travel(pictureAngle: Double, clockwise: Boolean): Double {
        val t = if (clockwise) pictureAngle - 180 else 180 - pictureAngle
        val m = ((t % 360) + 360) % 360
        return if (m == 0.0) 360.0 else m
    }

    /** Sur les 8 positions ; demi-tour = 180. */
    private fun snap(angle: Double): Double = normalize((angle / 45).roundToInt() * 45.0)

    /** ]-180, 180] */
    private fun normalize(angle: Double): Double {
        var a = ((angle % 360) + 360) % 360
        if (a > 180) a -= 360
        if (a == -180.0) a = 180.0
        return a
    }

    private fun angleBetween(a: Double, b: Double): Double = abs(normalize(a - b))

    private fun distanceToLine(p: LatLon, line: List<LatLon>): Double {
        if (line.size < 2) return line.firstOrNull()?.let { geodesicDistanceMeters(p, it) } ?: Double.MAX_VALUE
        val cumulative = TrackGeometry.cumulativeDistances(line)
        return TrackGeometry.project(p, line, cumulative)?.distanceToTrackMeters ?: Double.MAX_VALUE
    }

    /** Ce qu'on lit sur les panneaux : numéro de route et destination, sinon le nom. */
    private fun roadLabel(road: OsmRoad): String? {
        val ref = road.ref?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val destination = road.destination?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val signed = listOfNotNull(ref, destination).joinToString(" – ")
        if (signed.isNotEmpty()) return signed
        return road.name?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Événements du Road Book avec les ronds-points analysés : chaque passage REMPLACE ce qui a été
     * détecté entre son approche et sa sortie (événement Valhalla, courbes de l'anneau et de
     * l'approche) par UN événement rond-point, à l'entrée de l'anneau.
     */
    fun applyTo(events: List<Checkpoint>, passages: List<RoundaboutPassage>, points: List<LatLon>, cumulative: DoubleArray): List<Checkpoint> {
        if (passages.isEmpty()) return events
        val margin = EVENT_SUPPRESSION_MARGIN_METERS
        val kept = events.filter { event ->
            val c = event.cumulativeDistanceMeters(cumulative) ?: return@filter true
            passages.none { c >= it.entryCumulativeMeters - margin && c <= it.exitCumulativeMeters + margin }
        }
        val roundabouts = passages.map { passage ->
            val index = cumulative.indices.minByOrNull { abs(cumulative[it] - passage.entryCumulativeMeters) } ?: 0
            Checkpoint(
                coordinate = passage.entryCoordinate,
                turnAngleDegrees = abs(passage.exitAngleDegrees),
                direction = passage.direction,
                tier = RoadbookTier.ROUNDABOUT,
                sequenceIndex = 0,
                sourcePointIndex = min(index, points.size - 1),
                roundaboutExitCount = passage.exitNumber,
                trackCumulativeDistanceMeters = passage.entryCumulativeMeters,
                roundabout = passage,
            )
        }
        return (kept + roundabouts)
            .sortedBy { it.cumulativeDistanceMeters(cumulative) ?: 0.0 }
            .mapIndexed { index, checkpoint -> checkpoint.copy(sequenceIndex = index + 1) }
    }
}
