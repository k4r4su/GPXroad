package com.olivier.gpxroad.android.net

import android.os.SystemClock
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.nav.NavConstants
import com.olivier.gpxroad.shared.nav.nominatimViewbox
import org.json.JSONArray
import java.net.URLEncoder

/** Résultat de recherche : libellé complet et position. */
data class Place(val label: String, val coordinate: LatLon)

/**
 * Recherche d'adresses et de lieux (`NominatimGeocodingService` iOS) : Nominatim public, une requête
 * par seconde au plus (règle d'usage OSM), 6 résultats, biais de proximité autour de la position
 * (jamais une restriction : une adresse lointaine bien écrite ressort quand même). Bloquant.
 */
class NominatimClient {
    private var lastRequestMillis = 0L

    @Synchronized
    fun search(query: String, near: LatLon?, language: String): List<Place> {
        val wait = lastRequestMillis + (NavConstants.NOMINATIM_MIN_INTERVAL_SECONDS * 1000).toLong() - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
        lastRequestMillis = SystemClock.elapsedRealtime()
        val url = buildString {
            append(NavConstants.NOMINATIM_BASE_URL).append("/search?format=json")
            append("&q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            append("&accept-language=").append(URLEncoder.encode(language, "UTF-8"))
            append("&limit=").append(NavConstants.NOMINATIM_RESULT_LIMIT)
            near?.let { append("&viewbox=").append(URLEncoder.encode(nominatimViewbox(it), "UTF-8")) }
        }
        val entries = JSONArray(Http.request(url, timeoutMillis = TIMEOUT_MILLIS).toString(Charsets.UTF_8))
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.getJSONObject(index)
            val lat = entry.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = entry.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            Place(entry.optString("display_name"), LatLon(lat, lon))
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 12_000
    }
}
