package com.olivier.gpxroad.android.ride

import android.content.Context
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
    private const val OSM_ATTRIBUTION = "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"

    fun vector(context: Context): String? = runCatching {
        val style = JSONObject(context.assets.open(VECTOR_STYLE_ASSET).bufferedReader().use { it.readText() })
        style.getJSONObject("sources").getJSONObject(VECTOR_SOURCE).put("url", VECTOR_TILES_URL)
        val layers = style.getJSONArray("layers")
        for (index in 0 until layers.length()) patchSymbolForCapUp(layers.getJSONObject(index))
        style.toString()
    }.getOrNull()

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

    fun raster(): String = JSONObject()
        .put("version", 8)
        .put(
            "sources",
            JSONObject().put(
                "osm-raster-source",
                JSONObject().put("type", "raster").put("tiles", JSONArray().put(OSM_TILES)).put("tileSize", 256)
                    .put("minzoom", 0).put("maxzoom", 19).put("attribution", OSM_ATTRIBUTION),
            ),
        )
        .put("layers", JSONArray().put(JSONObject().put("id", "osm-raster-layer").put("type", "raster").put("source", "osm-raster-source")))
        .toString()
}
