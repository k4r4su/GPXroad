package com.olivier.gpxroad.shared.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrackOrderKeyTest {
    private val points = (0..5).map { GpxPoint(47.0 + it * 0.01, 7.0 + it * 0.02, null, null) }

    /** La Bibliothèque Android calcule la clé d'un sens sans charger tous les points : mêmes règles que `reordered`. */
    @Test
    fun keyOfTheReversedDirectionNeedsOnlyTheLastTwoPointsReversed() {
        val fullReversed = TrackOrder.traversalKey("ID", TrackOrder.reordered(points, reversed = true))
        val fromTheEnds = TrackOrder.traversalKey("ID", points.takeLast(2).reversed())
        assertEquals(fullReversed, fromTheEnds)
        assertEquals(TrackOrder.traversalKey("ID", points), TrackOrder.traversalKey("ID", points.take(2)))
        assertNotEquals(fullReversed, TrackOrder.traversalKey("ID", points), "les deux sens ont des clés différentes")
    }
}
