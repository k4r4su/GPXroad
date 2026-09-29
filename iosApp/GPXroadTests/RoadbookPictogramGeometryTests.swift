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

    /// Sans analyse OSM (rond-point connu seulement par Valhalla) : entrée, sortie, AUCUNE branche ni
    /// numéro inventé — le numéro Valhalla (« 2 » partout, it34) n'est jamais affiché.
    func testWithoutOSMAnalysisNothingIsInvented() {
        XCTAssertTrue(roundabout(.left, 90, exit: 3).branches.isEmpty)
        XCTAssertNil(roundabout(.left, 90, exit: 3).exitNumber)
    }

    /// It34 : toutes les branches réelles, la sortie à la position analysée, le numéro OSM au centre.
    func testAnalyzedRoundaboutDrawsEveryBranch() {
        let analyzed = RoadbookRoundabout(
            entryCumulativeMeters: 100, exitCumulativeMeters: 140, entryCoordinate: CLLocationCoordinate2D(latitude: 47, longitude: 7),
            exitAngleDegrees: -90, exitNumber: 3, exitRoadName: "D 419",
            branches: [.init(angleDegrees: 180, kind: .entry), .init(angleDegrees: 90, kind: .countedExit), .init(angleDegrees: 45, kind: .minor),
                       .init(angleDegrees: 0, kind: .countedExit), .init(angleDegrees: -90, kind: .takenExit), .init(angleDegrees: -135, kind: .noExit)],
            clockwise: false, thenExitNumber: nil, thenExitAngleDegrees: nil
        )
        let checkpoint = Checkpoint(coordinate: analyzed.entryCoordinate, turnAngleDegrees: 90, direction: .left, tier: .roundabout, sequenceIndex: 1, sourcePointIndex: 0, roundaboutExitCount: 3, roundabout: analyzed)
        let drawing = RoadbookRoundaboutDrawing(checkpoint: checkpoint, in: rect)
        XCTAssertEqual(drawing.exitNumber, 3)
        XCTAssertEqual(drawing.branches.map(\.kind), [.countedExit, .minor, .countedExit, .noExit], "ni l'entrée ni la sortie prise en double")
        XCTAssertNotNil(drawing.branches.last?.bar, "sens interdit barré")
        XCTAssertLessThan(drawing.exit.to.x, drawing.center.x - 30, "sortie à gauche")
        XCTAssertTrue(drawing.path.contains { $0.x > drawing.center.x + 20 }, "par la droite (circulation à droite)")
        let minor = drawing.branches[1].segment.to
        let counted = drawing.branches[0].segment.to
        XCTAssertLessThan(hypot(minor.x - drawing.center.x, minor.y - drawing.center.y), hypot(counted.x - drawing.center.x, counted.y - drawing.center.y), "petite voie plus courte")
    }
}
