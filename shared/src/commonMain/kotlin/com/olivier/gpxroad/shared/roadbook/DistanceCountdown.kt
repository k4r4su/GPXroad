package com.olivier.gpxroad.shared.roadbook

import kotlin.math.ceil

/**
 * Compte à rebours affiché jusqu'au prochain virage / repère / point de reprise (demande du
 * propriétaire, 29/09) : la distance ne change qu'à des paliers ronds, jamais à chaque fix GPS
 * (écrans ProMotion qui descendent à 1 Hz : pas de chiffre qui bouge pour rien).
 *
 * Kilomètres : … 2 km, 1,5 km, 1 km, 900 m, 800 m … 300 m, 200 m, 150 m, 100 m, 90 m … 10 m, 0 m.
 * Toujours arrondi AU-DESSUS : la distance affichée n'est jamais plus courte que la vraie (« 200 m »
 * = encore au plus 200 m), et « 0 m » seulement une fois le point atteint.
 */
object DistanceCountdown {
    /** Distance en mètres ramenée au palier supérieur. */
    fun steppedMeters(rawMeters: Double): Double {
        val meters = if (rawMeters.isNaN()) 0.0 else rawMeters.coerceAtLeast(0.0)
        val step = when {
            meters > 10_000 -> 1_000.0
            meters > 1_000 -> 500.0
            meters > 200 -> 100.0
            meters > 100 -> 50.0
            else -> 10.0
        }
        return if (meters <= 0) 0.0 else ceil(meters / step - 1e-9) * step
    }

    /** Même idée en miles : 0,5 mi au-dessus de 1 mi (1 mi au-delà de 10), 0,1 mi en dessous. */
    fun steppedMiles(rawMiles: Double): Double {
        val miles = if (rawMiles.isNaN()) 0.0 else rawMiles.coerceAtLeast(0.0)
        val step = when {
            miles > 10 -> 1.0
            miles > 1 -> 0.5
            else -> 0.1
        }
        return if (miles <= 0) 0.0 else ceil(miles / step - 1e-9) * step
    }
}
