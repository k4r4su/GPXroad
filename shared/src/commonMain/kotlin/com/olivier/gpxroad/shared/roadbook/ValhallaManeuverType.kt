package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon

/**
 * Énumération EXACTE `DirectionsLeg_Maneuver_Type` de Valhalla (vérifiée contre la documentation
 * de référence turn-by-turn) — un type inconnu retombe sur [NONE].
 */
enum class ValhallaManeuverType(val rawValue: Int) {
    NONE(0), START(1), START_RIGHT(2), START_LEFT(3), DESTINATION(4), DESTINATION_RIGHT(5), DESTINATION_LEFT(6),
    BECOMES(7), CONTINUE_STRAIGHT(8), SLIGHT_RIGHT(9), RIGHT(10), SHARP_RIGHT(11), UTURN_RIGHT(12), UTURN_LEFT(13),
    SHARP_LEFT(14), LEFT(15), SLIGHT_LEFT(16), RAMP_STRAIGHT(17), RAMP_RIGHT(18), RAMP_LEFT(19), EXIT_RIGHT(20),
    EXIT_LEFT(21), STAY_STRAIGHT(22), STAY_RIGHT(23), STAY_LEFT(24), MERGE(25), ROUNDABOUT_ENTER(26),
    ROUNDABOUT_EXIT(27), FERRY_ENTER(28), FERRY_EXIT(29), TRANSIT(30), TRANSIT_TRANSFER(31), TRANSIT_REMAIN_ON(32),
    TRANSIT_CONNECTION_START(33), TRANSIT_CONNECTION_TRANSFER(34), TRANSIT_CONNECTION_DESTINATION(35),
    POST_TRANSIT_CONNECTION_DESTINATION(36);

    /**
     * Palier route-aware (it24) ; `null` = pas une vraie décision de conduite (continuer tout droit,
     * la route change seulement de nom, départ/arrivée, transports) : écartée.
     */
    val roadbookTier: RoadbookTier?
        get() = when (this) {
            ROUNDABOUT_ENTER, ROUNDABOUT_EXIT -> RoadbookTier.ROUNDABOUT
            STAY_STRAIGHT, STAY_RIGHT, STAY_LEFT -> RoadbookTier.FORK
            MERGE, RAMP_STRAIGHT, RAMP_RIGHT, RAMP_LEFT, EXIT_RIGHT, EXIT_LEFT -> RoadbookTier.MERGE
            UTURN_RIGHT, UTURN_LEFT -> RoadbookTier.U_TURN
            SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, FERRY_ENTER, FERRY_EXIT ->
                RoadbookTier.LIGHT_DIRECTION_CHANGE
            else -> null
        }

    /** Sens déduit du type lui-même ; rond-point et fusion : neutre ([TurnDirection.STRAIGHT]). */
    val roadbookDirection: TurnDirection
        get() = when (this) {
            RIGHT, SLIGHT_RIGHT, SHARP_RIGHT, RAMP_RIGHT, EXIT_RIGHT, STAY_RIGHT, START_RIGHT, DESTINATION_RIGHT -> TurnDirection.RIGHT
            LEFT, SLIGHT_LEFT, SHARP_LEFT, RAMP_LEFT, EXIT_LEFT, STAY_LEFT, START_LEFT, DESTINATION_LEFT -> TurnDirection.LEFT
            UTURN_RIGHT, UTURN_LEFT -> TurnDirection.U_TURN
            else -> TurnDirection.STRAIGHT
        }

    companion object {
        fun fromRawValue(rawValue: Int): ValhallaManeuverType = entries.firstOrNull { it.rawValue == rawValue } ?: NONE
    }
}

/**
 * Manœuvre de map matching RETENUE (filtrage par type déjà appliqué).
 *
 * @property routeProgressFraction position le long de la route recalée (0-1) — départage les
 *   passages d'une trace qui repasse au même carrefour ; `null` = repli monotone.
 * @property streetNamesBefore rue(s) avant la manœuvre ; [streetNamesAfter] après.
 */
data class MapMatchedManeuver(
    val coordinate: LatLon,
    val type: ValhallaManeuverType,
    val roundaboutExitCount: Int? = null,
    val routeProgressFraction: Double? = null,
    val streetNamesBefore: List<String> = emptyList(),
    val streetNamesAfter: List<String> = emptyList(),
) {
    /** Demi-tour Valhalla confirmé sur la même route (même rue avant/après). */
    val isSameRoadUTurn: Boolean
        get() = (type == ValhallaManeuverType.UTURN_LEFT || type == ValhallaManeuverType.UTURN_RIGHT) &&
            streetNamesBefore.any { it in streetNamesAfter }

    /** La route suivie change de nom (deux noms connus, rien en commun). */
    val changesRoadName: Boolean
        get() = streetNamesBefore.isNotEmpty() && streetNamesAfter.isNotEmpty() && streetNamesBefore.none { it in streetNamesAfter }
}
