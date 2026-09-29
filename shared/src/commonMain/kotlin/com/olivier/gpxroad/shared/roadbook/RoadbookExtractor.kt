package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.max

/**
 * Une ligne du Road Book : l'événement, ses distances partielle (depuis la manœuvre précédente) et
 * cumulée, et le cap absolu moyen à suivre juste après (0-360°).
 */
data class RoadbookManeuver(
    val checkpoint: Checkpoint,
    val partialDistanceMeters: Double,
    val cumulativeDistanceMeters: Double,
    val headingDegrees: Double,
)

/**
 * Liste ORDONNÉE des manœuvres d'un Road Book (portage de `RoadbookExtractor.swift`) — pure : une
 * trace (déjà dans son sens de parcours) en entrée, aucun état partagé lu ni écrit.
 */
object RoadbookExtractor {
    fun maneuvers(
        points: List<LatLon>,
        settings: RoadbookSettings,
        mapMatchedManeuvers: List<MapMatchedManeuver> = emptyList(),
        coverage: List<CoveredRange>? = null,
        roundabouts: List<RoundaboutPassage> = emptyList(),
    ): List<RoadbookManeuver> {
        val checkpoints = RoadbookAnalyzer.buildRoadbookEvents(points, settings, mapMatchedManeuvers, coverage, roundabouts)
        if (checkpoints.isEmpty()) return emptyList()
        val cumulative = TrackGeometry.cumulativeDistances(points)
        var previousCumulative = 0.0
        return checkpoints.map { checkpoint ->
            val position = checkpoint.cumulativeDistanceMeters(cumulative) ?: previousCumulative
            val partial = max(position - previousCumulative, 0.0)
            previousCumulative = position
            RoadbookManeuver(
                checkpoint = checkpoint,
                partialDistanceMeters = partial,
                cumulativeDistanceMeters = position,
                // Rond-point : cap APRÈS la sortie de l'anneau, pas celui de l'entrée.
                headingDegrees = RoadbookAnalyzer.outgoingHeading(checkpoint.roundabout?.exitCumulativeMeters ?: position, points, cumulative, settings.windowAfterMeters),
            )
        }
    }
}
