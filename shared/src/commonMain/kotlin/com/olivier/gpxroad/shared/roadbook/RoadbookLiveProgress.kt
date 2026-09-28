package com.olivier.gpxroad.shared.roadbook

import kotlin.math.max

/** Prochain élément du Road Book à annoncer : son rang et la distance restante (m). */
data class LiveProgress(val index: Int, val distanceRemainingMeters: Double)

/**
 * Élément à venir en mode Assisté GPS (portage de `RoadbookLiveProgress.swift`) — pur, ne modifie
 * jamais la liste. Compte à rebours jusqu'à 0 m, puis maintien de l'élément ATTEINT pendant
 * [RoadbookConstants.LIVE_MANEUVER_HOLD_AFTER_METERS], sauf si le suivant est plus proche que cette
 * zone (virages enchaînés). Seul l'ordre le long de la trace compte (virage ou repère).
 */
object RoadbookLiveProgress {
    /** @param positions distances cumulées CROISSANTES des éléments. `null` : plus rien à venir. */
    fun next(positions: List<Double>, currentCumulativeDistanceMeters: Double): LiveProgress? {
        val first = positions.firstOrNull() ?: return null
        val reachedIndex = positions.indexOfLast { it <= currentCumulativeDistanceMeters }
        if (reachedIndex < 0) return LiveProgress(0, max(first - currentCumulativeDistanceMeters, 0.0))

        val hold = RoadbookConstants.LIVE_MANEUVER_HOLD_AFTER_METERS
        val distancePastReached = currentCumulativeDistanceMeters - positions[reachedIndex]
        val nextIndex = reachedIndex + 1
        if (nextIndex > positions.lastIndex) {
            return if (distancePastReached < hold) LiveProgress(reachedIndex, 0.0) else null
        }
        val gapToNext = positions[nextIndex] - positions[reachedIndex]
        if (distancePastReached < hold && gapToNext > hold) return LiveProgress(reachedIndex, 0.0)
        return LiveProgress(nextIndex, max(positions[nextIndex] - currentCumulativeDistanceMeters, 0.0))
    }
}

/**
 * Règle « Hors trace » UNIQUE (Ride et Road Book) : hystérésis à deux seuils — on sort au-delà de
 * [RoadbookConstants.OFF_TRACK_ENTER_METERS], on ne revient qu'en deçà de
 * [RoadbookConstants.OFF_TRACK_EXIT_METERS] ; entre les deux, rien ne change.
 */
object OffTrackDetector {
    fun isOffTrack(wasOffTrack: Boolean, distanceToTrackMeters: Double): Boolean =
        if (wasOffTrack) {
            distanceToTrackMeters > RoadbookConstants.OFF_TRACK_EXIT_METERS
        } else {
            distanceToTrackMeters > RoadbookConstants.OFF_TRACK_ENTER_METERS
        }
}
