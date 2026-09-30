package com.olivier.gpxroad.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.gpx.GpxDocument
import com.olivier.gpxroad.shared.gpx.GpxParseException
import com.olivier.gpxroad.shared.gpx.GpxParser
import com.olivier.gpxroad.shared.gpx.GpxPoint
import com.olivier.gpxroad.shared.gpx.TrackOrder
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Trace de la bibliothèque (équivalent de `GPXTrack` iOS). Le fichier GPX importé est copié tel
 * quel et n'est JAMAIS modifié (« trace sacrée ») ; le sens de parcours est un réglage à part.
 */
data class TrackEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val importDateMillis: Long,
    val contentTimeIso: String?,
    val pointCount: Int,
    val lengthMeters: Double,
    val reversed: Boolean = false,
)

/** Trace chargée, dans son sens de parcours. */
class LoadedTrack(val entry: TrackEntry, val document: GpxDocument) {
    val points: List<GpxPoint> = TrackOrder.reordered(document.points, entry.reversed)
    val latLons: List<LatLon> = points.map { LatLon(it.latitude, it.longitude) }
    val cumulative: DoubleArray = TrackGeometry.cumulativeDistances(latLons)
    val traversalKey: String = TrackOrder.traversalKey(entry.id, points)
}

/**
 * Bibliothèque des traces (équivalent de `LibraryStore` iOS) — SEULE source de vérité de la trace
 * active (règle it10) : `files/Tracks/<id>.gpx` + `index.json`, trace active dans les préférences.
 */
class TrackLibrary(private val context: Context) {
    private val directory = File(context.filesDir, "Tracks").apply { mkdirs() }
    private val indexFile = File(directory, "index.json")
    private val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    var tracks by mutableStateOf(loadIndex())
        private set
    var activeTrackId by mutableStateOf(preferences.getString(KEY_ACTIVE, null)?.takeIf { id -> tracks.any { it.id == id } })
        private set

    private var cache: LoadedTrack? = null

    val activeTrack: LoadedTrack?
        get() {
            val entry = tracks.firstOrNull { it.id == activeTrackId } ?: return null
            cache?.let { if (it.entry == entry) return it }
            val document = runCatching { GpxParser.parse(File(directory, entry.fileName).readText()) }.getOrNull() ?: return null
            return LoadedTrack(entry, document).also { cache = it }
        }

    sealed interface ImportResult {
        data class Success(val entry: TrackEntry) : ImportResult
        data class Failure(val reason: GpxParseException.Reason?) : ImportResult
    }

    /** Importe un GPX choisi par l'utilisateur ; devient la trace active s'il n'y en a pas. */
    fun import(uri: Uri): ImportResult {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            ?: return ImportResult.Failure(null)
        return importText(text, fallbackName = displayName(uri), activateIfNone = true)
    }

    /**
     * Ajoute un GPX (fichier importé, sortie enregistrée, sortie récupérée). Le texte est copié tel
     * quel. [activateIfNone] : devient la trace active s'il n'y en a aucune — jamais pour une sortie
     * enregistrée, qui ne vole pas la trace suivie (comme l'iPhone).
     */
    fun importText(text: String, fallbackName: String? = null, activateIfNone: Boolean = false): ImportResult {
        val document = try {
            GpxParser.parse(text)
        } catch (error: GpxParseException) {
            return ImportResult.Failure(error.reason)
        }
        val id = UUID.randomUUID().toString().uppercase()
        val fileName = "$id.gpx"
        File(directory, fileName).writeText(text)
        val latLons = document.points.map { LatLon(it.latitude, it.longitude) }
        val entry = TrackEntry(
            id = id,
            name = document.name ?: fallbackName ?: fileName,
            fileName = fileName,
            importDateMillis = System.currentTimeMillis(),
            contentTimeIso = document.metadataTimeIso,
            pointCount = document.points.size,
            lengthMeters = TrackGeometry.cumulativeDistances(latLons).lastOrNull() ?: 0.0,
        )
        tracks = listOf(entry) + tracks
        saveIndex()
        if (activateIfNone && activeTrackId == null) setActive(entry.id)
        return ImportResult.Success(entry)
    }

    /** Fichier GPX stocké (jamais renommé ni modifié). */
    fun file(entry: TrackEntry): File = File(directory, entry.fileName)

    fun setActive(id: String?) {
        activeTrackId = id
        preferences.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun setReversed(id: String, reversed: Boolean) = update(id) { it.copy(reversed = reversed) }

    fun delete(id: String) {
        val entry = tracks.firstOrNull { it.id == id } ?: return
        File(directory, entry.fileName).delete()
        tracks = tracks.filterNot { it.id == id }
        saveIndex()
        if (activeTrackId == id) setActive(null)
    }

    private fun update(id: String, change: (TrackEntry) -> TrackEntry) {
        tracks = tracks.map { if (it.id == id) change(it) else it }
        saveIndex()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()

    private fun loadIndex(): List<TrackEntry> = runCatching {
        val array = JSONArray(indexFile.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TrackEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                fileName = o.getString("fileName"),
                importDateMillis = o.getLong("importDate"),
                contentTimeIso = o.optString("contentTime").takeIf { it.isNotEmpty() },
                pointCount = o.getInt("pointCount"),
                lengthMeters = o.getDouble("lengthMeters"),
                reversed = o.optBoolean("reversed", false),
            )
        }.filter { File(directory, it.fileName).exists() }
    }.getOrDefault(emptyList())

    private fun saveIndex() {
        val array = JSONArray()
        tracks.forEach { t ->
            array.put(
                JSONObject()
                    .put("id", t.id).put("name", t.name).put("fileName", t.fileName)
                    .put("importDate", t.importDateMillis).put("contentTime", t.contentTimeIso ?: "")
                    .put("pointCount", t.pointCount).put("lengthMeters", t.lengthMeters).put("reversed", t.reversed),
            )
        }
        indexFile.writeText(array.toString())
    }

    private companion object {
        const val KEY_ACTIVE = "activeTrackID"
    }
}
