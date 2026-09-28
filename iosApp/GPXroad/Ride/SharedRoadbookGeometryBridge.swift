import Foundation
import CoreLocation
import GPXroadShared

/// Moteur de la partie géométrique du Road Book (it32, pilote Kotlin Multiplatform) — `.shared`
/// (module Kotlin `shared/`, par défaut) ou `.native` (implémentation Swift d'origine, gardée tant
/// que la parité n'est pas validée en conditions réelles). Choix de debug : Réglages > Avancé.
enum RoadbookGeometryEngine: String, CaseIterable, Identifiable {
    case shared, native

    static let defaultsKey = "debug.roadbookGeometryEngine"

    static var current: RoadbookGeometryEngine {
        UserDefaults.standard.string(forKey: defaultsKey).flatMap(RoadbookGeometryEngine.init(rawValue:)) ?? .shared
    }

    var id: String { rawValue }
}

/// Seule frontière Swift ↔ Kotlin du Road Book : convertit la trace en tableaux, appelle
/// `RoadbookGeometry` (Kotlin) et reconvertit ses événements en `Checkpoint`.
enum SharedRoadbookGeometryBridge {
    static func geometricEvents(
        points: [GPXPoint],
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        thresholds: RoadbookAnalyzer.TierThresholds,
        mergeMinDistanceMeters: Double
    ) -> [Checkpoint] {
        let latitudes = KotlinDoubleArray(size: Int32(points.count))
        let longitudes = KotlinDoubleArray(size: Int32(points.count))
        for (index, point) in points.enumerated() {
            latitudes.set(index: Int32(index), value: point.latitude)
            longitudes.set(index: Int32(index), value: point.longitude)
        }
        let events = RoadbookGeometry.shared.buildGeometricEvents(
            latitudes: latitudes,
            longitudes: longitudes,
            windowBeforeMeters: windowBeforeMeters,
            windowAfterMeters: windowAfterMeters,
            thresholds: TierThresholds(light: thresholds.light, marked: thresholds.marked, hard: thresholds.hard, veryHard: thresholds.veryHard),
            mergeMinDistanceMeters: mergeMinDistanceMeters
        )
        return events.map { event in
            Checkpoint(
                coordinate: CLLocationCoordinate2D(latitude: event.coordinate.latitude, longitude: event.coordinate.longitude),
                turnAngleDegrees: event.turnAngleDegrees,
                direction: direction(event.direction),
                tier: tier(event.tier),
                sequenceIndex: Int(event.sequenceIndex),
                sourcePointIndex: Int(event.sourcePointIndex),
                trackCumulativeDistanceMeters: event.trackCumulativeDistanceMeters
            )
        }
    }

    private static func tier(_ tier: GeometricTier) -> RoadbookTier {
        switch tier {
        case .light: return .light
        case .marked: return .marked
        case .hard: return .hard
        case .veryHard: return .veryHard
        case .uTurn: return .uTurn
        default: return .light
        }
    }

    private static func direction(_ direction: GeometricDirection) -> TurnDirection {
        switch direction {
        case .left: return .left
        case .right: return .right
        case .uTurn: return .uTurn
        default: return .straight
        }
    }
}
