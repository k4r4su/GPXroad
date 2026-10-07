import Foundation
import CoreLocation

/// Un point bloqué partagé tel que renvoyé par le serveur (voir server/app.py). Distinct
/// de `BlockageEvent` (journal 100% local, par trace) — celui-ci vient (ou peut venir) de
/// la communauté et n'est jamais modifié par le client, seulement affiché et comparé.
struct SharedBlockage: Codable, Identifiable, Equatable {
    /// `blocked` : chemin impraticable (arbre, barrière…), montré dans le Ride ; `forbidden` : chemin interdit aux véhicules,
    /// signalé depuis le planificateur d'itinéraire — jamais montré comme obstacle, mais évité par les itinéraires créés.
    enum Kind: String, Codable {
        case blocked, forbidden
    }

    let id: String
    let coordinate: CLLocationCoordinate2DCodable
    let note: String?
    let createdAt: Date
    let lastConfirmedAt: Date
    let kind: Kind
    /// Identifiant OpenStreetMap du chemin, s'il est connu.
    let wayID: Int64?

    init(id: String, coordinate: CLLocationCoordinate2D, note: String?, createdAt: Date, lastConfirmedAt: Date, kind: Kind = .blocked, wayID: Int64? = nil) {
        self.id = id
        self.coordinate = CLLocationCoordinate2DCodable(coordinate)
        self.note = note
        self.createdAt = createdAt
        self.lastConfirmedAt = lastConfirmedAt
        self.kind = kind
        self.wayID = wayID
    }

    private enum CodingKeys: String, CodingKey {
        case id, lat, lon, note, kind
        case createdAt = "created_at"
        case lastConfirmedAt = "last_confirmed_at"
        case wayID = "way_id"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        let lat = try container.decode(Double.self, forKey: .lat)
        let lon = try container.decode(Double.self, forKey: .lon)
        coordinate = CLLocationCoordinate2DCodable(CLLocationCoordinate2D(latitude: lat, longitude: lon))
        note = try container.decodeIfPresent(String.self, forKey: .note)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        lastConfirmedAt = try container.decode(Date.self, forKey: .lastConfirmedAt)
        // Absents des anciens serveurs et des caches d'avant : « blocked », comportement d'avant.
        kind = (try? container.decodeIfPresent(Kind.self, forKey: .kind)) ?? .blocked
        wayID = try container.decodeIfPresent(Int64.self, forKey: .wayID)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(coordinate.latitude, forKey: .lat)
        try container.encode(coordinate.longitude, forKey: .lon)
        try container.encodeIfPresent(note, forKey: .note)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encode(lastConfirmedAt, forKey: .lastConfirmedAt)
        try container.encode(kind, forKey: .kind)
        try container.encodeIfPresent(wayID, forKey: .wayID)
    }

    /// Sécurité côté client si le cache local n'a pas resynchronisé récemment — le serveur
    /// purge déjà à 180 j (voir server/app.py), ceci ne fait qu'appliquer la même règle en
    /// local pour ne jamais afficher un point que le serveur aurait déjà supprimé.
    var ageInDaysSinceConfirmation: Double {
        Date().timeIntervalSince(lastConfirmedAt) / 86400
    }
    /// Fondu visuel (spec Bloc 5) : > 90 j sans reconfirmation.
    var isFaded: Bool { ageInDaysSinceConfirmation > SharedBlockageConstants.fadeAfterDays }
    /// > 180 j : disparition complète.
    var isExpired: Bool { ageInDaysSinceConfirmation > SharedBlockageConstants.expireAfterDays }
    var mapOpacity: Double { isFaded ? 0.45 : 1.0 }
}

/// Signalement sortant (POST /blockages) — jamais nominatif : seul un ID anonyme rotatif
/// identifie l'auteur, jamais un compte (voir AnonymousReporterID.swift).
struct SharedBlockageOutgoingReport: Encodable, Equatable {
    let lat: Double
    let lon: Double
    let note: String?
    let reporterID: String
    let kind: SharedBlockage.Kind
    let wayID: Int64?

    init(coordinate: CLLocationCoordinate2D, note: String?, reporterID: String, kind: SharedBlockage.Kind = .blocked, wayID: Int64? = nil) {
        lat = coordinate.latitude
        lon = coordinate.longitude
        self.note = note
        self.reporterID = reporterID
        self.kind = kind
        self.wayID = wayID
    }

    private enum CodingKeys: String, CodingKey {
        case lat, lon, note, kind
        case reporterID = "reporter_id"
        case wayID = "way_id"
    }
}

/// JSON (de)codeurs partagés — le serveur (Python) émet des dates ISO 8601 avec
/// microsecondes ("...651092+00:00"), qu'`ISO8601DateFormatter` par défaut ne parse pas
/// (il attend 0 ou 3 décimales). On essaie la variante à décimales, puis la variante sans,
/// avant d'échouer — couvert par SharedBlockageCodingTests.
enum SharedBlockageCoding {
    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = fractionalFormatter.date(from: string) { return date }
            if let date = plainFormatter.date(from: string) { return date }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Date ISO 8601 invalide : \(string)")
        }
        return decoder
    }()

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(fractionalFormatter.string(from: date))
        }
        return encoder
    }()

    private static let fractionalFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let plainFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()
}
