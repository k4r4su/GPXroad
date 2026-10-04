package com.olivier.gpxroad.android

import com.olivier.gpxroad.android.net.BundledServer
import com.olivier.gpxroad.android.net.BundledServers
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Serveurs intégrés : lecture du JSON en base64 (valeurs FICTIVES). */
class BundledServersTest {
    private fun encode(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())

    @Test
    fun readsBothServersAndRejectsInsecureOrBrokenInput() {
        val ok = BundledServers.parse(encode("""{"valhalla":{"url":"https://v.example.org","user":"u","pass":"p\\w"},"overpass":{"url":"https://o.example.org/api/interpreter","user":"a","pass":"b"}}"""))
        assertEquals(BundledServer("https://v.example.org", "u", "p\\w"), ok.valhalla)
        assertEquals("https://o.example.org/api/interpreter", ok.overpass?.url)
        // HTTP en clair refusé, chaîne vide ou illisible : aucun serveur, jamais d'exception.
        assertNull(BundledServers.parse(encode("""{"valhalla":{"url":"http://v.example.org","user":"u","pass":"p"}}""")).valhalla)
        assertNull(BundledServers.parse("").valhalla)
        assertNull(BundledServers.parse("pas-du-base64!!").overpass)
    }
}
