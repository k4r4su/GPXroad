package com.olivier.gpxroad.shared.ride

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.max
import kotlin.math.min

/**
 * Mesures du Ride (portage de `RideSessionManager` iOS, partie `RideStatsPanel`) : vitesse max,
 * distance parcourue (seulement au-dessus de 1 km/h : la gigue GPS à l'arrêt ne compte pas),
 * moyenne depuis le début de la session, restant/pourcentage le long de la trace, arrivée estimée
 * à la vitesse moyenne des 5 dernières minutes (rien sous 2 km/h).
 */
class RideStats {
    var maxSpeedKmh = 0.0
        private set
    var traveledMeters = 0.0
        private set
    var averageSpeedKmh = 0.0
        private set
    private var startSeconds: Double? = null
    private var last: LatLon? = null
    private val etaSamples = ArrayDeque<Pair<Double, Double>>()
    private var etaSpeedKmh = 0.0

    fun update(position: LatLon, speedKmh: Double, timestampSeconds: Double) {
        val speed = max(speedKmh, 0.0)
        val start = startSeconds ?: timestampSeconds.also { startSeconds = it }
        maxSpeedKmh = max(maxSpeedKmh, speed)
        last?.let { if (speed > DISTANCE_MIN_SPEED_KMH) traveledMeters += geodesicDistanceMeters(it, position) }
        last = position
        val elapsedHours = (timestampSeconds - start) / 3600
        averageSpeedKmh = if (elapsedHours > 0) (traveledMeters / 1000) / elapsedHours else 0.0
        etaSamples.addLast(timestampSeconds to speed)
        while (etaSamples.isNotEmpty() && timestampSeconds - etaSamples.first().first > ETA_WINDOW_SECONDS) etaSamples.removeFirst()
        etaSpeedKmh = etaSamples.sumOf { it.second } / etaSamples.size
    }

    fun reset() {
        maxSpeedKmh = 0.0
        traveledMeters = 0.0
        averageSpeedKmh = 0.0
        startSeconds = null
        last = null
        etaSamples.clear()
        etaSpeedKmh = 0.0
    }

    /** Restant le long de la trace, pourcentage parcouru et durée restante (s, `null` à l'arrêt). */
    fun progress(trackLengthMeters: Double, cumulativeMeters: Double): RideProgress {
        val remaining = max(trackLengthMeters - cumulativeMeters, 0.0)
        val percent = if (trackLengthMeters > 0) min(100.0, max(0.0, cumulativeMeters / trackLengthMeters * 100)) else 0.0
        val seconds = if (etaSpeedKmh >= ETA_SILENCE_SPEED_KMH) remaining / 1000 / etaSpeedKmh * 3600 else null
        return RideProgress(remaining, percent, seconds)
    }

    companion object {
        const val DISTANCE_MIN_SPEED_KMH = 1.0
        const val ETA_WINDOW_SECONDS = 300.0
        const val ETA_SILENCE_SPEED_KMH = 2.0
    }
}

data class RideProgress(val remainingMeters: Double, val percentComplete: Double, val remainingSeconds: Double?)
