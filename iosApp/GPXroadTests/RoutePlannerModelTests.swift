import XCTest
import CoreLocation
@testable import GPXroad

/// Création d'itinéraire (06/10) : état du modèle sans réseau — le calcul Valhalla lui-même est vérifié sur une vraie
/// réponse côté Kotlin/Android (`PlannedRouteParsingTest`) et au simulateur.
@MainActor
final class RoutePlannerModelTests: XCTestCase {
    func testWaypointsAreAddedRemovedAndCleared() {
        let model = RoutePlannerModel()
        model.add(CLLocationCoordinate2D(latitude: 47.5, longitude: 7.5))
        XCTAssertEqual(model.waypoints.count, 1)
        XCTAssertEqual(model.status, .idle, "un seul point : rien à calculer")
        model.add(CLLocationCoordinate2D(latitude: 47.50005, longitude: 7.5))
        XCTAssertEqual(model.waypoints.count, 1, "à moins de 30 m du précédent : ignoré")
        model.add(CLLocationCoordinate2D(latitude: 47.6, longitude: 7.6))
        XCTAssertEqual(model.waypoints.count, 2)
        model.removeLast()
        XCTAssertEqual(model.waypoints.count, 1)
        model.clear()
        XCTAssertTrue(model.waypoints.isEmpty)
        XCTAssertNil(model.route)
    }

    func testWithoutValhallaItSaysSoInsteadOfDrawingAStraightLine() {
        let model = RoutePlannerModel()   // aucun serveur configuré
        model.add(CLLocationCoordinate2D(latitude: 47.5, longitude: 7.5))
        model.add(CLLocationCoordinate2D(latitude: 47.6, longitude: 7.6))
        guard case .failed = model.status else { return XCTFail("attendu : message « Valhalla n'est pas configuré »") }
        XCTAssertNil(model.route)
        XCTAssertFalse(model.canSave)
        XCTAssertNil(model.gpxData(named: "x"))
    }

    func testDefaultOptionsAreFunRoadsOnAMotorcycle() {
        let options = RoutePlannerModel().options
        XCTAssertEqual(options.vehicle, .motorcycle)
        XCTAssertTrue(options.avoidHighways)
        XCTAssertTrue(options.avoidTolls)
        XCTAssertFalse(options.allowTracks)
    }
}
