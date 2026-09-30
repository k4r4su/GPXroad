package com.olivier.gpxroad.shared.offline

import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Cartes hors ligne (`OfflineConstants` iOS) — tuiles vectorielles OpenFreeMap (niveau 14 au plus). */
object OfflineConstants {
    const val CORRIDOR_HALF_WIDTH_METERS = 1_000.0
    const val CORRIDOR_SAMPLE_STEP_METERS = 250.0
    const val CORRIDOR_MIN_ZOOM = 10
    /** Les tuiles vectorielles s'arrêtent au 14 (au-delà, la carte les agrandit elle-même). */
    const val VECTOR_MAX_ZOOM = 14
    const val CIRCLE_DEFAULT_RADIUS_KM = 15.0
    const val CIRCLE_MIN_RADIUS_KM = 1.0
    const val CIRCLE_MAX_RADIUS_KM = 200.0
    const val REGION_MIN_ZOOM = 5
    const val TILE_COUNT_HARD_CAP = 200_000
    /** Taille moyenne observée d'une tuile vectorielle OpenFreeMap (estimation avant téléchargement). */
    const val AVERAGE_VECTOR_TILE_BYTES = 30_000L
}

data class Tile(val z: Int, val x: Int, val y: Int)

data class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

/** Géométrie et estimation des zones hors ligne (portage de `TileCoordinate`/`CorridorPrecacheEstimator` iOS). */
object OfflineArea {
    fun covering(latitude: Double, longitude: Double, zoom: Int): Tile {
        val n = 2.0.pow(zoom)
        val x = floor((longitude + 180) / 360 * n).toInt()
        val latRad = latitude * PI / 180
        val y = floor((1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n).toInt()
        val last = n.toInt() - 1
        return Tile(zoom, x.coerceIn(0, last), y.coerceIn(0, last))
    }

    fun boxAround(center: LatLon, radiusMeters: Double): Box {
        val dLat = radiusMeters / 111_320.0
        val dLon = radiusMeters / (111_320.0 * max(cos(center.latitude * PI / 180), 0.01))
        return Box(center.latitude - dLat, center.latitude + dLat, center.longitude - dLon, center.longitude + dLon)
    }

    /** Nombre de tuiles d'une boîte à un niveau, SANS les énumérer (une boîte « monde » ne bloque rien). */
    fun tileCount(box: Box, zoom: Int): Long {
        val topLeft = covering(box.maxLat, box.minLon, zoom)
        val bottomRight = covering(box.minLat, box.maxLon, zoom)
        if (topLeft.x > bottomRight.x || topLeft.y > bottomRight.y) return 0
        return (bottomRight.x - topLeft.x + 1).toLong() * (bottomRight.y - topLeft.y + 1)
    }

    fun tiles(box: Box, zoom: Int): List<Tile> {
        val topLeft = covering(box.maxLat, box.minLon, zoom)
        val bottomRight = covering(box.minLat, box.maxLon, zoom)
        if (topLeft.x > bottomRight.x || topLeft.y > bottomRight.y) return emptyList()
        return (topLeft.x..bottomRight.x).flatMap { x -> (topLeft.y..bottomRight.y).map { y -> Tile(zoom, x, y) } }
    }

    /** Points de la trace tous les 250 m (le premier et le dernier toujours gardés). */
    fun sampled(points: List<LatLon>, step: Double = OfflineConstants.CORRIDOR_SAMPLE_STEP_METERS): List<LatLon> {
        if (points.size < 2) return points
        val result = arrayListOf(points.first())
        var accumulated = 0.0
        for (i in 1 until points.size) {
            accumulated += geodesicDistanceMeters(points[i - 1], points[i])
            if (accumulated >= step) {
                result += points[i]
                accumulated = 0.0
            }
        }
        if (result.last() != points.last()) result += points.last()
        return result
    }

    /** Couloir de la trace : un carré de ±1 km autour de chaque point échantillonné. */
    fun corridorBoxes(points: List<LatLon>): List<Box> = sampled(points).map { boxAround(it, OfflineConstants.CORRIDOR_HALF_WIDTH_METERS) }

    /** Tuiles distinctes couvrant ces boîtes, niveaux [minZoom]…[maxZoom]. */
    fun tileCount(boxes: List<Box>, minZoom: Int, maxZoom: Int): Int {
        val set = HashSet<Tile>()
        for (box in boxes) for (zoom in minZoom..maxZoom) set += tiles(box, zoom)
        return set.size
    }

    /** Zone circulaire : `null` au-delà du plafond de tuiles (jamais d'énumération géante). */
    fun circleTileCount(center: LatLon, radiusMeters: Double, minZoom: Int, maxZoom: Int): Long? {
        val box = boxAround(center, radiusMeters)
        var total = 0L
        for (zoom in minZoom..maxZoom) {
            total += tileCount(box, zoom)
            if (total > OfflineConstants.TILE_COUNT_HARD_CAP) return null
        }
        return total
    }

    /** Contour du cercle (36 segments, équirectangulaire), fermé — pour la carte et la zone à télécharger. */
    fun circle(center: LatLon, radiusMeters: Double, segments: Int = 36): List<LatLon> {
        val dLat = radiusMeters / 111_320.0
        val dLon = radiusMeters / (111_320.0 * max(cos(center.latitude * PI / 180), 0.01))
        val ring = (0 until segments).map { i ->
            val a = 2 * PI * i / segments
            LatLon(center.latitude + dLat * sin(a), center.longitude + dLon * cos(a))
        }
        return ring + ring.first()
    }

    fun estimatedBytes(tileCount: Long): Long = tileCount * OfflineConstants.AVERAGE_VECTOR_TILE_BYTES

    /** Plus petite boîte englobant une liste de points. */
    fun bounds(points: List<LatLon>): Box = Box(
        points.minOf { it.latitude }, points.maxOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.longitude },
    )

    fun clampRadiusKm(value: Double): Double = min(max(value, OfflineConstants.CIRCLE_MIN_RADIUS_KM), OfflineConstants.CIRCLE_MAX_RADIUS_KM)
}
