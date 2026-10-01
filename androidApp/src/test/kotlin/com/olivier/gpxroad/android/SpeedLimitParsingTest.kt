package com.olivier.gpxroad.android

import com.olivier.gpxroad.android.nav.NavSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Limitation de vitesse : lecture de la réponse Overpass `out tags` (format réel de l'API). */
class SpeedLimitParsingTest {
    private fun response(tags: String) =
        """{"version":0.6,"generator":"Overpass API","elements":[$tags]}""".toByteArray()

    @Test
    fun readsTheFirstNumericMaxspeed() {
        assertEquals(50, NavSession.parseMaxSpeed(response("""{"type":"way","id":1,"tags":{"highway":"secondary","maxspeed":"50","name":"Rue de Bourgfelden"}}""")))
        assertNull(NavSession.parseMaxSpeed(response("""{"type":"way","id":2,"tags":{"highway":"residential","maxspeed":"FR:urban"}}""")))
        assertNull(NavSession.parseMaxSpeed(response("")))
        assertFailsWith<Exception> { NavSession.parseMaxSpeed("<html>busy</html>".toByteArray()) }
    }
}
