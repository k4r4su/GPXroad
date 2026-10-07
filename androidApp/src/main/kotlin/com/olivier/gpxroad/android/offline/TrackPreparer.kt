package com.olivier.gpxroad.android.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.LoadedTrack
import com.olivier.gpxroad.android.net.Http
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.roadbook.data.LandmarkPhase
import com.olivier.gpxroad.android.roadbook.data.RoadbookCaches
import com.olivier.gpxroad.android.roadbook.data.RoadbookData
import com.olivier.gpxroad.shared.offline.AutoPrefetch
import com.olivier.gpxroad.shared.offline.ReadinessLevel
import com.olivier.gpxroad.shared.offline.ReadinessPart
import com.olivier.gpxroad.shared.offline.TrackPreparation
import com.olivier.gpxroad.shared.offline.TrackReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Préparation complète de la trace active (idée du 06/10, `TrackPreparer` iOS) : carte du couloir, repères du Road Book,
 * recalage Valhalla et ronds-points, téléchargés dès que le réseau est bon. Règles (couloir, « prête » / « incomplète »)
 * = `shared/offline/TrackPreparation`. Les téléchargements eux-mêmes sont ceux du Road Book (`RoadbookData`, même cache
 * disque) et de la carte (`OfflineMaps`) : ce service décide seulement QUAND et dans quel ordre.
 */
class TrackPreparer(
    private val context: Context,
    private val settings: AppSettings,
    private val servers: ServerSettings,
    private val data: RoadbookData,
    private val offline: OfflineMaps,
) {
    private val caches = RoadbookCaches(context.filesDir)
    private val scope = MainScope()
    private var job: Job? = null
    private val failures = HashMap<String, Long>()
    private val memo = HashMap<String, TrackReadiness>()
    private var revision by mutableIntStateOf(0)

    var preparingTrackId by mutableStateOf<String?>(null)
        private set

    /** À appeler quand un cache a pu changer hors de ce service (retour sur la Bibliothèque). */
    fun invalidate() {
        memo.clear()
        revision++
    }

    /** Ce qui est déjà en local pour cette trace (lecture mémorisée : la fiche et la liste la relisent souvent). */
    fun readiness(track: LoadedTrack): TrackReadiness {
        val rev = revision
        val id = track.entry.id
        val enabled = settings.landmarkCategories
        val mapReady = offline.zoneForTrack(id)?.isComplete == true
        val valhalla = servers.valhalla != null
        val key = "$id|${track.traversalKey}|${enabled.hashCode()}|$valhalla|$mapReady|$rev"
        return memo.getOrPut(key) {
            TrackPreparation.evaluate(
                map = mapReady,
                landmarks = if (enabled.isEmpty()) null else caches.landmarks(id)?.fetchedCategories?.containsAll(enabled) == true,
                routeMatch = if (!valhalla) null else caches.mapMatch(track.traversalKey) != null,
                roundabouts = caches.roundabouts(id) != null,
            )
        }
    }

    /**
     * Prépare ce qui manque. `force` : demande de l'utilisateur (ignore le réglage automatique, l'attente après un échec et le
     * type de réseau, pas l'absence de réseau).
     */
    fun prepare(track: LoadedTrack, force: Boolean = false) {
        if (job != null) return
        val state = readiness(track)
        if (state.level == ReadinessLevel.READY) return
        val id = track.entry.id
        if (force) {
            if (!Http.isOnline(context)) return
        } else {
            if (!settings.autoPrepareEnabled || !Http.isGoodForDownloads(context, settings.autoMapCellular, AutoPrefetch.MIN_CELLULAR_KBPS)) return
            failures[id]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MILLIS) return }
        }
        val missing = state.missing.toSet()
        val enabled = settings.landmarkCategories
        preparingTrackId = id
        job = scope.launch {
            var complete = true
            // Les petites données d'abord (Road Book), la carte (la plus lourde) en dernier.
            if (ReadinessPart.ROUTE_MATCH in missing) {
                data.forgetMapMatchAttempt()
                data.ensureMapMatch(track)
                complete = waitFor(2 * MINUTE) { caches.mapMatch(track.traversalKey) != null } && complete
            }
            if (ReadinessPart.ROUNDABOUTS in missing) {
                data.ensureRoundabouts(track)
                complete = waitFor(4 * MINUTE) { caches.roundabouts(id) != null } && complete
            }
            if (ReadinessPart.LANDMARKS in missing) {
                data.updateLandmarks(track, data.maneuvers.orEmpty(), enabled)
                complete = waitFor(10 * MINUTE, stopIf = { data.landmarkPhase.let { it is LandmarkPhase.Failed || it is LandmarkPhase.Offline } }) {
                    caches.landmarks(id)?.fetchedCategories?.containsAll(enabled) == true
                } && complete
            }
            if (ReadinessPart.MAP in missing) complete = downloadMap(track) && complete
            if (complete) failures.remove(id) else failures[id] = System.currentTimeMillis()
            job = null
            preparingTrackId = null
            invalidate()
        }
    }

    private suspend fun downloadMap(track: LoadedTrack): Boolean {
        val id = track.entry.id
        offline.zoneForTrack(id)?.takeIf { it.failed }?.let {
            offline.delete(it.id)   // une zone restée en échec est refaite
            waitFor(10_000) { offline.zoneForTrack(id) == null }
        }
        if (offline.zoneForTrack(id) == null) offline.downloadTrack(id, track.entry.name, track.latLons)
        return waitFor(30 * MINUTE, stopIf = { offline.zoneForTrack(id)?.failed == true }) { offline.zoneForTrack(id)?.isComplete == true }
    }

    /** Attend [condition] (lue hors du fil principal : lecture de fichiers), au plus [timeoutMillis]. */
    private suspend fun waitFor(timeoutMillis: Long, stopIf: () -> Boolean = { false }, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < timeoutMillis) {
            if (withContext(Dispatchers.IO) { condition() }) return true
            if (stopIf()) return false
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        return false
    }

    private companion object {
        const val MINUTE = 60_000L
        const val POLL_MILLIS = 2_000L
        const val RETRY_AFTER_MILLIS = 10 * MINUTE
    }
}
