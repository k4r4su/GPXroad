import XCTest
import CoreTelephony
import GPXroadShared
@testable import GPXroad

/// Carte automatique autour de soi (04/10) — règle réseau, résolveur, réglages ; la règle de
/// renouvellement et les zones (`AutoPrefetch`) sont testées en Kotlin (commonTest), partagées avec Android.
@MainActor
final class AutoMapTests: XCTestCase {
    private let lte = [CTRadioAccessTechnologyLTE]

    private func good(wifi: Bool = false, cellular: Bool = false, lowData: Bool = false, techs: [String] = [], allow: Bool = true, satisfied: Bool = true) -> Bool {
        AutoMapNetworkPolicy.isGood(isSatisfied: satisfied, isWifiOrEthernet: wifi, isCellular: cellular, isLowDataMode: lowData, cellularTechnologies: techs, allowCellular: allow)
    }

    func testWifiIsGoodEvenWhenCellularNotAllowed() {
        XCTAssertTrue(good(wifi: true, allow: false))
    }

    func testFastCellularNeedsTheSettingAndA4GOr5GRadio() {
        XCTAssertTrue(good(cellular: true, techs: lte))
        XCTAssertTrue(good(cellular: true, techs: [CTRadioAccessTechnologyNR]))
        XCTAssertFalse(good(cellular: true, techs: lte, allow: false))
        XCTAssertFalse(good(cellular: true, techs: [CTRadioAccessTechnologyEdge]))
        XCTAssertFalse(good(cellular: true, techs: [CTRadioAccessTechnologyWCDMA]))
    }

    func testNoNetworkOrLowDataModeIsNeverGood() {
        XCTAssertFalse(good(wifi: true, satisfied: false))
        XCTAssertFalse(good(wifi: true, lowData: true))
        XCTAssertFalse(good(cellular: true, lowData: true, techs: lte))
    }

    func testOfflineCoverageKeepsTheVectorStyleInsteadOfRaster() {
        let offlineWithCoverage = MapSourceResolver.resolve(
            activeVectorPackageFileURL: nil, isNetworkReachable: false, hasOfflineVectorCoverage: true, themePreset: .standard
        )
        XCTAssertEqual(offlineWithCoverage, .vectorHosted(flavor: .standard))
        let offlineWithout = MapSourceResolver.resolve(
            activeVectorPackageFileURL: nil, isNetworkReachable: false, hasOfflineVectorCoverage: false, themePreset: .standard
        )
        XCTAssertFalse(offlineWithout.isVector)
    }

    func testSettingsDefaultsMatchAndroid() {
        let suite = "AutoMapTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = RideSettingsStore(defaults: defaults)
        XCTAssertTrue(settings.autoMapEnabled)
        XCTAssertEqual(settings.autoMapRadiusKm, 15)
        XCTAssertTrue(settings.autoMapCellular)
        XCTAssertEqual(AutoMapConstants.radiusOptionsKm, AutoPrefetch.shared.RADIUS_OPTIONS_KM.map { $0.intValue })
        XCTAssertEqual(AutoMapConstants.defaultRadiusKm, Int(AutoPrefetch.shared.DEFAULT_RADIUS_KM))
        XCTAssertEqual(AutoMapConstants.maxAutoPacks, Int(AutoPrefetch.shared.MAX_AUTO_ZONES))
    }
}
