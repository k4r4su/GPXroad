import XCTest
@testable import GPXroad

/// Compte à rebours jusqu'au prochain virage / repère (demande du propriétaire, 29/09) : la
/// valeur affichée ne change qu'aux paliers demandés, jamais à chaque fix GPS.
final class DistanceCountdownTests: XCTestCase {
    func testApproachingATurnShowsOnlyTheRequestedSteps() {
        let shown = stride(from: 2_000.0, through: 0, by: -1).map { DistanceUnit.km.countdownString(fromMeters: $0) }
        var distinct: [String] = []
        for text in shown where distinct.last != text { distinct.append(text) }
        XCTAssertEqual(distinct, ["2 km", "1,5 km", "1 km", "900 m", "800 m", "700 m", "600 m", "500 m", "400 m", "300 m", "200 m", "150 m",
                                  "100 m", "90 m", "80 m", "70 m", "60 m", "50 m", "40 m", "30 m", "20 m", "10 m", "0 m"])
    }

    func testMilesKeepTheirOwnSteps() {
        XCTAssertEqual(DistanceUnit.mi.countdownString(fromMeters: 2_000), "1,5 mi")
        XCTAssertEqual(DistanceUnit.mi.countdownString(fromMeters: 600), "0,4 mi")
    }

    /// Les distances statiques (liste du Road Book, PDF) gardent leur précision.
    func testStaticDistancesAreUnchanged() {
        XCTAssertEqual(DistanceUnit.km.displayString(fromMeters: 587), "587 m")
    }
}
