import Foundation
import CoreLocation
import GPXroadShared

/// Itinéraire calculé par Valhalla pour la création d'une trace à la volée (06/10).
struct PlannedRoute: Equatable {
    let points: [CLLocationCoordinate2D]
    let distanceMeters: Double
    let durationSeconds: Double
    /// Formes (polyline6) de chaque tronçon, telles que Valhalla les a renvoyées : relues par le contrôle d'accès des pistes.
    let legShapes: [String]

    static func == (lhs: PlannedRoute, rhs: PlannedRoute) -> Bool {
        lhs.distanceMeters == rhs.distanceMeters && lhs.durationSeconds == rhs.durationSeconds && lhs.points.count == rhs.points.count
    }
}

extension ValhallaRoutingService {
    /// `/route` pour tous les points posés en un seul appel, corps de requête construit par la règle commune
    /// (`shared/plan/RoutePlanner`, identique sur Android). Valhalla recale chaque tronçon sur les routes existantes.
    static func plan(
        waypoints: [CLLocationCoordinate2D],
        options: PlanOptions,
        excluding excluded: [CLLocationCoordinate2D] = [],
        configuration: ValhallaConfiguration
    ) async throws -> PlannedRoute {
        guard let url = endpointURL(configuration.endpointURLString, path: "route") else {
            throw ValhallaRoutingError.invalidEndpoint
        }
        let body = RoutePlanner.shared.requestBody(points: waypoints.map(SharedRoadbook.latLon), options: options, excludeLocations: excluded.map(SharedRoadbook.latLon))
        var request = URLRequest(url: url, timeoutInterval: RideConstants.valhallaRequestTimeoutSeconds)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(body.utf8)
        applyBasicAuth(to: &request, configuration: configuration)

        let data = try await performRequest(request)
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let trip = root["trip"] as? [String: Any],
              let legs = trip["legs"] as? [[String: Any]]
        else { throw ValhallaRoutingError.noRoute }
        let encodedShapes = legs.compactMap { $0["shape"] as? String }
        let points = encodedShapes.map { decodePolyline6($0) }.flatMap { $0 }
        guard points.count > 1 else { throw ValhallaRoutingError.noRoute }
        // Les tronçons se rejoignent au même point : un seul gardé.
        var merged: [CLLocationCoordinate2D] = []
        for point in points where !(merged.last.map { $0.latitude == point.latitude && $0.longitude == point.longitude } ?? false) {
            merged.append(point)
        }
        let summary = trip["summary"] as? [String: Any]
        let kilometers = (summary?["length"] as? Double) ?? RoutePlanner.shared.lengthMeters(points: merged.map(SharedRoadbook.latLon)) / 1000
        return PlannedRoute(points: merged, distanceMeters: kilometers * 1000, durationSeconds: (summary?["time"] as? Double) ?? 0, legShapes: encodedShapes)
    }
}
