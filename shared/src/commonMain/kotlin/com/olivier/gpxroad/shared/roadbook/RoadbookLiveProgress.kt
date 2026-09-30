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

/**
 * Ce que le Road Book assisté affiche EN GRAND : [heroIndex] (rang dans la liste virages + repères)
 * et sa distance ; [leadingLandmarkIndex] : repère de décor passé en ligne secondaire de la carte
 * du virage (« ⛪ Église à 150 m »), `null` sinon.
 */
data class RoadbookFocus(
    val heroIndex: Int,
    val distanceRemainingMeters: Double,
    val leadingLandmarkIndex: Int? = null,
    val leadingLandmarkDistanceMeters: Double? = null,
)

/**
 * Priorité au virage (retour terrain du 30/09, Ferrette : église, borne, station puis virage en
 * 160 m — « on n'a pas le temps de voir le virage »). Quand le prochain élément est un repère de
 * DÉCOR et qu'un virage suit à moins de [TURN_PRIORITY_MIN_METERS] ou de [TURN_PRIORITY_SECONDS]
 * à la vitesse actuelle (le plus grand des deux), seulement séparé d'autres repères de décor, la
 * grande carte montre le virage ; le repère le plus proche devient sa ligne secondaire. Les repères
 * qui demandent une action (panneaux, feux, passage à niveau, entrée d'agglomération, ralentisseur)
 * gardent leur grande carte.
 */
object RoadbookFocusRule {
    const val TURN_PRIORITY_MIN_METERS = 300.0
    const val TURN_PRIORITY_SECONDS = 15.0

    /** Repère de décor : ne demande aucune action au conducteur. */
    fun isDecor(category: LandmarkCategory): Boolean =
        category.group != LandmarkGroup.SIGN && category != LandmarkCategory.SPEED_BUMP

    /**
     * @param progress résultat de [RoadbookLiveProgress.next] sur les positions de [entries].
     * @param speedMetersPerSecond vitesse actuelle (`null` ou négative : distance minimale seule).
     */
    fun focus(entries: List<RoadbookEntry>, progress: LiveProgress?, currentCumulativeDistanceMeters: Double, speedMetersPerSecond: Double?): RoadbookFocus? {
        progress ?: return null
        val plain = RoadbookFocus(progress.index, progress.distanceRemainingMeters)
        val first = entries.getOrNull(progress.index) as? RoadbookEntry.Landmark ?: return plain
        if (!isDecor(first.landmark.info.category)) return plain
        val window = maxOf(TURN_PRIORITY_MIN_METERS, (speedMetersPerSecond ?: 0.0).coerceAtLeast(0.0) * TURN_PRIORITY_SECONDS)
        for (j in progress.index + 1 until entries.size) {
            when (val entry = entries[j]) {
                is RoadbookEntry.Landmark -> if (!isDecor(entry.landmark.info.category)) return plain
                is RoadbookEntry.Maneuver -> {
                    val distance = entry.cumulativeDistanceMeters - currentCumulativeDistanceMeters
                    if (distance > window) return plain
                    return RoadbookFocus(j, maxOf(distance, 0.0), progress.index, progress.distanceRemainingMeters)
                }
            }
        }
        return plain
    }
}
