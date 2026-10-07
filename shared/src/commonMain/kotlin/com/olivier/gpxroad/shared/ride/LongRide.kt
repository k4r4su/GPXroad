package com.olivier.gpxroad.shared.ride

/** Mode longue sortie (idée du 06/10) : économie de batterie quand elle baisse (ou toujours). */
enum class LongRideMode { OFF, AUTO, ALWAYS }

/**
 * Règles communes iOS/Android. Mode actif : téléchargements automatiques (carte autour de soi, préparation de la trace)
 * suspendus et animation de la carte limitée. Alerte : une fois par niveau franchi (15 % puis 5 %) pendant un enregistrement.
 */
object LongRide {
    /** En mode automatique : actif à partir de ce niveau, hors charge. */
    const val AUTO_THRESHOLD_PERCENT = 20
    val ALERT_LEVELS_PERCENT = listOf(15, 5)

    /** Images par seconde de la carte quand le mode est actif (au lieu du maximum de l'appareil). */
    const val LOW_POWER_MAX_FPS = 30

    fun isActive(mode: LongRideMode, batteryPercent: Int?, charging: Boolean): Boolean = when (mode) {
        LongRideMode.OFF -> false
        LongRideMode.ALWAYS -> true
        LongRideMode.AUTO -> batteryPercent != null && !charging && batteryPercent <= AUTO_THRESHOLD_PERCENT
    }

    /**
     * Niveau à annoncer maintenant, ou `null`. Seulement en enregistrement, hors charge ; un niveau n'est annoncé qu'une fois
     * (`lastAlerted` = dernier niveau annoncé, `null` = aucun — à remettre à `null` quand la batterie remonte ou se recharge).
     */
    fun nextBatteryAlert(batteryPercent: Int?, charging: Boolean, recording: Boolean, lastAlerted: Int?): Int? {
        if (batteryPercent == null || charging || !recording) return null
        val reached = ALERT_LEVELS_PERCENT.filter { batteryPercent <= it }.minOrNull() ?: return null
        return if (lastAlerted == null || reached < lastAlerted) reached else null
    }
}
