package com.olivier.gpxroad.shared

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Distance de Vincenty (formule inverse) sur l'ellipsoïde WGS84 */
internal fun vincentyDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val semiMajor = 6_378_137.0
    val flattening = 1 / 298.257223563
    val semiMinor = (1 - flattening) * semiMajor
    val l = (lon2 - lon1) * PI / 180
    val u1 = atan((1 - flattening) * tan(lat1 * PI / 180))
    val u2 = atan((1 - flattening) * tan(lat2 * PI / 180))
    val sinU1 = sin(u1)
    val cosU1 = cos(u1)
    val sinU2 = sin(u2)
    val cosU2 = cos(u2)

    var lambda = l
    var sinSigma: Double
    var cosSigma: Double
    var sigma: Double
    var cosSqAlpha: Double
    var cos2SigmaM: Double
    var iterations = 0
    while (true) {
        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
        val x = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        sinSigma = sqrt(cosU2 * sinLambda * cosU2 * sinLambda + x * x)
        if (sinSigma == 0.0) return 0.0
        cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
        sigma = atan2(sinSigma, cosSigma)
        val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
        cosSqAlpha = 1 - sinAlpha * sinAlpha
        cos2SigmaM = if (cosSqAlpha != 0.0) cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha else 0.0
        val c = flattening / 16 * cosSqAlpha * (4 + flattening * (4 - 3 * cosSqAlpha))
        val previous = lambda
        lambda = l + (1 - c) * flattening * sinAlpha *
            (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
        iterations += 1
        if (abs(lambda - previous) < 1e-12 || iterations >= 200) break
    }
    val uSq = cosSqAlpha * (semiMajor * semiMajor - semiMinor * semiMinor) / (semiMinor * semiMinor)
    val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
    val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
    val deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
        bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
    return semiMinor * bigA * (sigma - deltaSigma)
}
