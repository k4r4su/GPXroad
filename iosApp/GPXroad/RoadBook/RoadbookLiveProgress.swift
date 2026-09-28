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

    private static func next(positions: [Double], currentCumulativeDistanceMeters: Double) -> (index: Int, distanceRemainingMeters: Double)? {
        guard let progress = GPXroadShared.RoadbookLiveProgress.shared.next(
            positions: positions.map { KotlinDouble(double: $0) },
            currentCumulativeDistanceMeters: currentCumulativeDistanceMeters
        ) else { return nil }
        return (Int(progress.index), progress.distanceRemainingMeters)
    }
}
