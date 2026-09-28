import Foundation
import CoreLocation

/// Statut "Hors trace" du Road Book (it30) — MÊME règle que le Ride (`OffTrackDetector` :
/// hystérésis 30 m / 25 m, aucune constante dupliquée), même terminologie. Calcul pur à partir
/// de la position et de la trace dans son sens de parcours.
struct RoadbookOffTrackState: Equatable {
    private(set) var isOffTrack = false
    /// Depuis quand, pour afficher la distance de reprise après le même délai que la puce Ride.
    private(set) var sinceDate: Date?
    private(set) var distanceToTrackMeters: Double?
    /// Distance à vol d'oiseau du point de retour : le point de la trace le plus proche DEVANT la
    /// dernière position sur la trace (it33, même règle que le Ride, `RejoinPlanner.nearestAhead`).
    private(set) var rejoinDistanceMeters: Double?
    /// Dernière position connue SUR la trace (distance cumulée) : le point de retour est au-delà.
    private(set) var lastOnTrackCumulativeMeters: Double?

    mutating func update(location: CLLocation, points: [GPXPoint], cumulativeDistances: [Double]) {
        guard let projection = TrackProjector.project(location.coordinate, onto: points, cumulativeDistances: cumulativeDistances) else { return }
        let offTrack = OffTrackDetector.isOffTrack(wasOffTrack: isOffTrack, distanceToTrackMeters: projection.distanceToTrackMeters)
        if offTrack, !isOffTrack { sinceDate = location.timestamp }
        isOffTrack = offTrack
        distanceToTrackMeters = projection.distanceToTrackMeters
        guard offTrack else {
            sinceDate = nil
            rejoinDistanceMeters = nil
            lastOnTrackCumulativeMeters = projection.cumulativeDistanceMeters
            return
        }
        rejoinDistanceMeters = SharedRoadbook.rejoinTarget(from: location.coordinate, points: points, cumulativeDistances: cumulativeDistances, fromCumulativeMeters: lastOnTrackCumulativeMeters ?? 0)
            .map { RoadbookAnalyzer.distanceMeters(location.coordinate, SharedRoadbook.coordinate($0.coordinate)) }
    }

    mutating func reset() {
        self = RoadbookOffTrackState()
    }

    /// Distance de reprise affichée seulement après le même délai que la puce du Ride
    /// (`RideConstants.offTrackChipDistanceDelaySeconds`) : discret tant que ça peut se résorber.
    func showsRejoinDistance(now: Date) -> Bool {
        guard isOffTrack, let sinceDate else { return false }
        return now.timeIntervalSince(sinceDate) >= RideConstants.offTrackChipDistanceDelaySeconds
    }
}
