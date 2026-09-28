package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * INSTANTANÉ FIGÉ du Road Book (it33) — même trace de référence (4 branches, 3 virages francs),
 * mêmes repères et même résultat attendu que `RoadbookStableRegressionTests` (garde-fou du jalon
 * it28, côté iOS), mais sur la chaîne Kotlin seule : manœuvres, sélection des repères, fusion. Les
 * candidats sont construits tels que le décodage Overpass (Swift) les produit. Si ce test casse,
 * le comportement validé du Road Book a changé — voir « Jalon stable » dans CLAUDE.md.
 */
class RoadbookReferenceSnapshotTest {
    private val origin = LatLon(47.60, 7.35)
    private val legs = listOf(0.0 to 1500.0, 90.0 to 1200.0, 0.0 to 1000.0, 135.0 to 800.0)

    private fun destination(from: LatLon, bearingDegrees: Double, distanceMeters: Double): LatLon {
        val earthRadius = 6_371_000.0
        val bearing = bearingDegrees * PI / 180
        val lat1 = from.latitude * PI / 180
        val lon1 = from.longitude * PI / 180
        val angular = distanceMeters / earthRadius
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return LatLon(lat2 * 180 / PI, lon2 * 180 / PI)
    }

    private val legStarts: List<LatLon> by lazy {
        val starts = mutableListOf(origin)
        for (leg in legs.dropLast(1)) starts.add(destination(starts.last(), leg.first, leg.second))
        starts
    }

    private val referenceTrack: List<LatLon> by lazy {
        val points = mutableListOf<LatLon>()
        legs.forEachIndexed { index, (bearing, length) ->
            var meters = if (index == 0) 0.0 else 10.0
            while (meters <= length) {
                points.add(destination(legStarts[index], bearing, meters))
                meters += 10.0
            }
        }
        points
    }

    private fun at(leg: Int, along: Double, lateral: Double = 0.0): LatLon {
        val onTrack = destination(legStarts[leg], legs[leg].first, along)
        if (lateral == 0.0) return onTrack
        return destination(onTrack, legs[leg].first + if (lateral > 0) 90 else -90, abs(lateral))
    }

    /** Candidats tels que le décodage Overpass les produit (réponse de référence du jalon it28). */
    private val referenceData: LandmarkData by lazy {
        val sideStop = at(0, 900.0, 10.0)
        val sideRoadAxis = RoadbookAnalyzer.bearing(at(0, 900.0, -5.0), at(0, 900.0, 60.0))
        LandmarkData(
            candidates = listOf(
                // Pièges : stop d'une rue latérale, panneau vu de dos, église trop loin, boulangerie désactivée.
                LandmarkCandidate(LandmarkCategory.STOP_SIGN, "Stop", sideStop, roadAxes = listOf(sideRoadAxis)),
                LandmarkCandidate(LandmarkCategory.CITY_SIGN, "Dos", at(2, 400.0, 6.0), LandmarkOrientation.Faces(0.0)),
                LandmarkCandidate(LandmarkCategory.CHURCH, "Église lointaine", at(1, 800.0, 300.0)),
                LandmarkCandidate(LandmarkCategory.BAKERY, "Au bon pain", at(2, 250.0, 12.0)),
                // Repères attendus.
                LandmarkCandidate(LandmarkCategory.CITY_SIGN, "Hundsbach", at(0, 400.0, 6.0), LandmarkOrientation.Faces(180.0)),
                LandmarkCandidate(LandmarkCategory.CHURCH, "Église Saint-Blaise", at(1, 600.0, 15.0)),
                LandmarkCandidate(LandmarkCategory.GIVE_WAY_SIGN, "Cédez-le-passage", at(2, 10.0, -8.0)),
                LandmarkCandidate(LandmarkCategory.FUEL, "Total", at(2, 500.0, -120.0)),
                LandmarkCandidate(LandmarkCategory.SPEED_BUMP, "Ralentisseur", at(3, 350.0)),
            ),
        )
    }

    private fun km(meters: Double): String {
        val hundredths = (meters / 10).roundToLong()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    private fun describe(info: LandmarkInfo): String {
        val lateral = info.lateralDistanceMeters?.let { " ${(it / 10).roundToInt() * 10} m" } ?: ""
        return "${info.category} ${info.label}${info.side?.let { " $it" } ?: ""}$lateral"
    }

    private fun roadBook(points: List<LatLon>, mapMatched: List<MapMatchedManeuver> = emptyList()): List<String> {
        val maneuvers = RoadbookExtractor.maneuvers(points, RoadbookSettings(), mapMatched)
        val enabled = LandmarkCategory.entries.filter { it.isEnabledByDefault }.toSet()
        val selection = LandmarkSelector.select(referenceData, points, maneuvers, enabled)
        return RoadbookEntry.merge(maneuvers, selection.standalone).map { entry ->
            when (entry) {
                is RoadbookEntry.Maneuver -> {
                    val attached = selection.attached[entry.index]?.let { " + ${describe(it)}" } ?: ""
                    "${entry.index + 1}. ${km(entry.cumulativeDistanceMeters)} km ${entry.maneuver.checkpoint.tier} ${entry.maneuver.checkpoint.direction}$attached"
                }
                is RoadbookEntry.Landmark -> "   ${km(entry.cumulativeDistanceMeters)} km ${describe(entry.landmark.info)}"
            }
        }
    }

    @Test
    fun referenceRoadBookForward() {
        assertEquals(
            listOf(
                "   0.40 km CITY_SIGN Hundsbach RIGHT",
                "1. 1.50 km HARD RIGHT",
                "   2.10 km CHURCH Église Saint-Blaise RIGHT",
                "2. 2.70 km HARD LEFT + GIVE_WAY_SIGN Cédez-le-passage LEFT",
                "   3.20 km FUEL Total LEFT 120 m",
                "3. 3.70 km VERY_HARD RIGHT",
                "   4.05 km SPEED_BUMP Ralentisseur",
            ),
            roadBook(referenceTrack),
        )
    }

    @Test
    fun referenceRoadBookReversed() {
        assertEquals(
            listOf(
                "   0.45 km SPEED_BUMP Ralentisseur",
                "1. 0.80 km VERY_HARD LEFT",
                "   1.30 km FUEL Total RIGHT 120 m",
                "   1.40 km CITY_SIGN Dos LEFT",
                "2. 1.80 km HARD RIGHT + GIVE_WAY_SIGN Cédez-le-passage RIGHT",
                "   2.40 km CHURCH Église Saint-Blaise LEFT",
                "3. 3.00 km HARD LEFT",
            ),
            roadBook(referenceTrack.reversed()),
        )
    }

    @Test
    fun referenceRoadBookWithRouteAwareManeuvers() {
        val mapMatched = listOf(
            MapMatchedManeuver(at(0, 800.0), ValhallaManeuverType.ROUNDABOUT_ENTER, 2, 800.0 / 4500),
            MapMatchedManeuver(legStarts[1], ValhallaManeuverType.RIGHT, null, 1500.0 / 4500),
            MapMatchedManeuver(at(1, 300.0), ValhallaManeuverType.CONTINUE_STRAIGHT, null, 1800.0 / 4500),
            MapMatchedManeuver(legStarts[2], ValhallaManeuverType.LEFT, null, 2700.0 / 4500),
            MapMatchedManeuver(legStarts[3], ValhallaManeuverType.SHARP_RIGHT, null, 3700.0 / 4500),
        )
        assertEquals(
            listOf(
                "   0.40 km CITY_SIGN Hundsbach RIGHT",
                "1. 0.80 km ROUNDABOUT STRAIGHT",
                "2. 1.50 km HARD RIGHT",
                "   2.10 km CHURCH Église Saint-Blaise RIGHT",
                "3. 2.70 km HARD LEFT + GIVE_WAY_SIGN Cédez-le-passage LEFT",
                "   3.20 km FUEL Total LEFT 120 m",
                "4. 3.70 km VERY_HARD RIGHT",
                "   4.05 km SPEED_BUMP Ralentisseur",
            ),
            roadBook(referenceTrack, mapMatched),
        )
    }
}
