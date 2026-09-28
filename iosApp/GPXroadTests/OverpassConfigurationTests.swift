import XCTest
@testable import GPXroad

/// It33 bis : serveur Overpass du propriétaire — maison (réseau local, sans auth, Wi-Fi seulement),
/// puis adresse publique (Basic Auth), puis instance publique OSM en secours, qui ne reçoit JAMAIS
/// les identifiants. Trousseau, réglages et identifiants FICTIFS isolés du vrai.
final class OverpassConfigurationTests: XCTestCase {
    private let service = "OverpassConfigurationTests.\(UUID().uuidString)"
    private let lan = "http://192.0.2.10:8003/api/interpreter"
    private let own = "https://overpass.example.org/api/interpreter"
    private var defaults: UserDefaults!
    private var suite = ""

    override func setUp() {
        suite = "OverpassConfigurationTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        OverpassConfiguration.resetLANAvailability()
    }

    override func tearDown() {
        ValhallaKeychainStore.clear(service: service)
        defaults.removePersistentDomain(forName: suite)
        OverpassConfiguration.resetLANAvailability()
    }

    private func configure(enabled: Bool = true, lan: String? = nil, own: String? = nil) {
        defaults.set(enabled, forKey: OverpassConfiguration.enabledDefaultsKey)
        defaults.set(lan ?? self.lan, forKey: OverpassConfiguration.lanEndpointDefaultsKey)
        defaults.set(own ?? self.own, forKey: OverpassConfiguration.endpointDefaultsKey)
    }

    private func attempts(now: Date = Date()) -> [OverpassConfiguration.Attempt] {
        OverpassConfiguration.attempts(formBody: Data("data=x".utf8), timeout: 60, defaults: defaults, keychainService: service, now: now)
    }

    func testDisabledUsesOnlyThePublicInstance() {
        configure(enabled: false)
        XCTAssertEqual(attempts().map(\.kind), [.publicFallback])
        XCTAssertEqual(attempts().first?.request.url?.absoluteString, OverpassConfiguration.publicFallbackEndpoint)
    }

    func testEnabledTriesHomeThenOwnThenPublic() {
        configure()
        let all = attempts()
        XCTAssertEqual(all.map(\.kind), [.lan, .own, .publicFallback])
        XCTAssertEqual(all.map { $0.request.url?.absoluteString }, [lan, own, OverpassConfiguration.publicFallbackEndpoint])
        XCTAssertTrue(all.allSatisfy { $0.request.httpMethod == "POST" && $0.request.httpBody == Data("data=x".utf8) })
    }

    func testTheHomeServerIsWiFiOnlyWithAShortTimeoutAndNoCredentials() {
        configure()
        ValhallaKeychainStore.save(username: "fake-user", password: "fake-pass", service: service)
        let home = attempts()[0].request
        XCTAssertFalse(home.allowsCellularAccess, "l'adresse de la maison n'existe pas sur le réseau mobile")
        XCTAssertEqual(home.timeoutInterval, OverpassConfiguration.lanTimeoutSeconds)
        XCTAssertNil(home.value(forHTTPHeaderField: "Authorization"))
    }

    func testCredentialsGoOnlyToTheOwnersPublicAddress() {
        configure()
        ValhallaKeychainStore.save(username: "fake-user", password: "fake-pass", service: service)
        let all = attempts()
        XCTAssertEqual(all[1].request.value(forHTTPHeaderField: "Authorization"), "Basic " + Data("fake-user:fake-pass".utf8).base64EncodedString())
        XCTAssertNil(all[2].request.value(forHTTPHeaderField: "Authorization"), "jamais d'identifiants vers l'instance publique OSM")
    }

    func testNoAuthorizationWithoutCredentials() {
        configure()
        XCTAssertTrue(attempts().allSatisfy { $0.request.value(forHTTPHeaderField: "Authorization") == nil })
    }

    func testAFailingHomeServerIsSetAsideForAFewMinutes() {
        configure()
        let now = Date()
        OverpassConfiguration.reportFailure(of: .lan, now: now)
        XCTAssertEqual(attempts(now: now.addingTimeInterval(60)).map(\.kind), [.own, .publicFallback])
        XCTAssertEqual(attempts(now: now.addingTimeInterval(OverpassConfiguration.lanRetryAfterFailureSeconds + 1)).map(\.kind), [.lan, .own, .publicFallback])
        OverpassConfiguration.reportFailure(of: .own, now: now)
        XCTAssertEqual(attempts(now: now.addingTimeInterval(OverpassConfiguration.lanRetryAfterFailureSeconds + 1)).first?.kind, .lan, "seul le réseau local est mis de côté")
    }

    func testEmptyAddressesAreSkippedAndNothingIsHardCoded() {
        configure(lan: "  ", own: "")
        XCTAssertEqual(attempts().map(\.kind), [.publicFallback], "aucune adresse du propriétaire par défaut")
        XCTAssertNil(OverpassConfiguration.statusRequest(endpoint: "", username: "", password: ""))
        XCTAssertEqual(OverpassConfiguration.statusRequest(endpoint: own, username: "", password: "")?.url?.absoluteString, "https://overpass.example.org/api/status")
    }
}
