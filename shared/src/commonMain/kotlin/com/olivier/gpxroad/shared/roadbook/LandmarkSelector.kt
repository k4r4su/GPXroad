package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sélection PURE des repères visibles le long d'une trace DÉJÀ dans son sens de parcours (portage de
 * `RoadbookLandmarkSelector`, jalon it28) : visibilité (rayon par catégorie, sens des panneaux, axe
 * de la route porteuse), côté, rattachement au carrefour, priorité et densité. Réglages :
 * [LandmarkConstants].
 */
object LandmarkSelector {
    private class Placement(val info: LandmarkInfo, val coordinate: LatLon, val cumulative: Double, val lateral: Double)

    fun select(
        data: LandmarkData,
        points: List<LatLon>,
        maneuvers: List<RoadbookManeuver>,
        enabledCategories: Set<LandmarkCategory> = LandmarkCategory.entries.toSet(),
        cityEntryFallbackEnabled: Boolean = LandmarkConstants.CITY_ENTRY_FALLBACK_ENABLED,
    ): LandmarkSelection {
        val cumulative = TrackGeometry.cumulativeDistances(points)
        if (points.size <= 1 || (cumulative.lastOrNull() ?: 0.0) <= 0) return LandmarkSelection.EMPTY

        val placements = data.candidates
            .filter { it.category in enabledCategories }
            .flatMap { visiblePlacements(it, points, cumulative) }
            .toMutableList()
        if (cityEntryFallbackEnabled && LandmarkCategory.CITY_SIGN in enabledCategories) {
            val mappedSigns = placements.filter { it.info.category == LandmarkCategory.CITY_SIGN }.map { it.cumulative to it.info.label }
            placements += CityEntryDetector.entries(data.builtUpAreas, data.places, mappedSigns, points, cumulative).map {
                Placement(LandmarkInfo(LandmarkCategory.CITY_SIGN, it.name, cityEntryName = it.name), it.coordinate, it.cumulativeDistanceMeters, 0.0)
            }
        }

        val maneuverPositions = maneuvers.map { it.cumulativeDistanceMeters }
        val attached = mutableMapOf<Int, Placement>()
        val standalone = mutableListOf<Placement>()
        // Services : voie à part — jamais rattachés à un virage ni écartés par la densité.
        val services = placements.filter { it.info.category.group == LandmarkGroup.SERVICE }
        for (placement in placements) {
            if (placement.info.category.group == LandmarkGroup.SERVICE) continue
            val nearest = maneuverPositions.indices.minByOrNull { abs(maneuverPositions[it] - placement.cumulative) }
            if (nearest != null && abs(maneuverPositions[nearest] - placement.cumulative) <= LandmarkConstants.JUNCTION_RADIUS_METERS) {
                val current = attached[nearest]
                if (current == null || isStronger(placement, current)) attached[nearest] = placement
            } else {
                standalone.add(placement)
            }
        }

        return LandmarkSelection(
            attached = attached.mapValues { it.value.info },
            standalone = (densityLimited(standalone, maneuverPositions) + deduplicatedServices(services))
                .sortedBy { it.cumulative }
                .map { LandmarkCheckpoint(it.info, it.coordinate, it.cumulative) },
        )
    }

    // Visibilité

    /** Un placement par PASSAGE de la trace à portée du candidat — côté et sens sur le cap d'arrivée. */
    private fun visiblePlacements(candidate: LandmarkCandidate, points: List<LatLon>, cumulative: DoubleArray): List<Placement> {
        val radius = candidate.category.visibilityRadiusMeters
        return TrackGeometry.passes(candidate.coordinate, points, cumulative, radius).mapNotNull { pass ->
            val onTrack = TrackGeometry.interpolatedCoordinate(pass.cumulativeDistanceMeters, points, cumulative) ?: return@mapNotNull null
            val heading = approachHeading(pass.cumulativeDistanceMeters, points, cumulative) ?: return@mapNotNull null
            val axes = candidate.roadAxes
            if (candidate.category.requiresRoadAlignment && !axes.isNullOrEmpty() && axes.none { isAligned(it, heading) }) return@mapNotNull null
            val orientation = candidate.orientation
            if (candidate.category.isDirectional && orientation != null && !isSeen(orientation, heading)) return@mapNotNull null
            // Côté seulement pour ce qui est posé À CÔTÉ de la route.
            val isOnRoad = candidate.category.isOnRoad || !axes.isNullOrEmpty()
            val side = if (isOnRoad || pass.distanceToTrackMeters < LandmarkConstants.SIDE_MIN_OFFSET_METERS) null else side(candidate.coordinate, onTrack, heading)
            val showsDistance = candidate.category.group == LandmarkGroup.SERVICE &&
                pass.distanceToTrackMeters >= LandmarkConstants.SERVICE_SHOW_DISTANCE_FROM_METERS
            Placement(
                LandmarkInfo(candidate.category, candidate.label, side, if (showsDistance) pass.distanceToTrackMeters else null),
                candidate.coordinate,
                pass.cumulativeDistanceMeters,
                pass.distanceToTrackMeters,
            )
        }
    }

    /** Un panneau vu de dos est ignoré. */
    private fun isSeen(orientation: LandmarkOrientation, travelHeading: Double): Boolean = when (orientation) {
        is LandmarkOrientation.AppliesToTravelBearing ->
            abs(RoadbookAnalyzer.signedAngleDifference(orientation.bearing, travelHeading)) < 90
        is LandmarkOrientation.Faces ->
            abs(RoadbookAnalyzer.signedAngleDifference(orientation.bearing + 180, travelHeading)) <= LandmarkConstants.SIGN_FACING_TOLERANCE_DEGREES
    }

    /** Cap de la trajectoire d'ARRIVÉE sur le repère (corde des derniers mètres) ; tout début de trace : vers l'avant. */
    private fun approachHeading(c: Double, points: List<LatLon>, cumulative: DoubleArray): Double? {
        val total = cumulative.lastOrNull() ?: 0.0
        val span = LandmarkConstants.APPROACH_METERS
        val from = if (c >= span / 2) max(c - span, 0.0) else c
        val to = if (c >= span / 2) c else min(c + span, total)
        val a = TrackGeometry.interpolatedCoordinate(from, points, cumulative) ?: return null
        val b = TrackGeometry.interpolatedCoordinate(to, points, cumulative) ?: return null
        if (geodesicDistanceMeters(a, b) <= 1) return null
        return RoadbookAnalyzer.bearing(a, b)
    }

    /** Axe de route aligné sur le cap, dans un sens ou dans l'autre. */
    private fun isAligned(axis: Double, heading: Double): Boolean {
        val difference = abs(RoadbookAnalyzer.signedAngleDifference(axis, heading))
        val tolerance = LandmarkConstants.ROAD_ALIGNMENT_TOLERANCE_DEGREES
        return difference <= tolerance || difference >= 180 - tolerance
    }

    private fun side(target: LatLon, origin: LatLon, travelHeading: Double): LandmarkSide {
        val toTarget = RoadbookAnalyzer.bearing(origin, target)
        return if (RoadbookAnalyzer.signedAngleDifference(travelHeading, toTarget) > 0) LandmarkSide.RIGHT else LandmarkSide.LEFT
    }

    // Priorité et densité

    /** Famille (priorité fixe), puis ordre du catalogue, puis le plus proche de la trace. */
    private fun isStronger(lhs: Placement, rhs: Placement): Boolean {
        val l = LandmarkConstants.GROUP_PRIORITY.indexOf(lhs.info.category.group)
        val r = LandmarkConstants.GROUP_PRIORITY.indexOf(rhs.info.category.group)
        if (l != r) return l < r
        if (lhs.info.category.ordinal != rhs.info.category.ordinal) return lhs.info.category.ordinal < rhs.info.category.ordinal
        return lhs.lateral < rhs.lateral
    }

    /**
     * Repères trop proches fusionnés (le plus prioritaire reste), puis au plus
     * [LandmarkConstants.MAX_PER_SEGMENT] par tronçon entre deux changements de direction — les
     * entrées d'agglomération n'en sont jamais écartées.
     */
    private fun densityLimited(placements: List<Placement>, maneuverPositions: List<Double>): List<Placement> {
        val merged = mutableListOf<Placement>()
        for (placement in placements.sortedBy { it.cumulative }) {
            val last = merged.lastOrNull()
            if (last != null && placement.cumulative - last.cumulative < LandmarkConstants.MERGE_METERS) {
                if (isStronger(placement, last)) merged[merged.lastIndex] = placement
            } else {
                merged.add(placement)
            }
        }
        return merged
            .groupBy { placement -> maneuverPositions.count { it < placement.cumulative } }
            .values
            .flatMap { segment ->
                val entries = segment.filter { it.info.category == LandmarkCategory.CITY_SIGN }
                val others = segment.filter { it.info.category != LandmarkCategory.CITY_SIGN }
                    .sortedWith { a, b -> if (isStronger(a, b)) -1 else if (isStronger(b, a)) 1 else 0 }
                entries + others.take(max(LandmarkConstants.MAX_PER_SEGMENT - entries.size, 0))
            }
            .sortedBy { it.cumulative }
    }

    /** Doublons d'un même service (nœud + surface, deux bornes d'une station) : le plus proche de la trace reste. */
    private fun deduplicatedServices(services: List<Placement>): List<Placement> {
        val kept = mutableListOf<Placement>()
        for (placement in services.sortedBy { it.cumulative }) {
            val index = kept.indexOfLast {
                it.info.category == placement.info.category && placement.cumulative - it.cumulative < LandmarkConstants.SERVICE_MERGE_METERS
            }
            if (index >= 0) {
                if (placement.lateral < kept[index].lateral) kept[index] = placement
            } else {
                kept.add(placement)
            }
        }
        return kept
    }
}

/** Entrée de localité CALCULÉE (repli it29) : là où la trace entre dans une zone bâtie nommée. */
data class CityEntry(val name: String, val coordinate: LatLon, val cumulativeDistanceMeters: Double)

/**
 * Repli « Entrée de <localité> » (portage de `RoadbookCityEntryDetector`, it29) — pur, aucun
 * réseau. Les panneaux `city_limit` sont rarement cartographiés, les zones bâties
 * (`landuse=residential`, polygones `place`) le sont partout : l'entrée est placée au bord de la
 * zone bâtie, nommée d'après le polygone `place` traversé ou le nœud `place` le plus proche. Un
 * panneau cartographié gagne ; jamais deux entrées de suite dans la même localité.
 */
object CityEntryDetector {
    private class Ring(val points: List<LatLon>, val name: String?) {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }

        /** Pair-impair (lancer de rayon), après un test de boîte englobante. */
        fun contains(p: LatLon): Boolean {
            if (p.latitude < minLat || p.latitude > maxLat || p.longitude < minLon || p.longitude > maxLon) return false
            var inside = false
            var j = points.size - 1
            for (i in points.indices) {
                val a = points[i]
                val b = points[j]
                if ((a.latitude > p.latitude) != (b.latitude > p.latitude) &&
                    p.longitude < (b.longitude - a.longitude) * (p.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }
    }

    private class Run(var start: Double, var end: Double, var areaName: String?)

    private class Segment(val start: Double, var end: Double, val name: String, val coordinate: LatLon)

    fun entries(
        areas: List<BuiltUpArea>,
        places: List<Place>,
        mappedSigns: List<Pair<Double, String>>,
        points: List<LatLon>,
        cumulative: DoubleArray,
    ): List<CityEntry> {
        val rings = areas.flatMap { area -> area.rings.filter { it.size >= 3 }.map { Ring(it, area.name) } }
        val total = cumulative.lastOrNull() ?: 0.0
        if (rings.isEmpty() || points.size <= 1 || total <= 0) return emptyList()

        val step = LandmarkConstants.CITY_ENTRY_SAMPLE_METERS
        fun coordinate(meters: Double) = TrackGeometry.interpolatedCoordinate(meters, points, cumulative)

        // Passages en zone bâtie (échantillons consécutifs à l'intérieur).
        val runs = mutableListOf<Run>()
        var meters = 0.0
        while (meters <= total) {
            val p = coordinate(meters)
            if (p != null && rings.any { it.contains(p) }) {
                val named = rings.firstOrNull { it.name != null && it.contains(p) }?.name
                val last = runs.lastOrNull()
                if (last != null && meters - last.end <= step * 1.5) {
                    last.end = meters
                    last.areaName = last.areaName ?: named
                } else {
                    runs.add(Run(meters, meters, named))
                }
            }
            meters += step
        }

        // Traversées : passages séparés de moins de MERGE_GAP (village morcelé, villages mitoyens).
        val traversals = mutableListOf<MutableList<Run>>()
        for (run in runs) {
            val last = traversals.lastOrNull()?.lastOrNull()
            if (last != null && run.start - last.end <= LandmarkConstants.CITY_ENTRY_MERGE_GAP_METERS) {
                traversals.last().add(run)
            } else {
                traversals.add(mutableListOf(run))
            }
        }

        val entries = mutableListOf<CityEntry>()
        val announced = mappedSigns.toMutableList()
        for (traversal in traversals) {
            val first = traversal.first()
            val last = traversal.last()
            if (last.end - first.start < LandmarkConstants.CITY_ENTRY_MIN_RUN_METERS) continue
            // Localité le long de la traversée : l'entrée, puis chaque CHANGEMENT de localité.
            val segments = mutableListOf<Segment>()
            for (run in traversal) {
                var m = run.start
                while (m <= run.end) {
                    val p = coordinate(m)
                    val name = if (p != null) run.areaName ?: placeName(p, places) else null
                    if (p != null && name != null) {
                        val lastSegment = segments.lastOrNull()
                        if (lastSegment != null && lastSegment.name == name) lastSegment.end = m else segments.add(Segment(m, m, name, p))
                    }
                    m += LandmarkConstants.CITY_ENTRY_NAME_CHECK_METERS
                }
            }
            // Hystérésis : un changement de localité qui ne dure pas n'en est pas un.
            val stable = segments.filterIndexed { index, segment ->
                index == 0 || segment.end - segment.start >= LandmarkConstants.CITY_ENTRY_NAME_MIN_STRETCH_METERS
            }
            for (segment in stable) {
                val name = segment.name
                val previous = announced.filter { it.first < segment.start }.maxByOrNull { it.first }
                if (previous?.second == name) continue
                // Trace qui DÉMARRE dans la localité : pas une entrée, mais on y est.
                if (segment.start < step) {
                    announced.add(segment.start to name)
                    continue
                }
                val dedup = LandmarkConstants.CITY_ENTRY_SIGN_DEDUP_METERS
                if (mappedSigns.any {
                        abs(it.first - segment.start) < dedup ||
                            (it.second == name && it.first >= first.start - dedup && it.first <= last.end + dedup)
                    }
                ) {
                    continue
                }
                entries.add(CityEntry(name, segment.coordinate, segment.start))
                announced.add(segment.start to name)
            }
        }
        return entries
    }

    /**
     * Localité la plus proche du point d'entrée (dans sa portée). Un quartier n'est candidat que si
     * aucune ville n'est proche ; le nœud `village` siège d'une commune nouvelle (un `suburb` tout
     * près) est écarté au profit des anciens villages, dont les panneaux portent les noms.
     */
    fun placeName(coordinate: LatLon, places: List<Place>): String? {
        val withDistance = places.map { it to geodesicDistanceMeters(coordinate, it.coordinate) }
            .filter { it.second <= LandmarkConstants.placeReachMeters(it.first.kind) }
        val nearCity = withDistance.any { it.first.kind == PlaceKind.TOWN || it.first.kind == PlaceKind.CITY }
        fun isMergedCommuneSeat(place: Place) = place.kind == PlaceKind.VILLAGE && places.any {
            it.kind == PlaceKind.SUBURB && geodesicDistanceMeters(it.coordinate, place.coordinate) <= LandmarkConstants.CITY_ENTRY_PARENT_SEAT_METERS
        }
        return withDistance
            .filter { if (nearCity) it.first.kind != PlaceKind.SUBURB else !isMergedCommuneSeat(it.first) }
            .minByOrNull { it.second }
            ?.first?.name
    }
}

/** Une ligne du Road Book : manœuvre (avec son rang) OU repère, dans l'ordre de la trace. */
sealed class RoadbookEntry {
    abstract val cumulativeDistanceMeters: Double

    data class Maneuver(val maneuver: RoadbookManeuver, val index: Int) : RoadbookEntry() {
        override val cumulativeDistanceMeters: Double get() = maneuver.cumulativeDistanceMeters
    }

    data class Landmark(val landmark: LandmarkCheckpoint) : RoadbookEntry() {
        override val cumulativeDistanceMeters: Double get() = landmark.cumulativeDistanceMeters
    }

    companion object {
        /** À distance égale, la manœuvre passe avant le repère (tri stable). */
        fun merge(maneuvers: List<RoadbookManeuver>, landmarks: List<LandmarkCheckpoint>): List<RoadbookEntry> =
            (maneuvers.mapIndexed { index, maneuver -> Maneuver(maneuver, index) } + landmarks.map { Landmark(it) })
                .sortedBy { it.cumulativeDistanceMeters }
    }
}
