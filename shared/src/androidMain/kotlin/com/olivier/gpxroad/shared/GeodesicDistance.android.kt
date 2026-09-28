package com.olivier.gpxroad.shared

actual fun geodesicDistanceMeters(a: LatLon, b: LatLon): Double = vincentyDistanceMeters(a, b)
