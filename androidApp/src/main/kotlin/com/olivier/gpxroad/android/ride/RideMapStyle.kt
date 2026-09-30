package com.olivier.gpxroad.android.ride

import android.content.Context
import com.olivier.gpxroad.shared.map.ColorFlavorPatcher
import com.olivier.gpxroad.shared.map.MapColorFlavor
import com.olivier.gpxroad.shared.map.MapTheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * Styles de la carte du Ride (portage de `MapEngineConstants.buildStyleJSON` iOS) : le style
 * vectoriel « Liberty » embarqué (MÊME fichier que l'iPhone, copié à la compilation) sur les tuiles
 * OpenFreeMap, palette Standard (inchangée) ; hors réseau, le raster OSM.
 */
object RideMapStyle {
    private const val VECTOR_STYLE_ASSET = "vector-style-liberty.json"
    private const val VECTOR_SOURCE = "openmaptiles"
    private const val VECTOR_TILES_URL = "https://tiles.openfreemap.org/planet"
    private const val OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    /** Thème Relief (`TileSource.openTopoMap` iOS). */
    private val OPENTOPOMAP_TILES = listOf("a", "b", "c").map { "https://$it.tile.opentopomap.org/{z}/{x}/{y}.png" }
    private const val OPENTOPOMAP_ATTRIBUTION = "Kartendaten: © OpenStreetMap-Mitwirkende, SRTM | Style: © OpenTopoMap (CC-BY-SA)"
    private const val OSM_ATTRIBUTION = "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"

    /** Style du thème : vectoriel retouché par sa palette, ou Relief (raster OpenTopoMap). */
    fun forTheme(context: Context, theme: MapTheme, online: Boolean): String {
        val flavor = theme.flavor
        return when {
            !online -> raster()
            flavor == null -> raster(OPENTOPOMAP_TILES, OPENTOPOMAP_ATTRIBUTION, 17)
            else -> vector(context, flavor) ?: raster()
        }
    }

    fun vector(context: Context, flavor: MapColorFlavor = MapColorFlavor.STANDARD): String? = runCatching {
        val style = JSONObject(context.assets.open(VECTOR_STYLE_ASSET).bufferedReader().use { it.readText() })
        style.getJSONObject("sources").getJSONObject(VECTOR_SOURCE).put("url", VECTOR_TILES_URL)
        val layers = style.getJSONArray("layers")
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            patchSymbolForCapUp(layer)
            if (!flavor.isIdentity) layer.optJSONObject("paint")?.let { layer.put("paint", recolor(it, flavor)) }
        }
        style.toString()
    }.getOrNull()

    /** Palette (`ColorFlavorPatcher` iOS) : chaque couleur des propriétés `paint`, expressions comprises. */
    private fun recolor(value: Any, flavor: MapColorFlavor): Any = when (value) {
        is String -> ColorFlavorPatcher.transformedColorString(value, flavor) ?: value
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(recolor(value.get(i), flavor)) }
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { key -> out.put(key, recolor(value.get(key), flavor)) } }
        else -> value
    }

    /**
     * Cap-en-haut (`patchedSymbolLayerForCapUp` iOS) : les noms de lieux restent droits à l'écran,
     * les noms de route suivent la route — rendu explicite pour chaque calque de symboles.
     */
    private fun patchSymbolForCapUp(layer: JSONObject) {
        if (layer.optString("type") != "symbol") return
        val layout = layer.optJSONObject("layout") ?: return
        val placement = layout.opt("symbol-placement") as? String
        val alignment = if (placement == "line" || placement == "line-center") "map" else "viewport"
        if (layout.has("text-field")) layout.put("text-rotation-alignment", alignment)
        if (layout.has("icon-image")) layout.put("icon-rotation-alignment", alignment)
    }

    fun raster(tiles: List<String> = listOf(OSM_TILES), attribution: String = OSM_ATTRIBUTION, maxZoom: Int = 19): String = JSONObject()
        .put("version", 8)
        .put(
            "sources",
            JSONObject().put(
                "osm-raster-source",
                JSONObject().put("type", "raster").put("tiles", JSONArray(tiles)).put("tileSize", 256)
                    .put("minzoom", 0).put("maxzoom", maxZoom).put("attribution", attribution),
            ),
        )
        .put("layers", JSONArray().put(JSONObject().put("id", "osm-raster-layer").put("type", "raster").put("source", "osm-raster-source")))
        .toString()
}
