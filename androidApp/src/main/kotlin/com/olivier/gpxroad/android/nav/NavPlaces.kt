package com.olivier.gpxroad.android.nav

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.android.net.Place
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.nav.SearchHistory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Domicile, Travail et recherches récentes d'« Aller à » (`NavFavoritesStore` et
 * `NavSearchHistoryStore` iOS) : `files/nav-places.json`, jamais envoyé nulle part.
 */
class NavPlaces(context: Context) {
    private val file = File(context.filesDir, "nav-places.json")

    var home by mutableStateOf<Place?>(null)
        private set
    var work by mutableStateOf<Place?>(null)
        private set
    var history by mutableStateOf<List<Place>>(emptyList())
        private set

    init {
        runCatching {
            val o = JSONObject(file.readText())
            home = o.optJSONObject("home")?.let(::place)
            work = o.optJSONObject("work")?.let(::place)
            val h = o.optJSONArray("history") ?: JSONArray()
            history = (0 until h.length()).map { place(h.getJSONObject(it)) }
        }
    }

    fun updateHome(place: Place?) {
        home = place
        save()
    }

    fun updateWork(place: Place?) {
        work = place
        save()
    }

    fun record(place: Place) {
        history = SearchHistory.record(history, place) { it.label }
        save()
    }

    fun forget(place: Place) {
        history = history - place
        save()
    }

    private fun place(o: JSONObject) = Place(o.getString("label"), LatLon(o.getDouble("lat"), o.getDouble("lon")))

    private fun json(p: Place) = JSONObject().put("label", p.label).put("lat", p.coordinate.latitude).put("lon", p.coordinate.longitude)

    private fun save() {
        val o = JSONObject()
        home?.let { o.put("home", json(it)) }
        work?.let { o.put("work", json(it)) }
        o.put("history", JSONArray(history.map(::json)))
        file.writeText(o.toString())
    }
}
