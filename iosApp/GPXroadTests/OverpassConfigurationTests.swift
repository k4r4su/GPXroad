import XCTest
@testable import GPXroad

/// It33 : Overpass auto-hébergé du propriétaire d'abord (Basic Auth), public en secours — les
/// identifiants ne partent JAMAIS vers l'instance publique. Trousseau et réglages isolés du vrai.
final class OverpassConfigurationTests: XCTestCase {
    private let service = "OverpassConfigurationTests.\(UUID().uuidString)"
    private var defaults: UserDefaults!
    private var suite = ""

    override func setUp() {
        suite = "OverpassConfigurationTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        ValhallaKeychainStore.clear(service: service)
        defaults.removePersistentDomain(forName: suite)
    }

    private func requests() -> [URLRequest] {
        OverpassConfiguration.requests(formBody: Data("data=x".utf8), timeout: 5, defaults: defaults, keychainService: service)
    }

    func testTheOwnersInstanceComesFirstThenThePublicOne() {
        XCTAssertEqual(requests().map { $0.url?.absoluteString }, [OverpassConfiguration.defaultEndpoint, OverpassConfiguration.publicEndpoint])
        XCTAssertTrue(requests().allSatisfy { $0.httpMethod == "POST" && $0.httpBody == Data("data=x".utf8) })
    }

    func testCredentialsAreSentOnlyToTheOwnersInstance() throws {
        ValhallaKeychainStore.save(username: "olivier", password: "secret", service: service)
        let all = requests()
        XCTAssertEqual(all[0].value(forHTTPHeaderField: "Authorization"), "Basic " + Data("olivier:secret".utf8).base64EncodedString())
        XCTAssertNil(all[1].value(forHTTPHeaderField: "Authorization"), "jamais d'identifiants vers l'instance publique")
    }

    func testNoAuthorizationWithoutCredentials() {
        XCTAssertTrue(requests().allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == nil })
    }

    func testACustomEndpointReplacesTheDefaultOne() {
        defaults.set(" https://overpass.example.org/api/interpreter ", forKey: OverpassConfiguration.endpointDefaultsKey)
        XCTAssertEqual(requests().first?.url?.absoluteString, "https://overpass.example.org/api/interpreter")
        XCTAssertEqual(OverpassConfiguration.statusRequest(endpoint: "https://overpass.example.org/api/interpreter", username: "", password: "")?.url?.absoluteString, "https://overpass.example.org/api/status")
    }
}
