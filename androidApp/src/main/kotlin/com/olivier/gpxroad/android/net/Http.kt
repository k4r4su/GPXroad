package com.olivier.gpxroad.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Réponse HTTP autre que 200, ou réseau en échec (message montré dans les Réglages). */
class HttpException(message: String) : IOException(message)

/**
 * Requêtes HTTP de l'app (bloquantes : à appeler hors du fil principal). `HttpURLConnection`, sans
 * dépendance. Même User-Agent que l'iPhone (bonne conduite envers les services OSM publics).
 */
object Http {
    const val USER_AGENT = "GPXroad/0.3 (contact: oliv.zim@gmail.com)"

    fun basicAuthorization(username: String, password: String): String? =
        if (username.isEmpty() && password.isEmpty()) null
        else "Basic " + Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * Corps d'une réponse 200. [onBytes] : octets reçus au fil de l'eau (progression des repères) ;
     * [isCancelled] interrompt la lecture (trace changée entre-temps).
     */
    fun request(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String? = null,
        authorization: String? = null,
        timeoutMillis: Int,
        onBytes: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ByteArray {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw HttpException("URL invalide")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("User-Agent", USER_AGENT)
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            authorization?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code != 200) throw HttpException("HTTP $code")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16_384)
                while (true) {
                    if (isCancelled()) throw HttpException("annulé")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    onBytes(read)
                }
            }
            return output.toByteArray()
        } catch (error: HttpException) {
            throw error
        } catch (error: IOException) {
            throw HttpException(error.javaClass.simpleName + (error.message?.let { " : $it" } ?: ""))
        } finally {
            connection.disconnect()
        }
    }

    /** Réseau disponible (repères : état « hors ligne » plutôt qu'un échec). */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Wi-Fi (ou Ethernet) : seule condition pour essayer le serveur de la maison. */
    fun isOnLocalNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
