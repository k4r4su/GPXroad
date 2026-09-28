import Foundation

/// Instance Overpass de l'app (it33) : celle du propriétaire d'abord (adresse et Basic Auth dans
/// Réglages > Avancé > Serveur Overpass), l'instance publique en secours si elle ne répond pas.
/// Les identifiants ne partent JAMAIS vers l'instance publique. Adresse en UserDefaults (non
/// sensible), identifiants dans le Trousseau (`ValhallaKeychainStore`, service dédié).
enum OverpassConfiguration {
    static let endpointDefaultsKey = "settings.overpassEndpointURLString"
    static let keychainService = "com.olivier.gpxlibre.overpass"
    /// Instance auto-hébergée du propriétaire (mise en service le 28/09/2026 : sans limite de
    /// débit, réponses < 1 s mesurées).
    static let defaultEndpoint = "https://overpass.zim.ovh/api/interpreter"
    /// Secours : instance publique, par intermittence en 429/504 (voir `landmarkRetryDelaysSeconds`).
    static let publicEndpoint = "https://overpass-api.de/api/interpreter"

    /// Adresse de l'instance du propriétaire (réglage, sinon celle par défaut).
    static func ownEndpoint(defaults: UserDefaults = .standard) -> String {
        let value = defaults.string(forKey: endpointDefaultsKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? defaultEndpoint : value
    }

    /// Requêtes POST `data=<requête>` à essayer dans l'ordre : instance du propriétaire (avec
    /// Basic Auth si des identifiants sont enregistrés), puis instance publique (sans identifiants).
    static func requests(
        formBody: Data,
        timeout: TimeInterval,
        defaults: UserDefaults = .standard,
        keychainService: String = keychainService
    ) -> [URLRequest] {
        let own = ownEndpoint(defaults: defaults)
        var endpoints = [(own, true)]
        if own != publicEndpoint { endpoints.append((publicEndpoint, false)) }
        return endpoints.compactMap { endpoint, isOwn in
            guard let url = URL(string: endpoint) else { return nil }
            var request = URLRequest(url: url, timeoutInterval: timeout)
            request.httpMethod = "POST"
            request.setValue(MapEngineConstants.userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            if isOwn, let authorization = basicAuthorization(service: keychainService) {
                request.setValue(authorization, forHTTPHeaderField: "Authorization")
            }
            request.httpBody = formBody
            return request
        }
    }

    /// `/api/status` de l'instance du propriétaire (test de connexion dans Réglages).
    static func statusRequest(endpoint: String, username: String, password: String) -> URLRequest? {
        guard let url = URL(string: endpoint.replacingOccurrences(of: "/interpreter", with: "/status")) else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue(MapEngineConstants.userAgent, forHTTPHeaderField: "User-Agent")
        if !username.isEmpty || !password.isEmpty {
            request.setValue("Basic " + Data("\(username):\(password)".utf8).base64EncodedString(), forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private static func basicAuthorization(service: String) -> String? {
        let username = ValhallaKeychainStore.username(service: service)
        let password = ValhallaKeychainStore.password(service: service)
        guard !username.isEmpty || !password.isEmpty else { return nil }
        return "Basic " + Data("\(username):\(password)".utf8).base64EncodedString()
    }
}
