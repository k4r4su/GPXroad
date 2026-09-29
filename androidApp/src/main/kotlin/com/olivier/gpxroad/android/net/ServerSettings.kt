package com.olivier.gpxroad.android.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Serveur Valhalla prêt à l'emploi (activé, adresse renseignée). */
data class ValhallaConfiguration(val endpoint: String, val username: String, val password: String)

/**
 * Serveurs de l'app (Réglages > Serveurs), mêmes champs que l'iPhone : Valhalla (map matching,
 * chemin de retour) et Overpass (réseau de la maison, adresse publique). Désactivés et vides par
 * défaut ; adresses dans les préférences, identifiants chiffrés ([SecureStore]).
 */
class ServerSettings(context: Context) {
    private val preferences = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    var valhallaEnabled by mutableStateOf(preferences.getBoolean(VALHALLA_ENABLED, false))
        private set
    var valhallaEndpoint by mutableStateOf(preferences.getString(VALHALLA_ENDPOINT, "") ?: "")
        private set
    var valhallaUsername by mutableStateOf(secure.get(VALHALLA_USERNAME))
        private set
    var valhallaPassword by mutableStateOf(secure.get(VALHALLA_PASSWORD))
        private set

    var overpassEnabled by mutableStateOf(preferences.getBoolean(OVERPASS_ENABLED, false))
        private set
    var overpassEndpoint by mutableStateOf(preferences.getString(OVERPASS_ENDPOINT, "") ?: "")
        private set
    var overpassLanEndpoint by mutableStateOf(preferences.getString(OVERPASS_LAN_ENDPOINT, "") ?: "")
        private set
    var overpassUsername by mutableStateOf(secure.get(OVERPASS_USERNAME))
        private set
    var overpassPassword by mutableStateOf(secure.get(OVERPASS_PASSWORD))
        private set

    /** `null` tant que Valhalla est désactivé ou sans adresse : le Road Book reste géométrique. */
    val valhalla: ValhallaConfiguration?
        get() = if (valhallaEnabled && valhallaEndpoint.isNotBlank()) ValhallaConfiguration(valhallaEndpoint.trim(), valhallaUsername, valhallaPassword) else null

    fun updateValhalla(enabled: Boolean, endpoint: String, username: String, password: String) {
        valhallaEnabled = enabled
        valhallaEndpoint = endpoint.trim()
        valhallaUsername = username
        valhallaPassword = password
        preferences.edit().putBoolean(VALHALLA_ENABLED, enabled).putString(VALHALLA_ENDPOINT, valhallaEndpoint).apply()
        secure.set(VALHALLA_USERNAME, username)
        secure.set(VALHALLA_PASSWORD, password)
    }

    fun updateOverpass(enabled: Boolean, endpoint: String, lanEndpoint: String, username: String, password: String) {
        overpassEnabled = enabled
        overpassEndpoint = endpoint.trim()
        overpassLanEndpoint = lanEndpoint.trim()
        overpassUsername = username
        overpassPassword = password
        preferences.edit()
            .putBoolean(OVERPASS_ENABLED, enabled)
            .putString(OVERPASS_ENDPOINT, overpassEndpoint)
            .putString(OVERPASS_LAN_ENDPOINT, overpassLanEndpoint)
            .apply()
        secure.set(OVERPASS_USERNAME, username)
        secure.set(OVERPASS_PASSWORD, password)
    }

    private companion object {
        const val VALHALLA_ENABLED = "valhallaEnabled"
        const val VALHALLA_ENDPOINT = "valhallaEndpoint"
        const val VALHALLA_USERNAME = "valhalla.username"
        const val VALHALLA_PASSWORD = "valhalla.password"
        const val OVERPASS_ENABLED = "overpassEnabled"
        const val OVERPASS_ENDPOINT = "overpassEndpoint"
        const val OVERPASS_LAN_ENDPOINT = "overpassLanEndpoint"
        const val OVERPASS_USERNAME = "overpass.username"
        const val OVERPASS_PASSWORD = "overpass.password"
    }
}
