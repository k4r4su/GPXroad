import Foundation
import CoreLocation
import GPXroadShared

/// Portion d'itinéraire dont l'accès est incertain ou interdit d'après OpenStreetMap (voir `shared/plan/TrackAccess`).
struct FlaggedSegment: Identifiable, Equatable {
    let id = UUID()
    let verdict: AccessVerdict
    let wayIDs: [Int64]
    let name: String?
    let lengthMeters: Double
    let coordinates: [CLLocationCoordinate2D]

    static func == (lhs: FlaggedSegment, rhs: FlaggedSegment) -> Bool { lhs.id == rhs.id }

    /// Point du milieu : c'est là qu'un signalement « interdit » est posé.
    var midpoint: CLLocationCoordinate2D? { coordinates.isEmpty ? nil : coordinates[coordinates.count / 2] }
}

/// Contrôle d'accès des pistes d'un itinéraire créé (06/10) : Valhalla `trace_attributes` donne les chemins OSM suivis, Overpass
/// leurs étiquettes d'accès, `TrackAccess` (commun avec Android) classe. `nil` = vérification impossible (réseau, serveur) :
/// l'appelant le dit, il ne conclut jamais « tout est autorisé ».
enum TrackAccessChecker {
    static func check(route: PlannedRoute, vehicle: PlanVehicle, configuration: ValhallaConfiguration) async -> [FlaggedSegment]? {
        var legs: [(edges: [AccessEdge], shape: [CLLocationCoordinate2D])] = []
        for encoded in route.legShapes {
            guard let leg = try? await attributes(of: encoded, vehicle: vehicle, configuration: configuration) else { return nil }
            legs.append(leg)
        }
        let allEdges = legs.flatMap(\.edges)
        let ids = TrackAccess.shared.checkedWayIds(edges: allEdges).map(\.int64Value)
        var tags: [KotlinLong: [String: String]] = [:]
        if !ids.isEmpty {
            guard let fetched = await wayTags(ids: ids) else { return nil }
            for (id, wayTags) in fetched { tags[KotlinLong(value: id)] = wayTags }
        }
        var result: [FlaggedSegment] = []
        for leg in legs {
            for segment in TrackAccess.shared.segments(edges: leg.edges, tagsByWay: tags, vehicle: vehicle) {
                let begin = max(0, Int(segment.beginShapeIndex))
                let end = min(leg.shape.count - 1, Int(segment.endShapeIndex))
                guard begin < end else { continue }
                result.append(FlaggedSegment(
                    verdict: segment.verdict,
                    wayIDs: segment.wayIds.map(\.int64Value),
                    name: segment.name,
                    lengthMeters: segment.lengthMeters,
                    coordinates: Array(leg.shape[begin...end])
                ))
            }
        }
        return result
    }

    private static func attributes(of encodedShape: String, vehicle: PlanVehicle, configuration: ValhallaConfiguration) async throws -> (edges: [AccessEdge], shape: [CLLocationCoordinate2D]) {
        guard let url = ValhallaRoutingService.endpointURL(configuration.endpointURLString, path: "trace_attributes") else { throw ValhallaRoutingError.invalidEndpoint }
        var request = URLRequest(url: url, timeoutInterval: RideConstants.valhallaRequestTimeoutSeconds)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(RoutePlanner.shared.attributesRequestBody(encodedPolyline6: encodedShape, vehicle: vehicle).utf8)
        ValhallaRoutingService.applyBasicAuth(to: &request, configuration: configuration)
        let data = try await ValhallaRoutingService.performRequest(request)
        return try parseAttributes(data)
    }

    /// Réponse `/trace_attributes` : arêtes (un chemin OSM chacune) et forme recalée dans laquelle elles sont indexées.
    static func parseAttributes(_ data: Data) throws -> (edges: [AccessEdge], shape: [CLLocationCoordinate2D]) {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawEdges = root["edges"] as? [[String: Any]],
              let encodedShape = root["shape"] as? String
        else { throw ValhallaRoutingError.noRoute }
        let edges: [AccessEdge] = rawEdges.compactMap { edge in
            guard let wayID = (edge["way_id"] as? NSNumber)?.int64Value,
                  let begin = (edge["begin_shape_index"] as? NSNumber)?.int32Value,
                  let end = (edge["end_shape_index"] as? NSNumber)?.int32Value
            else { return nil }
            return AccessEdge(
                wayId: wayID,
                use: (edge["use"] as? String) ?? "road",
                unpaved: (edge["unpaved"] as? Bool) ?? false,
                lengthMeters: ((edge["length"] as? Double) ?? 0) * 1000,   // Valhalla : kilomètres
                beginShapeIndex: begin,
                endShapeIndex: end,
                name: (edge["names"] as? [String])?.first
            )
        }
        return (edges, ValhallaRoutingService.decodePolyline6(encodedShape))
    }

    /// Étiquettes OSM des chemins demandés (Overpass : maison, adresse du propriétaire, puis instance publique).
    private static func wayTags(ids: [Int64]) async -> [Int64: [String: String]]? {
        let query = "[out:json][timeout:25];way(id:\(ids.map(String.init).joined(separator: ",")));out tags;"
        guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) else { return nil }
        let body = Data("data=\(encoded)".utf8)
        for attempt in OverpassConfiguration.attempts(formBody: body, timeout: RoadBookConstants.landmarkRequestTimeoutSeconds) {
            if let (data, response) = try? await URLSession.shared.data(for: attempt.request),
               (response as? HTTPURLResponse)?.statusCode == 200,
               let parsed = parseWayTags(data) {
                return parsed
            }
            OverpassConfiguration.reportFailure(of: attempt.kind)
        }
        return nil
    }

    static func parseWayTags(_ data: Data) -> [Int64: [String: String]]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let elements = root["elements"] as? [[String: Any]]
        else { return nil }
        var result: [Int64: [String: String]] = [:]
        for element in elements {
            guard let id = (element["id"] as? NSNumber)?.int64Value, let tags = element["tags"] as? [String: String] else { continue }
            result[id] = tags
        }
        return result
    }
}
