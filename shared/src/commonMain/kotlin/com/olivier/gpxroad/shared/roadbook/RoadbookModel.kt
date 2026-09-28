package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon

/**
 * Palier d'un événement du Road Book (voir le détail historique de chaque palier dans
 * `RoadbookTier.swift`, qui en garde l'affichage : libellé, pictogramme, rotation).
 * - LIGHT…VERY_HARD : angle géométrique (seuils [TierThresholds]) ;
 * - U_TURN : demi-tour sur la MÊME route (géométrie ou Valhalla confirmé), jamais un simple angle ;
 * - LIGHT_DIRECTION_CHANGE, ROUNDABOUT, FORK, MERGE : détectés par map matching Valhalla seulement.
 */
enum class RoadbookTier { LIGHT, MARKED, HARD, VERY_HARD, U_TURN, LIGHT_DIRECTION_CHANGE, ROUNDABOUT, FORK, MERGE }

enum class TurnDirection { LEFT, RIGHT, STRAIGHT, U_TURN }

/**
 * Un événement du Road Book (virage, rond-point…) — équivalent de `Checkpoint` (Swift), qui en
 * garde l'identité déterministe et l'affichage.
 *
 * @property sourcePointIndex index dans les points de la trace (cap de sortie, repli de distance).
 * @property trackCumulativeDistanceMeters position EXACTE le long de la trace (carrefour réel
 *   projeté pour une manœuvre Valhalla) ; `null` = repli sur la distance du point source.
 */
data class Checkpoint(
    val coordinate: LatLon,
    val turnAngleDegrees: Double,
    val direction: TurnDirection,
    val tier: RoadbookTier,
    val sequenceIndex: Int,
    val sourcePointIndex: Int,
    val roundaboutExitCount: Int? = null,
    val trackCumulativeDistanceMeters: Double? = null,
) {
    /** Seul point de lecture de la distance cumulée d'un événement. */
    fun cumulativeDistanceMeters(trackCumulativeDistances: DoubleArray): Double? =
        trackCumulativeDistanceMeters ?: trackCumulativeDistances.getOrNull(sourcePointIndex)
}

/** Paliers d'angle — SEUL endroit où un angle devient un palier ; sous `light`, jamais un virage. */
data class TierThresholds(val light: Double, val marked: Double, val hard: Double, val veryHard: Double) {
    fun tier(absoluteAngle: Double): RoadbookTier = when {
        absoluteAngle >= veryHard -> RoadbookTier.VERY_HARD
        absoluteAngle >= hard -> RoadbookTier.HARD
        absoluteAngle >= marked -> RoadbookTier.MARKED
        else -> RoadbookTier.LIGHT
    }

    companion object {
        val DEFAULT = TierThresholds(
            RoadbookConstants.LIGHT_THRESHOLD_DEGREES_DEFAULT,
            RoadbookConstants.MARKED_THRESHOLD_DEGREES_DEFAULT,
            RoadbookConstants.HARD_THRESHOLD_DEGREES_DEFAULT,
            RoadbookConstants.VERY_HARD_THRESHOLD_DEGREES_DEFAULT,
        )
    }
}

/** Réglages de calcul du Road Book (fenêtres de mesure, paliers, fusion) — ceux de Réglages > Roadbook. */
data class RoadbookSettings(
    val windowBeforeMeters: Double = RoadbookConstants.WINDOW_BEFORE_METERS_DEFAULT,
    val windowAfterMeters: Double = RoadbookConstants.WINDOW_AFTER_METERS_DEFAULT,
    val thresholds: TierThresholds = TierThresholds.DEFAULT,
    val mergeMinDistanceMeters: Double = RoadbookConstants.TURN_MERGE_MIN_DISTANCE_METERS_DEFAULT,
)

/** Constantes du Road Book (seuils métier) — les valeurs d'affichage restent natives. */
object RoadbookConstants {
    const val WINDOW_BEFORE_METERS_DEFAULT = 40.0
    const val WINDOW_AFTER_METERS_DEFAULT = 40.0
    const val LIGHT_THRESHOLD_DEGREES_DEFAULT = 25.0
    const val MARKED_THRESHOLD_DEGREES_DEFAULT = 45.0
    const val HARD_THRESHOLD_DEGREES_DEFAULT = 90.0
    const val VERY_HARD_THRESHOLD_DEGREES_DEFAULT = 135.0
    const val TURN_MERGE_MIN_DISTANCE_METERS_DEFAULT = 150.0

    /** Sommets candidats à moins de cette distance : un seul virage (grappe), portant le virage net. */
    const val TURN_CLUSTER_METERS = 50.0

    /** Changement de route (noms Valhalla) retenu dès ce changement de cap, sous le seuil minimal. */
    const val ROAD_CHANGE_MIN_TURN_DEGREES = 10.0

    /** Demi-tour : angle minimal ET retour sur son propre tracé à moins de `SAME_PATH_MAX`. */
    const val U_TURN_MIN_DEGREES = 175.0
    const val U_TURN_SAME_PATH_MAX_METERS = 12.0

    /** Premiers/derniers mètres de la trace : un demi-tour y est une manœuvre de stationnement. */
    const val U_TURN_ENDPOINT_GUARD_METERS = 200.0

    /** Carrefour Valhalla plus loin que ça de la trace : hors parcours, ignoré. */
    const val MAP_MATCH_MAX_OFF_TRACK_METERS = 60.0

    /** Repli sans progression Valhalla : passages « aussi proches » à cette tolérance près. */
    const val MAP_MATCH_REPASS_TOLERANCE_METERS = 15.0

    /** Mode Assisté : manœuvre atteinte maintenue affichée sur cette distance (sauf virages enchaînés). */
    const val LIVE_MANEUVER_HOLD_AFTER_METERS = 15.0

    /** Hors trace à hystérésis (règle unique Ride + Road Book) : on sort au-delà, on revient en deçà. */
    const val OFF_TRACK_ENTER_METERS = 30.0
    const val OFF_TRACK_EXIT_METERS = 25.0
}
