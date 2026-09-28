package com.olivier.gpxroad.shared

/** Coordonnée WGS84 — équivalent commun de `CLLocationCoordinate2D`, converti à la frontière Swift. */
data class LatLon(val latitude: Double, val longitude: Double)

/**
 * Distance géodésique (m) entre deux coordonnées : Vincenty sur l'ellipsoïde WGS84, la MÊME sur
 * iOS et Android (décision it33 : une seule formule, une seule logique à maintenir).
 *
 * Remplace `CLLocation.distance(from:)` partout où l'app mesure le long d'une trace — mesuré à
 * it32 : la formule d'Apple n'est pas documentée, ne correspond à aucune formule standard (+11 m
 * sur 18 km par rapport à Vincenty) et varie légèrement d'un appel à l'autre dans le simulateur.
 */
fun geodesicDistanceMeters(a: LatLon, b: LatLon): Double =
    vincentyDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

/** Variante sans allocation, pour les appels depuis Swift (une par segment de trace). */
fun geodesicDistanceMeters(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double =
    vincentyDistanceMeters(latitude1, longitude1, latitude2, longitude2)
