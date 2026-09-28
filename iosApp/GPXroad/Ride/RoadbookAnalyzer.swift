import Foundation
import CoreLocation
import GPXroadShared

/// Événements du Road Book (virages, ronds-points…) d'une trace — SOURCE UNIQUE pour les épingles
/// carte, la bannière latérale du Ride et le Road Book (invariant it14 : une seule liste). Ne
/// modifie jamais la trace.
///
/// Depuis it33, TOUTE la logique vit dans le module partagé Kotlin (`shared/.../roadbook/
/// RoadbookAnalyzer.kt`, commune à iOS et Android) : ce type n'est plus qu'une façade Swift aux
/// mêmes signatures qu'avant, pour que les appelants et les tests ne changent pas. L'historique des
/// règles (cordes de cap, grappes, vrai demi-tour, fusion Valhalla au vrai carrefour) est documenté
/// dans le fichier Kotlin et dans RoadBook/CLAUDE.md.
enum RoadbookAnalyzer {

    static func buildRoadbookEvents(
        for track: GPXTrack,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        lightThresholdDegrees: Double,
        markedThresholdDegrees: Double,
        hardThresholdDegrees: Double,
        veryHardThresholdDegrees: Double,
        mergeMinDistanceMeters: Double,
        mapMatchedManeuvers: [MapMatchedManeuver] = []
    ) -> [Checkpoint] {
        let settings = SharedRoadbook.settings(
            windowBeforeMeters: windowBeforeMeters,
            windowAfterMeters: windowAfterMeters,
            thresholds: TierThresholds(light: lightThresholdDegrees, marked: markedThresholdDegrees, hard: hardThresholdDegrees, veryHard: veryHardThresholdDegrees),
            mergeMinDistanceMeters: mergeMinDistanceMeters
        )
        return GPXroadShared.RoadbookAnalyzer.shared
            .buildRoadbookEvents(points: SharedRoadbook.latLons(track.points), settings: settings, mapMatchedManeuvers: mapMatchedManeuvers.map(SharedRoadbook.mapMatched))
            .map(SharedRoadbook.checkpoint)
    }

    /// Paliers d'angle (seuils de Réglages > Roadbook), transmis tels quels au module partagé.
    struct TierThresholds {
        let light: Double
        let marked: Double
        let hard: Double
        let veryHard: Double
    }

    /// Distance géodésique de l'app pour tout ce qui se mesure le long d'une trace : Vincenty, la
    /// MÊME fonction que le module partagé (it33 : une seule formule sur iOS et Android — plus
    /// `CLLocation.distance`, non documentée et non déterministe, voir shared/LatLon.kt).
    static func distanceMeters(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        LatLonKt.geodesicDistanceMeters(latitude1: a.latitude, longitude1: a.longitude, latitude2: b.latitude, longitude2: b.longitude)
    }

    /// Cap initial (degrés) de `from` vers `to`.
    static func bearing(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        GPXroadShared.RoadbookAnalyzer.shared.bearing(from: SharedRoadbook.latLon(from), to: SharedRoadbook.latLon(to))
    }

    /// Différence signée entre deux caps, normalisée dans (-180, 180]. Positif = virage à droite.
    static func signedAngleDifference(from: Double, to: Double) -> Double {
        GPXroadShared.RoadbookAnalyzer.shared.signedAngleDifference(from: from, to: to)
    }
}
