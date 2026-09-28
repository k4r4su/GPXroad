import Foundation

/// Instances Overpass de l'app (it33 bis, même structure que Valhalla : toggle, adresses, Basic Auth
/// en Trousseau, test de connexion — Réglages > Avancé > Serveur Overpass). Ordre d'essai quand le
/// serveur du propriétaire est activé :
/// 1. réseau de la maison (HTTP, sans authentification) — en Wi-Fi seulement, écarté quelques
///    minutes après un échec (hors de chez soi, l'adresse locale ne répond pas) ;
/// 2. adresse publique du propriétaire (HTTPS, Basic Auth) ;
/// 3. instance publique OSM, en secours — JAMAIS avec les identifiants du propriétaire.
/// Désactivé : l'instance publique seule. Rien de sensible dans le code : adresses vides par défaut
/// (UserDefaults, `RideSettingsStore`), identifiants saisis dans les Réglages, stockés dans le
/// Trousseau (`ValhallaKeychainStore`, service dédié).
enum OverpassConfiguration {
    static let enabledDefaultsKey = "settings.overpassEnabled"
    static let endpointDefaultsKey = "settings.overpassEndpointURLString"
    static let lanEndpointDefaultsKey = "settings.overpassLANEndpointURLString"
    static let keychainService = "com.olivier.gpxlibre.overpass"
    /// Secours : instance publique OSM, par intermittence en 429/504 (`landmarkRetryDelaysSeconds`).
    static let publicFallbackEndpoint = "https://overpass-api.de/api/interpreter"
    /// Réseau local : réponse attendue en moins d'une seconde (0,17 s mesuré) — au-delà, on passe.
    static let lanTimeoutSeconds: TimeInterval = 4
    /// Après un échec du réseau local, il n'est plus essayé pendant ce temps.
    static let lanRetryAfterFailureSeconds: TimeInterval = 300

    enum Kind: Equatable {
        case lan, own, publicFallback
    }

    struct Attempt {
        let kind: Kind
        let request: URLRequest
    }

    /// Requêtes POST `data=<requête>` à essayer dans l'ordre (voir en tête).
    static func attempts(
        formBody: Data,
        timeout: TimeInterval,
        defaults: UserDefaults = .standard,
        keychainService: String = keychainService,
        now: Date = Date()
    ) -> [Attempt] {
        var attempts: [Attempt] = []
        if defaults.bool(forKey: enabledDefaultsKey) {
            if let lan = url(defaults, lanEndpointDefaultsKey), isLANAvailable(now: now) {
                var request = post(lan, body: formBody, timeout: min(timeout, lanTimeoutSeconds))
                // Jamais par le réseau mobile : l'adresse de la maison n'y existe pas (échec immédiat).
                request.allowsCellularAccess = false
                attempts.append(Attempt(kind: .lan, request: request))
            }
            if let own = url(defaults, endpointDefaultsKey) {
                var request = post(own, body: formBody, timeout: timeout)
                if let authorization = basicAuthorization(service: keychainService) {
                    request.setValue(authorization, forHTTPHeaderField: "Authorization")
                }
                attempts.append(Attempt(kind: .own, request: request))
            }
        }
        if let fallback = URL(string: publicFallbackEndpoint) {
            attempts.append(Attempt(kind: .publicFallback, request: post(fallback, body: formBody, timeout: timeout)))
        }
        return attempts
    }

    /// À appeler quand une requête a échoué : le réseau local est écarté quelques minutes.
    static func reportFailure(of kind: Kind, now: Date = Date()) {
        guard kind == .lan else { return }
        lock.lock()
        lanUnavailableUntil = now.addingTimeInterval(lanRetryAfterFailureSeconds)
        lock.unlock()
    }

    /// Réinitialise l'écart du réseau local (test de connexion réussi, tests unitaires).
    static func resetLANAvailability() {
        lock.lock()
        lanUnavailableUntil = nil
        lock.unlock()
    }

    /// `/api/status` d'une instance (test de connexion dans les Réglages).
    static func statusRequest(endpoint: String, username: String, password: String) -> URLRequest? {
        let trimmed = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed.replacingOccurrences(of: "/interpreter", with: "/status")) else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue(MapEngineConstants.userAgent, forHTTPHeaderField: "User-Agent")
        if !username.isEmpty || !password.isEmpty {
            request.setValue(authorizationValue(username: username, password: password), forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private static let lock = NSLock()
    private static var lanUnavailableUntil: Date?

    private static func isLANAvailable(now: Date) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let until = lanUnavailableUntil else { return true }
        return now >= until
    }

    private static func url(_ defaults: UserDefaults, _ key: String) -> URL? {
        let value = defaults.string(forKey: key)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? nil : URL(string: value)
    }

    private static func post(_ url: URL, body: Data, timeout: TimeInterval) -> URLRequest {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.setValue(MapEngineConstants.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        return request
    }

    private static func basicAuthorization(service: String) -> String? {
        let username = ValhallaKeychainStore.username(service: service)
        let password = ValhallaKeychainStore.password(service: service)
        guard !username.isEmpty || !password.isEmpty else { return nil }
        return authorizationValue(username: username, password: password)
    }

    private static func authorizationValue(username: String, password: String) -> String {
        "Basic " + Data("\(username):\(password)".utf8).base64EncodedString()
    }
}

/// Dernière instance Overpass ayant répondu (Réglages > Serveur Overpass) — même rôle que
/// `RoutingActivityMonitor` pour Valhalla : confirmer à l'œil que le serveur de la maison, ou le
/// serveur public du propriétaire, répond vraiment.
@MainActor
final class OverpassActivityMonitor: ObservableObject {
    static let shared = OverpassActivityMonitor()

    struct Event: Equatable {
        let kind: OverpassConfiguration.Kind
        let date: Date
    }

    @Published private(set) var lastEvent: Event?

    func record(_ kind: OverpassConfiguration.Kind, at date: Date = Date()) {
        lastEvent = Event(kind: kind, date: date)
    }
}
