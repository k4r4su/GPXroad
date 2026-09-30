package com.olivier.gpxroad.android.sync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.net.Http
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.recording.IsoTime
import com.olivier.gpxroad.shared.ride.SharedBlockage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Base partagée ANONYME des points bloqués (`SharedBlockageSyncCoordinator` iOS, serveur
 * `server/app.py` auto-hébergé) : adresse vide par défaut = aucune requête réseau. Identifiant
 * anonyme renouvelé tous les 30 jours, jamais lié à un compte. Cache local
 * `files/Sync/shared-blockages.json` ; synchro au plus une fois par jour autour de la trace.
 */
class SharedBlockageSync(context: Context) {
    private val preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "Sync/shared-blockages.json")
    private val scope = MainScope()
    private var inFlight = false
    private var lastAttemptMillis = 0L

    var shareEnabled by mutableStateOf(preferences.getBoolean("share", true))
        private set
    var serverUrl by mutableStateOf(preferences.getString("serverUrl", "").orEmpty())
        private set
    var blockages by mutableStateOf(load())
        private set

    fun update(enabled: Boolean, url: String) {
        shareEnabled = enabled
        serverUrl = url.trim()
        preferences.edit().putBoolean("share", enabled).putString("serverUrl", serverUrl).apply()
    }

    /** Synchro autour de la trace si due (24 h), jamais plus d'un essai par minute. */
    fun syncIfNeeded(points: List<LatLon>) {
        val url = serverUrl
        val now = System.currentTimeMillis()
        if (!shareEnabled || url.isEmpty() || points.isEmpty() || inFlight || now - lastAttemptMillis < 60_000) return
        if (now - preferences.getLong("lastSync", 0) < SYNC_INTERVAL_MILLIS) return
        lastAttemptMillis = now
        inFlight = true
        val pad = 0.05
        val query = "min_lat=${points.minOf { it.latitude } - pad}&min_lon=${points.minOf { it.longitude } - pad}" +
            "&max_lat=${points.maxOf { it.latitude } + pad}&max_lon=${points.maxOf { it.longitude } + pad}"
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                runCatching { parseList(Http.request(url.trimEnd('/') + "/blockages?" + query, timeoutMillis = TIMEOUT_MILLIS)) }.getOrNull()
            }
            inFlight = false
            if (fetched != null) {
                merge(fetched)
                preferences.edit().putLong("lastSync", System.currentTimeMillis()).apply()
            }
        }
    }

    /** Signalement anonyme d'un chemin bloqué (échec sans conséquence : rien n'est bloquant). */
    fun report(position: LatLon) {
        val url = serverUrl
        if (!shareEnabled || url.isEmpty()) return
        val body = JSONObject().put("lat", position.latitude).put("lon", position.longitude).put("reporter_id", reporterId()).toString()
        scope.launch {
            val confirmed = withContext(Dispatchers.IO) {
                runCatching {
                    parse(JSONObject(Http.request(url.trimEnd('/') + "/blockages", "POST", body.toByteArray(), "application/json", timeoutMillis = TIMEOUT_MILLIS).toString(Charsets.UTF_8)))
                }.getOrNull()
            }
            confirmed?.let { merge(listOf(it)) }
        }
    }

    private fun reporterId(): String {
        val created = preferences.getLong("reporterCreated", 0)
        val existing = preferences.getString("reporterId", null)
        if (existing != null && System.currentTimeMillis() - created < ROTATION_MILLIS) return existing
        val fresh = UUID.randomUUID().toString().uppercase()
        preferences.edit().putString("reporterId", fresh).putLong("reporterCreated", System.currentTimeMillis()).apply()
        return fresh
    }

    private fun merge(fetched: List<SharedBlockage>) {
        val now = System.currentTimeMillis()
        blockages = (blockages.associateBy { it.id } + fetched.associateBy { it.id }).values.filterNot { it.isExpired(now) }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONArray(blockages.map { b ->
                JSONObject().put("id", b.id).put("lat", b.coordinate.latitude).put("lon", b.coordinate.longitude)
                    .put("note", b.note ?: JSONObject.NULL).put("last_confirmed_at", IsoTime.format(b.lastConfirmedMillis))
            }).toString())
        }
    }

    private fun load(): List<SharedBlockage> =
        runCatching { parseList(file.readBytes()) }.getOrDefault(emptyList()).filterNot { it.isExpired(System.currentTimeMillis()) }

    private fun parseList(data: ByteArray): List<SharedBlockage> {
        val array = JSONArray(data.toString(Charsets.UTF_8))
        return (0 until array.length()).mapNotNull { parse(array.getJSONObject(it)) }
    }

    private fun parse(o: JSONObject): SharedBlockage? {
        val confirmed = IsoTime.parse(o.optString("last_confirmed_at")) ?: return null
        return SharedBlockage(o.getString("id"), LatLon(o.getDouble("lat"), o.getDouble("lon")), o.optString("note").takeIf { it.isNotEmpty() && it != "null" }, confirmed)
    }

    private companion object {
        const val SYNC_INTERVAL_MILLIS = 24 * 3600 * 1000L
        const val ROTATION_MILLIS = 30 * 24 * 3600 * 1000L
        const val TIMEOUT_MILLIS = 8_000
    }
}
