import Foundation
import GPXroadShared

/// Élément à venir en mode Assisté GPS (spec "roadbook-mode", it23 ; maintien après le virage, fix
/// "roadbook-live-progress-hold" ; priorité par ordre d'arrivée, it30) — façade Swift du module
/// partagé (`shared/.../roadbook/RoadbookLiveProgress.kt`, it33, règles documentées là-bas). Ne
/// touche jamais la liste affichée : calcule un rang et une distance restante EN PLUS.
enum RoadbookLiveProgress {
    /// `nil` si plus aucune manœuvre à venir.
    static func nextManeuver(
        maneuvers: [RoadbookManeuver],
        currentCumulativeDistanceMeters: Double
    ) -> (index: Int, distanceRemainingMeters: Double)? {
        next(positions: maneuvers.map(\.cumulativeDistanceMeters), currentCumulativeDistanceMeters: currentCumulativeDistanceMeters)
    }

    /// Prochain élément du Road Book, TOUS TYPES CONFONDUS (virage ou repère) : seul l'ordre le long
    /// de la trace compte.
    static func nextEntry(
        entries: [RoadbookEntry],
        currentCumulativeDistanceMeters: Double
    ) -> (index: Int, distanceRemainingMeters: Double)? {
        next(positions: entries.map(\.cumulativeDistanceMeters), currentCumulativeDistanceMeters: currentCumulativeDistanceMeters)
    }

    /// Élément affiché EN GRAND (it34, priorité au virage sur les repères de décor — règle partagée
    /// `RoadbookFocusRule`) : le prochain élément, SAUF un repère de décor suivi de près par un virage
    /// (≤ 300 m ou 15 s) — alors le virage, avec ce repère en ligne secondaire.
    struct Focus: Equatable {
        let index: Int
        let distanceRemainingMeters: Double
        let leadingLandmarkIndex: Int?
        let leadingLandmarkDistanceMeters: Double?
    }

    static func focus(entries: [RoadbookEntry], currentCumulativeDistanceMeters: Double, speedMetersPerSecond: Double?) -> Focus? {
        guard let progress = nextEntry(entries: entries, currentCumulativeDistanceMeters: currentCumulativeDistanceMeters) else { return nil }
        // Seuls les éléments qui suivent comptent (quelques-uns) : conversion limitée, à chaque position GPS.
        let slice = entries[progress.index..<min(progress.index + lookAhead, entries.count)]
        let shared: [GPXroadShared.RoadbookEntry] = slice.map { entry in
            switch entry {
            case .maneuver(let maneuver, let index):
                return GPXroadShared.RoadbookEntry.Maneuver(maneuver: SharedRoadbook.sharedManeuver(maneuver), index: Int32(index))
            case .landmark(let landmark):
                return GPXroadShared.RoadbookEntry.Landmark(landmark: SharedRoadbook.sharedLandmarkCheckpoint(landmark))
            }
        }
        guard let focus = GPXroadShared.RoadbookFocusRule.shared.focus(
            entries: shared,
            progress: GPXroadShared.LiveProgress(index: 0, distanceRemainingMeters: progress.distanceRemainingMeters),
            currentCumulativeDistanceMeters: currentCumulativeDistanceMeters,
            speedMetersPerSecond: speedMetersPerSecond.map { KotlinDouble(double: $0) }
        ) else { return nil }
        return Focus(
            index: progress.index + Int(focus.heroIndex),
            distanceRemainingMeters: focus.distanceRemainingMeters,
            leadingLandmarkIndex: focus.leadingLandmarkIndex.map { progress.index + Int($0.int32Value) },
            leadingLandmarkDistanceMeters: focus.leadingLandmarkDistanceMeters?.doubleValue
        )
    }

    /// Éléments examinés après le prochain (bien au-delà de ce que 300 m / 15 s peuvent contenir).
    private static let lookAhead = 12

    private static func next(positions: [Double], currentCumulativeDistanceMeters: Double) -> (index: Int, distanceRemainingMeters: Double)? {
        guard let progress = GPXroadShared.RoadbookLiveProgress.shared.next(
            positions: positions.map { KotlinDouble(double: $0) },
            currentCumulativeDistanceMeters: currentCumulativeDistanceMeters
        ) else { return nil }
        return (Int(progress.index), progress.distanceRemainingMeters)
    }
}
