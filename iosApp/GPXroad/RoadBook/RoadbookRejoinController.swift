import Foundation
import CoreLocation
import GPXroadShared

/// Ce que l'écran Road Book affiche hors trace (it33) : le chemin pour rejoindre la trace, ses
/// virages, et où l'on en est dessus.
struct RoadbookRejoinDisplay: Equatable {
    enum Status: Equatable {
        /// Hors trace depuis trop peu de temps : pas encore de chemin demandé.
        case waiting
        case computing
        case routed
        /// Pas de réseau ou routage en échec : icône hors trace, jamais une liste vide ou fausse.
        case unavailable
    }

    var status: Status
    /// Virages du chemin de reprise (mêmes paliers et pictogrammes que ceux de la trace).
    var maneuvers: [RoadbookManeuver] = []
    /// Rang du prochain virage du chemin dans `maneuvers` (`nil` : tout droit jusqu'à la trace).
    var nextManeuverIndex: Int?
    var distanceToNextManeuverMeters: Double?
    /// Distance restante jusqu'à la trace, le long du chemin.
    var remainingToTrackMeters: Double?
    /// Distance le long du chemin déjà parcourue (pour situer les virages suivants).
    var routeCumulativeDistanceMeters: Double = 0
    /// Position du point de retour le long de la TRACE : la suite normale du Road Book reprend là.
    var targetCumulativeDistanceMeters: Double?
}

/// Chemin de reprise du Road Book (it33, demande terrain : « le Road Book se met à jour avec les
/// nouveaux virages pour rejoindre la trace, tout en gardant une icône qui indique qu'on est hors
/// trace »). Le Road Book a SA reprise : quand il est à l'écran, le GPS du Ride est arrêté — même
/// règle et même service de routage que le Ride, jamais deux calculs en parallèle.
///
/// Règle (logique dans le module partagé, `RejoinPlanner`) :
/// - point de retour = le point de la trace le plus proche DEVANT soi (jamais un point déjà
///   parcouru), rejoint PAR LES ROUTES (`DetourRoutingService`, Valhalla ou OSRM), pas à vol
///   d'oiseau ;
/// - point dépassé (derrière soi, en roulant, 10 s d'affilée) : nouveau point, au-delà de l'ancien ;
/// - réévaluation périodique (même intervalle que le Ride) si le point le plus proche a bougé ;
/// - retour sur la trace (règle hors-trace 30/25 m) : tout est effacé, le Road Book reprend.
@MainActor
final class RoadbookRejoinController: ObservableObject {
    @Published private(set) var display: RoadbookRejoinDisplay?

    private var lastOnTrackCumulative: Double?
    /// Au-delà d'une cible dépassée, jamais revenir en arrière.
    private var floorCumulative: Double?
    private var offTrackSince: Date?
    private var target: GPXroadShared.RejoinTarget?
    private var plan: GPXroadShared.RejoinPlan?
    private var lastEvaluation: Date?
    private var routingTask: Task<Void, Never>?
    private let passedDetector = GPXroadShared.RejoinPassedDetector()

    /// Appelé à chaque position en mode Assisté GPS.
    func update(
        location: CLLocation,
        track: GPXTrack,
        cumulativeDistances: [Double],
        isOffTrack: Bool,
        onTrackCumulative: Double?,
        settings: GPXroadShared.RoadbookSettings,
        valhalla: ValhallaConfiguration?
    ) {
        guard isOffTrack else {
            if let onTrackCumulative { lastOnTrackCumulative = onTrackCumulative }
            clearRejoin()
            return
        }
        let now = location.timestamp
        let since = offTrackSince ?? now
        offTrackSince = since
        guard now.timeIntervalSince(since) >= RideConstants.recomputeDivergenceDurationSeconds else {
            display = RoadbookRejoinDisplay(status: .waiting)
            return
        }

        if let target {
            if SharedRoadbook.updatePassed(passedDetector, location: location, target: SharedRoadbook.coordinate(target.coordinate)) {
                floorCumulative = target.cumulativeDistanceMeters + 1
                retarget(from: location, track: track, cumulativeDistances: cumulativeDistances, settings: settings, valhalla: valhalla, force: true)
            } else if let lastEvaluation, now.timeIntervalSince(lastEvaluation) >= RideConstants.autoRecomputeReevaluationIntervalSeconds {
                retarget(from: location, track: track, cumulativeDistances: cumulativeDistances, settings: settings, valhalla: valhalla, force: false)
            }
        } else {
            retarget(from: location, track: track, cumulativeDistances: cumulativeDistances, settings: settings, valhalla: valhalla, force: true)
        }
        refreshProgress(position: location.coordinate)
    }

    func reset() {
        lastOnTrackCumulative = nil
        clearRejoin()
    }

    private func clearRejoin() {
        routingTask?.cancel()
        routingTask = nil
        offTrackSince = nil
        floorCumulative = nil
        target = nil
        plan = nil
        lastEvaluation = nil
        passedDetector.reset()
        display = nil
    }

    private func retarget(
        from location: CLLocation,
        track: GPXTrack,
        cumulativeDistances: [Double],
        settings: GPXroadShared.RoadbookSettings,
        valhalla: ValhallaConfiguration?,
        force: Bool
    ) {
        lastEvaluation = location.timestamp
        let from = max(floorCumulative ?? 0, lastOnTrackCumulative ?? 0)
        guard let newTarget = SharedRoadbook.rejoinTarget(from: location.coordinate, points: track.points, cumulativeDistances: cumulativeDistances, fromCumulativeMeters: from) else {
            display = RoadbookRejoinDisplay(status: .unavailable)
            return
        }
        if !force, let target, display?.status == .routed,
           RoadbookAnalyzer.distanceMeters(SharedRoadbook.coordinate(target.coordinate), SharedRoadbook.coordinate(newTarget.coordinate)) <= RideConstants.autoRecomputeRetargetMinDistanceMeters {
            return
        }
        target = newTarget
        plan = nil
        passedDetector.reset()
        display = RoadbookRejoinDisplay(status: .computing, targetCumulativeDistanceMeters: newTarget.cumulativeDistanceMeters)

        routingTask?.cancel()
        let origin = location.coordinate
        let destination = SharedRoadbook.coordinate(newTarget.coordinate)
        routingTask = Task { [weak self] in
            let route = try? await DetourRoutingService.requestRoute(from: origin, candidates: [destination], profile: .route, valhalla: valhalla)
            guard !Task.isCancelled, let self, self.target == newTarget else { return }
            guard let route, route.coordinates.count > 1 else {
                self.display = RoadbookRejoinDisplay(status: .unavailable, targetCumulativeDistanceMeters: newTarget.cumulativeDistanceMeters)
                return
            }
            self.plan = SharedRoadbook.rejoinPlan(target: newTarget, route: route.coordinates, settings: settings)
            self.refreshProgress(position: origin)
        }
    }

    private func refreshProgress(position: CLLocationCoordinate2D) {
        guard let plan else { return }
        let progress = SharedRoadbook.rejoinProgress(plan, position: position)
        display = RoadbookRejoinDisplay(
            status: .routed,
            maneuvers: plan.maneuvers.map(SharedRoadbook.maneuver),
            nextManeuverIndex: progress.nextManeuverIndex.map { Int($0.int32Value) },
            distanceToNextManeuverMeters: progress.distanceToNextManeuverMeters?.doubleValue,
            remainingToTrackMeters: progress.remainingToTrackMeters,
            routeCumulativeDistanceMeters: progress.routeCumulativeDistanceMeters,
            targetCumulativeDistanceMeters: plan.target.cumulativeDistanceMeters
        )
    }
}
