package com.olivier.gpxroad.shared

/** Coordonnée WGS84 — équivalent commun de `CLLocationCoordinate2D`, converti à la frontière Swift. */
data class LatLon(val latitude: Double, val longitude: Double)

/**
 * Distance géodésique (m) entre deux coordonnées.
 *
 * iOS : `CLLocation.distance(from:)`, comme le Swift natif (`RoadbookAnalyzer.distanceMeters`) —
 * condition pour que le Road Book reste celui validé (it28). Mesuré à it32 : cette formule Apple
 * n'est pas documentée, ne correspond à aucune formule standard et varie légèrement selon le
 * moment de l'appel ; passer à Vincenty déplace de quelques dixièmes de mètre les distances
 * cumulées d'une longue trace, assez pour ajouter ou retirer un événement (2 sur 203 sur
 * vosges-tour). Android : Vincenty WGS84 (formule explicite, déterministe). Unifier les deux
 * plateformes = décision produit à prendre (voir MIGRATION_AUDIT.md, constat 1).
 */
expect fun geodesicDistanceMeters(a: LatLon, b: LatLon): Double
