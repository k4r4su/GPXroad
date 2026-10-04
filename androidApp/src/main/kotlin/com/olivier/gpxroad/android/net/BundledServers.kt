package com.olivier.gpxroad.android.net

import java.util.Base64
import com.olivier.gpxroad.android.BuildConfig
import org.json.JSONObject

/** Un serveur intégré : adresse et identifiants Basic Auth. */
data class BundledServer(val url: String, val username: String, val password: String)

/**
 * Serveurs Valhalla et Overpass intégrés à un APK de test PRIVÉ (`scripts/build-tester-apk.sh`).
 * Vide dans tous les autres builds. ⚠ Tout ce qui est dans un APK s'extrait : à ne donner qu'à des
 * testeurs de confiance, et à renouveler ensuite côté serveur. Les valeurs ne passent jamais par le
 * dépôt : elles sont lues dans l'environnement du build.
 *
 * Utilisés SEULEMENT quand l'utilisateur n'a pas configuré lui-même le serveur dans les Réglages
 * (son choix reste prioritaire).
 */
data class BundledServers(val valhalla: BundledServer?, val overpass: BundledServer?) {
    companion object {
        val current: BundledServers by lazy { parse(BuildConfig.BUNDLED_SERVERS) }

        /** JSON `{"valhalla":{"url","user","pass"},"overpass":{…}}` en base64 ; tout défaut → aucun serveur. */
        fun parse(base64: String): BundledServers = runCatching {
            if (base64.isBlank()) return@runCatching BundledServers(null, null)
            val json = JSONObject(String(Base64.getMimeDecoder().decode(base64), Charsets.UTF_8))
            fun server(key: String): BundledServer? = json.optJSONObject(key)?.let { o ->
                o.optString("url").takeIf { it.startsWith("https://") }?.let { BundledServer(it, o.optString("user"), o.optString("pass")) }
            }
            BundledServers(server("valhalla"), server("overpass"))
        }.getOrDefault(BundledServers(null, null))
    }
}
