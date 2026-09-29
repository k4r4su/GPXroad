package com.olivier.gpxroad.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Position GPS (équivalent de `LocationManager` de l'app iOS) : `LocationManager` Android, sans
 * dépendance aux services Google. Démarré seulement quand un écran en a besoin (Road Book en mode
 * assisté), une mise à jour par seconde.
 */
class LocationTracker(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var location by mutableStateOf<Location?>(null)
        private set
    var isRunning by mutableStateOf(false)
        private set

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Un point réseau (imprécis) ne remplace jamais un point GPS de moins de 10 s. */
    private val listener = LocationListener { update ->
        val current = location
        val staleGps = current == null || current.provider != LocationManager.GPS_PROVIDER || update.time - current.time > 10_000
        if (update.provider == LocationManager.GPS_PROVIDER || staleGps) location = update
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning || !hasPermission) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            manager.getLastKnownLocation(provider)?.let { last ->
                if (location == null || last.time > (location?.time ?: 0)) location = last
            }
        }
        isRunning = providers.isNotEmpty()
    }

    fun stop() {
        if (!isRunning) return
        manager.removeUpdates(listener)
        isRunning = false
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}
