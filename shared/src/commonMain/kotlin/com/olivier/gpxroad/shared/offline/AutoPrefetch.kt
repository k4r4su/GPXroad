package com.olivier.gpxroad.shared.offline

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters

/**
 * Carte « automatique » autour de soi (retour terrain du 04/10 : une zone sans réseau, carte
 * illisible) : quand le réseau est bon (Wi-Fi, ou 4G/5G si permis), l'app garde d'avance les tuiles
 * vectorielles d'un disque de 10 à 20 km autour de la position, plus les 30 prochains km de la trace
 * suivie. Elle se renouvelle en roulant ; les plus anciennes zones automatiques sont supprimées.
 */
object AutoPrefetch {
    val RADIUS_OPTIONS_KM = listOf(10, 15, 20)
    const val DEFAULT_RADIUS_KM = 15

    /** Trace suivie : couloir de ±1 km sur ses 30 prochains km (hors du disque, le plus utile en route). */
    const val TRACK_AHEAD_METERS = 30_000.0

    /** On ne renouvelle pas plus d'une fois toutes les 10 minutes, même en cas d'échec. */
    const val MIN_INTERVAL_MILLIS = 10 * 60 * 1000L

    /** Zones automatiques gardées : la courante et les deux précédentes (la carte reste lisible derrière soi). */
    const val MAX_AUTO_ZONES = 3

    /** Réseau considéré rapide : débit descendant estimé d'au moins 5 Mbit/s (≈ 4G ou mieux). */
    const val MIN_CELLULAR_KBPS = 5_000

    /** Renouvellement dès qu'on s'éloigne du centre de la dernière zone du tiers du rayon : toujours ≥ 2/3 du rayon d'avance. */
    fun refreshDistanceMeters(radiusMeters: Double): Double = radiusMeters / 3

    fun shouldRefresh(
        position: LatLon,
        lastCenter: LatLon?,
        radiusMeters: Double,
        nowMillis: Long,
        lastAttemptMillis: Long?,
        networkGood: Boolean,
        busy: Boolean,
    ): Boolean {
        if (busy || !networkGood) return false
        if (lastAttemptMillis != null && nowMillis - lastAttemptMillis < MIN_INTERVAL_MILLIS) return false
        return lastCenter == null || geodesicDistanceMeters(lastCenter, position) >= refreshDistanceMeters(radiusMeters)
    }

    /** Points de la trace de [fromCumulativeMeters] à +[TRACK_AHEAD_METERS] (la partie devant soi). */
    fun trackAhead(points: List<LatLon>, cumulative: DoubleArray, fromCumulativeMeters: Double): List<LatLon> {
        if (points.size != cumulative.size) return emptyList()
        val end = fromCumulativeMeters + TRACK_AHEAD_METERS
        return points.filterIndexed { index, _ -> cumulative[index] in fromCumulativeMeters..end }
    }

    /** Anneaux à télécharger : le disque, puis un carré de ±1 km autour des points de la trace devant soi. */
    fun rings(center: LatLon, radiusMeters: Double, trackAhead: List<LatLon>): List<List<LatLon>> {
        val result = arrayListOf(OfflineArea.circle(center, radiusMeters))
        if (trackAhead.size >= 2) {
            OfflineArea.corridorBoxes(trackAhead).forEach { box ->
                result += listOf(
                    LatLon(box.minLat, box.minLon), LatLon(box.minLat, box.maxLon),
                    LatLon(box.maxLat, box.maxLon), LatLon(box.maxLat, box.minLon), LatLon(box.minLat, box.minLon),
                )
            }
        }
        return result
    }
}
