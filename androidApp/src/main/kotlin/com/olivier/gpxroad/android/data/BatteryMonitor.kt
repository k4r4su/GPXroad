package com.olivier.gpxroad.android.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.shared.ride.LongRide

/**
 * Niveau de batterie et charge (états Compose), pour le mode longue sortie et l'alerte « l'enregistrement risque d'être
 * coupé » (`BatteryMonitor` iOS). Écoute le diffusion système `ACTION_BATTERY_CHANGED` tant que l'activité vit.
 */
class BatteryMonitor(private val context: Context) {
    /** 0 à 100, `null` si inconnu. */
    var percent by mutableStateOf<Int?>(null)
        private set
    var isCharging by mutableStateOf(false)
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(intent)
    }

    init {
        // Diffusion « collante » : donne tout de suite l'état courant.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::update)
    }

    private fun update(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        percent = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Économie active ? (téléchargements automatiques suspendus, carte à 30 images/s) */
    fun isLongRideActive(settings: AppSettings): Boolean = LongRide.isActive(settings.longRideMode, percent, isCharging)
}
