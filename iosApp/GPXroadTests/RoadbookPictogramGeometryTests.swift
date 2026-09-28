import XCTest
import CoreLocation
@testable import GPXroad

/// It33 — rond-point dessiné : la sortie est placée où la trace sort réellement, le trajet tourne par
/// la droite (circulation à droite), les sorties passées sont dessinées, le numéro est au centre.
final class RoadbookPictogramGeometryTests: XCTestCase {
    private let rect = CGRect(x: 0, y: 0, width: 100, height: 100)

    private func roundabout(_ direction: TurnDirection, _ angle: Double, exit: Int?) -> RoadbookRoundaboutDrawing {
        RoadbookRoundaboutDrawing(checkpoint: Checkpoint(coordinate: CLLocationCoordinate2D(latitude: 47, longitude: 7), turnAngleDegrees: angle, direction: direction, tier: .roundabout, sequenceIndex: 1, sourcePointIndex: 0, roundaboutExitCount: exit), in: rect)
    }

    func testTheEntryIsAtTheBottomAndTheExitWhereTheTraceLeaves() {
        let right = roundabout(.right, 90, exit: 1)
        XCTAssertGreaterThan(right.entry.from.y, right.center.y, "entrée en bas")
        XCTAssertGreaterThan(right.exit.to.x, right.center.x + 30, "sortie à droite")
        let straight = roundabout(.straight, 3, exit: 2)
        XCTAssertLessThan(straight.exit.to.y, straight.center.y - 30, "tout droit : sortie en haut")
        let left = roundabout(.left, 90, exit: 3)
        XCTAssertLessThan(left.exit.to.x, left.center.x - 30, "sortie à gauche")
    }

    func testThePathGoesRoundByTheRightLikeTheTraffic() {
        let left = roundabout(.left, 90, exit: 3)
        // Pour sortir à gauche, on passe par la droite puis par le haut du rond-point.
        XCTAssertTrue(left.path.contains { $0.x > left.center.x + 20 }, "passe par la droite")
        XCTAssertTrue(left.path.contains { $0.y < left.center.y - 20 }, "puis par le haut")
    }

    func testSkippedExitsAndTheExitNumber() {
        XCTAssertEqual(roundabout(.left, 90, exit: 3).skippedExits.count, 2)
        XCTAssertEqual(roundabout(.left, 90, exit: 3).exitNumber, 3)
        XCTAssertTrue(roundabout(.right, 90, exit: nil).skippedExits.isEmpty, "rang inconnu : aucune sortie inventée")
        XCTAssertNil(roundabout(.right, 90, exit: nil).exitNumber)
    }
}
