package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Événements du Road Book d'une trace (portage de `RoadbookAnalyzer.swift`, it32-it33) — SOURCE
 * UNIQUE des virages pour les épingles carte, la bannière latérale du Ride et le Road Book
 * (invariant it14 : une seule liste). Ne modifie jamais la trace.
 *
 * Règles (historique détaillé dans le CLAUDE.md du Road Book) :
 * - angle = cap MOYEN avant (corde interpolée) vs cap moyen après, jamais une somme d'écarts
 *   segment par segment (fix "roadbook-turn-angle-from-heading-chords") ;
 * - sommets rapprochés regroupés en un virage portant le virage NET ; zigzag parasite ignoré ;
 * - demi-tour seulement si la trace repart sur son propre tracé, jamais près du départ/arrivée ;
 * - manœuvres Valhalla fusionnées dans la même liste, à la position du vrai carrefour, sur le bon
 *   passage de la trace, libellé et sens toujours issus de la géométrie de la trace suivie.
 */
object RoadbookAnalyzer {

    /**
     * @param coverage portions de la trace réellement recalées par le map matching
     *   ([MapMatchCoverage]) ; `null` = inconnue : toute la trace est considérée couverte dès qu'il y
     *   a des manœuvres (tests, anciens appels).
     */
    fun buildRoadbookEvents(
        points: List<LatLon>,
        settings: RoadbookSettings,
        mapMatchedManeuvers: List<MapMatchedManeuver> = emptyList(),
        coverage: List<CoveredRange>? = null,
        roundabouts: List<RoundaboutPassage> = emptyList(),
    ): List<Checkpoint> {
        val windowBefore = settings.windowBeforeMeters
        val windowAfter = settings.windowAfterMeters
        if (points.size <= 2 || windowBefore <= 0 || windowAfter <= 0) return emptyList()
        val cumulative = TrackGeometry.cumulativeDistances(points)
        // It34 : ronds-points analysés sur OSM — chacun remplace ce qui a été détecté autour de lui.
        return RoundaboutAnalyzer.applyTo(baseEvents(points, settings, mapMatchedManeuvers, coverage, cumulative), roundabouts, points, cumulative)
    }

    private fun baseEvents(
        points: List<LatLon>,
        settings: RoadbookSettings,
        mapMatchedManeuvers: List<MapMatchedManeuver>,
        coverage: List<CoveredRange>?,
        cumulative: DoubleArray,
    ): List<Checkpoint> {
        // Route connue (map matching Valhalla) : seuls les vrais carrefours comptent — « si on reste
        // sur la même route, même si elle tourne, il n'y a pas de changement de direction » (retour
        // terrain it33). MAIS seulement là où Valhalla a réellement recalé la trace (it33 bis : un
        // recalage partiel faisait disparaître tous les changements de route du reste) — ailleurs,
        // la géométrie de la trace, comme sans map matching.
        if (coverage == null) {
            if (mapMatchedManeuvers.isNotEmpty()) return routeAwareEvents(mapMatchedManeuvers, points, cumulative, settings)
            return geometricEvents(points, cumulative, settings)
        }
        if (coverage.isEmpty()) return geometricEvents(points, cumulative, settings)
        val routeAware = routeAwareEvents(mapMatchedManeuvers, points, cumulative, settings)
        val routePositions = routeAware.map { it.trackCumulativeDistanceMeters ?: 0.0 }
        val outside = geometricEvents(points, cumulative, settings).filter { event ->
            val position = event.cumulativeDistanceMeters(cumulative) ?: return@filter false
            coverage.none { it.contains(position) } &&
                routePositions.none { abs(it - position) < settings.mergeMinDistanceMeters }
        }
        return (routeAware + outside)
            .sortedBy { it.cumulativeDistanceMeters(cumulative) ?: 0.0 }
            .mapIndexed { index, checkpoint -> checkpoint.copy(sequenceIndex = index + 1) }
    }

    /** Détection par la géométrie de la trace seule (virages, grappes, vrai demi-tour, fusion). */
    private fun geometricEvents(points: List<LatLon>, cumulative: DoubleArray, settings: RoadbookSettings): List<Checkpoint> {
        val windowBefore = settings.windowBeforeMeters
        val windowAfter = settings.windowAfterMeters
        val thresholds = settings.thresholds

        val candidates = mutableListOf<Candidate>()
        for (i in 1 until points.size - 1) {
            val angle = headingChange(cumulative[i], points, cumulative, windowBefore, windowAfter) ?: continue
            if (abs(angle) < thresholds.light) continue
            candidates.add(Candidate(i, angle))
        }

        val raw = mutableListOf<RawEvent>()
        for (candidate in clustered(candidates, points, cumulative, windowBefore, windowAfter, thresholds.light)) {
            val i = candidate.pointIndex
            val absAngle = abs(candidate.angle)
            val reversesOnSamePath = absAngle >= RoadbookConstants.U_TURN_MIN_DEGREES &&
                returnsOnSamePath(i, points, cumulative, min(windowBefore, windowAfter))
            if (reversesOnSamePath && isNearTrackEndpoint(cumulative[i], cumulative)) continue
            val tier = if (reversesOnSamePath) RoadbookTier.U_TURN else thresholds.tier(absAngle)
            val direction = when {
                tier == RoadbookTier.U_TURN -> TurnDirection.U_TURN
                candidate.angle > 0 -> TurnDirection.RIGHT
                else -> TurnDirection.LEFT
            }
            raw.add(RawEvent(points[i], absAngle, direction, tier, i))
        }

        return mergeNearby(raw, settings.mergeMinDistanceMeters, cumulative)
    }

    /** Manœuvre Valhalla placée sur la trace (position exacte le long d'elle, segment porteur). */
    private class Placed(val maneuver: MapMatchedManeuver, val projection: TrackGeometry.Projection, val order: Int) {
        val cumulative: Double get() = projection.cumulativeDistanceMeters
        val tier: RoadbookTier get() = maneuver.type.roadbookTier ?: RoadbookTier.LIGHT_DIRECTION_CHANGE
    }

    /**
     * Road Book quand la ROUTE est connue (it33, retour terrain : virages annoncés sur une route qui ne
     * fait que tourner, ronds-points écrasés par un « virage fort » géométrique, carrefours confus).
     * Événements = uniquement les points de décision Valhalla, jamais une courbe de la route :
     * - manœuvres à moins de [RoadbookConstants.JUNCTION_CLUSTER_METERS] l'une de l'autre = UN
     *   carrefour (deux manœuvres Valhalla pour le même croisement décalé), qualifié par le virage
     *   NET de la trace, de l'approche du premier à la sortie du dernier ;
     * - rond-point : entrée + sortie = UN événement, angle et sens de la sortie réellement prise ;
     * - virage en restant sur la MÊME route (même nom avant/après) : seulement s'il est franc
     *   (≥ palier « fort ») — sinon on suit la route ; sinon, virage si la trace tourne vraiment, ou
     *   changement de route avec un changement de cap sensible ;
     * - libellé et sens toujours issus de la géométrie de la trace suivie, position = vrai carrefour.
     */
    private fun routeAwareEvents(
        matchedManeuvers: List<MapMatchedManeuver>,
        points: List<LatLon>,
        cumulative: DoubleArray,
        settings: RoadbookSettings,
    ): List<Checkpoint> {
        val placed = mutableListOf<Placed>()
        var previous: Double? = null
        for ((order, maneuver) in matchedManeuvers.withIndex()) {
            if (maneuver.type.roadbookTier == null) continue
            val projection = placement(maneuver, points, cumulative, previous, RoadbookConstants.JUNCTION_CLUSTER_METERS) ?: continue
            previous = projection.cumulativeDistanceMeters
            placed.add(Placed(maneuver, projection, order))
        }
        placed.sortBy { it.cumulative }

        // Regroupement : rond-point (entrée → sortie), puis carrefours rapprochés.
        val groups = mutableListOf<MutableList<Placed>>()
        var i = 0
        while (i < placed.size) {
            val current = placed[i]
            if (current.maneuver.type == ValhallaManeuverType.ROUNDABOUT_ENTER) {
                // Entrée → première sortie à portée ; ce qui est entre les deux fait partie du rond-point.
                val exitIndex = (i + 1 until placed.size)
                    .takeWhile { placed[it].cumulative - current.cumulative <= RoadbookConstants.ROUNDABOUT_MAX_SPAN_METERS }
                    .firstOrNull { placed[it].maneuver.type == ValhallaManeuverType.ROUNDABOUT_EXIT }
                groups.add(if (exitIndex != null) placed.subList(i, exitIndex + 1).toMutableList() else mutableListOf(current))
                i = (exitIndex ?: i) + 1
                continue
            }
            val last = groups.lastOrNull()
            if (last != null && last.first().tier != RoadbookTier.ROUNDABOUT &&
                current.cumulative - last.last().cumulative <= RoadbookConstants.JUNCTION_CLUSTER_METERS
            ) {
                last.add(current)
            } else {
                groups.add(mutableListOf(current))
            }
            i++
        }

        val thresholds = settings.thresholds
        val events = mutableListOf<Checkpoint>()
        for (group in groups) {
            val first = group.first()
            val last = group.last()
            val net = headingChange(first.cumulative, last.cumulative, points, cumulative, settings.windowBeforeMeters, settings.windowAfterMeters)
                ?: headingChange(first.cumulative, points, cumulative, settings.windowBeforeMeters, settings.windowAfterMeters)
                ?: 0.0
            val kind = group.map { it.tier }.minByOrNull { TIER_PRIORITY.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                ?: RoadbookTier.LIGHT_DIRECTION_CHANGE
            var tier: RoadbookTier
            var direction: TurnDirection
            val roundaboutExitCount: Int? = null
            when (kind) {
                RoadbookTier.ROUNDABOUT -> {
                    tier = RoadbookTier.ROUNDABOUT
                    direction = when {
                        abs(net) < RoadbookConstants.ROUNDABOUT_STRAIGHT_TOLERANCE_DEGREES -> TurnDirection.STRAIGHT
                        net > 0 -> TurnDirection.RIGHT
                        else -> TurnDirection.LEFT
                    }
                    // Numéro Valhalla jamais repris (it34) : « 2 » pour tous les ronds-points de
                    // l'iPhone, faux une fois sur deux — seule l'analyse OSM donne un numéro.
                }
                RoadbookTier.FORK, RoadbookTier.MERGE -> {
                    tier = kind
                    direction = group.firstOrNull { it.tier == kind }?.maneuver?.type?.roadbookDirection ?: TurnDirection.STRAIGHT
                }
                RoadbookTier.U_TURN -> {
                    if (isNearTrackEndpoint(first.cumulative, cumulative)) continue
                    val uTurn = group.first { it.tier == RoadbookTier.U_TURN }.maneuver
                    if (uTurn.isSameRoadUTurn) {
                        tier = RoadbookTier.U_TURN
                        direction = TurnDirection.U_TURN
                    } else {
                        tier = RoadbookTier.VERY_HARD
                        direction = if (uTurn.type == ValhallaManeuverType.UTURN_LEFT) TurnDirection.LEFT else TurnDirection.RIGHT
                    }
                }
                else -> {
                    // Rues avant/après dans l'ORDRE DE LA ROUTE Valhalla, pas celui des positions
                    // projetées : deux manœuvres au même carrefour peuvent y être à égalité.
                    val routeOrder = group.sortedBy { it.order }
                    val before = routeOrder.first().maneuver.streetNamesBefore
                    val after = routeOrder.last().maneuver.streetNamesAfter
                    val sameRoad = before.any { it in after }
                    val changesRoad = before.isNotEmpty() && after.isNotEmpty() && !sameRoad
                    val isRealTurn = abs(net) >= thresholds.light
                    val keep = if (sameRoad) {
                        abs(net) >= thresholds.hard
                    } else {
                        isRealTurn || (changesRoad && abs(net) >= RoadbookConstants.ROAD_CHANGE_MIN_TURN_DEGREES)
                    }
                    if (!keep) continue
                    tier = if (isRealTurn) thresholds.tier(abs(net)) else RoadbookTier.LIGHT_DIRECTION_CHANGE
                    direction = if (net > 0) TurnDirection.RIGHT else TurnDirection.LEFT
                }
            }

            val segmentStart = first.projection.nearestSegmentIndex
            val segmentEnd = min(segmentStart + 1, points.size - 1)
            val pointIndex = if (first.cumulative - cumulative[segmentStart] <= cumulative[segmentEnd] - first.cumulative) segmentStart else segmentEnd
            events.add(
                Checkpoint(
                    coordinate = first.maneuver.coordinate,
                    turnAngleDegrees = abs(net),
                    direction = direction,
                    tier = tier,
                    sequenceIndex = events.size + 1,
                    sourcePointIndex = pointIndex,
                    roundaboutExitCount = roundaboutExitCount,
                    trackCumulativeDistanceMeters = first.cumulative,
                ),
            )
        }
        return events
    }

    /** Nature d'un carrefour à plusieurs manœuvres Valhalla : la plus contraignante l'emporte. */
    private val TIER_PRIORITY = listOf(RoadbookTier.ROUNDABOUT, RoadbookTier.U_TURN, RoadbookTier.FORK, RoadbookTier.MERGE, RoadbookTier.LIGHT_DIRECTION_CHANGE)

    /**
     * Passage de la trace où placer une manœuvre : celui qui correspond à sa progression le long de
     * la route recalée ; sans cette donnée, le premier passage plausible au-delà de la manœuvre
     * précédente (en sautant celui qui retomberait sur elle si un passage ultérieur est aussi proche).
     */
    private fun placement(
        maneuver: MapMatchedManeuver,
        points: List<LatLon>,
        cumulative: DoubleArray,
        previousMatchedCumulative: Double?,
        mergeMin: Double,
    ): TrackGeometry.Projection? {
        val maxOffTrack = RoadbookConstants.MAP_MATCH_MAX_OFF_TRACK_METERS
        val fraction = maneuver.routeProgressFraction
        val total = cumulative.lastOrNull() ?: 0.0
        if (fraction != null && total > 0) {
            val expected = fraction * total
            return TrackGeometry.passes(maneuver.coordinate, points, cumulative, maxOffTrack)
                .minByOrNull { abs(it.cumulativeDistanceMeters - expected) }
        }
        val candidates = TrackGeometry.passes(maneuver.coordinate, points, cumulative, maxOffTrack, previousMatchedCumulative ?: 0.0)
        val bestDistance = candidates.minOfOrNull { it.distanceToTrackMeters } ?: return null
        val plausible = candidates.filter { it.distanceToTrackMeters <= bestDistance + RoadbookConstants.MAP_MATCH_REPASS_TOLERANCE_METERS }
        if (previousMatchedCumulative != null && plausible.size > 1 &&
            plausible[0].cumulativeDistanceMeters - previousMatchedCumulative < mergeMin
        ) {
            return plausible[1]
        }
        return plausible.firstOrNull()
    }

    /** Changement de cap signé (droite > 0) à la distance cumulée `c`. `null` si la trace est trop courte autour. */
    fun headingChange(
        c: Double,
        points: List<LatLon>,
        cumulativeDistances: DoubleArray,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
    ): Double? = headingChange(c, c, points, cumulativeDistances, windowBeforeMeters, windowAfterMeters)

    /** Virage net d'une portion [start, end] : cap d'approche (corde finissant à start) vs cap de sortie (corde partant de end). */
    private fun headingChange(
        start: Double,
        end: Double,
        points: List<LatLon>,
        cumulativeDistances: DoubleArray,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
    ): Double? {
        if (cumulativeDistances.isEmpty()) return null
        val total = cumulativeDistances.last()
        val approachStart = max(start - windowBeforeMeters, 0.0)
        val exitEnd = min(end + windowAfterMeters, total)
        if (start - approachStart < windowBeforeMeters / 2 || exitEnd - end < windowAfterMeters / 2) return null
        val a = TrackGeometry.interpolatedCoordinate(approachStart, points, cumulativeDistances) ?: return null
        val s = TrackGeometry.interpolatedCoordinate(start, points, cumulativeDistances) ?: return null
        val e = TrackGeometry.interpolatedCoordinate(end, points, cumulativeDistances) ?: return null
        val b = TrackGeometry.interpolatedCoordinate(exitEnd, points, cumulativeDistances) ?: return null
        return signedAngleDifference(bearing(a, s), bearing(e, b))
    }

    /** Cap absolu (0-360°) moyen à suivre APRÈS la distance cumulée `c` ; repli sur le cap d'arrivée en fin de trace. */
    fun outgoingHeading(c: Double, points: List<LatLon>, cumulativeDistances: DoubleArray, windowAfterMeters: Double): Double {
        if (cumulativeDistances.isEmpty()) return 0.0
        val total = cumulativeDistances.last()
        if (total <= 0) return 0.0
        val m = TrackGeometry.interpolatedCoordinate(c, points, cumulativeDistances) ?: return 0.0
        val forward = if (total - c >= 1) TrackGeometry.interpolatedCoordinate(min(c + windowAfterMeters, total), points, cumulativeDistances) else null
        val heading = if (forward != null) {
            bearing(m, forward)
        } else {
            val back = TrackGeometry.interpolatedCoordinate(max(c - windowAfterMeters, 0.0), points, cumulativeDistances) ?: return 0.0
            bearing(back, m)
        }
        return (heading + 360) % 360
    }

    fun bearing(from: LatLon, to: LatLon): Double {
        val lat1 = from.latitude * PI / 180
        val lat2 = to.latitude * PI / 180
        val deltaLon = (to.longitude - from.longitude) * PI / 180
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return (atan2(y, x) * 180 / PI) % 360
    }

    /** Différence signée entre deux caps, normalisée dans (-180, 180]. Positif = droite. */
    fun signedAngleDifference(from: Double, to: Double): Double {
        var diff = (to - from) % 360
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }

    private data class Candidate(val pointIndex: Int, val angle: Double)

    private class RawEvent(
        val coordinate: LatLon,
        val angle: Double,
        val direction: TurnDirection,
        val tier: RoadbookTier,
        val pointIndex: Int,
    )

    /** Vrai demi-tour : le point `probeMeters` APRÈS le virage retombe sur le tracé des `probeMeters` PRÉCÉDENTS. */
    private fun returnsOnSamePath(i: Int, points: List<LatLon>, cumulativeDistances: DoubleArray, probeMeters: Double): Boolean {
        val c = cumulativeDistances[i]
        val probe = TrackGeometry.interpolatedCoordinate(c + probeMeters, points, cumulativeDistances) ?: return false
        val approachStart = TrackGeometry.interpolatedCoordinate(c - probeMeters, points, cumulativeDistances) ?: return false
        val approach = mutableListOf(approachStart)
        for (k in 0..i) {
            if (cumulativeDistances[k] > c - probeMeters) approach.add(points[k])
        }
        if (approach.size <= 1) return false
        val projection = TrackGeometry.project(probe, approach, TrackGeometry.cumulativeDistances(approach)) ?: return false
        return projection.distanceToTrackMeters <= RoadbookConstants.U_TURN_SAME_PATH_MAX_METERS
    }

    private fun isNearTrackEndpoint(cumulative: Double, cumulativeDistances: DoubleArray): Boolean {
        val total = cumulativeDistances.lastOrNull() ?: 0.0
        val guardMeters = RoadbookConstants.U_TURN_ENDPOINT_GUARD_METERS
        return cumulative < guardMeters || cumulative > total - guardMeters
    }

    /** Un événement par grappe de sommets rapprochés, portant le virage NET de la grappe. */
    private fun clustered(
        candidates: List<Candidate>,
        points: List<LatLon>,
        cumulativeDistances: DoubleArray,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        minimumTurnDegrees: Double,
    ): List<Candidate> {
        val groups = mutableListOf<MutableList<Candidate>>()
        for (candidate in candidates) {
            val last = groups.lastOrNull()?.lastOrNull()
            if (last != null &&
                cumulativeDistances[candidate.pointIndex] - cumulativeDistances[last.pointIndex] <= RoadbookConstants.TURN_CLUSTER_METERS
            ) {
                groups.last().add(candidate)
            } else {
                groups.add(mutableListOf(candidate))
            }
        }

        return groups.mapNotNull { group ->
            if (group.size <= 1) return@mapNotNull group.firstOrNull()
            val first = group.first()
            val last = group.last()
            val net = headingChange(
                cumulativeDistances[first.pointIndex],
                cumulativeDistances[last.pointIndex],
                points,
                cumulativeDistances,
                windowBeforeMeters,
                windowAfterMeters,
            ) ?: group.fold(0.0) { sum, candidate -> sum + candidate.angle }
            if (abs(net) < minimumTurnDegrees) return@mapNotNull null
            val apex = group.filter { (it.angle > 0) == (net > 0) }.firstMaxByAbsAngle()
            if (apex != null) return@mapNotNull Candidate(apex.pointIndex, net)
            // Grappe de plus de 180° : la différence de caps s'est « enroulée » (210° à droite se lit
            // −150°). Virage réel = 360° − |net|, dans le sens de ses sommets.
            val wrapped = group.firstMaxByAbsAngle() ?: return@mapNotNull null
            Candidate(wrapped.pointIndex, (if (wrapped.angle > 0) 1 else -1) * (360 - abs(net)))
        }
    }

    /** Premier élément d'angle absolu maximal (même choix qu'en Swift en cas d'égalité). */
    private fun List<Candidate>.firstMaxByAbsAngle(): Candidate? {
        var best: Candidate? = null
        for (candidate in this) {
            if (best == null || abs(best.angle) < abs(candidate.angle)) best = candidate
        }
        return best
    }

    /** Fusionne les événements trop rapprochés en gardant l'angle le plus marqué. */
    private fun mergeNearby(raw: List<RawEvent>, minDistanceMeters: Double, cumulativeDistances: DoubleArray): List<Checkpoint> {
        val merged = mutableListOf<RawEvent>()
        for (candidate in raw) {
            val lastIndex = merged.lastIndex
            if (lastIndex >= 0 && geodesicDistanceMeters(merged[lastIndex].coordinate, candidate.coordinate) < minDistanceMeters) {
                if (candidate.angle > merged[lastIndex].angle) merged[lastIndex] = candidate
            } else {
                merged.add(candidate)
            }
        }
        return merged.mapIndexed { index, item ->
            Checkpoint(
                coordinate = item.coordinate,
                turnAngleDegrees = item.angle,
                direction = item.direction,
                tier = item.tier,
                sequenceIndex = index + 1,
                sourcePointIndex = item.pointIndex,
                trackCumulativeDistanceMeters = cumulativeDistances[item.pointIndex],
            )
        }
    }
}
