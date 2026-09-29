package com.olivier.gpxroad.android.roadbook

import android.content.res.Resources
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.shared.roadbook.Checkpoint
import com.olivier.gpxroad.shared.roadbook.DistanceCountdown
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
}
