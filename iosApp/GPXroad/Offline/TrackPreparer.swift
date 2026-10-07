import Foundation
import CoreLocation
import MapLibre
import GPXroadShared

/// MapLibre ne connaît l'état d'une zone déjà sur disque (téléchargée avant un redémarrage) qu'après `requestProgress()` :
/// sans cet appel, `state` reste `.unknown` et la zone paraît absente.
enum OfflinePacks {
    static func requestStatesIfNeeded(_ packs: [MLNOfflinePack]) {
        for pack in packs where pack.state == .unknown { pack.requestProgress() }
    }
}

/// Préparation complète de la trace active (idée du 06/10) : carte du couloir (±1 km, niveaux 5 à 14), repères du Road Book,
/// recalage Valhalla et ronds-points — tout ce qu'il faut pour rouler sans réseau, téléchargé dès que le réseau est bon.
/// Règles (couloir, « prête » / « incomplète ») = `shared/offline/TrackPreparation`, communes avec Android.
@MainActor
final class TrackPreparer: ObservableObject {
    /// Incrémenté à chaque changement d'état : les vues relisent `readiness(for:settings:)`.
    @Published private(set) var revision = 0
    @Published private(set) var preparingTrackID: UUID?

    private struct TrackPackInfo: Codable { var track: String }

    private var task: Task<Void, Never>?
    private var failures: [UUID: Date] = [:]
    private var mapWaiter: (pack: MLNOfflinePack, continuation: CheckedContinuation<Bool, Never>)?
    private var observers: [NSObjectProtocol] = []
    private let landmarkLoader = RoadbookLandmarkLoader()
    private let mapMatchProvider: MapMatchingProvider = ValhallaMapMatchingProvider()
    static let retryAfterSeconds: TimeInterval = 10 * 60
    static let mapTimeoutSeconds: UInt64 = 30 * 60

    init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: NSNotification.Name.MLNOfflinePackProgressChanged, object: nil, queue: .main) { [weak self] note in
            let pack = note.object as? MLNOfflinePack
            Task { @MainActor in self?.progressChanged(pack) }
        })
        observers.append(center.addObserver(forName: NSNotification.Name.MLNOfflinePackError, object: nil, queue: .main) { [weak self] note in
            let pack = note.object as? MLNOfflinePack
            Task { @MainActor in self?.packFailed(pack) }
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    // MARK: - État

    private func trackPackID(_ pack: MLNOfflinePack) -> String? {
        guard pack.state != .invalid, let info = try? JSONDecoder().decode(TrackPackInfo.self, from: pack.context) else { return nil }
        return info.track
    }

    private func hasMapPack(for trackID: UUID) -> Bool {
        let packs = MLNOfflineStorage.shared.packs ?? []
        OfflinePacks.requestStatesIfNeeded(packs)
        return packs.contains { trackPackID($0) == trackID.uuidString && $0.state == .complete }
    }

    /// Ce qui est déjà en local pour cette trace (le sens de parcours compte pour le recalage).
    func readiness(for track: GPXTrack, settings: RideSettingsStore) -> TrackReadiness {
        _ = revision   // dépendance SwiftUI
        let enabled = settings.roadbookLandmarkCategories
        let landmarks: Bool? = enabled.isEmpty ? nil
            : (RoadbookLandmarkDataCache.shared.data(for: track.id)?.fetchedCategories ?? []).isSuperset(of: enabled)
        let routeMatch: Bool? = settings.valhallaConfigurationIfEnabled == nil ? nil
            : (RoadbookMapMatchCache.shared.entry(for: track) != nil)
        return TrackPreparation.shared.evaluate(
            map: hasMapPack(for: track.id),
            landmarks: landmarks.map { KotlinBoolean(bool: $0) },
            routeMatch: routeMatch.map { KotlinBoolean(bool: $0) },
            roundabouts: KotlinBoolean(bool: RoadbookRoundaboutStore.shared.data(for: track.id) != nil)
        )
    }

    // MARK: - Préparation

    /// Prépare ce qui manque. `force` : demande de l'utilisateur (ignore le réglage automatique, l'attente après un échec
    /// et le type de réseau, pas l'absence de réseau).
    func prepare(track: GPXTrack, settings: RideSettingsStore, network: NetworkMonitor, force: Bool = false, suspended: Bool = false) {
        guard task == nil else { return }
        let state = readiness(for: track, settings: settings)
        guard state.level != .ready else { return }
        if force {
            guard network.isReachable else { return }
        } else {
            guard settings.autoPrepareEnabled, !suspended, network.isGoodForAutoMap(allowCellular: settings.autoMapCellular) else { return }
            if let failed = failures[track.id], Date().timeIntervalSince(failed) < Self.retryAfterSeconds { return }
        }
        let missing = Set(state.missing)
        preparingTrackID = track.id
        revision += 1
        landmarkLoader.isOnline = { [weak network] in network?.isReachable ?? true }
        task = Task { [weak self] in
            guard let self else { return }
            var complete = true
            // Les petites données d'abord (Road Book), la carte (la plus lourde) en dernier.
            if missing.contains(.routeMatch) { complete = await self.matchRoute(track: track, settings: settings) && complete }
            if missing.contains(.roundabouts) {
                RoadbookRoundaboutStore.shared.ensure(trackID: track.id, points: track.points)
                await RoadbookRoundaboutStore.shared.settle()
                complete = (RoadbookRoundaboutStore.shared.data(for: track.id) != nil) && complete
            }
            if missing.contains(.landmarks) {
                self.landmarkLoader.update(trackID: track.id, traversalKey: track.traversalKey, points: track.points, maneuvers: [], enabled: settings.roadbookLandmarkCategories)
                await self.landmarkLoader.settle()
            }
            if missing.contains(.map) { complete = await self.downloadMap(track: track) && complete }
            self.finish(trackID: track.id, success: complete && !Task.isCancelled)
        }
    }

    private func finish(trackID: UUID, success: Bool) {
        if success { failures[trackID] = nil } else { failures[trackID] = Date() }
        task = nil
        preparingTrackID = nil
        revision += 1
    }

    private func matchRoute(track: GPXTrack, settings: RideSettingsStore) async -> Bool {
        guard let configuration = settings.valhallaConfigurationIfEnabled else { return true }
        let maxPoints = RideConstants.mapMatchingMaxTracePoints
        let coordinates = track.points.map(\.coordinate)
        let sampled: [CLLocationCoordinate2D]
        if coordinates.count > maxPoints, maxPoints > 1 {
            let step = Double(coordinates.count - 1) / Double(maxPoints - 1)
            sampled = (0..<maxPoints).map { coordinates[Int((Double($0) * step).rounded())] }
        } else {
            sampled = coordinates
        }
        guard let result = try? await mapMatchProvider.matchRoute(coordinates: sampled, configuration: configuration) else { return false }
        let coverage = SharedRoadbook.coverage(trackPoints: track.points, matchedShapes: result.matchedShapes)
        RoadbookMapMatchCache.shared.store(traversalKey: track.traversalKey, maneuvers: result.maneuvers, coverage: coverage)
        return true
    }

    // MARK: - Carte du couloir

    private func downloadMap(track: GPXTrack) async -> Bool {
        let rings = TrackPreparation.shared.mapRings(points: SharedRoadbook.latLons(track.points))
        let polygons: [MLNPolygon] = rings.map { ring in
            var coordinates = ring.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
            return MLNPolygon(coordinates: &coordinates, count: UInt(coordinates.count))
        }
        guard !polygons.isEmpty, let context = try? JSONEncoder().encode(TrackPackInfo(track: track.id.uuidString)) else { return false }
        let region = MLNShapeOfflineRegion(
            styleURL: AutoMapConstants.referenceStyleURL,
            shape: MLNMultiPolygon(polygons: polygons),
            fromZoomLevel: AutoMapConstants.corridorMinZoom,
            toZoomLevel: AutoMapConstants.maxZoom
        )
        // Une zone de cette trace restée à moitié (réseau coupé) est remplacée.
        for old in (MLNOfflineStorage.shared.packs ?? []) where trackPackID(old) == track.id.uuidString {
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                MLNOfflineStorage.shared.removePack(old) { _ in done.resume() }
            }
        }
        let pack: MLNOfflinePack? = await withCheckedContinuation { continuation in
            MLNOfflineStorage.shared.addPack(for: region, withContext: context) { pack, error in
                continuation.resume(returning: error == nil ? pack : nil)
            }
        }
        guard let pack else { return false }
        pack.resume()
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            mapWaiter = (pack, continuation)
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: Self.mapTimeoutSeconds * 1_000_000_000)
                await MainActor.run { self?.resolveWaiter(for: pack, success: false) }
            }
        }
    }

    private func resolveWaiter(for pack: MLNOfflinePack, success: Bool) {
        guard let waiter = mapWaiter, waiter.pack === pack else { return }
        mapWaiter = nil
        if !success { MLNOfflineStorage.shared.removePack(pack) { _ in } }
        waiter.continuation.resume(returning: success)
    }

    private func progressChanged(_ pack: MLNOfflinePack?) {
        guard let pack else { return }
        if pack.state == .complete { resolveWaiter(for: pack, success: true) }
        revision += 1   // l'état d'une zone déjà sur disque vient de devenir connu
    }

    private func packFailed(_ pack: MLNOfflinePack?) {
        guard let pack else { return }
        resolveWaiter(for: pack, success: false)
    }
}

extension RideSettingsStore {
    /// Valhalla configuré et activé (mêmes conditions que le Road Book), sinon `nil` : aucun appel réseau.
    var valhallaConfigurationIfEnabled: ValhallaConfiguration? {
        guard valhallaEnabled, !valhallaEndpointURLString.isEmpty else { return nil }
        return ValhallaConfiguration(
            endpointURLString: valhallaEndpointURLString,
            username: ValhallaKeychainStore.username(),
            password: ValhallaKeychainStore.password()
        )
    }
}
