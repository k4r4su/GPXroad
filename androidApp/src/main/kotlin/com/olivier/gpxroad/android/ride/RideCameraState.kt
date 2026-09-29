package com.olivier.gpxroad.android.ride

import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.shared.ride.AutoZoom
import com.olivier.gpxroad.shared.ride.RideCameraConstants
import com.olivier.gpxroad.shared.ride.SpeedSmoother
import kotlin.math.max
import kotlin.math.min

/**
 * Caméra du Ride (partie caméra de `RideSessionManager` iOS) : zoom automatique selon la vitesse
 * LISSÉE, +/- manuels prioritaires, suivi suspendu 5 s après un geste sur la carte, « Me recentrer ».
 * Vit le temps de l'activité : revenir sur l'onglet ne remet ni le zoom ni la vitesse à zéro.
 */
class RideCameraState {
    private val smoother = SpeedSmoother()
    private val autoZoom = AutoZoom()
    private var lastFix: Location? = null

    /** Distance caméra automatique (m) ; départ = zoom par défaut de l'iPhone (~1,7 km). */
    var autoDistanceMeters by mutableDoubleStateOf(RideCameraConstants.DEFAULT_RIDE_ZOOM_METERS)
        private set
    var manualDistanceMeters by mutableStateOf<Double?>(null)
        private set
    var lastGestureMillis by mutableStateOf<Long?>(null)
        private set

    /** Change à chaque +/- ou recentrage : la carte applique la caméra tout de suite. */
    var commandToken by mutableIntStateOf(0)
        private set

    /** Vitesse GPS brute (compteur uniquement, jamais une décision automatique). */
    var rawSpeedKmh by mutableDoubleStateOf(0.0)
        private set

    /** Dernier cap GPS fiable : conservé à l'arrêt (le cap GPS n'a alors plus de sens). */
    var courseDegrees by mutableStateOf<Double?>(null)
        private set

    val effectiveDistanceMeters: Double get() = manualDistanceMeters ?: autoDistanceMeters

    fun isManualOverrideActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        lastGestureMillis?.let { nowMillis - it < (RideCameraConstants.MANUAL_ZOOM_OVERRIDE_TIMEOUT_SECONDS * 1000).toLong() } ?: false

    fun onLocation(location: Location) {
        val speedKmh = if (location.hasSpeed()) max(location.speed.toDouble(), 0.0) * 3.6 else 0.0
        rawSpeedKmh = speedKmh
        if (speedKmh >= MIN_SPEED_FOR_COURSE_KMH) {
            // Cap GPS s'il est précis ; sinon (appareil qui ne le fournit pas, émulateur) déduit des
            // deux dernières positions, dès qu'elles sont assez éloignées pour être fiables.
            val preciseBearing = location.hasBearing() && (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees < MAX_BEARING_ACCURACY_DEGREES)
            val previous = lastFix
            when {
                preciseBearing -> courseDegrees = location.bearing.toDouble()
                previous != null && previous.distanceTo(location) >= MIN_DISTANCE_FOR_COURSE_METERS -> courseDegrees = ((previous.bearingTo(location) + 360) % 360).toDouble()
            }
        }
        if (lastFix == null || lastFix!!.distanceTo(location) >= MIN_DISTANCE_FOR_COURSE_METERS) lastFix = location
        val smoothed = smoother.add(speedKmh, location.time / 1000.0)
        autoDistanceMeters = autoZoom.update(smoothed, location.time / 1000.0)
    }

    fun zoomIn() = adjustManualZoom(RideCameraConstants.MANUAL_ZOOM_STEP_FACTOR)
    fun zoomOut() = adjustManualZoom(1 / RideCameraConstants.MANUAL_ZOOM_STEP_FACTOR)

    private fun adjustManualZoom(factor: Double) {
        val base = manualDistanceMeters ?: autoDistanceMeters
        manualDistanceMeters = min(max(base * factor, RideCameraConstants.MANUAL_ZOOM_MIN_METERS), RideCameraConstants.MANUAL_ZOOM_MAX_METERS)
        lastGestureMillis = System.currentTimeMillis()
        commandToken++
    }

    /** Pan ou pincement sur la carte : suivi suspendu [RideCameraConstants.MANUAL_ZOOM_OVERRIDE_TIMEOUT_SECONDS]. */
    fun onUserGesture() {
        lastGestureMillis = System.currentTimeMillis()
    }

    /** Recentre et reprend le suivi immédiatement. */
    fun recenter() {
        manualDistanceMeters = null
        lastGestureMillis = null
        commandToken++
    }

    private companion object {
        const val MIN_SPEED_FOR_COURSE_KMH = 5.0
        const val MAX_BEARING_ACCURACY_DEGREES = 30f
        const val MIN_DISTANCE_FOR_COURSE_METERS = 5f
    }
}
