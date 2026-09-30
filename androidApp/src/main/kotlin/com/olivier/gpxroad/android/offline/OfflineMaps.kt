package com.olivier.gpxroad.android.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.offline.OfflineArea
import com.olivier.gpxroad.shared.offline.OfflineConstants
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Zone hors ligne : couloir d'une trace ou cercle ; progression 0-1 pendant le téléchargement. */
data class OfflineZone(
    val id: Long,
    val name: String,
    val trackId: String?,
    val createdMillis: Long,
    val sizeBytes: Long,
    val progress: Float,
    val isComplete: Boolean,
    val failed: Boolean,
)

/**
 * Cartes hors ligne (équivalent du cache de tuiles iOS, `Offline/`) avec le stockage hors ligne de
 * MapLibre : les tuiles VECTORIELLES du style de l'app (les trois palettes les partagent) sont
 * gardées pour une zone ; hors réseau, la carte du Ride garde alors son style vectoriel.
 * - couloir de ±1 km autour d'une trace (niveaux 10 à 14, comme le précache iOS) ;
 * - cercle autour d'une position (1 à 200 km, niveaux 5 à 14).
 */
class OfflineMaps(private val context: Context) {
    private val manager: OfflineManager
    private val regions = HashMap<Long, OfflineRegion>()

    var zones by mutableStateOf<List<OfflineZone>>(emptyList())
        private set

    val hasZones: Boolean get() = zones.any { it.isComplete || it.sizeBytes > 0 }

    init {
        MapLibre.getInstance(context)
        manager = OfflineManager.getInstance(context)
        refresh()
    }

    fun zoneForTrack(trackId: String): OfflineZone? = zones.firstOrNull { it.trackId == trackId }

    fun downloadTrack(trackId: String, name: String, points: List<LatLon>) {
        if (points.size < 2 || zoneForTrack(trackId) != null) return
        val polygons = OfflineArea.corridorBoxes(points).map { box ->
            Polygon.fromLngLats(
                listOf(
                    listOf(
                        Point.fromLngLat(box.minLon, box.minLat), Point.fromLngLat(box.maxLon, box.minLat),
                        Point.fromLngLat(box.maxLon, box.maxLat), Point.fromLngLat(box.minLon, box.maxLat),
                        Point.fromLngLat(box.minLon, box.minLat),
                    ),
                ),
            )
        }
        create(MultiPolygon.fromPolygons(polygons), OfflineConstants.CORRIDOR_MIN_ZOOM, name, trackId)
    }

    fun downloadCircle(name: String, center: LatLon, radiusMeters: Double, maxZoom: Int) {
        val ring = OfflineArea.circle(center, radiusMeters).map { Point.fromLngLat(it.longitude, it.latitude) }
        create(Polygon.fromLngLats(listOf(ring)), OfflineConstants.REGION_MIN_ZOOM, name, null, maxZoom)
    }

    fun delete(id: Long) {
        val region = regions[id] ?: return
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                regions.remove(id)
                zones = zones.filterNot { it.id == id }
            }

            override fun onError(error: String) = Unit
        })
    }

    private fun create(geometry: org.maplibre.geojson.Geometry, minZoom: Int, name: String, trackId: String?, maxZoom: Int = OfflineConstants.VECTOR_MAX_ZOOM) {
        val definition = OfflineGeometryRegionDefinition(styleUrl(), geometry, minZoom.toDouble(), maxZoom.toDouble(), context.resources.displayMetrics.density, false)
        val metadata = JSONObject().put("name", name).put("trackId", trackId ?: JSONObject.NULL).put("created", System.currentTimeMillis()).toString().toByteArray()
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                regions[offlineRegion.id] = offlineRegion
                upsert(zoneOf(offlineRegion, null))
                observe(offlineRegion)
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) = Unit
        })
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                upsert(zoneOf(region, status))
                if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }

            override fun onError(error: OfflineRegionError) {
                zones = zones.map { if (it.id == region.id) it.copy(failed = true) else it }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
        })
    }

    /** Relit les zones enregistrées ; une zone interrompue (app fermée) reprend son téléchargement. */
    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().forEach { region ->
                    regions[region.id] = region
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            upsert(zoneOf(region, status))
                            if (status != null && !status.isComplete) {
                                observe(region)
                                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                            }
                        }

                        override fun onError(error: String?) = Unit
                    })
                }
            }

            override fun onError(error: String) = Unit
        })
    }

    private fun zoneOf(region: OfflineRegion, status: OfflineRegionStatus?): OfflineZone {
        val meta = runCatching { JSONObject(String(region.metadata)) }.getOrNull()
        val required = status?.requiredResourceCount ?: 0
        return OfflineZone(
            id = region.id,
            name = meta?.optString("name").orEmpty(),
            trackId = meta?.optString("trackId")?.takeIf { it.isNotEmpty() && it != "null" },
            createdMillis = meta?.optLong("created") ?: 0,
            sizeBytes = status?.completedResourceSize ?: 0,
            progress = if (required > 0) (status!!.completedResourceCount.toFloat() / required).coerceIn(0f, 1f) else 0f,
            isComplete = status?.isComplete == true,
            failed = false,
        )
    }

    private fun upsert(zone: OfflineZone) {
        zones = (zones.filterNot { it.id == zone.id } + zone).sortedByDescending { it.createdMillis }
    }

    /**
     * Style de référence du téléchargement : « Liberty » publié par OpenFreeMap, dont dérive le style
     * embarqué — MÊMES adresses de tuiles, glyphes et icônes (vérifié le 01/10), donc tout ce qu'il
     * télécharge sert la carte de l'app. (Le téléchargeur de MapLibre refuse un style en fichier local.)
     */
    private fun styleUrl(): String = REFERENCE_STYLE_URL

    private companion object {
        const val REFERENCE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }
}
