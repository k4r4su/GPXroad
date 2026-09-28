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

    fun buildRoadbookEvents(
        points: List<LatLon>,
        settings: RoadbookSettings,
        mapMatchedManeuvers: List<MapMatchedManeuver> = emptyList(),
    ): List<Checkpoint> {
        val windowBefore = settings.windowBeforeMeters
        val windowAfter = settings.windowAfterMeters
        if (points.size <= 2 || windowBefore <= 0 || windowAfter <= 0) return emptyList()
        val cumulative = TrackGeometry.cumulativeDistances(points)
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

        val geometricEvents = mergeNearby(raw, settings.mergeMinDistanceMeters, cumulative)
        if (mapMatchedManeuvers.isEmpty()) return geometricEvents
        return mergingMapMatchedDirectionChanges(mapMatchedManeuvers, geometricEvents, points, cumulative, settings)
    }

    /**
     * Fusionne les manœuvres de map matching dans la liste géométrique, dans l'ordre de progression :
     * position = vrai carrefour projeté (distance INTERPOLÉE) sur le bon passage de la trace ;
     * doublon mesuré le long de la trace ; libellé et sens issus de la géométrie de la trace.
     */
    private fun mergingMapMatchedDirectionChanges(
        matchedManeuvers: List<MapMatchedManeuver>,
        geometricEvents: List<Checkpoint>,
        points: List<LatLon>,
        cumulative: DoubleArray,
        settings: RoadbookSettings,
    ): List<Checkpoint> {
        val combined = geometricEvents.toMutableList()
        var previousMatchedCumulative: Double? = null
        val thresholds = settings.thresholds
        val mergeMin = settings.mergeMinDistanceMeters

        for (maneuver in matchedManeuvers) {
            var tier = maneuver.type.roadbookTier ?: continue
            val projection = placement(maneuver, points, cumulative, previousMatchedCumulative, mergeMin) ?: continue
            previousMatchedCumulative = projection.cumulativeDistanceMeters
            val exactCumulative = projection.cumulativeDistanceMeters
            if (combined.any { abs((it.cumulativeDistanceMeters(cumulative) ?: Double.POSITIVE_INFINITY) - exactCumulative) < mergeMin }) continue

            val turn = headingChange(exactCumulative, points, cumulative, settings.windowBeforeMeters, settings.windowAfterMeters) ?: 0.0
            var direction = maneuver.type.roadbookDirection
            when (tier) {
                RoadbookTier.ROUNDABOUT, RoadbookTier.FORK, RoadbookTier.MERGE -> Unit
                RoadbookTier.U_TURN -> {
                    if (isNearTrackEndpoint(exactCumulative, cumulative)) continue
                    if (!maneuver.isSameRoadUTurn) {
                        tier = RoadbookTier.VERY_HARD
                        direction = if (maneuver.type == ValhallaManeuverType.UTURN_LEFT) TurnDirection.LEFT else TurnDirection.RIGHT
                    }
                }
                else -> {
                    val isRealTurn = abs(turn) >= thresholds.light
                    val isRoadChange = maneuver.changesRoadName && abs(turn) >= RoadbookConstants.ROAD_CHANGE_MIN_TURN_DEGREES
                    if (!isRealTurn && !isRoadChange) continue
                    tier = if (isRealTurn) thresholds.tier(abs(turn)) else RoadbookTier.LIGHT_DIRECTION_CHANGE
                    direction = if (turn > 0) TurnDirection.RIGHT else TurnDirection.LEFT
                }
            }

            val segmentStart = projection.nearestSegmentIndex
            val segmentEnd = min(segmentStart + 1, points.size - 1)
            val pointIndex = if (exactCumulative - cumulative[segmentStart] <= cumulative[segmentEnd] - exactCumulative) segmentStart else segmentEnd

            combined.add(
                Checkpoint(
                    coordinate = maneuver.coordinate,
                    turnAngleDegrees = abs(turn),
                    direction = direction,
                    tier = tier,
                    sequenceIndex = 0,
                    sourcePointIndex = pointIndex,
                    roundaboutExitCount = maneuver.roundaboutExitCount,
                    trackCumulativeDistanceMeters = exactCumulative,
                ),
            )
        }

        return combined
            .sortedBy { it.cumulativeDistanceMeters(cumulative) ?: 0.0 }
            .mapIndexed { index, checkpoint -> checkpoint.copy(sequenceIndex = index + 1) }
    }

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
