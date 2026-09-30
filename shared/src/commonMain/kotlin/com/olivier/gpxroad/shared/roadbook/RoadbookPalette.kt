package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.tan

/** Palette du Road Book (`RoadbookPalette` iOS) : papier (clair) le jour, sombre la nuit. */
enum class RoadbookPalette { PAPER, NIGHT }

/** Réglage : automatique (lever/coucher du soleil à la position), ou forcé. */
enum class RoadbookPaletteSetting(val override: RoadbookPalette?) { AUTOMATIC(null), PAPER(RoadbookPalette.PAPER), NIGHT(RoadbookPalette.NIGHT) }

object RoadbookPaletteResolver {
    /** Sans position : jour de 7 h à 20 h (heure locale), comme l'iPhone. */
    const val FALLBACK_DAY_START_HOUR = 7
    const val FALLBACK_DAY_END_HOUR = 20
    /** Réévaluée toutes les 5 minutes tant que l'écran reste ouvert. */
    const val REEVALUATION_INTERVAL_SECONDS = 300

    fun resolve(setting: RoadbookPaletteSetting, nowMillis: Long, position: LatLon?, localHour: Int): RoadbookPalette {
        setting.override?.let { return it }
        position?.let { SolarTime.sunriseSunset(nowMillis, it) }?.let { (sunrise, sunset) ->
            return if (nowMillis in sunrise until sunset) RoadbookPalette.PAPER else RoadbookPalette.NIGHT
        }
        return if (localHour in FALLBACK_DAY_START_HOUR until FALLBACK_DAY_END_HOUR) RoadbookPalette.PAPER else RoadbookPalette.NIGHT
    }
}

/**
 * Lever et coucher du soleil (portage de `SolarTimeCalculator` iOS : déclinaison approchée, sans
 * réfraction — suffisant pour choisir une palette). `null` en jour ou nuit polaire.
 */
object SolarTime {
    fun sunriseSunset(nowMillis: Long, position: LatLon): Pair<Long, Long>? {
        val dayMillis = 86_400_000L
        val startOfDay = floorDiv(nowMillis, dayMillis) * dayMillis
        val dayOfYear = dayOfYearUtc(startOfDay)
        val latitude = position.latitude * PI / 180
        val declination = -23.44 * PI / 180 * cos(2 * PI / 365.0 * (dayOfYear + 10))
        val cosHourAngle = -tan(latitude) * tan(declination)
        if (cosHourAngle < -1 || cosHourAngle > 1) return null
        val halfDayHours = acos(cosHourAngle) * 180 / PI / 15
        val noon = 12 - position.longitude / 15
        return startOfDay + ((noon - halfDayHours) * 3_600_000).toLong() to startOfDay + ((noon + halfDayHours) * 3_600_000).toLong()
    }

    /** Rang du jour dans l'année (1 = 1er janvier), UTC. */
    private fun dayOfYearUtc(startOfDayMillis: Long): Int {
        val days = startOfDayMillis / 86_400_000L
        // Année civile par l'algorithme de H. Hinnant (jours depuis 1970).
        val z = days + 719_468
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val jan1 = daysFromCivil(year, 1, 1)
        return (days - jan1 + 1).toInt()
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && (a < 0) != (b < 0)) q - 1 else q
    }

    private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
        val y = if (month <= 2) year - 1 else year
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
