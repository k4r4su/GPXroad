package com.olivier.gpxroad.shared.track

import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.gpx.GpxPoint
import com.olivier.gpxroad.shared.recording.IsoTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Statistiques « geek » d'une trace (portage de `TrackMetricsCalculator` iOS, it21) : `null` sans
 * horodatage RÉEL exploitable — jamais une vitesse fausse affichée comme une mesure.
 */
data class TrackMetrics(
    val durationSeconds: Double,
    val movingDurationSeconds: Double,
    val averageSpeedKmh: Double,
    val averageMovingSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val minElevationMeters: Double,
    val maxElevationMeters: Double,
    val maxGradePercent: Double,
)

/** Résumé toujours disponible (fiche d'une trace) : distance et dénivelé positif. */
data class TrackSummary(val lengthMeters: Double, val elevationGainMeters: Double)

object TrackMetricsCalculator {
    const val MOVING_SPEED_THRESHOLD_KMH = 3.0
    const val MAX_PLAUSIBLE_SPEED_KMH = 260.0
    const val MIN_SEGMENT_METERS_FOR_GRADE = 20.0

    fun summary(points: List<GpxPoint>): TrackSummary {
        var length = 0.0
        var gain = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            length += geodesicDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val ea = a.elevation
            val eb = b.elevation
            if (ea != null && eb != null && eb > ea) gain += eb - ea
        }
        return TrackSummary(length, gain)
    }

    fun compute(points: List<GpxPoint>): TrackMetrics? {
        if (points.size < 2) return null
        val times = points.map { p -> p.timeIso?.let(IsoTime::parse) }
        val first = times.first() ?: return null
        val last = times.last() ?: return null
        val durationSeconds = (last - first) / 1000.0
        if (durationSeconds <= 0) return null

        var totalDistance = 0.0
        var movingSeconds = 0.0
        var maxSpeed = 0.0
        var gain = 0.0
        var loss = 0.0
        var minElevation = points.first().elevation
        var maxElevation = points.first().elevation
        var maxGrade = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segment = geodesicDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            totalDistance += segment
            val ea = a.elevation
            val eb = b.elevation
            if (ea != null && eb != null) {
                val delta = eb - ea
                if (delta > 0) gain += delta else loss += -delta
                minElevation = min(minElevation ?: eb, eb)
                maxElevation = max(maxElevation ?: eb, eb)
                if (segment >= MIN_SEGMENT_METERS_FOR_GRADE) maxGrade = max(maxGrade, abs(delta) / segment * 100)
            }
            val ta = times[i - 1] ?: continue
            val tb = times[i] ?: continue
            val deltaSeconds = (tb - ta) / 1000.0
            if (deltaSeconds <= 0) continue
            val speed = segment / deltaSeconds * 3.6
            if (speed <= MAX_PLAUSIBLE_SPEED_KMH) maxSpeed = max(maxSpeed, speed)
            if (speed >= MOVING_SPEED_THRESHOLD_KMH) movingSeconds += deltaSeconds
        }
        return TrackMetrics(
            durationSeconds = durationSeconds,
            movingDurationSeconds = movingSeconds,
            averageSpeedKmh = (totalDistance / 1000) / (durationSeconds / 3600),
            averageMovingSpeedKmh = if (movingSeconds > 0) (totalDistance / 1000) / (movingSeconds / 3600) else 0.0,
            maxSpeedKmh = maxSpeed,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            minElevationMeters = minElevation ?: 0.0,
            maxElevationMeters = maxElevation ?: 0.0,
            maxGradePercent = maxGrade,
        )
    }
}
