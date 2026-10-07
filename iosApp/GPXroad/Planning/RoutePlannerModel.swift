import Foundation
import CoreLocation
import GPXroadShared

/// État de l'écran « Créer un itinéraire » (06/10) : points posés, options, itinéraire calculé.
@MainActor
final class RoutePlannerModel: ObservableObject {
    enum Status: Equatable {
        case idle
        case computing
        case failed(String)
    }

    @Published private(set) var waypoints: [CLLocationCoordinate2D] = []
    @Published var options = PlanOptions(vehicle: .motorcycle, avoidHighways: true, avoidTolls: true, avoidFerries: true, allowTracks: false)
    @Published private(set) var route: PlannedRoute?
    @Published private(set) var status: Status = .idle
    /// Contrôle d'accès des pistes (seulement quand « Autoriser les pistes » est actif) : tronçons à signaler.
    enum AccessStatus: Equatable {
        case idle, checking, checked, unavailable
    }
    @Published private(set) var flagged: [FlaggedSegment] = []
    @Published private(set) var accessStatus: AccessStatus = .idle
    /// Change quand l'itinéraire doit être recadré sur la carte (premier calcul, ou après « Tout effacer »).
    @Published private(set) var fitToken = 0

    private var computeTask: Task<Void, Never>?
    private var accessTask: Task<Void, Never>?
    /// Points à éviter (signalements bloqués ou interdits de la zone) : fournis par l'écran, relus à chaque calcul.
    var exclusions: ((minLat: Double, minLon: Double, maxLat: Double, maxLon: Double)) -> [CLLocationCoordinate2D] = { _ in [] }
    /// Serveur Valhalla à utiliser (réglages de l'app) ; posé par l'écran à son apparition.
    var configuration: () -> ValhallaConfiguration? = { nil }

    var isConfigured: Bool { configuration() != nil }
    var canSave: Bool { route != nil && status != .computing }

    func add(_ coordinate: CLLocationCoordinate2D) {
        let current = waypoints.map(SharedRoadbook.latLon)
        guard RoutePlanner.shared.canAdd(waypoints: current, candidate: SharedRoadbook.latLon(coordinate)) else { return }
        waypoints.append(coordinate)
        recompute()
    }

    func remove(at index: Int) {
        guard waypoints.indices.contains(index) else { return }
        waypoints.remove(at: index)
        recompute()
    }

    func removeLast() {
        guard !waypoints.isEmpty else { return }
        waypoints.removeLast()
        recompute()
    }

    func clear() {
        computeTask?.cancel()
        accessTask?.cancel()
        waypoints = []
        route = nil
        flagged = []
        accessStatus = .idle
        status = .idle
        fitToken += 1
    }

    func optionsChanged() { recompute() }

    func recompute() {
        computeTask?.cancel()
        accessTask?.cancel()
        guard waypoints.count >= Int(RoutePlanner.shared.MIN_WAYPOINTS) else {
            route = nil
            flagged = []
            accessStatus = .idle
            status = .idle
            return
        }
        guard let configuration = configuration() else {
            status = .failed(String(localized: "Valhalla n'est pas configuré : active-le dans Réglages > Avancé > Routage Valhalla pour créer un itinéraire.", bundle: .appLanguage))
            return
        }
        status = .computing
        let points = waypoints
        let options = options
        let isFirst = route == nil
        let padding = 0.5   // degrés autour des points posés
        let bounds = (
            minLat: (points.map(\.latitude).min() ?? 0) - padding, minLon: (points.map(\.longitude).min() ?? 0) - padding,
            maxLat: (points.map(\.latitude).max() ?? 0) + padding, maxLon: (points.map(\.longitude).max() ?? 0) + padding
        )
        let excluded = exclusions(bounds)
        computeTask = Task { [weak self] in
            do {
                // Petit délai : plusieurs points posés d'affilée ne font qu'un calcul.
                try await Task.sleep(nanoseconds: 350_000_000)
                let planned = try await ValhallaRoutingService.plan(waypoints: points, options: options, excluding: excluded, configuration: configuration)
                guard !Task.isCancelled, let self else { return }
                self.route = planned
                self.status = .idle
                if isFirst { self.fitToken += 1 }
                self.checkAccess(of: planned, options: options, configuration: configuration)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.route = nil
                self.flagged = []
                self.accessStatus = .idle
                self.status = .failed(String(localized: "Itinéraire impossible entre ces points.", bundle: .appLanguage))
            }
        }
    }

    /// Pistes autorisées ? Seulement si l'utilisateur les a permises : on lit alors l'accès de chaque chemin non goudronné.
    private func checkAccess(of planned: PlannedRoute, options: PlanOptions, configuration: ValhallaConfiguration) {
        accessTask?.cancel()
        flagged = []
        guard options.allowTracks else {
            accessStatus = .idle
            return
        }
        accessStatus = .checking
        accessTask = Task { [weak self] in
            let result = await TrackAccessChecker.check(route: planned, vehicle: options.vehicle, configuration: configuration)
            guard !Task.isCancelled, let self else { return }
            if let result {
                self.flagged = result
                self.accessStatus = .checked
            } else {
                self.accessStatus = .unavailable
            }
        }
    }

    /// GPX de l'itinéraire, même format que les sorties enregistrées.
    func gpxData(named name: String) -> Data? {
        guard let route else { return nil }
        let points = route.points.map { GPXPoint(latitude: $0.latitude, longitude: $0.longitude) }
        return GPXExporter.export(trackName: name, points: points, waypoints: [], comment: String(localized: "Itinéraire créé dans GPXroad", bundle: .appLanguage))
    }
}
