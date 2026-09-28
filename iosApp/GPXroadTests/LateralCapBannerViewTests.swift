import XCTest
@testable import GPXroad

/// Countdown par paliers de la bannière latérale — depuis le 29/09, mêmes paliers que tout le
/// reste de l'app (`DistanceUnit.countdownString`) : 100 m au-dessus de 200 m, 50 m jusqu'à
/// 100 m, puis 10 m, toujours arrondi au-dessus.
final class LateralCapBannerViewTests: XCTestCase {
    func testHundredMetreStepsAboveTwoHundred() {
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(600), "600 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(599), "600 m", "reste à 600 juste après l'apparition de la bannière")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(501), "600 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(500), "500 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(201), "300 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(200), "200 m")
    }

    func testFiftyThenTenMetreStepsNearTheTurn() {
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(151), "200 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(150), "150 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(101), "150 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(100), "100 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(99), "100 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(21), "30 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(9), "10 m")
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(0), "0 m")
    }

    func testNegativeDistanceClampsToZero() {
        XCTAssertEqual(LateralCapBannerView.steppedDistanceText(-5), "0 m")
    }
}
