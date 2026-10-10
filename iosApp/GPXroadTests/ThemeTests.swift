import XCTest
@testable import GPXroad

/// Design (10/10) : fond de carte de nuit, choix automatique.
@MainActor
final class ThemeTests: XCTestCase {
    private func freshSettings() -> (RideSettingsStore, () -> Void) {
        let suite = "ThemeTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        return (RideSettingsStore(defaults: defaults), { defaults.removePersistentDomain(forName: suite) })
    }

    func testDefaultDesignIsClairAndAutomaticFollowsTheIPhone() {
        let (settings, cleanup) = freshSettings()
        defer { cleanup() }
        XCTAssertEqual(settings.appDesign, .clair)
        XCTAssertFalse(AppDesign.auto.palette(systemScheme: .light).isDark)
        XCTAssertTrue(AppDesign.auto.palette(systemScheme: .dark).isDark)
        XCTAssertNil(AppDesign.auto.forcedScheme)
        XCTAssertEqual(AppDesign.sombre.forcedScheme, .dark)
    }

    func testNightMapKeepsRoadsBrighterThanLandAndInvertsText() {
        let land = ColorFlavorPatcher.nightLightness(0.96, isText: false)   // fond clair du style
        let road = ColorFlavorPatcher.nightLightness(1.0, isText: false)    // route blanche
        XCTAssertGreaterThan(land, 0.25, "carte lisible : fond de carte gris moyen, pas noir")
        XCTAssertLessThan(land, 0.45, "mais toujours sombre")
        XCTAssertGreaterThan(road, land + 0.2, "les routes ressortent nettement du fond")
        XCTAssertGreaterThan(ColorFlavorPatcher.nightLightness(0.15, isText: true), 0.7, "texte sombre → texte clair")
        XCTAssertLessThan(ColorFlavorPatcher.nightLightness(1.0, isText: true), 0.15, "halo blanc → halo sombre")
        // Ordre conservé sur les surfaces : plus clair avant = plus clair après.
        XCTAssertLessThan(ColorFlavorPatcher.nightLightness(0.5, isText: false), ColorFlavorPatcher.nightLightness(0.9, isText: false))
    }

    func testNightFlavorChangesColoursButNeverTheLayout() {
        let layers: [[String: Any]] = [["id": "road", "layout": ["symbol-placement": "line"], "paint": ["line-color": "#ffffff"]]]
        let patched = ColorFlavorPatcher.apply(.nuit, toLayers: layers)
        XCTAssertNotEqual(patched[0]["paint"] as? [String: String], ["line-color": "#ffffff"])
        XCTAssertEqual((patched[0]["layout"] as? [String: String])?["symbol-placement"], "line", "la rotation des labels n'est jamais touchée")
    }
}
