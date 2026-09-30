import XCTest
import CoreLocation
@testable import GPXroad

/// It34 (retour terrain, Ferrette) : un repère de décor suivi de près par un virage laisse la
/// grande carte au virage — règle partagée `RoadbookFocusRule`, façade `RoadbookLiveProgress.focus`.
final class RoadbookFocusTests: XCTestCase {
    private func turn(_ at: Double) -> RoadbookEntry {
        let checkpoint = Checkpoint(coordinate: CLLocationCoordinate2D(latitude: 47, longitude: 7), turnAngleDegrees: 90, direction: .left, tier: .hard, sequenceIndex: 1, sourcePointIndex: 0, trackCumulativeDistanceMeters: at)
        return .maneuver(RoadbookManeuver(checkpoint: checkpoint, partialDistanceMeters: at, cumulativeDistanceMeters: at, headingDegrees: 0), index: 0)
    }

    private func landmark(_ at: Double, _ category: RoadbookLandmarkCategory) -> RoadbookEntry {
        .landmark(RoadbookLandmarkCheckpoint(info: RoadbookLandmarkInfo(category: category, label: category.rawValue, side: nil), latitude: 47, longitude: 7, cumulativeDistanceMeters: at))
    }

    func testFerretteShowsTheTurnWithTheChurchBelow() throws {
        // Éléments DÉJÀ passés avant, pour vérifier le décalage des rangs.
        let entries = [turn(1_000), landmark(2_000, .stopSign), landmark(4_449, .church), landmark(4_508, .chargingStation), landmark(4_604, .fuel), turn(4_607)]
        let focus = try XCTUnwrap(RoadbookLiveProgress.focus(entries: entries, currentCumulativeDistanceMeters: 4_400, speedMetersPerSecond: 13.9))
        XCTAssertEqual(focus.index, 5, "le virage")
        XCTAssertEqual(focus.distanceRemainingMeters, 207, accuracy: 1e-6)
        XCTAssertEqual(focus.leadingLandmarkIndex, 2, "l'église")
        XCTAssertEqual(focus.leadingLandmarkDistanceMeters ?? -1, 49, accuracy: 1e-6)
    }

    func testAStopKeepsItsCard() throws {
        let entries = [landmark(1_000, .stopSign), turn(1_050)]
        let focus = try XCTUnwrap(RoadbookLiveProgress.focus(entries: entries, currentCumulativeDistanceMeters: 900, speedMetersPerSecond: nil))
        XCTAssertEqual(focus.index, 0)
        XCTAssertNil(focus.leadingLandmarkIndex)
    }
}
