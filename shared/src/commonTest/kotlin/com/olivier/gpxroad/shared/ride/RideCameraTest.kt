package com.olivier.gpxroad.shared.ride

import kotlin.test.Test
import kotlin.test.assertEquals

class RideCameraTest {
    @Test
    fun bucketsNeedTheHysteresisMarginToChange() {
        val zoom = AutoZoom(ZoomPreset.NORMAL)
        assertEquals(280.0 * 0.7, zoom.update(10.0, 0.0), 1e-9) // < 40 km/h : contexte piste
        zoom.update(22.0, 1.0)
        assertEquals(0, zoom.bucketIndex) // 22 km/h : pas encore 20 + 4
        zoom.update(25.0, 2.0)
        assertEquals(1, zoom.bucketIndex)
        zoom.update(18.0, 3.0)
        assertEquals(1, zoom.bucketIndex) // 18 km/h : pas encore sous 20 - 4
        zoom.update(15.0, 4.0)
        assertEquals(0, zoom.bucketIndex)
    }

    @Test
    fun fastRoadNeedsSixtySecondsAndClampsToTheBounds() {
        val zoom = AutoZoom(ZoomPreset.NORMAL)
        assertEquals(1100.0, zoom.update(100.0, 0.0), 1e-9)
        assertEquals(RideContext.NORMAL, zoom.context)
        assertEquals(1540.0, zoom.update(100.0, 60.0), 1e-9)
        assertEquals(RideContext.FAST_ROAD, zoom.context)
        assertEquals(1500.0, zoom.update(100.0, 61.0, maxMeters = 1500.0), 1e-9)
    }

    @Test
    fun smoothedSpeedAveragesTheLastTenSeconds() {
        val smoother = SpeedSmoother()
        smoother.add(0.0, 0.0)
        assertEquals(10.0, smoother.add(20.0, 5.0), 1e-9)
        assertEquals(30.0, smoother.add(40.0, 12.0), 1e-9) // le fix de t=0 est sorti de la fenêtre
    }

    @Test
    fun zoomLevelMatchesMapLibreIosFraming() {
        // Même formule que MLNZoomLevelForAltitude : 1 km de distance caméra sur un écran de
        // 800 points à l'équateur ≈ zoom 16,8 ; doubler la distance = un niveau de moins.
        val z1 = RideCameraMath.zoomLevel(1000.0, 0.0, 800.0)
        val z2 = RideCameraMath.zoomLevel(2000.0, 0.0, 800.0)
        assertEquals(1.0, z1 - z2, 1e-9)
        assertEquals(16.83, z1, 0.01)
        assertEquals(1666.0, RideCameraConstants.DEFAULT_RIDE_ZOOM_METERS, 0.1)
    }
}
