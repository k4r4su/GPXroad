package com.olivier.gpxroad.shared.ride

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** Palier de zoom : jusqu'à cette vitesse (km/h), la caméra se tient à cette distance (m). */
data class ZoomBucket(val speedUpToKmh: Double, val cameraDistanceMeters: Double)

/** Préréglage de zoom automatique (Réglages iPhone > Navigation), mêmes valeurs que `RideConstants`. */
enum class ZoomPreset(val buckets: List<ZoomBucket>) {
    PRUDENT(listOf(ZoomBucket(20.0, 220.0), ZoomBucket(50.0, 350.0), ZoomBucket(90.0, 550.0), ZoomBucket(Double.POSITIVE_INFINITY, 750.0))),
    NORMAL(listOf(ZoomBucket(20.0, 280.0), ZoomBucket(50.0, 450.0), ZoomBucket(90.0, 750.0), ZoomBucket(Double.POSITIVE_INFINITY, 1100.0))),
    RAPIDE(listOf(ZoomBucket(20.0, 320.0), ZoomBucket(50.0, 600.0), ZoomBucket(90.0, 1000.0), ZoomBucket(Double.POSITIVE_INFINITY, 1500.0))),
}

/** Contexte de conduite : route rapide (dézoome), piste (zoome), normal. */
enum class RideContext { NORMAL, FAST_ROAD, TRACK }

/** Constantes caméra du Ride (valeurs de `RideConstants` iOS). */
object RideCameraConstants {
    const val SPEED_SMOOTHING_WINDOW_SECONDS = 10.0
    const val ZOOM_HYSTERESIS_MARGIN_KMH = 4.0
    const val MANUAL_ZOOM_OVERRIDE_TIMEOUT_SECONDS = 5.0
    const val MANUAL_ZOOM_STEP_FACTOR = 0.7
    const val MANUAL_ZOOM_MIN_METERS = 120.0
    const val MANUAL_ZOOM_MAX_METERS = 2_000_000.0
    const val AUTO_ZOOM_MIN_METERS_DEFAULT = 150.0
    const val AUTO_ZOOM_MAX_METERS_DEFAULT = 2000.0
    const val FAST_ROAD_SPEED_THRESHOLD_KMH = 60.0
    const val FAST_ROAD_SUSTAINED_DURATION_SECONDS = 60.0
    const val TRACK_SPEED_THRESHOLD_KMH = 40.0
    const val FAST_ROAD_CAMERA_DISTANCE_MULTIPLIER = 1.4
    const val TRACK_CAMERA_DISTANCE_MULTIPLIER = 0.7

    /** Point GPS à cette fraction de la hauteur libre, depuis le haut, en cap-en-haut (réglable iPhone). */
    const val ANCHOR_Y_FRACTION_DEFAULT = 0.75

    /** Au-delà, la bannière latérale annonce le prochain virage. */
    const val BANNER_ALERT_START_METERS = 600.0

    /** Zoom de départ : 5 crans de dézoom depuis le premier palier « Normal » (~1 666 m). */
    val DEFAULT_RIDE_ZOOM_METERS: Double = 280.0 / MANUAL_ZOOM_STEP_FACTOR.pow(5)
}

/** Vitesse lissée sur une fenêtre glissante (zoom et contexte ; jamais le compteur affiché). */
class SpeedSmoother(private val windowSeconds: Double = RideCameraConstants.SPEED_SMOOTHING_WINDOW_SECONDS) {
    private val samples = ArrayDeque<Pair<Double, Double>>()

    /** @param timestampSeconds horodatage du fix ; @return moyenne des vitesses de la fenêtre (km/h). */
    fun add(speedKmh: Double, timestampSeconds: Double): Double {
        samples.addLast(timestampSeconds to max(speedKmh, 0.0))
        while (samples.isNotEmpty() && timestampSeconds - samples.first().first > windowSeconds) samples.removeFirst()
        return samples.sumOf { it.second } / samples.size
    }

    fun reset() = samples.clear()
}

/**
 * Zoom automatique selon la vitesse (portage de `RideSessionManager.updateZoomBucket` et
 * `updateRideContext`) : paliers avec hystérésis de 4 km/h (jamais d'oscillation), contexte route
 * rapide (> 60 km/h pendant 60 s) ou piste (< 40 km/h), puis bornes [min, max].
 */
class AutoZoom(private val preset: ZoomPreset = ZoomPreset.NORMAL) {
    var bucketIndex = 0
        private set
    var context = RideContext.NORMAL
        private set
    private var fastSince: Double? = null

    /** @return distance caméra (m) pour cette vitesse lissée. */
    fun update(
        smoothedSpeedKmh: Double,
        timestampSeconds: Double,
        minMeters: Double = RideCameraConstants.AUTO_ZOOM_MIN_METERS_DEFAULT,
        maxMeters: Double = RideCameraConstants.AUTO_ZOOM_MAX_METERS_DEFAULT,
    ): Double {
        updateContext(smoothedSpeedKmh, timestampSeconds)
        val buckets = preset.buckets
        bucketIndex = min(bucketIndex, buckets.lastIndex)
        val proposed = buckets.indexOfFirst { smoothedSpeedKmh <= it.speedUpToKmh }.takeIf { it >= 0 } ?: buckets.lastIndex
        if (proposed != bucketIndex) {
            val movingUp = proposed > bucketIndex
            val boundary = buckets[if (movingUp) bucketIndex else proposed].speedUpToKmh
            val margin = RideCameraConstants.ZOOM_HYSTERESIS_MARGIN_KMH
            val crossed = if (movingUp) smoothedSpeedKmh > boundary + margin else smoothedSpeedKmh < boundary - margin
            if (crossed) bucketIndex = proposed
        }
        val distance = buckets[bucketIndex].cameraDistanceMeters * when (context) {
            RideContext.FAST_ROAD -> RideCameraConstants.FAST_ROAD_CAMERA_DISTANCE_MULTIPLIER
            RideContext.TRACK -> RideCameraConstants.TRACK_CAMERA_DISTANCE_MULTIPLIER
            RideContext.NORMAL -> 1.0
        }
        return min(max(distance, minMeters), maxMeters)
    }

    private fun updateContext(speedKmh: Double, now: Double) {
        if (speedKmh > RideCameraConstants.FAST_ROAD_SPEED_THRESHOLD_KMH) {
            val since = fastSince ?: now.also { fastSince = it }
            if (now - since >= RideCameraConstants.FAST_ROAD_SUSTAINED_DURATION_SECONDS) context = RideContext.FAST_ROAD
        } else {
            fastSince = null
            if (speedKmh < RideCameraConstants.TRACK_SPEED_THRESHOLD_KMH) context = RideContext.TRACK
            else if (context == RideContext.FAST_ROAD) context = RideContext.NORMAL
        }
    }
}

object RideCameraMath {
    private const val EARTH_RADIUS_M = 6378137.0
    private const val TILE_SIZE = 512.0

    /** Champ de vision de MapLibre iOS (`MLNAngularFieldOfView`). */
    private const val ANGULAR_FIELD_OF_VIEW_DEGREES = 30.0

    /**
     * Niveau de zoom MapLibre équivalent à `MLNMapCamera(lookingAtCenter:acrossDistance:)` iOS, pitch
     * nul (`MLNZoomLevelForAltitude`) : même cadrage sur les deux plateformes pour une même distance.
     * @param viewHeightPoints hauteur de la carte en points (iOS) / dp (Android).
     */
    fun zoomLevel(cameraDistanceMeters: Double, latitudeDegrees: Double, viewHeightPoints: Double): Double {
        val metersTall = cameraDistanceMeters * 2 * tan(ANGULAR_FIELD_OF_VIEW_DEGREES * PI / 180 / 2)
        val metersPerPoint = metersTall / viewHeightPoints
        val mapPixelWidth = cos(latitudeDegrees * PI / 180) * 2 * PI * EARTH_RADIUS_M / metersPerPoint
        return ln(mapPixelWidth / TILE_SIZE) / ln(2.0)
    }
}
