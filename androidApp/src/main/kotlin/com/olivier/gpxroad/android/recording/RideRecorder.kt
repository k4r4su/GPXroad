package com.olivier.gpxroad.android.recording

import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.olivier.gpxroad.shared.recording.GpxWriter
import com.olivier.gpxroad.shared.recording.RecordedPoint
import com.olivier.gpxroad.shared.recording.RecordingConstants
import com.olivier.gpxroad.shared.recording.RecordingDensity
import com.olivier.gpxroad.shared.recording.RecordingSampler
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Enregistrement de la sortie (équivalent de `RideRecorder` iOS, it30) — UN SEUL pour tout le
 * processus ([get]), indépendant des écrans : changer d'onglet, éteindre l'écran ou passer dans une
 * autre app ne l'arrête jamais.
 *
 * Règles (les mêmes que l'iPhone) :
 * - démarré, mis en pause, repris et terminé UNIQUEMENT par l'utilisateur ;
 * - GPS de l'enregistrement tenu par un service de premier plan ([RecordingService], notification
 *   visible), actif SEULEMENT pendant l'enregistrement, coupé en pause et à la fin ;
 * - chaque point gardé est ajouté IMMÉDIATEMENT au journal sur disque : après un arrêt de l'app,
 *   la sortie est retrouvée EN PAUSE (jamais de reprise automatique) ;
 * - copie de secours GPX tous les 10 points (« Sorties non enregistrées »).
 */
class RideRecorder private constructor(private val context: Context) {
    enum class State { IDLE, RECORDING, PAUSED }

    var state by mutableStateOf(State.IDLE)
        private set
    var pointCount by mutableIntStateOf(0)
        private set
    /** Sortie retrouvée au lancement après un arrêt de l'app. */
    var wasRestoredAfterInterruption by mutableStateOf(false)
        private set

    private val points = mutableListOf<RecordedPoint>()
    var sessionId: String? = null
        private set
    var sessionStartedMillis: Long? = null
        private set

    private val journal = RecordingJournal(File(context.filesDir, "RideRecording"))
    val unsavedRides = UnsavedRideStore(File(context.filesDir, "UnsavedRides"))
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Proposition « Enregistrer cette sortie ? » déjà faite, par trace (une fois par lancement). */
    val promptedTrackIds = mutableSetOf<String>()

    /** Une sortie est EN COURS (active, en pause, ou points non terminés). */
    val isInProgress: Boolean get() = state != State.IDLE || pointCount > 0

    val recordedPoints: List<RecordedPoint> get() = points.toList()
    val recordedDistanceMeters: Double get() = RecordingSampler.lengthMeters(points)

    init {
        restoreFromJournal()
    }

    /** Démarre ou reprend (la permission de localisation est vérifiée par l'écran avant). */
    fun start() {
        if (state == State.IDLE && pointCount == 0) {
            sessionId = UUID.randomUUID().toString().uppercase()
            sessionStartedMillis = System.currentTimeMillis()
            journal.begin(sessionId!!, sessionStartedMillis!!)
        }
        state = State.RECORDING
        wasRestoredAfterInterruption = false
        ContextCompat.startForegroundService(context, RecordingService.intent(context))
    }

    fun pause() {
        if (state != State.RECORDING) return
        state = State.PAUSED
        context.stopService(RecordingService.intent(context))
    }

    /** Fin de sortie (enregistrée dans la Bibliothèque, ou abandonnée) : tout est effacé. */
    fun finish() {
        context.stopService(RecordingService.intent(context))
        sessionId?.let(unsavedRides::discard)
        journal.clear()
        state = State.IDLE
        points.clear()
        pointCount = 0
        sessionId = null
        sessionStartedMillis = null
        wasRestoredAfterInterruption = false
    }

    /** Appelé par le service à chaque position GPS. */
    fun ingest(location: Location) {
        if (state != State.RECORDING) return
        val candidate = RecordedPoint(location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null, location.time)
        if (!RecordingSampler.shouldRecord(points.lastOrNull(), candidate, density)) return
        points.add(candidate)
        pointCount = points.size
        journal.append(candidate)
        checkpointUnsavedRideIfNeeded()
    }

    /** GPX de la sortie (Terminer la sortie). */
    fun gpx(name: String, comment: String?): String = GpxWriter.write(name, points, comment)

    private val density: RecordingDensity
        get() = preferences.getString("recordingDensity", null)?.let { runCatching { RecordingDensity.valueOf(it) }.getOrNull() } ?: RecordingDensity.PRECIS

    private fun checkpointUnsavedRideIfNeeded() {
        val id = sessionId ?: return
        val started = sessionStartedMillis ?: return
        if (points.size % RecordingConstants.UNSAVED_CHECKPOINT_EVERY_N_POINTS != 0) return
        val name = unsavedRideName(context, started)
        val retention = preferences.getInt("unsavedRetention", RecordingConstants.UNSAVED_RETENTION_DEFAULT)
        unsavedRides.checkpoint(id, started, GpxWriter.write(name, points), points.size, retention)
    }

    private fun restoreFromJournal() {
        val restored = journal.load()
        if (restored == null || restored.points.isEmpty()) {
            journal.clear()
            return
        }
        sessionId = restored.sessionId
        sessionStartedMillis = restored.startedMillis
        points.addAll(restored.points)
        pointCount = points.size
        state = State.PAUSED
        wasRestoredAfterInterruption = true
    }

    companion object {
        @Volatile private var instance: RideRecorder? = null

        fun get(context: Context): RideRecorder =
            instance ?: synchronized(this) { instance ?: RideRecorder(context.applicationContext).also { instance = it } }

        fun unsavedRideName(context: Context, startedMillis: Long): String =
            context.getString(com.olivier.gpxroad.android.R.string.recording_unsaved_name, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startedMillis)))
    }
}

/**
 * Journal de l'enregistrement en cours (`RideRecordingJournal` iOS) : `journal.jsonl`, une ligne JSON
 * AJOUTÉE par point (jamais de réécriture complète) ; première ligne = session. Une ligne tronquée
 * (arrêt pendant l'écriture) est ignorée.
 */
class RecordingJournal(private val directory: File) {
    class Restored(val sessionId: String, val startedMillis: Long, val points: List<RecordedPoint>)

    private val file get() = File(directory.apply { mkdirs() }, "journal.jsonl")

    fun begin(sessionId: String, startedMillis: Long) {
        file.writeText(JSONObject().put("sessionID", sessionId).put("startedAt", startedMillis).toString() + "\n")
    }

    fun append(point: RecordedPoint) {
        val line = JSONObject().put("lat", point.latitude).put("lon", point.longitude).put("time", point.timeMillis)
        point.elevation?.let { line.put("ele", it) }
        file.appendText(line.toString() + "\n")
    }

    fun load(): Restored? {
        val lines = runCatching { file.readLines() }.getOrNull()?.filter { it.isNotBlank() } ?: return null
        val header = lines.firstOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val sessionId = header.optString("sessionID").takeIf { it.isNotEmpty() } ?: return null
        val points = lines.drop(1).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                RecordedPoint(o.getDouble("lat"), o.getDouble("lon"), if (o.has("ele")) o.getDouble("ele") else null, o.getLong("time"))
            }.getOrNull()
        }
        return Restored(sessionId, header.optLong("startedAt"), points)
    }

    fun clear() {
        file.delete()
    }
}
