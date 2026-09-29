package com.olivier.gpxroad.android.roadbook.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.data.LoadedTrack
import com.olivier.gpxroad.android.net.Http
import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.roadbook.LandmarkConstants
import com.olivier.gpxroad.shared.roadbook.LandmarkSelection
import com.olivier.gpxroad.shared.roadbook.LandmarkSelector
import com.olivier.gpxroad.shared.roadbook.MapMatchCoverage
import com.olivier.gpxroad.shared.roadbook.OsmMiniRoundabout
import com.olivier.gpxroad.shared.roadbook.OsmRoad
import com.olivier.gpxroad.shared.roadbook.RoadbookExtractor
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import com.olivier.gpxroad.shared.roadbook.RoadbookSettings
import com.olivier.gpxroad.shared.roadbook.RoundaboutAnalyzer
import com.olivier.gpxroad.shared.roadbook.RoundaboutMapData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** État du chargement des repères (bandeau du Road Book) — jamais bloquant. */
sealed interface LandmarkPhase {
    data object Idle : LandmarkPhase
    data class Downloading(val completedChunks: Int, val totalChunks: Int, val receivedBytes: Long, val retrying: Boolean) : LandmarkPhase
    data object Analyzing : LandmarkPhase
    data object Finished : LandmarkPhase
    data object Failed : LandmarkPhase
    data class Offline(val hasCachedData: Boolean) : LandmarkPhase
}

/**
 * Données réseau du Road Book pour la trace active (portage de `RoadBookTabView` +
 * `RoadbookRoundaboutStore` + `RoadbookLandmarkLoader` iOS) — seul endroit qui décide QUAND
 * appeler Valhalla ou Overpass :
 * - map matching Valhalla une fois par parcours (trace + sens) si Valhalla est configuré ; cache
 *   disque ; échec = Road Book géométrique, jamais un blocage ;
 * - ronds-points OSM par trace, tronçon par tronçon, rien en cache au premier échec, nouvel essai
 *   après 60 s ;
 * - repères : catégories activées seulement, complément pour une catégorie jamais téléchargée,
 *   tronçons de 8 km (progression), reprise au tronçon en échec (« Réessayer »).
 */
class RoadbookData(
    private val context: Context,
    private val servers: ServerSettings,
    private val overpass: OverpassClient,
    private val routing: RoutingClient,
) {
    private val caches = RoadbookCaches(context.filesDir)
    private val scope: CoroutineScope = MainScope()

    /** Manœuvres Valhalla et couverture du parcours affiché (`null` : Road Book géométrique). */
    var mapMatch by mutableStateOf<MapMatchEntry?>(null)
        private set
    var roundabouts by mutableStateOf<RoundaboutMapData?>(null)
        private set
    var landmarkPhase by mutableStateOf<LandmarkPhase>(LandmarkPhase.Idle)
        private set
    var landmarkSelection by mutableStateOf(LandmarkSelection.EMPTY)
        private set

    /**
     * Manœuvres de la trace active — UNE seule liste pour le Road Book ET le Ride (épingles, bannière
     * latérale), comme `SharedRoadbook.maneuvers` iOS. `null` pendant le premier calcul d'une trace ;
     * l'ancienne liste reste affichée pendant un recalcul (arrivée de Valhalla, des ronds-points).
     */
    var maneuvers by mutableStateOf<List<RoadbookManeuver>?>(null)
        private set
    private var maneuversTrackKey: String? = null
    private var maneuversInputs: Any? = null
    private var maneuversJob: Job? = null

    /** Recalcule les manœuvres si la trace, les réglages ou les données réseau ont changé. */
    fun refreshManeuvers(track: LoadedTrack, settings: RoadbookSettings) {
        val inputs = listOf(track.traversalKey, settings, mapMatch, roundabouts)
        if (inputs == maneuversInputs) return
        maneuversInputs = inputs
        if (track.traversalKey != maneuversTrackKey) {
            maneuversTrackKey = track.traversalKey
            maneuvers = null
        }
        val mapMatch = mapMatch
        val roundabouts = roundabouts
        maneuversJob?.cancel()
        maneuversJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                val passages = roundabouts?.takeIf { it.roads.isNotEmpty() || it.miniRoundabouts.isNotEmpty() }
                    ?.let { RoundaboutAnalyzer.passages(track.latLons, it) } ?: emptyList()
                RoadbookExtractor.maneuvers(track.latLons, settings, mapMatch?.maneuvers ?: emptyList(), mapMatch?.coverage, passages)
            }
            if (maneuversTrackKey == track.traversalKey) maneuvers = result
        }
    }

    private var mapMatchKey: String? = null
    private var mapMatchJob: Job? = null

    private var roundaboutTrackId: String? = null
    private var roundaboutJob: Job? = null
    private var roundaboutFailedAt = 0L

    private var landmarkTrackId: String? = null
    private var landmarkTraversalKey: String? = null
    private var landmarkData: StoredLandmarkData? = null
    private var landmarkPoints: List<LatLon> = emptyList()
    private var landmarkManeuvers: List<RoadbookManeuver> = emptyList()
    private var enabledCategories: Set<LandmarkCategory> = emptySet()
    private var failedCategories: Set<LandmarkCategory>? = null
    private var partial: PartialDownload? = null
    private var downloadJob: Job? = null
    private var selectionJob: Job? = null
    private var hideJob: Job? = null

    private class PartialDownload(
        val trackId: String,
        val categories: Set<LandmarkCategory>,
        val totalChunks: Int,
        val base: StoredLandmarkData,
        val completedChunks: Int,
        val received: StoredLandmarkData,
    )

    // MARK: Valhalla

    /** À chaque affichage du Road Book (trace, sens ou serveur changé) : map matching si nécessaire. */
    fun ensureMapMatch(track: LoadedTrack) {
        val configuration = servers.valhalla
        val key = track.traversalKey + "|" + (configuration?.endpoint ?: "-")
        if (key == mapMatchKey) return
        mapMatchKey = key
        mapMatchJob?.cancel()
        if (configuration == null) {
            mapMatch = null
            return
        }
        caches.mapMatch(track.traversalKey)?.let {
            mapMatch = it
            return
        }
        mapMatch = null
        val points = track.latLons
        mapMatchJob = scope.launch {
            val entry = withContext(Dispatchers.IO) {
                runCatching {
                    val result = routing.mapMatch(points, configuration)
                    MapMatchEntry(result.maneuvers, MapMatchCoverage.coveredRanges(points, result.matchedShapes))
                }.getOrNull()
            } ?: return@launch
            if (mapMatchKey != key) return@launch
            caches.storeMapMatch(track.traversalKey, entry)
            mapMatch = entry
        }
    }

    // MARK: Ronds-points

    fun ensureRoundabouts(track: LoadedTrack) {
        val id = track.entry.id
        if (id != roundaboutTrackId) {
            roundaboutTrackId = id
            roundaboutJob?.cancel()
            roundaboutJob = null
            roundaboutFailedAt = 0
            roundabouts = caches.roundabouts(id)
        }
        if (roundabouts != null || roundaboutJob != null || track.latLons.size < 2) return
        if (System.currentTimeMillis() - roundaboutFailedAt < ROUNDABOUT_RETRY_AFTER_MILLIS) return
        val points = track.latLons
        roundaboutJob = scope.launch {
            val data = withContext(Dispatchers.IO) { fetchRoundabouts(points) }
            if (roundaboutTrackId != id) return@launch
            roundaboutJob = null
            if (data == null) {
                roundaboutFailedAt = System.currentTimeMillis()
                return@launch
            }
            caches.storeRoundabouts(id, data)
            roundabouts = data
        }
    }

    /** Tous les tronçons ; `null` au premier échec — jamais un résultat partiel en cache. */
    private fun fetchRoundabouts(points: List<LatLon>): RoundaboutMapData? {
        val roads = mutableListOf<OsmRoad>()
        val minis = mutableListOf<OsmMiniRoundabout>()
        val knownRoads = mutableSetOf<Long>()
        val knownMinis = mutableSetOf<Long>()
        for (chunk in RoadbookOverpass.chunks(points)) {
            val query = RoadbookOverpass.roundaboutQuery(chunk) ?: return null
            val parsed = overpass.fetchOnce(query, RoadbookOverpass.REQUEST_TIMEOUT_SECONDS * 1000) { RoadbookOverpass.parseRoundabouts(it) } ?: return null
            roads.addAll(parsed.roads.filter { knownRoads.add(it.id) })
            minis.addAll(parsed.miniRoundabouts.filter { knownMinis.add(it.nodeId) })
        }
        return RoundaboutMapData(roads, minis)
    }

    // MARK: Repères

    /** À chaque changement de trace/sens, de manœuvres ou de catégories activées. */
    fun updateLandmarks(track: LoadedTrack, maneuvers: List<RoadbookManeuver>, enabled: Set<LandmarkCategory>) {
        val trackId = track.entry.id
        if (trackId != landmarkTrackId) {
            downloadJob?.cancel()
            downloadJob = null
            hideJob?.cancel()
            failedCategories = null
            partial = null
            landmarkData = caches.landmarks(trackId)
            landmarkPhase = LandmarkPhase.Idle
        }
        if (track.traversalKey != landmarkTraversalKey) landmarkSelection = LandmarkSelection.EMPTY
        if (enabled != enabledCategories) failedCategories = null
        landmarkTrackId = trackId
        landmarkTraversalKey = track.traversalKey
        landmarkPoints = track.latLons
        landmarkManeuvers = maneuvers
        enabledCategories = enabled
        reselect()
        ensureLandmarksDownloaded()
    }

    /** Bouton « Réessayer », ou retour du réseau. */
    fun retryLandmarks() {
        failedCategories = null
        ensureLandmarksDownloaded()
    }

    private fun ensureLandmarksDownloaded() {
        val trackId = landmarkTrackId ?: return
        if (downloadJob != null) return
        val missing = enabledCategories - (landmarkData?.fetchedCategories ?: emptySet())
        if (missing.isEmpty()) {
            if (landmarkPhase is LandmarkPhase.Offline || landmarkPhase == LandmarkPhase.Failed) landmarkPhase = LandmarkPhase.Idle
            return
        }
        if (!Http.isOnline(context)) {
            landmarkPhase = LandmarkPhase.Offline(hasCachedData = landmarkData?.candidates?.isNotEmpty() == true)
            return
        }
        failedCategories?.let { if (it.containsAll(missing)) return }
        val chunks = RoadbookOverpass.chunks(landmarkPoints)
        if (chunks.isEmpty()) return
        hideJob?.cancel()
        val resumed = partial?.takeIf { it.trackId == trackId && it.categories == missing && it.totalChunks == chunks.size }
        val start = PartialDownload(trackId, missing, chunks.size, resumed?.base ?: landmarkData ?: StoredLandmarkData.EMPTY, resumed?.completedChunks ?: 0, resumed?.received ?: StoredLandmarkData.EMPTY)
        landmarkPhase = LandmarkPhase.Downloading(start.completedChunks, chunks.size, 0, false)
        downloadJob = scope.launch { downloadLandmarks(chunks, start) }
    }

    private suspend fun downloadLandmarks(chunks: List<List<LatLon>>, start: PartialDownload) {
        val trackId = start.trackId
        var received = start.received
        var bytes = 0L
        for (index in start.completedChunks until chunks.size) {
            val result = withContext(Dispatchers.IO) {
                fetchLandmarkChunk(chunks[index], start.categories, isCancelled = { landmarkTrackId != trackId }, onBytes = { count ->
                    bytes += count
                    scope.launch { if (landmarkTrackId == trackId) landmarkPhase = LandmarkPhase.Downloading(index, chunks.size, bytes, false) }
                }, onRetry = {
                    scope.launch { if (landmarkTrackId == trackId) landmarkPhase = LandmarkPhase.Downloading(index, chunks.size, bytes, true) }
                })
            }
            if (landmarkTrackId != trackId) return
            if (result == null) {
                failedCategories = start.categories
                partial = PartialDownload(trackId, start.categories, chunks.size, start.base, index, received)
                landmarkPhase = if (Http.isOnline(context)) LandmarkPhase.Failed else LandmarkPhase.Offline(landmarkData?.candidates?.isNotEmpty() == true)
                downloadJob = null
                reselect()
                return
            }
            received = received.adding(result, emptySet())
            // Au fur et à mesure : affichés, pas encore marqués « téléchargés ».
            landmarkData = start.base.adding(received, emptySet())
            landmarkPhase = LandmarkPhase.Downloading(index + 1, chunks.size, bytes, false)
            reselect()
        }
        partial = null
        val complete = start.base.adding(received, start.categories)
        caches.storeLandmarks(trackId, complete)
        landmarkData = complete
        landmarkPhase = LandmarkPhase.Analyzing
        reselect()
        selectionJob?.join()
        if (landmarkTrackId != trackId) return
        downloadJob = null
        landmarkPhase = LandmarkPhase.Finished
        hideJob = scope.launch {
            delay(DONE_DISPLAY_MILLIS)
            if (landmarkPhase == LandmarkPhase.Finished) landmarkPhase = LandmarkPhase.Idle
        }
        // Une catégorie activée PENDANT le téléchargement : son complément maintenant.
        ensureLandmarksDownloaded()
    }

    /** Un tronçon, avec les nouveaux essais d'Overpass saturé (5, 15, 30 s) ; `null` si tout échoue. */
    private suspend fun fetchLandmarkChunk(
        points: List<LatLon>,
        categories: Set<LandmarkCategory>,
        isCancelled: () -> Boolean,
        onBytes: (Int) -> Unit,
        onRetry: () -> Unit,
    ): StoredLandmarkData? {
        val query = RoadbookOverpass.landmarkQuery(points, categories, LandmarkConstants.CITY_ENTRY_FALLBACK_ENABLED) ?: return null
        val delays = RoadbookOverpass.RETRY_DELAYS_SECONDS
        for (attempt in 0..delays.size) {
            val data = overpass.fetchOnce(query, RoadbookOverpass.REQUEST_TIMEOUT_SECONDS * 1000, onBytes, isCancelled) { it }
            if (data != null) return RoadbookOverpass.parseLandmarks(data)?.filtered(categories)
            if (attempt >= delays.size || isCancelled()) break
            onRetry()
            delay((delays[attempt] * 1000).toLong())
        }
        return null
    }

    private fun reselect() {
        val data = landmarkData
        val points = landmarkPoints
        if (data == null || points.size < 2) {
            landmarkSelection = LandmarkSelection.EMPTY
            return
        }
        val maneuvers = landmarkManeuvers
        val enabled = enabledCategories
        val key = landmarkTraversalKey
        val previous = selectionJob
        selectionJob = scope.launch {
            previous?.join()
            val selection = withContext(Dispatchers.Default) {
                LandmarkSelector.select(data.shared, points, maneuvers, enabled, LandmarkConstants.CITY_ENTRY_FALLBACK_ENABLED)
            }
            if (landmarkTraversalKey == key && landmarkManeuvers === maneuvers && enabledCategories == enabled) landmarkSelection = selection
        }
    }

    private companion object {
        const val ROUNDABOUT_RETRY_AFTER_MILLIS = 60_000L
        const val DONE_DISPLAY_MILLIS = 2_000L
    }
}
