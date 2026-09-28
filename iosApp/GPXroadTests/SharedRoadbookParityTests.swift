import XCTest
import CoreLocation
@testable import GPXroad

/// It32 (pilote Kotlin Multiplatform) — PARITÉ entre la géométrie du Road Book en Swift natif
/// (`RoadbookAnalyzer.nativeGeometricEvents`, référence) et son portage Kotlin (`shared/`, via
/// `SharedRoadbookGeometryBridge`) : mêmes entrées → mêmes événements. Nombre, palier, sens, rang,
/// index de point et coordonnée : STRICTEMENT égaux. Angle et distance cumulée : à une tolérance
/// près, parce que l'égalité au bit près n'est pas atteignable — mesuré à it32 :
/// - le Swift natif mesure les distances avec `CLLocation.distance(from:)`, dont la formule n'est
///   pas documentée et qui n'est même PAS déterministe dans le simulateur (même paire de points,
///   deux résultats selon le moment, jusqu'à 1,6·10⁻⁴ d'écart relatif sur un segment de 3 m) ; le
///   module partagé utilise Vincenty (déterministe, identique sur iOS et Android) ;
/// - `sin`/`cos` d'un même argument deviennent `__sincos_stret` (un ulp d'écart) en Kotlin/Native
///   et en Swift `-O`, pas en Swift Debug.
/// Écarts réels relevés (et imprimés à chaque run) : ≈ 0,01° d'angle, ≈ 1,5·10⁻⁴ de distance
/// relative — sans commune mesure avec l'écart entre deux paliers (≥ 20°) ni avec l'arrondi du
/// Road Book (10 m). Couvre les géométries des tests du Road Book, 400 traces aléatoires
/// reproductibles (doublons, épingles, demi-tours, densités de 2 à 300 m, 5 latitudes) et des
/// traces réelles (voir `testParityOnRealTracks`), chaque fois avec 3 jeux de réglages.
final class SharedRoadbookParityTests: XCTestCase {

    private struct Settings {
        let before: Double
        let after: Double
        let thresholds: RoadbookAnalyzer.TierThresholds
        let merge: Double
    }

    private let settingsVariants: [Settings] = [
        Settings(before: NavigationConstants.roadbookWindowBeforeMetersDefault, after: NavigationConstants.roadbookWindowAfterMetersDefault,
                 thresholds: .init(light: 25, marked: 45, hard: 90, veryHard: 135), merge: 150),
        Settings(before: 30, after: 80, thresholds: .init(light: 30, marked: 50, hard: 95, veryHard: 140), merge: 50),
        Settings(before: 80, after: 30, thresholds: .init(light: 20, marked: 40, hard: 80, veryHard: 120), merge: 300),
    ]

    // MARK: - Comparaison

    private func assertParity(_ points: [GPXPoint], _ label: String, file: StaticString = #filePath, line: UInt = #line) {
        let track = GPXTrack(id: UUID(), name: label, fileName: "\(label).gpx", importDate: Date(timeIntervalSince1970: 0), points: points, waypoints: [])
        for settings in settingsVariants {
            let native = events(track, settings, engine: .native)
            let shared = events(track, settings, engine: .shared)
            XCTAssertEqual(native.count, shared.count, "\(label) : nombre d'événements", file: file, line: line)
            for (n, s) in zip(native, shared) {
                let structural = n.coordinate.latitude == s.coordinate.latitude
                    && n.coordinate.longitude == s.coordinate.longitude
                    && n.direction == s.direction
                    && n.tier == s.tier
                    && n.sequenceIndex == s.sequenceIndex
                    && n.sourcePointIndex == s.sourcePointIndex
                let angleGap = abs(n.turnAngleDegrees - s.turnAngleDegrees)
                let distanceGap = abs((n.trackCumulativeDistanceMeters ?? 0) - (s.trackCumulativeDistanceMeters ?? 0))
                let distanceTolerance = max(distanceToleranceMeters, distanceToleranceRatio * (n.trackCumulativeDistanceMeters ?? 0))
                maxAngleGap = max(maxAngleGap, angleGap)
                maxDistanceRatio = max(maxDistanceRatio, distanceGap / max(n.trackCumulativeDistanceMeters ?? 1, 1))
                XCTAssertTrue(structural && angleGap <= angleToleranceDegrees && distanceGap <= distanceTolerance,
                              "\(label) #\(n.sequenceIndex) : natif \(describe(n)) ≠ partagé \(describe(s))", file: file, line: line)
            }
            comparedEvents += native.count
        }
    }

    private var comparedEvents = 0
    private let angleToleranceDegrees = 0.05
    private let distanceToleranceMeters = 0.01
    private let distanceToleranceRatio = 1e-3
    private var maxAngleGap = 0.0
    private var maxDistanceRatio = 0.0

    private func events(_ track: GPXTrack, _ s: Settings, engine: RoadbookGeometryEngine) -> [Checkpoint] {
        RoadbookAnalyzer.buildRoadbookEvents(
            for: track,
            windowBeforeMeters: s.before,
            windowAfterMeters: s.after,
            lightThresholdDegrees: s.thresholds.light,
            markedThresholdDegrees: s.thresholds.marked,
            hardThresholdDegrees: s.thresholds.hard,
            veryHardThresholdDegrees: s.thresholds.veryHard,
            mergeMinDistanceMeters: s.merge,
            engine: engine
        )
    }

    private func describe(_ c: Checkpoint) -> String {
        "(\(c.coordinate.latitude), \(c.coordinate.longitude)) \(c.turnAngleDegrees)° \(c.direction) \(c.tier) i\(c.sourcePointIndex) \(c.trackCumulativeDistanceMeters ?? -1) m"
    }

    // MARK: - Générateurs (mêmes formules que les tests du Road Book)

    private func destination(from coordinate: CLLocationCoordinate2D, bearingDegrees: Double, distanceMeters: Double) -> CLLocationCoordinate2D {
        let earthRadius = 6_371_000.0
        let bearing = bearingDegrees * .pi / 180
        let lat1 = coordinate.latitude * .pi / 180
        let lon1 = coordinate.longitude * .pi / 180
        let angularDistance = distanceMeters / earthRadius
        let lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
        let lon2 = lon1 + atan2(sin(bearing) * sin(angularDistance) * cos(lat1), cos(angularDistance) - sin(lat1) * sin(lat2))
        return CLLocationCoordinate2D(latitude: lat2 * 180 / .pi, longitude: lon2 * 180 / .pi)
    }

    private func track(from origin: CLLocationCoordinate2D, _ segments: [(length: Double, turnAfter: Double)], pointSpacing: Double = 0) -> [GPXPoint] {
        var coordinate = origin
        var points = [GPXPoint(latitude: coordinate.latitude, longitude: coordinate.longitude)]
        var bearing = 0.0
        for segment in segments {
            let steps = pointSpacing > 0 ? max(Int((segment.length / pointSpacing).rounded()), 1) : 1
            for _ in 0..<steps {
                coordinate = destination(from: coordinate, bearingDegrees: bearing, distanceMeters: segment.length / Double(steps))
                points.append(GPXPoint(latitude: coordinate.latitude, longitude: coordinate.longitude))
            }
            bearing += segment.turnAfter
        }
        return points
    }

    /// Générateur pseudo-aléatoire déterministe (SplitMix64) : même suite de traces à chaque run.
    private struct SeededGenerator: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    // MARK: - Cas

    func testParityOnTheRoadbookTestGeometries() {
        let origin = CLLocationCoordinate2D(latitude: 45.0, longitude: 5.0)
        for turn in stride(from: -180.0, through: 180.0, by: 5) {
            assertParity(track(from: origin, [(100, turn), (100, 0)]), "sommet \(turn)°")
            assertParity(track(from: origin, [(400, turn), (400, 0)], pointSpacing: 20), "virage dense \(turn)°")
        }
        for (count, length, turn) in [(10, 20.0, 8.0), (4, 30.0, 70.0), (10, 50.0, 0.0), (10, 15.0, 15.0), (30, 20.0, 3.0)] {
            assertParity(track(from: origin, Array(repeating: (length, turn), count: count)), "courbe \(count)×\(length) m/\(turn)°")
        }
        assertParity(track(from: origin, [(300, 90), (40, 90), (300, 0)]), "épingle")
        assertParity(track(from: origin, [(300, 90), (35, 90), (300, 0)], pointSpacing: 5), "épingle dense")
        assertParity(track(from: origin, [(400, 180), (400, 0)]), "demi-tour")
        assertParity(track(from: origin, [(100, 180), (400, 0)]), "demi-tour au départ")
        assertParity(track(from: origin, [(300, -40), (15, 40), (300, 0)], pointSpacing: 5), "zigzag")
        assertParity(track(from: origin, [(300, 0), (0.0001, 0), (300, 90), (500, -90), (300, 0)], pointSpacing: 20), "chicane")

        var field = track(from: CLLocationCoordinate2D(latitude: 47.6, longitude: 7.4), [(400, -102), (31, 0), (0.0001, 0), (239, 0), (58, 0), (302, 0)])
        field.insert(field[2], at: 2)
        assertParity(field, "retour terrain (doublon)")
    }

    func testParityOnSeededRandomTracks() {
        var rng = SeededGenerator(state: 0x6750_5872_6F61_6432)
        let origins = [(45.0, 5.0), (47.6, 7.4), (0.2, -60.0), (64.1, -21.9), (-33.9, 151.2)]
        for index in 0..<400 {
            let origin = origins[index % origins.count]
            var coordinate = CLLocationCoordinate2D(latitude: origin.0, longitude: origin.1)
            var bearing = Double.random(in: 0..<360, using: &rng)
            var points = [GPXPoint(latitude: coordinate.latitude, longitude: coordinate.longitude)]
            let spacing = [2.0, 5.0, 15.0, 40.0, 120.0, 300.0][index % 6]
            for _ in 0..<Int.random(in: 20...250, using: &rng) {
                let roll = Double.random(in: 0..<1, using: &rng)
                if roll < 0.04, let last = points.last {
                    points.append(last) // point dupliqué (segment de 0 m)
                    continue
                }
                if roll < 0.10 {
                    bearing += Double.random(in: 150...210, using: &rng) // épingle / demi-tour
                } else if roll < 0.35 {
                    bearing += Double.random(in: -120...120, using: &rng)
                } else {
                    bearing += Double.random(in: -12...12, using: &rng)
                }
                coordinate = destination(from: coordinate, bearingDegrees: bearing, distanceMeters: spacing * Double.random(in: 0.2...1.8, using: &rng))
                points.append(GPXPoint(latitude: coordinate.latitude, longitude: coordinate.longitude))
            }
            assertParity(points, "aléatoire #\(index)")
        }
        XCTAssertGreaterThan(comparedEvents, 1000, "précondition : la parité porte sur un volume significatif d'événements")
        print("Parité Road Book, traces aléatoires : \(comparedEvents) événements identiques, écart d'angle max \(maxAngleGap)°, écart de distance max \(maxDistanceRatio) (relatif)")
    }

    /// Traces GPX réelles, lues sans modification, dans les deux sens : un dossier fourni via
    /// `TEST_RUNNER_GPXROAD_PARITY_GPX_DIR=<dossier> xcodebuild test …` (validation it32 : 15 traces
    /// du propriétaire, copiées dans le scratchpad). Sans dossier : la trace d'exemple embarquée —
    /// jamais les vraies données de l'app (`Documents/`), et jamais de test sauté.
    func testParityOnRealTracks() throws {
        var sources: [(String, URL)] = []
        if let path = ProcessInfo.processInfo.environment["GPXROAD_PARITY_GPX_DIR"] {
            let files = try FileManager.default.contentsOfDirectory(at: URL(fileURLWithPath: path), includingPropertiesForKeys: nil)
            sources = files.filter { $0.pathExtension.lowercased() == "gpx" }
                .sorted { $0.lastPathComponent < $1.lastPathComponent }
                .map { ($0.lastPathComponent, $0) }
        } else {
            sources = [("sample-trail.gpx", try XCTUnwrap(Bundle.main.url(forResource: "sample-trail", withExtension: "gpx")))]
        }
        XCTAssertFalse(sources.isEmpty)
        var pointCount = 0
        for (name, url) in sources {
            let parsed = try GPXParser.parse(data: Data(contentsOf: url))
            pointCount += parsed.points.count
            assertParity(parsed.points, name)
            assertParity(Array(parsed.points.reversed()), "\(name) (sens inverse)")
        }
        print("Parité Road Book sur \(sources.count) trace(s) réelle(s) (\(pointCount) points) : \(comparedEvents) événements identiques, écart d'angle max \(maxAngleGap)°, écart de distance max \(maxDistanceRatio) (relatif)")
    }

    /// Coût mesuré (informatif, jamais bloquant) : même trace dense, deux moteurs.
    func testReportsTheCostOfBothEngines() {
        var rng = SeededGenerator(state: 42)
        var coordinate = CLLocationCoordinate2D(latitude: 47.6, longitude: 7.4)
        var bearing = 0.0
        var points = [GPXPoint(latitude: coordinate.latitude, longitude: coordinate.longitude)]
        for _ in 0..<20_000 {
            bearing += Double.random(in: 0..<1, using: &rng) < 0.05 ? Double.random(in: -110...110, using: &rng) : Double.random(in: -6...6, using: &rng)
            coordinate = destination(from: coordinate, bearingDegrees: bearing, distanceMeters: 15)
            points.append(GPXPoint(latitude: coordinate.latitude, longitude: coordinate.longitude))
        }
        let track = GPXTrack(id: UUID(), name: "Dense", fileName: "dense.gpx", importDate: Date(), points: points, waypoints: [])
        var timings: [RoadbookGeometryEngine: Double] = [:]
        var counts: [RoadbookGeometryEngine: Int] = [:]
        for engine in [RoadbookGeometryEngine.native, .shared, .native, .shared] {
            let start = CFAbsoluteTimeGetCurrent()
            counts[engine] = events(track, settingsVariants[0], engine: engine).count
            timings[engine] = CFAbsoluteTimeGetCurrent() - start
        }
        XCTAssertEqual(counts[.native], counts[.shared])
        print(String(format: "Coût Road Book, 20 000 points : Swift natif %.0f ms, Kotlin partagé %.0f ms (%d événements)",
                     (timings[.native] ?? 0) * 1000, (timings[.shared] ?? 0) * 1000, counts[.native] ?? 0))
    }
}
