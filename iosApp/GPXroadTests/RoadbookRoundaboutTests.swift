import XCTest
import CoreLocation
@testable import GPXroad

/// It34 — ronds-points analysés sur OSM, côté iOS : décodage Overpass, cache par trace, textes,
/// frontière Kotlin et chaîne complète jusqu'au Road Book et au Ride (données de synthèse ; la
/// validation sur les traces réelles du propriétaire se fait hors dépôt, voir RoadBook/CLAUDE.md).
@MainActor
final class RoadbookRoundaboutTests: XCTestCase {
    private var directory: URL!

    override func setUp() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("RoadbookRoundaboutTests-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Géométrie de synthèse : anneau de 16 nœuds, rayon 15 m ; branche k au nœud k
    // (k = 0 sud, 4 est, 8 nord, 12 ouest), circulation à droite.

    private let origin = CLLocationCoordinate2D(latitude: 47.6, longitude: 7.3)
    private let radius = 15.0

    private func at(_ x: Double, _ y: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: origin.latitude + y / 111_320, longitude: origin.longitude + x / (111_320 * cos(origin.latitude * .pi / 180)))
    }

    private func ringPoint(_ k: Double, _ r: Double? = nil) -> CLLocationCoordinate2D {
        let a = (-90 + k * 22.5) * .pi / 180
        return at((r ?? radius) * cos(a), (r ?? radius) * sin(a))
    }

    private func road(_ id: Int64, _ nodes: [Int64], _ coordinates: [CLLocationCoordinate2D], highway: String = "secondary", junction: String? = nil, name: String? = nil, ref: String? = nil) -> RoadbookRoundaboutMapData.Road {
        .init(id: id, nodeIDs: nodes, latitudes: coordinates.map(\.latitude), longitudes: coordinates.map(\.longitude), highway: highway, junction: junction, oneway: nil, access: nil, motorVehicle: nil, name: name, ref: ref, destination: nil)
    }

    private var mapData: RoadbookRoundaboutMapData {
        let ks = (0...16).map { $0 % 16 }
        let ring = road(1, ks.map { 100 + Int64($0) }, ks.map { ringPoint(Double($0)) }, highway: "primary", junction: "roundabout")
        func arm(_ k: Int, name: String?, ref: String? = nil) -> RoadbookRoundaboutMapData.Road {
            road(10 + Int64(k), [100 + Int64(k), 200 + Int64(k)], [ringPoint(Double(k)), ringPoint(Double(k), 215)], name: name, ref: ref)
        }
        return RoadbookRoundaboutMapData(roads: [ring, arm(0, name: "Rue Sud"), arm(4, name: "Rue Est"), arm(8, name: "Rue Nord"), arm(12, name: nil, ref: "D 419")], miniRoundabouts: [])
    }

    /// Sud → anneau par la droite → sortie ouest (à gauche, 3e sortie).
    private var leftTurnTrack: GPXTrack {
        var coordinates = stride(from: 200.0, through: 20, by: -5).map { ringPoint(0, radius + $0) }
        coordinates += stride(from: 0.0, through: 12, by: 0.5).map { ringPoint($0) }
        coordinates += stride(from: 20.0, through: 200, by: 5).map { ringPoint(12, radius + $0) }
        return GPXTrack(id: UUID(), name: "Rond-point", fileName: "r.gpx", importDate: Date(), points: coordinates.map { GPXPoint(latitude: $0.latitude, longitude: $0.longitude) }, waypoints: [])
    }

    // MARK: Décodage

    func testOverpassResponseIsDecoded() throws {
        let json = """
        {"elements":[
          {"type":"way","id":1,"nodes":[1,2,3,1],"geometry":[{"lat":47.6,"lon":7.3},{"lat":47.6001,"lon":7.3},{"lat":47.6,"lon":7.3001},{"lat":47.6,"lon":7.3}],"tags":{"highway":"primary","junction":"roundabout"}},
          {"type":"way","id":2,"nodes":[2,9],"geometry":[{"lat":47.6001,"lon":7.3},{"lat":47.602,"lon":7.3}],"tags":{"highway":"secondary","ref":"D 419","destination":"Belfort;Mulhouse","oneway":"yes","motor_vehicle":"private"}},
          {"type":"way","id":3,"nodes":[3,8],"geometry":[{"lat":47.6,"lon":7.3001},null],"tags":{"highway":"service"}},
          {"type":"way","id":4,"nodes":[5,6],"geometry":[{"lat":47.6,"lon":7.3},{"lat":47.6,"lon":7.31}],"tags":{"highway":"footway"}},
          {"type":"node","id":50,"lat":47.61,"lon":7.31,"tags":{"highway":"mini_roundabout","direction":"clockwise"}}
        ]}
        """
        let data = try XCTUnwrap(RoadbookRoundaboutOverpass.parse(Data(json.utf8)))
        XCTAssertEqual(data.roads.map(\.id), [1, 2, 4], "route à géométrie incomplète écartée")
        let d419 = try XCTUnwrap(data.roads.first { $0.id == 2 })
        XCTAssertEqual(d419.ref, "D 419")
        XCTAssertEqual(d419.destination, "Belfort;Mulhouse")
        XCTAssertEqual(d419.oneway, "yes")
        XCTAssertEqual(d419.motorVehicle, "private")
        XCTAssertEqual(data.miniRoundabouts, [.init(nodeID: 50, latitude: 47.61, longitude: 7.31, clockwise: true)])
        XCTAssertNil(RoadbookRoundaboutOverpass.parse(Data("pas du json".utf8)))
    }

    func testQueryAsksForRingsTheirRoadsAndMiniRoundabouts() throws {
        let query = try XCTUnwrap(RoadbookRoundaboutOverpass.query(for: leftTurnTrack.points))
        XCTAssertTrue(query.contains(#"["junction"~"^(roundabout|circular)$"]"#))
        XCTAssertTrue(query.contains("way(bn.rn)"))
        XCTAssertTrue(query.contains(#"node["highway"="mini_roundabout"]"#))
        XCTAssertNil(RoadbookRoundaboutOverpass.query(for: [GPXPoint(latitude: 47, longitude: 7)]))
    }

    func testChunksAreMergedWithoutDuplicates() {
        let a = RoadbookRoundaboutMapData(roads: [road(1, [1, 2], [origin, origin])], miniRoundabouts: [.init(nodeID: 9, latitude: 0, longitude: 0, clockwise: false)])
        let b = RoadbookRoundaboutMapData(roads: [road(1, [1, 2], [origin, origin]), road(2, [2, 3], [origin, origin])], miniRoundabouts: [.init(nodeID: 9, latitude: 0, longitude: 0, clockwise: false)])
        let merged = a.adding(b)
        XCTAssertEqual(merged.roads.map(\.id), [1, 2])
        XCTAssertEqual(merged.miniRoundabouts.count, 1)
        XCTAssertNotEqual(a.fingerprint, merged.fingerprint)
    }

    // MARK: Cache et chargement

    func testStoreDownloadsOnceAndKeepsTheResultOnDisk() async {
        let data = mapData
        let calls = Counter()
        let store = RoadbookRoundaboutStore(directoryOverride: directory, fetch: { _ in await calls.increment(); return data })
        let trackID = UUID()
        store.ensure(trackID: trackID, points: leftTurnTrack.points)
        store.ensure(trackID: trackID, points: leftTurnTrack.points)
        await store.settle()
        let count = await calls.value
        XCTAssertEqual(count, 1, "un seul téléchargement à la fois")
        XCTAssertEqual(store.data(for: trackID), data)
        XCTAssertEqual(store.revision, 1)

        let reopened = RoadbookRoundaboutStore(directoryOverride: directory, fetch: { _ in XCTFail("cache disque"); return nil })
        XCTAssertEqual(reopened.data(for: trackID), data, "relu au prochain lancement")
    }

    func testAFailureIsNotCachedAndIsRetriedOnlyAfterTheDelay() async {
        var now = Date()
        let calls = Counter()
        let store = RoadbookRoundaboutStore(directoryOverride: directory, fetch: { _ in await calls.increment(); return nil }, now: { now })
        let trackID = UUID()
        store.ensure(trackID: trackID, points: leftTurnTrack.points)
        await store.settle()
        XCTAssertNil(store.data(for: trackID))
        XCTAssertEqual(store.revision, 0)

        store.ensure(trackID: trackID, points: leftTurnTrack.points)
        await store.settle()
        var count = await calls.value
        XCTAssertEqual(count, 1, "pas de nouvel essai tout de suite")

        now = now.addingTimeInterval(RoadbookRoundaboutStore.retryAfterSeconds + 1)
        store.ensure(trackID: trackID, points: leftTurnTrack.points)
        await store.settle()
        count = await calls.value
        XCTAssertEqual(count, 2)
    }

    // MARK: Chaîne complète

    func testRoadbookAnnouncesTheThirdExitOnTheLeftWithTheRoadName() throws {
        let maneuvers = RoadbookExtractor.maneuvers(
            for: leftTurnTrack, windowBeforeMeters: 40, windowAfterMeters: 40,
            lightThresholdDegrees: 25, markedThresholdDegrees: 45, hardThresholdDegrees: 90, veryHardThresholdDegrees: 135,
            mergeMinDistanceMeters: 150, roundaboutData: mapData
        )
        let roundabout = try XCTUnwrap(maneuvers.single)
        XCTAssertEqual(roundabout.checkpoint.tier, .roundabout)
        XCTAssertEqual(roundabout.checkpoint.roundaboutExitCount, 3)
        XCTAssertEqual(roundabout.checkpoint.instructionLabel, "Rond-point · 3e sortie · à gauche")
        XCTAssertEqual(roundabout.checkpoint.roundaboutDetail, "→ D 419")
        XCTAssertEqual(roundabout.checkpoint.roundabout?.branches.count, 4)

        // Aller-retour par la frontière Kotlin : rien de perdu (sélection des repères, reprise).
        let back = SharedRoadbook.checkpoint(SharedRoadbook.sharedCheckpoint(roundabout.checkpoint))
        XCTAssertEqual(back.roundabout, roundabout.checkpoint.roundabout)
    }

    func testRideRebuildsItsEventsWhenRoundaboutDataArrives() async throws {
        let suite = "RoadbookRoundaboutTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = RideSessionManager(settings: RideSettingsStore(defaults: defaults), networkMonitor: NetworkMonitor(), modeStore: RideModeStore(), sharedBlockages: SharedBlockageSyncCoordinator(defaults: defaults))
        let store = RoadbookRoundaboutStore(directoryOverride: directory, fetch: { _ in nil })
        session.roundaboutStore = store
        let track = leftTurnTrack
        session.start(track: track)
        XCTAssertNil(session.checkpoints.first { $0.roundabout != nil })

        store.store(mapData, for: track.id)
        for _ in 0..<50 where session.checkpoints.first(where: { $0.roundabout != nil }) == nil {
            await Task.yield()
        }
        XCTAssertEqual(session.checkpoints.first { $0.tier == .roundabout }?.roundaboutExitCount, 3)
        session.stop()
    }

    // MARK: Textes

    func testTexts() {
        func passage(_ number: Int?, _ angle: Double, name: String? = nil, then: Int? = nil, thenAngle: Double? = nil) -> RoadbookRoundabout {
            RoadbookRoundabout(entryCumulativeMeters: 0, exitCumulativeMeters: 0, entryCoordinate: origin, exitAngleDegrees: angle, exitNumber: number, exitRoadName: name, branches: [], clockwise: false, thenExitNumber: then, thenExitAngleDegrees: thenAngle)
        }
        XCTAssertEqual(RoadbookRoundaboutText.instruction(passage(1, 0)), "Rond-point · 1re sortie · tout droit")
        XCTAssertEqual(RoadbookRoundaboutText.instruction(passage(2, 45)), "Rond-point · 2e sortie · légèrement à droite")
        XCTAssertEqual(RoadbookRoundaboutText.instruction(passage(5, -135)), "Rond-point · 5e sortie · fortement à gauche")
        XCTAssertEqual(RoadbookRoundaboutText.instruction(passage(nil, 180)), "Rond-point · demi-tour", "sans numéro sûr : direction seule")
        XCTAssertEqual(RoadbookRoundaboutText.roadName(passage(1, 0, name: "D 419 – Belfort")), "→ D 419 – Belfort")
        XCTAssertEqual(RoadbookRoundaboutText.chained(passage(2, -90, then: 1, thenAngle: 90)), "puis 1re sortie")
        XCTAssertEqual(RoadbookRoundaboutText.chained(passage(2, -90, then: nil, thenAngle: 0)), "puis tout droit")
        XCTAssertNil(RoadbookRoundaboutText.chained(passage(2, -90)))

        let turn = Checkpoint(coordinate: origin, turnAngleDegrees: 90, direction: .left, tier: .hard, sequenceIndex: 1, sourcePointIndex: 0)
        XCTAssertEqual(turn.instructionLabel, RoadbookTier.hard.label)
        XCTAssertNil(turn.roundaboutDetail)
    }
}

private actor Counter {
    private(set) var value = 0
    func increment() { value += 1 }
}

private extension Array {
    /// Seul élément, `nil` s'il y en a zéro ou plusieurs.
    var single: Element? { count == 1 ? first : nil }
}
