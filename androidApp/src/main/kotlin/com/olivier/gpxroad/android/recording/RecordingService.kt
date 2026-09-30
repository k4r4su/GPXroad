package com.olivier.gpxroad.android.recording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.olivier.gpxroad.android.MainActivity
import com.olivier.gpxroad.android.R

/**
 * GPS de l'enregistrement (équivalent de `CoreLocationRecordingSource` iOS) : service de premier plan
 * de type « localisation », démarré depuis l'écran (autorisation « pendant l'utilisation » suffisante,
 * comme sur l'iPhone) — il continue écran éteint et dans les autres apps, avec une notification
 * permanente (l'équivalent Android de l'indicateur bleu d'iOS). Arrêté en pause et à la fin.
 * Tué par le système : pas de redémarrage automatique ; la sortie est retrouvée en pause au lancement.
 */
class RecordingService : Service() {
    private lateinit var manager: LocationManager
    private val recorder by lazy { RideRecorder.get(this) }
    private var lastNotifiedCount = -1

    private val listener = LocationListener { location ->
        recorder.ingest(location)
        if (recorder.pointCount != lastNotifiedCount) updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            recorder.pause()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching {
            manager.removeUpdates(listener)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        }.onFailure {
            // Localisation retirée entre-temps : rien à enregistrer, pause propre.
            recorder.pause()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::manager.isInitialized) manager.removeUpdates(listener)
        super.onDestroy()
    }

    private fun updateNotification() {
        lastNotifiedCount = recorder.pointCount
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_rec_format, recorder.pointCount))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.recording_pause), pause).build())
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 30
        private const val ACTION_PAUSE = "com.olivier.gpxroad.recording.PAUSE"
        private const val UPDATE_INTERVAL_MS = 1_000L

        fun intent(context: Context) = Intent(context, RecordingService::class.java)
    }
}
