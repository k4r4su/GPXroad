import Foundation
import CoreLocation

// Ronds-points analysés sur OpenStreetMap (it34, retour terrain : « le but c'est que ça ne soit pas
// ambigu »). Ici : données OSM téléchargées et mises en cache par trace, modèle affiché et textes.
// L'ANALYSE (anneau, branches, numéro de sortie, contrôles) vit dans le module partagé
// (`RoundaboutAnalyzer.kt`), appelée via `SharedRoadbook`.

/// Anneaux, routes qui y aboutissent et mini-giratoires autour d'une trace — ne dépend que de la
/// GÉOMÉTRIE (cache par `GPXTrack.id`, valable dans les deux sens de parcours).
struct RoadbookRoundaboutMapData: Codable, Equatable {
    struct Road: Codable, Equatable {
        let id: Int64
        let nodeIDs: [Int64]
        let latitudes: [Double]
        let longitudes: [Double]
        let highway: String
        let junction: String?
        let oneway: String?
        let access: String?
        let motorVehicle: String?
        let name: String?
        let ref: String?
        let destination: String?
    }

    struct MiniRoundabout: Codable, Equatable {
        let nodeID: Int64
        let latitude: Double
        let longitude: Double
        let clockwise: Bool
    }

    let roads: [Road]
    let miniRoundabouts: [MiniRoundabout]

    static let empty = RoadbookRoundaboutMapData(roads: [], miniRoundabouts: [])

    /// Identité du contenu (clé des résultats mémorisés).
    var fingerprint: String {
        var hasher = Hasher()
        for road in roads { hasher.combine(road.id); hasher.combine(road.nodeIDs.count) }
        for mini in miniRoundabouts { hasher.combine(mini.nodeID) }
        return "\(roads.count)-\(miniRoundabouts.count)-\(hasher.finalize())"
    }

    /// Fusion de tronçons voisins (un élément présent dans deux tronçons n'est gardé qu'une fois).
    func adding(_ other: RoadbookRoundaboutMapData) -> RoadbookRoundaboutMapData {
        var knownRoads = Set(roads.map(\.id))
        var knownMinis = Set(miniRoundabouts.map(\.nodeID))
        return RoadbookRoundaboutMapData(
            roads: roads + other.roads.filter { knownRoads.insert($0.id).inserted },
            miniRoundabouts: miniRoundabouts + other.miniRoundabouts.filter { knownMinis.insert($0.nodeID).inserted }
        )
    }
}

// MARK: - Overpass

enum RoadbookRoundaboutOverpass {
    /// Pas d'échantillonnage de la trace dans la requête et rayon `around` (distance à la polyligne).
    static let sampleSpacingMeters: Double = 40
    static let aroundMeters = 40

    /// Anneaux croisés par le tronçon, routes reliées à leurs nœuds (géométrie complète), et
    /// mini-giratoires avec leurs routes. Le filtrage fin (passage réel de la trace) est fait par
    /// `RoundaboutAnalyzer`. `nil` si la trace est trop courte.
    static func query(for points: [GPXPoint]) -> String? {
        let cumulative = TrackProjector.cumulativeDistances(for: points)
        guard points.count > 1, let total = cumulative.last, total > 0 else { return nil }
        let spacing = max(sampleSpacingMeters, total / Double(RoadBookConstants.landmarkQueryMaxPolylinePoints - 1))
        let sampleCount = Int((total / spacing).rounded(.up))
        let polyline = (0...sampleCount)
            .compactMap { TrackProjector.interpolatedCoordinate(atCumulativeDistance: min(Double($0) * spacing, total), points: points, cumulativeDistances: cumulative) }
            .map { String(format: "%.6f,%.6f", $0.latitude, $0.longitude) }
            .joined(separator: ",")
        let around = Int((spacing / 2).rounded(.up)) + aroundMeters
        return """
        [out:json][timeout:\(Int(RoadBookConstants.landmarkRequestTimeoutSeconds))];
        way["highway"]["junction"~"^(roundabout|circular)$"](around:\(around),\(polyline))->.r;
        .r out geom;
        node(w.r)->.rn;
        (way(bn.rn)["highway"]; - .r;)->.c;
        .c out geom;
        node["highway"="mini_roundabout"](around:\(around),\(polyline))->.m;
        .m out;
        way(bn.m)["highway"]->.mc;
        .mc out geom;
        """
    }

    static func parse(_ data: Data) -> RoadbookRoundaboutMapData? {
        guard let response = try? JSONDecoder().decode(Response.self, from: data) else { return nil }
        var roads: [RoadbookRoundaboutMapData.Road] = []
        var minis: [RoadbookRoundaboutMapData.MiniRoundabout] = []
        var seen = Set<Int64>()
        for element in response.elements {
            let tags = element.tags ?? [:]
            if element.type == "node", tags["highway"] == "mini_roundabout", let lat = element.lat, let lon = element.lon {
                minis.append(.init(nodeID: element.id, latitude: lat, longitude: lon, clockwise: tags["direction"] == "clockwise"))
                continue
            }
            guard element.type == "way", let highway = tags["highway"], let nodes = element.nodes, let geometry = element.geometry,
                  nodes.count == geometry.count, nodes.count > 1, seen.insert(element.id).inserted
            else { continue }
            // Nœud manquant dans la géométrie (hors de la zone renvoyée) : route inutilisable.
            let coordinates = geometry.compactMap { $0 }
            guard coordinates.count == nodes.count else { continue }
            roads.append(.init(
                id: element.id,
                nodeIDs: nodes,
                latitudes: coordinates.map(\.lat),
                longitudes: coordinates.map(\.lon),
                highway: highway,
                junction: tags["junction"],
                oneway: tags["oneway"],
                access: tags["access"] ?? tags["vehicle"],
                motorVehicle: tags["motor_vehicle"] ?? tags["motorcar"],
                name: tags["name"],
                ref: tags["ref"],
                destination: tags["destination"]
            ))
        }
        return RoadbookRoundaboutMapData(roads: roads, miniRoundabouts: minis)
    }

    /// Tous les tronçons de la trace (même découpage que les repères) ; `nil` au premier échec —
    /// jamais un résultat partiel mis en cache (un rond-point manquant = direction seule à tort).
    static func fetch(points: [GPXPoint]) async -> RoadbookRoundaboutMapData? {
        var result = RoadbookRoundaboutMapData.empty
        for chunk in RoadbookLandmarkLoader.chunks(of: points) {
            guard let query = query(for: chunk),
                  let encoded = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~")))
            else { return nil }
            let body = Data("data=\(encoded)".utf8)
            var parsed: RoadbookRoundaboutMapData?
            for candidate in OverpassConfiguration.attempts(formBody: body, timeout: RoadBookConstants.landmarkRequestTimeoutSeconds) where parsed == nil {
                guard !Task.isCancelled else { return nil }
                if let (data, response) = try? await URLSession.shared.data(for: candidate.request),
                   (response as? HTTPURLResponse)?.statusCode == 200,
                   let decoded = parse(data) {
                    parsed = decoded
                    await OverpassActivityMonitor.shared.record(candidate.kind)
                } else {
                    OverpassConfiguration.reportFailure(of: candidate.kind)
                }
            }
            guard let parsed else { return nil }
            result = result.adding(parsed)
        }
        return result
    }

    private struct Response: Decodable {
        let elements: [Element]
        struct Element: Decodable {
            let type: String
            let id: Int64
            let lat: Double?
            let lon: Double?
            let tags: [String: String]?
            let nodes: [Int64]?
            let geometry: [LatLon?]?
        }
        struct LatLon: Decodable {
            let lat: Double
            let lon: Double
        }
    }
}

// MARK: - Cache et chargement

/// Données des ronds-points par trace : cache disque + téléchargement à la demande, partagé par
/// l'onglet Road Book (qui déclenche le téléchargement) et le Ride (qui relit le cache). Échec :
/// rien n'est mis en cache, nouvel essai possible après `retryAfterSeconds`.
@MainActor
final class RoadbookRoundaboutStore: ObservableObject {
    static let shared = RoadbookRoundaboutStore()

    typealias Fetcher = @Sendable (_ points: [GPXPoint]) async -> RoadbookRoundaboutMapData?

    /// Change à chaque nouvelle donnée : les écrans recalculent leurs manœuvres.
    @Published private(set) var revision = 0

    static let retryAfterSeconds: TimeInterval = 60

    private struct Entry: Codable {
        let trackID: UUID
        let data: RoadbookRoundaboutMapData
    }

    private var entries: [UUID: RoadbookRoundaboutMapData] = [:]
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var failures: [UUID: Date] = [:]
    private var loaded = false
    private let directoryOverride: URL?
    private let fetch: Fetcher
    private let now: () -> Date

    init(directoryOverride: URL? = nil, fetch: @escaping Fetcher = { await RoadbookRoundaboutOverpass.fetch(points: $0) }, now: @escaping () -> Date = Date.init) {
        self.directoryOverride = directoryOverride
        self.fetch = fetch
        self.now = now
    }

    private var fileURL: URL {
        let directory = directoryOverride
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("RoadbookRoundaboutCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("index-v1.json")
    }

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        if let data = try? Data(contentsOf: fileURL), let decoded = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = Dictionary(decoded.map { ($0.trackID, $0.data) }, uniquingKeysWith: { _, latest in latest })
        }
    }

    func data(for trackID: UUID) -> RoadbookRoundaboutMapData? {
        loadIfNeeded()
        return entries[trackID]
    }

    /// Télécharge les données de la trace si elles manquent (une seule fois à la fois par trace).
    func ensure(trackID: UUID, points: [GPXPoint]) {
        loadIfNeeded()
        guard entries[trackID] == nil, tasks[trackID] == nil, points.count > 1 else { return }
        if let failed = failures[trackID], now().timeIntervalSince(failed) < Self.retryAfterSeconds { return }
        let fetch = fetch
        tasks[trackID] = Task { [weak self] in
            let result = await fetch(points)
            guard let self else { return }
            self.tasks[trackID] = nil
            guard let result else {
                self.failures[trackID] = self.now()
                return
            }
            self.failures[trackID] = nil
            self.store(result, for: trackID)
        }
    }

    func store(_ data: RoadbookRoundaboutMapData, for trackID: UUID) {
        loadIfNeeded()
        entries[trackID] = data
        revision += 1
        let encoded = entries.map { Entry(trackID: $0.key, data: $0.value) }
        if let json = try? JSONEncoder().encode(encoded) { try? json.write(to: fileURL) }
    }

    /// Attend la fin des téléchargements en cours (tests).
    func settle() async {
        for task in Array(tasks.values) { await task.value }
    }
}

// MARK: - Modèle affiché

/// Rond-point d'un événement du Road Book, tel que dessiné et annoncé (voir `RoundaboutPassage`,
/// Kotlin). Angles du pictogramme : 0 = en haut (tout droit), sens horaire, entrée à 180.
struct RoadbookRoundabout: Equatable {
    enum BranchKind: Equatable { case entry, takenExit, countedExit, minor, noExit }

    struct Branch: Equatable {
        let angleDegrees: Double
        let kind: BranchKind
    }

    let entryCumulativeMeters: Double
    let exitCumulativeMeters: Double
    let entryCoordinate: CLLocationCoordinate2D
    /// Position dessinée de la sortie (8 positions) — la direction annoncée.
    let exitAngleDegrees: Double
    /// `nil` dès qu'un contrôle échoue : jamais un numéro douteux.
    let exitNumber: Int?
    /// « D 419 », destination signalée ou nom de la route de sortie.
    let exitRoadName: String?
    let branches: [Branch]
    /// Circulation dans le sens des aiguilles d'une montre (pays où l'on roule à gauche).
    let clockwise: Bool
    let thenExitNumber: Int?
    let thenExitAngleDegrees: Double?

    static func == (lhs: RoadbookRoundabout, rhs: RoadbookRoundabout) -> Bool {
        lhs.entryCumulativeMeters == rhs.entryCumulativeMeters && lhs.exitAngleDegrees == rhs.exitAngleDegrees &&
            lhs.exitNumber == rhs.exitNumber && lhs.exitRoadName == rhs.exitRoadName && lhs.branches == rhs.branches &&
            lhs.clockwise == rhs.clockwise && lhs.thenExitNumber == rhs.thenExitNumber
    }
}

/// Textes d'un rond-point : « Rond-point · 3e sortie · à gauche », « → D 419 », « puis 1re sortie ».
enum RoadbookRoundaboutText {
    static func exitOrdinal(_ number: Int) -> String {
        switch number {
        case 1: return String(localized: "1re sortie", bundle: .appLanguage)
        case 2: return String(localized: "2e sortie", bundle: .appLanguage)
        case 3: return String(localized: "3e sortie", bundle: .appLanguage)
        default: return String(localized: "\(number)e sortie", bundle: .appLanguage)
        }
    }

    /// Sens de la sortie sur les 8 positions du pictogramme.
    static func direction(angleDegrees: Double) -> String {
        let sector = Int((angleDegrees / 45).rounded())
        switch sector {
        case 0: return String(localized: "tout droit", bundle: .appLanguage)
        case 1: return String(localized: "légèrement à droite", bundle: .appLanguage)
        case 2: return String(localized: "à droite", bundle: .appLanguage)
        case 3: return String(localized: "fortement à droite", bundle: .appLanguage)
        case -1: return String(localized: "légèrement à gauche", bundle: .appLanguage)
        case -2: return String(localized: "à gauche", bundle: .appLanguage)
        case -3: return String(localized: "fortement à gauche", bundle: .appLanguage)
        default: return String(localized: "demi-tour", bundle: .appLanguage)
        }
    }

    /// « 3e sortie · à gauche » (numéro seulement s'il est sûr).
    static func exitSummary(_ roundabout: RoadbookRoundabout) -> String {
        let direction = direction(angleDegrees: roundabout.exitAngleDegrees)
        guard let number = roundabout.exitNumber else { return direction }
        return "\(exitOrdinal(number)) · \(direction)"
    }

    /// Libellé complet d'un événement rond-point.
    static func instruction(_ roundabout: RoadbookRoundabout) -> String {
        "\(RoadbookTier.roundabout.label) · \(exitSummary(roundabout))"
    }

    static func roadName(_ roundabout: RoadbookRoundabout) -> String? {
        roundabout.exitRoadName.map { "→ \($0)" }
    }

    /// Rond-point enchaîné juste après : « puis 1re sortie » (ou « puis tout droit »).
    static func chained(_ roundabout: RoadbookRoundabout) -> String? {
        guard let angle = roundabout.thenExitAngleDegrees else { return nil }
        let next = roundabout.thenExitNumber.map(exitOrdinal) ?? direction(angleDegrees: angle)
        return String(localized: "puis \(next)", bundle: .appLanguage)
    }
}

extension Checkpoint {
    /// Libellé de l'instruction : pour un rond-point analysé, sortie et direction ; sinon le palier.
    var instructionLabel: String {
        if tier == .roundabout, let roundabout { return RoadbookRoundaboutText.instruction(roundabout) }
        return tier.label
    }

    /// Ligne secondaire d'un rond-point analysé : route de sortie, puis rond-point enchaîné.
    var roundaboutDetail: String? {
        guard tier == .roundabout, let roundabout else { return nil }
        let parts = [RoadbookRoundaboutText.roadName(roundabout), RoadbookRoundaboutText.chained(roundabout)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}
