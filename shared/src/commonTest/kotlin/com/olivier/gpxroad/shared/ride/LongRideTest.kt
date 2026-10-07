package com.olivier.gpxroad.shared.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LongRideTest {
    @Test
    fun autoModeNeedsLowBatteryAndNoCharger() {
        assertTrue(LongRide.isActive(LongRideMode.AUTO, 20, false))
        assertTrue(LongRide.isActive(LongRideMode.AUTO, 7, false))
        assertFalse(LongRide.isActive(LongRideMode.AUTO, 21, false))
        assertFalse(LongRide.isActive(LongRideMode.AUTO, 7, true), "en charge : pas d'économie")
        assertFalse(LongRide.isActive(LongRideMode.AUTO, null, false), "niveau inconnu : jamais")
    }

    @Test
    fun offAndAlwaysIgnoreTheBattery() {
        assertFalse(LongRide.isActive(LongRideMode.OFF, 3, false))
        assertTrue(LongRide.isActive(LongRideMode.ALWAYS, 100, true))
    }

    @Test
    fun batteryAlertsOncePerLevelOnlyWhileRecordingOffTheCharger() {
        assertNull(LongRide.nextBatteryAlert(50, false, true, null))
        assertEquals(15, LongRide.nextBatteryAlert(15, false, true, null))
        assertNull(LongRide.nextBatteryAlert(14, false, true, 15), "déjà annoncé à 15 %")
        assertEquals(5, LongRide.nextBatteryAlert(5, false, true, 15))
        assertNull(LongRide.nextBatteryAlert(2, false, true, 5))
        assertNull(LongRide.nextBatteryAlert(10, false, false, null), "pas d'enregistrement : rien à perdre")
        assertNull(LongRide.nextBatteryAlert(10, true, true, null), "en charge")
        assertEquals(5, LongRide.nextBatteryAlert(4, false, true, null), "tombée d'un coup : une seule alerte")
    }
}
