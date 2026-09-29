package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.abs

/**
 * Position courante rapportée à la trace. [cumulativeDistanceMeters] : avancement le long de la
 * trace (`null` tant qu'on n'a jamais été sur la trace) ; [distanceToTrackMeters] : écart latéral.
 */
data class TrackMatch(val cumulativeDistanceMeters: Double?, val distanceToTrackMeters: Double, val isOffTrack: Boolean)

/**
 * Suivi de la position le long d'une trace, fix après fix (Road Book assisté et Ride sur Android ;
 * même règle que le Road Book iOS) :
 * - parmi les PASSAGES de la trace près de la position (boucle, aller-retour), on garde le plus
 *   proche DEVANT l'avancement précédent — jamais un saut vers l'autre sens d'un aller-retour ;
 * - hors trace : règle unique [OffTrackDetector] (hystérésis), l'avancement reste figé.
 */
class LiveTrackMatcher {
    private var lastCumulative: Double? = null
    private var offTrack = false

    fun update(position: LatLon, points: List<LatLon>, cumulativeDistances: DoubleArray): TrackMatch {
        val passes = TrackGeometry.passes(position, points, cumulativeDistances, SEARCH_METERS)
        val chosen = if (passes.isEmpty()) {
            null
        } else {
            val best = passes.minOf { it.distanceToTrackMeters }
            val plausible = passes.filter { it.distanceToTrackMeters <= best + RoadbookConstants.MAP_MATCH_REPASS_TOLERANCE_METERS }
            val last = lastCumulative
            when {
                last == null -> plausible.first()
                // On avance le long de la trace : le passage le plus proche DEVANT (petit recul toléré
                // pour l'imprécision GPS), sinon le plus proche tout court.
                else -> plausible.filter { it.cumulativeDistanceMeters >= last - BACKTRACK_TOLERANCE_METERS }
                    .minByOrNull { it.cumulativeDistanceMeters - last }
                    ?: plausible.minBy { abs(it.cumulativeDistanceMeters - last) }
            }
        }
        val distance = chosen?.distanceToTrackMeters
            ?: TrackGeometry.project(position, points, cumulativeDistances)?.distanceToTrackMeters
            ?: Double.MAX_VALUE
        offTrack = OffTrackDetector.isOffTrack(offTrack, distance)
        if (chosen != null && !offTrack) lastCumulative = chosen.cumulativeDistanceMeters
        return TrackMatch(lastCumulative, distance, offTrack)
    }

    fun reset() {
        lastCumulative = null
        offTrack = false
    }

    private companion object {
        /** Passages cherchés jusqu'à cette distance (au-delà : hors trace de toute façon). */
        const val SEARCH_METERS = 60.0
        /** Recul accepté sur la trace entre deux positions (imprécision GPS, arrêt). */
        const val BACKTRACK_TOLERANCE_METERS = 50.0
    }
}
