import Foundation
import CoreLocation
import MapLibre
import GPXroadShared

/// Carte automatique autour de soi (retour terrain du 04/10 : zone sans réseau, carte illisible).
/// Même règle qu'Android (`shared/offline/AutoPrefetch`, relue ici) : quand le réseau est bon, télécharge
/// le disque de 10/15/20 km autour de la position + les 30 prochains km de la trace suivie (niveaux 5 à 14),
/// renouvelle quand on s'éloigne du tiers du rayon (au plus toutes les 10 min), garde les 3 dernières zones.
/// Stockage : paquets hors ligne MapLibre (`MLNOfflineStorage`), distincts du cache raster maison.
@MainActor
final class AutoMapPrefetcher: ObservableObject {
    /// Vrai si une zone automatique terminée couvre la position : le Ride garde alors le style vectoriel hors ligne.
    @Published private(set) var coversPosition = false

    private struct PackInfo: Codable {
        var auto: Bool
        var lat: Double
        var lon: Double
        var radiusMeters: Double
        var date: Date
    }

    private var currentPack: MLNOfflinePack?
    private var currentPackStart: Date?
    /// Vrai entre la demande d'ajout et la réponse de MapLibre (le paquet n'existe pas encore).
    private var isStarting = false
    private var lastAttempt: Date?
    private var observers: [NSObjectProtocol] = []

    init() {
        MLNOfflineStorage.shared.setMaximumAmbientCacheSize(AutoMapConstants.ambientCacheBytes) { _ in }
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

    // MARK: - Appelé à chaque position GPS du Ride

    func update(location: CLLocation, track: GPXTrack?, settings: RideSettingsStore, network: NetworkMonitor) {
        guard let packs = MLNOfflineStorage.shared.packs else { return }   // pas encore chargés
        OfflinePacks.requestStatesIfNeeded(packs)   // zones d'avant le redémarrage : état inconnu tant qu'on ne le demande pas
        refreshCoverage(position: location.coordinate, packs: packs)
        guard settings.autoMapEnabled else { return }
        dropStuckPackIfNeeded()

        let radiusMeters = Double(settings.autoMapRadiusKm) * 1000
        let position = SharedRoadbook.latLon(location.coordinate)
        let last = autoPacks(in: packs).max { $0.info.date < $1.info.date }
        let lastCenter = last.map { GPXroadShared.LatLon(latitude: $0.info.lat, longitude: $0.info.lon) }
        let now = Date()
        let shouldRefresh = AutoPrefetch.shared.shouldRefresh(
            position: position,
            lastCenter: lastCenter,
            radiusMeters: radiusMeters,
            nowMillis: Int64(now.timeIntervalSince1970 * 1000),
            lastAttemptMillis: lastAttempt.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) },
            networkGood: network.isGoodForAutoMap(allowCellular: settings.autoMapCellular),
            busy: currentPack != nil || isStarting
        )
        guard shouldRefresh else { return }
        lastAttempt = now
        download(center: location.coordinate, radiusMeters: radiusMeters, ahead: trackAhead(of: track, from: location.coordinate))
    }

    // MARK: - Téléchargement

    private func trackAhead(of track: GPXTrack?, from position: CLLocationCoordinate2D) -> [GPXroadShared.LatLon] {
        guard let track, track.points.count > 1 else { return [] }
        let cumulative = TrackProjector.cumulativeDistances(for: track)
        var nearest = 0
        var best = Double.infinity
        for (index, point) in track.points.enumerated() {
            let d = RoadbookAnalyzer.distanceMeters(position, point.coordinate)
            if d < best { best = d; nearest = index }
        }
        guard best <= AutoMapConstants.maxDistanceToTrackMeters else { return [] }   // loin de la trace : disque seul
        return AutoPrefetch.shared.trackAhead(
            points: SharedRoadbook.latLons(track.points),
            cumulative: SharedRoadbook.doubleArray(cumulative),
            fromCumulativeMeters: cumulative[nearest]
        )
    }

    private func download(center: CLLocationCoordinate2D, radiusMeters: Double, ahead: [GPXroadShared.LatLon]) {
        let rings = AutoPrefetch.shared.rings(center: SharedRoadbook.latLon(center), radiusMeters: radiusMeters, trackAhead: ahead)
        let polygons: [MLNPolygon] = rings.map { ring in
            var coordinates = ring.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
            return MLNPolygon(coordinates: &coordinates, count: UInt(coordinates.count))
        }
        guard !polygons.isEmpty else { return }
        let region = MLNShapeOfflineRegion(
            styleURL: AutoMapConstants.referenceStyleURL,
            shape: MLNMultiPolygon(polygons: polygons),
            fromZoomLevel: AutoMapConstants.minZoom,
            toZoomLevel: AutoMapConstants.maxZoom
        )
        let info = PackInfo(auto: true, lat: center.latitude, lon: center.longitude, radiusMeters: radiusMeters, date: Date())
        guard let context = try? JSONEncoder().encode(info) else { return }
        // Réservé tout de suite : un seul téléchargement automatique à la fois.
        currentPackStart = Date()
        isStarting = true
        MLNOfflineStorage.shared.addPack(for: region, withContext: context) { [weak self] pack, error in
            Task { @MainActor in
                guard let self else { return }
                self.isStarting = false
                if let pack, error == nil {
                    self.currentPack = pack
                    pack.resume()
                } else {
                    self.currentPack = nil
                    self.currentPackStart = nil
                }
            }
        }
    }

    private func progressChanged(_ pack: MLNOfflinePack?) {
        guard let pack else { return }
        guard pack === currentPack else {
            // État d'une ancienne zone enfin connu : la couverture de la position peut changer.
            if let position = lastPosition { refreshCoverage(position: position, packs: MLNOfflineStorage.shared.packs ?? []) }
            return
        }
        guard pack.state == .complete else { return }
        currentPack = nil
        currentPackStart = nil
        prune()
        if let position = lastPosition { refreshCoverage(position: position, packs: MLNOfflineStorage.shared.packs ?? []) }
    }

    private func packFailed(_ pack: MLNOfflinePack?) {
        guard let pack, pack === currentPack else { return }
        currentPack = nil
        currentPackStart = nil
        MLNOfflineStorage.shared.removePack(pack) { _ in }   // une zone échouée est supprimée, la suivante repart de zéro
    }

    /// Un téléchargement qui n'avance plus (coupure réseau en route) ne doit pas bloquer les suivants indéfiniment.
    private func dropStuckPackIfNeeded() {
        guard let start = currentPackStart, Date().timeIntervalSince(start) > AutoMapConstants.stuckAfterSeconds else { return }
        if let pack = currentPack, pack.state != .invalid {
            MLNOfflineStorage.shared.removePack(pack) { _ in }
        }
        currentPack = nil
        currentPackStart = nil
        isStarting = false
    }

    // MARK: - Zones existantes

    private var lastPosition: CLLocationCoordinate2D?

    private func autoPacks(in packs: [MLNOfflinePack]) -> [(pack: MLNOfflinePack, info: PackInfo)] {
        packs.compactMap { pack in
            guard pack.state != .invalid,
                  let info = try? JSONDecoder().decode(PackInfo.self, from: pack.context), info.auto else { return nil }
            return (pack, info)
        }
    }

    /// Garde les 3 dernières zones terminées ; supprime les plus anciennes.
    private func prune() {
        let complete = autoPacks(in: MLNOfflineStorage.shared.packs ?? []).filter { $0.pack.state == .complete }
        let old = complete.sorted { $0.info.date > $1.info.date }.dropFirst(AutoMapConstants.maxAutoPacks)
        for entry in old { MLNOfflineStorage.shared.removePack(entry.pack) { _ in } }
    }

    private func refreshCoverage(position: CLLocationCoordinate2D, packs: [MLNOfflinePack]) {
        lastPosition = position
        let covered = autoPacks(in: packs).contains { entry in
            entry.pack.state == .complete
                && RoadbookAnalyzer.distanceMeters(position, CLLocationCoordinate2D(latitude: entry.info.lat, longitude: entry.info.lon)) <= entry.info.radiusMeters
        }
        if covered != coversPosition { coversPosition = covered }
    }
}
