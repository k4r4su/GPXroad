package com.olivier.gpxroad.android.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URLEncoder

/** Instance Overpass qui a répondu (Réglages : « dernier serveur ayant répondu »). */
enum class OverpassKind { LAN, OWN, PUBLIC_FALLBACK }

/**
 * Requêtes Overpass (portage de `OverpassConfiguration` iOS). Ordre d'essai quand le serveur du
 * propriétaire est activé :
 * 1. réseau de la maison (HTTP, sans authentification) — en Wi-Fi seulement, écarté
 *    [LAN_RETRY_AFTER_FAILURE_MILLIS] après un échec ;
 * 2. adresse publique du propriétaire (HTTPS obligatoire, Basic Auth) ;
 * 3. instance publique OSM, en secours — JAMAIS avec les identifiants.
 * Désactivé : l'instance publique seule.
 */
class OverpassClient(private val context: Context, private val servers: ServerSettings) {
    data class Attempt(val kind: OverpassKind, val url: String, val authorization: String?, val timeoutMillis: Int)

    var lastSuccess by mutableStateOf<Pair<OverpassKind, Long>?>(null)
        private set

    @Volatile private var lanUnavailableUntil = 0L

    fun attempts(timeoutMillis: Int, now: Long = System.currentTimeMillis()): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        if (servers.overpassEnabled) {
            val lan = servers.overpassLanEndpoint
            if (lan.isNotBlank() && now >= lanUnavailableUntil && Http.isOnLocalNetwork(context)) {
                attempts += Attempt(OverpassKind.LAN, lan, null, minOf(timeoutMillis, LAN_TIMEOUT_MILLIS))
            }
            val own = servers.overpassEndpoint
            if (own.startsWith("https://")) {
                attempts += Attempt(OverpassKind.OWN, own, Http.basicAuthorization(servers.overpassUsername, servers.overpassPassword), timeoutMillis)
            }
        }
        attempts += Attempt(OverpassKind.PUBLIC_FALLBACK, PUBLIC_FALLBACK_ENDPOINT, null, timeoutMillis)
        return attempts
    }

    /**
     * Une passe sur toutes les instances, dans l'ordre : corps de la première réponse 200 que
     * [accept] valide, sinon `null`. Un échec du réseau local l'écarte quelques minutes.
     */
    fun <T> fetchOnce(
        query: String,
        timeoutMillis: Int,
        onBytes: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        accept: (ByteArray) -> T?,
    ): T? {
        val body = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8)
        for (attempt in attempts(timeoutMillis)) {
            if (isCancelled()) return null
            val result = runCatching {
                Http.request(attempt.url, "POST", body, "application/x-www-form-urlencoded", attempt.authorization, attempt.timeoutMillis, onBytes, isCancelled)
            }.getOrNull()?.let(accept)
            if (result != null) {
                lastSuccess = attempt.kind to System.currentTimeMillis()
                return result
            }
            if (attempt.kind == OverpassKind.LAN) lanUnavailableUntil = System.currentTimeMillis() + LAN_RETRY_AFTER_FAILURE_MILLIS
        }
        return null
    }

    /** Test de connexion (Réglages) : `/api/status` de l'instance saisie, identifiants tels que saisis. */
    fun status(endpoint: String, username: String, password: String): String {
        val url = endpoint.trim().replace("/interpreter", "/status")
        val text = Http.request(url, authorization = Http.basicAuthorization(username, password), timeoutMillis = 15_000).toString(Charsets.UTF_8)
        lanUnavailableUntil = 0
        return text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "OK"
    }

    companion object {
        const val PUBLIC_FALLBACK_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val LAN_TIMEOUT_MILLIS = 4_000
        const val LAN_RETRY_AFTER_FAILURE_MILLIS = 300_000L
    }
}
