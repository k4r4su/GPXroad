package com.olivier.gpxroad.android.recording

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Copie de secours d'une sortie en cours, non encore « terminée ». */
data class UnsavedRide(val id: String, val fileName: String, val startedMillis: Long, val pointCount: Int)

/**
 * « Sorties non enregistrées » (`UnsavedRideStore` iOS, it19) : filet de secours si l'on oublie
 * « Terminer la sortie » ou si l'app s'arrête — GPX réécrit tous les 10 points dans un dossier À PART
 * de la Bibliothèque, jamais mélangé aux vraies traces tant qu'il n'est pas récupéré. Les plus
 * anciennes au-delà de la limite réglée sont supprimées.
 */
class UnsavedRideStore(private val directory: File) {
    var rides by mutableStateOf(load())
        private set

    private val indexFile get() = File(directory.apply { mkdirs() }, "index.json")

    fun file(ride: UnsavedRide) = File(directory, ride.fileName)

    fun checkpoint(sessionId: String, startedMillis: Long, gpx: String, pointCount: Int, maxRetained: Int) {
        val fileName = "$sessionId.gpx"
        runCatching { File(directory.apply { mkdirs() }, fileName).writeText(gpx) }.getOrElse { return }
        rides = if (rides.any { it.id == sessionId }) {
            rides.map { if (it.id == sessionId) it.copy(pointCount = pointCount) else it }
        } else {
            rides + UnsavedRide(sessionId, fileName, startedMillis, pointCount)
        }
        save()
        if (rides.size > maxRetained) {
            rides.sortedBy { it.startedMillis }.take(rides.size - maxRetained).forEach(::delete)
        }
    }

    fun discard(sessionId: String) {
        rides.firstOrNull { it.id == sessionId }?.let(::delete)
    }

    fun delete(ride: UnsavedRide) {
        file(ride).delete()
        rides = rides.filterNot { it.id == ride.id }
        save()
    }

    private fun load(): List<UnsavedRide> = runCatching {
        val array = JSONArray(File(directory, "index.json").readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            UnsavedRide(o.getString("id"), o.getString("fileName"), o.getLong("startedAt"), o.getInt("pointCount"))
        }.filter { File(directory, it.fileName).exists() }
    }.getOrDefault(emptyList())

    private fun save() {
        val array = JSONArray()
        rides.forEach { array.put(JSONObject().put("id", it.id).put("fileName", it.fileName).put("startedAt", it.startedMillis).put("pointCount", it.pointCount)) }
        indexFile.writeText(array.toString())
    }
}
