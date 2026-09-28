package com.olivier.gpxroad.shared

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocation

@OptIn(ExperimentalForeignApi::class)
actual fun geodesicDistanceMeters(a: LatLon, b: LatLon): Double =
    CLLocation(latitude = a.latitude, longitude = a.longitude)
        .distanceFromLocation(CLLocation(latitude = b.latitude, longitude = b.longitude))
