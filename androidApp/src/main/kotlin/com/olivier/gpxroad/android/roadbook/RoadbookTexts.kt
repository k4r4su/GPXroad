package com.olivier.gpxroad.android.roadbook

import android.content.res.Resources
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.shared.roadbook.Checkpoint
import com.olivier.gpxroad.shared.roadbook.DistanceCountdown
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.roadbook.LandmarkGroup
import com.olivier.gpxroad.shared.roadbook.LandmarkInfo
import com.olivier.gpxroad.shared.roadbook.LandmarkSide
import com.olivier.gpxroad.shared.roadbook.RoadbookTier
import com.olivier.gpxroad.shared.roadbook.RoundaboutPassage
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Textes du Road Book (équivalents de `RoadbookTier.label`, `RoadbookRoundaboutText` et
 * `DistanceUnit` iOS) — mêmes règles, traductions dans `res/values-*`.
 */
object RoadbookTexts {
    fun tierLabel(resources: Resources, tier: RoadbookTier): String = resources.getString(
        when (tier) {
            RoadbookTier.LIGHT -> R.string.tier_light
            RoadbookTier.MARKED -> R.string.tier_marked
            RoadbookTier.HARD -> R.string.tier_hard
            RoadbookTier.VERY_HARD -> R.string.tier_very_hard
            RoadbookTier.U_TURN -> R.string.tier_u_turn
            RoadbookTier.LIGHT_DIRECTION_CHANGE -> R.string.tier_direction_change
            RoadbookTier.ROUNDABOUT -> R.string.tier_roundabout
            RoadbookTier.FORK -> R.string.tier_fork
            RoadbookTier.MERGE -> R.string.tier_merge
        },
    )

    fun exitOrdinal(resources: Resources, number: Int): String = when (number) {
        1 -> resources.getString(R.string.exit_1)
        2 -> resources.getString(R.string.exit_2)
        3 -> resources.getString(R.string.exit_3)
        else -> resources.getString(R.string.exit_n, number)
    }

    /** Sens de sortie sur les 8 positions (0 tout droit, ±45 légèrement, ±90, ±135 fortement, 180). */
    fun direction(resources: Resources, angleDegrees: Double): String = resources.getString(
        when ((angleDegrees / 45).roundToInt()) {
            0 -> R.string.dir_straight
            1 -> R.string.dir_slight_right
            2 -> R.string.dir_right
            3 -> R.string.dir_sharp_right
            -1 -> R.string.dir_slight_left
            -2 -> R.string.dir_left
            -3 -> R.string.dir_sharp_left
            else -> R.string.dir_u_turn
        },
    )

    /** « Rond-point · 3e sortie · à gauche » pour un rond-point analysé, sinon le palier. */
    fun instruction(resources: Resources, checkpoint: Checkpoint): String {
        val passage = checkpoint.roundabout
        if (checkpoint.tier != RoadbookTier.ROUNDABOUT || passage == null) return tierLabel(resources, checkpoint.tier)
        val direction = direction(resources, passage.exitAngleDegrees)
        val summary = passage.exitNumber?.let { "${exitOrdinal(resources, it)} · $direction" } ?: direction
        return "${tierLabel(resources, RoadbookTier.ROUNDABOUT)} · $summary"
    }

    /** « → D 419 · puis 1re sortie » (rond-point analysé seulement). */
    fun roundaboutDetail(resources: Resources, checkpoint: Checkpoint): String? {
        val passage: RoundaboutPassage = checkpoint.roundabout ?: return null
        val road = passage.exitRoadName?.let { "→ $it" }
        val chained = passage.thenExitAngleDegrees?.let { angle ->
            val next = passage.thenExitNumber?.let { exitOrdinal(resources, it) } ?: direction(resources, angle)
            resources.getString(R.string.then_format, next)
        }
        return listOfNotNull(road, chained).joinToString(" · ").ifEmpty { null }
    }

    private const val METERS_PER_MILE = 1609.344

    /** Distance fixe (liste, cumulés) : mètres sous 1 km, sinon une décimale. */
    fun distance(meters: Double, unit: DistanceUnit): String {
        if (unit == DistanceUnit.KM && meters < 1000) return "${meters.roundToInt()} m"
        val value = if (unit == DistanceUnit.KM) meters / 1000 else meters / METERS_PER_MILE
        return "${decimal(value, 1, 1)} ${if (unit == DistanceUnit.KM) "km" else "mi"}"
    }

    /** Distance EN DIRECT : paliers ronds du module partagé (… 1,5 km, 1 km, 900 m … 10 m, 0 m). */
    fun countdown(meters: Double, unit: DistanceUnit): String {
        if (unit == DistanceUnit.KM) {
            val stepped = DistanceCountdown.steppedMeters(meters)
            return if (stepped < 1000) "${stepped.roundToInt()} m" else "${decimal(stepped / 1000, 0, 1)} km"
        }
        return "${decimal(DistanceCountdown.steppedMiles(meters / METERS_PER_MILE), 0, 1)} mi"
    }

    private fun decimal(value: Double, minFraction: Int, maxFraction: Int): String =
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = minFraction
            maximumFractionDigits = maxFraction
        }.format(value)

    // MARK: Repères (équivalents de `RoadbookLandmarkInfo`/`RoadbookLandmarkCategory` iOS)

    fun categoryLabel(resources: Resources, category: LandmarkCategory): String = resources.getString(
        when (category) {
            LandmarkCategory.CITY_SIGN -> R.string.lm_citySign
            LandmarkCategory.STOP_SIGN -> R.string.lm_stopSign
            LandmarkCategory.GIVE_WAY_SIGN -> R.string.lm_giveWaySign
            LandmarkCategory.TRAFFIC_SIGNALS -> R.string.lm_trafficSignals
            LandmarkCategory.LEVEL_CROSSING -> R.string.lm_levelCrossing
            LandmarkCategory.SPEED_BUMP -> R.string.lm_speedBump
            LandmarkCategory.BRIDGE -> R.string.lm_bridge
            LandmarkCategory.TUNNEL -> R.string.lm_tunnel
            LandmarkCategory.CHURCH -> R.string.lm_church
            LandmarkCategory.TOWN_HALL -> R.string.lm_townHall
            LandmarkCategory.WATER_TOWER -> R.string.lm_waterTower
            LandmarkCategory.MILL -> R.string.lm_mill
            LandmarkCategory.WAYSIDE_CROSS -> R.string.lm_waysideCross
            LandmarkCategory.CASTLE -> R.string.lm_castle
            LandmarkCategory.FUEL -> R.string.lm_fuel
            LandmarkCategory.CHARGING_STATION -> R.string.lm_chargingStation
            LandmarkCategory.PARKING -> R.string.lm_parking
            LandmarkCategory.REST_AREA -> R.string.lm_restArea
            LandmarkCategory.DRINKING_WATER -> R.string.lm_drinkingWater
            LandmarkCategory.RESTAURANT -> R.string.lm_restaurant
            LandmarkCategory.CAFE -> R.string.lm_cafe
            LandmarkCategory.BAKERY -> R.string.lm_bakery
            LandmarkCategory.SUPERMARKET -> R.string.lm_supermarket
            LandmarkCategory.PHARMACY -> R.string.lm_pharmacy
            LandmarkCategory.HOTEL -> R.string.lm_hotel
            LandmarkCategory.CAMPSITE -> R.string.lm_campsite
            LandmarkCategory.TRAIN_STATION -> R.string.lm_trainStation
            LandmarkCategory.SCHOOL -> R.string.lm_school
            LandmarkCategory.CEMETERY -> R.string.lm_cemetery
            LandmarkCategory.MEMORIAL -> R.string.lm_memorial
            LandmarkCategory.WIND_TURBINE -> R.string.lm_windTurbine
            LandmarkCategory.ANTENNA -> R.string.lm_antenna
            LandmarkCategory.LIGHTHOUSE -> R.string.lm_lighthouse
            LandmarkCategory.TOWER -> R.string.lm_tower
        },
    )

    /** Même pictogramme que l'iPhone (emoji par catégorie). */
    fun emoji(category: LandmarkCategory): String = when (category) {
        LandmarkCategory.CITY_SIGN -> "🏘️"
        LandmarkCategory.STOP_SIGN -> "🛑"
        LandmarkCategory.GIVE_WAY_SIGN -> "🔻"
        LandmarkCategory.TRAFFIC_SIGNALS -> "🚦"
        LandmarkCategory.LEVEL_CROSSING -> "🚂"
        LandmarkCategory.SPEED_BUMP -> "〰️"
        LandmarkCategory.BRIDGE -> "🌉"
        LandmarkCategory.TUNNEL -> "🚇"
        LandmarkCategory.CHURCH -> "⛪"
        LandmarkCategory.TOWN_HALL -> "🏛️"
        LandmarkCategory.WATER_TOWER -> "💧"
        LandmarkCategory.MILL -> "🌬️"
        LandmarkCategory.WAYSIDE_CROSS -> "✝️"
        LandmarkCategory.CASTLE -> "🏰"
        LandmarkCategory.FUEL -> "⛽"
        LandmarkCategory.CHARGING_STATION -> "🔌"
        LandmarkCategory.PARKING -> "🅿️"
        LandmarkCategory.REST_AREA -> "🚻"
        LandmarkCategory.DRINKING_WATER -> "🚰"
        LandmarkCategory.RESTAURANT -> "🍽️"
        LandmarkCategory.CAFE -> "☕"
        LandmarkCategory.BAKERY -> "🥖"
        LandmarkCategory.SUPERMARKET -> "🛒"
        LandmarkCategory.PHARMACY -> "💊"
        LandmarkCategory.HOTEL -> "🏨"
        LandmarkCategory.CAMPSITE -> "⛺"
        LandmarkCategory.TRAIN_STATION -> "🚉"
        LandmarkCategory.SCHOOL -> "🏫"
        LandmarkCategory.CEMETERY -> "🪦"
        LandmarkCategory.MEMORIAL -> "🎖️"
        LandmarkCategory.WIND_TURBINE -> "🌀"
        LandmarkCategory.ANTENNA -> "📡"
        LandmarkCategory.LIGHTHOUSE -> "🔦"
        LandmarkCategory.TOWER -> "🗼"
    }

    fun groupLabel(resources: Resources, group: LandmarkGroup): String = resources.getString(
        when (group) {
            LandmarkGroup.SIGN -> R.string.group_sign
            LandmarkGroup.INFRASTRUCTURE -> R.string.group_infrastructure
            LandmarkGroup.BUILDING -> R.string.group_building
            LandmarkGroup.SERVICE -> R.string.group_service
            LandmarkGroup.OTHER -> R.string.group_other
        },
    )

    /**
     * Libellé traduit : entrée de localité calculée (« Entrée de Ferrette »), libellé générique (clé
     * française du module partagé, traduite), ou nom propre OSM tel quel.
     */
    fun landmarkLabel(resources: Resources, info: LandmarkInfo): String {
        info.cityEntryName?.let { name ->
            val elides = name.firstOrNull()?.let { "AEIOUYÂÀÉÈÊËÎÏÔÖÛÜŒaeiouyâàéèêëîïôöûüœ".contains(it) } ?: false
            return resources.getString(if (elides) R.string.city_entry_elided else R.string.city_entry, name)
        }
        LandmarkCategory.entries.firstOrNull { it.genericLabel == info.label }?.let { return categoryLabel(resources, it) }
        return when (info.label) {
            "Clocher" -> resources.getString(R.string.lm_bell_tower)
            "Chapelle" -> resources.getString(R.string.lm_chapel)
            "Lieu de culte" -> resources.getString(R.string.lm_place_of_worship)
            "Oratoire" -> resources.getString(R.string.lm_wayside_shrine)
            else -> info.label
        }
    }

    /** « à droite, 120 m » — distance seulement pour un service en retrait de la route. */
    fun sideDescription(resources: Resources, info: LandmarkInfo): String? {
        val side = info.side?.let { resources.getString(if (it == LandmarkSide.LEFT) R.string.side_left else R.string.side_right) }
        val distance = info.lateralDistanceMeters?.let { "${(it / 10).roundToInt() * 10} m" }
        return when {
            side != null && distance != null -> "$side, $distance"
            side != null -> side
            distance != null -> resources.getString(R.string.side_away, distance)
            else -> null
        }
    }

    /** « Église Saint-Martin à droite ». */
    fun landmarkDisplayLabel(resources: Resources, info: LandmarkInfo): String =
        sideDescription(resources, info)?.let { "${landmarkLabel(resources, info)} $it" } ?: landmarkLabel(resources, info)

    /** Seconde ligne d'un repère : catégorie (si le libellé est un nom propre) et côté. */
    fun landmarkDetail(resources: Resources, info: LandmarkInfo): String {
        val category = if (info.label == info.category.genericLabel) null else categoryLabel(resources, info.category)
        return listOfNotNull(category, sideDescription(resources, info)).joinToString(" · ").ifEmpty { categoryLabel(resources, info.category) }
    }
}
