import XCTest
import CoreLocation
import GPXroadShared
@testable import GPXroad

/// Vraies réponses : `trace_attributes` (Valhalla, Vosges, moto) et étiquettes OSM de ces chemins (Overpass) — les mêmes que
/// `TrackAccessCheckerTest` côté Android. Plus le type « interdit » de la base partagée.
@MainActor
final class TrackAccessCheckerTests: XCTestCase {
    private let attributesJSON = #"""
{"edges": [{"way_id": 50316888, "use": "track", "unpaved": true, "length": 0.614, "begin_shape_index": 0, "end_shape_index": 47, "names": ["Neuer Kastelbergweg"]}, {"way_id": 1423063848, "use": "track", "unpaved": true, "length": 0.043, "begin_shape_index": 47, "end_shape_index": 49, "names": ["Neuer Kastelbergweg"]}, {"way_id": 1423063847, "use": "track", "unpaved": true, "length": 0.023, "begin_shape_index": 49, "end_shape_index": 51, "names": ["Neuer Kastelbergweg"]}, {"way_id": 617016644, "use": "track", "unpaved": true, "length": 0.752, "begin_shape_index": 51, "end_shape_index": 75, "names": ["Neuer Kastelbergweg"]}, {"way_id": 204998511, "use": "track", "unpaved": true, "length": 0.025, "begin_shape_index": 75, "end_shape_index": 77, "names": null}, {"way_id": 204998511, "use": "track", "unpaved": true, "length": 0.01, "begin_shape_index": 77, "end_shape_index": 78, "names": null}, {"way_id": 320090055, "use": "track", "unpaved": true, "length": 0.047, "begin_shape_index": 78, "end_shape_index": 83, "names": null}, {"way_id": 496693658, "use": "track", "unpaved": true, "length": 0.496, "begin_shape_index": 83, "end_shape_index": 106, "names": null}, {"way_id": 342773761, "use": "road", "unpaved": false, "length": 0.094, "begin_shape_index": 106, "end_shape_index": 110, "names": ["Route des Cr\u00eates", "D 430"]}], "shape": "mdtqzAgq{jL_DrG_D~G_C|F_BbFeAjEo@~EgA~KOjEChDNzL?tT?vRIvK]hJ]zJ?`FXrIj@bJJzE?zEOpHo@hHaEjZeAvHyA`JyAlGeClI}AbEcDxGmDzFwD`FgD`FaDzFwBpFkBrFmBlHmDpNgDpKsAdFkAxIcApM{@lHsBvKw@fE]|EIfDLpEX|HIfY?hHKrHF`Mb@nHpBpZ|@jT^zPDnOy@hc@_@~OaAbPeB~MiA`LUpNqAxLyBjNwCtKaFlKuPnTwRr]k]xt@u\\~r@_MvY_N|ZaPn^mG|NyEhIeBxBmCjDkAz@{A@cGwBmIsD_@EoAQcMg@cKyAaKgAwHDuJUiRm@mGs@yH_BgJuDaGiDme@uZwUmQwHeIsEkHsDwCkEkB{M{CgJwBkFaByIaG{CK}@nB{FiH{GkLqJ{ScF_M"}
"""#
    private let tagsJSON = #"""
{"elements": [{"type": "way", "id": 50316888, "tags": {"foot": "yes", "highway": "track", "motor_vehicle": "destination", "name": "Neuer Kastelbergweg", "sac_scale": "hiking", "surface": "gravel", "tracktype": "grade2"}}, {"type": "way", "id": 204998511, "tags": {"foot": "yes", "grade": "2", "highway": "track", "sac_scale": "hiking", "surface": "gravel", "tracktype": "grade2"}}, {"type": "way", "id": 320090055, "tags": {"foot": "yes", "highway": "track", "lit": "no", "sac_scale": "hiking", "surface": "gravel", "tracktype": "grade2", "trailblazed": "symbols"}}, {"type": "way", "id": 496693658, "tags": {"foot": "yes", "highway": "track", "lit": "no", "sac_scale": "hiking", "surface": "gravel", "tracktype": "grade2"}}, {"type": "way", "id": 617016644, "tags": {"foot": "yes", "highway": "track", "motor_vehicle": "destination", "name": "Neuer Kastelbergweg", "sac_scale": "hiking", "surface": "gravel", "tracktype": "grade2"}}, {"type": "way", "id": 1423063847, "tags": {"foot": "yes", "highway": "track", "motor_vehicle": "destination", "name": "Neuer Kastelbergweg", "sac_scale": "hiking", "surface": "gravel", "tracktype": "grade2"}}, {"type": "way", "id": 1423063848, "tags": {"destination:foot:backward": "Auberge du Kastelberg", "foot": "yes", "highway": "track", "motor_vehicle": "destination", "name": "Neuer Kastelbergweg", "sac_scale": "hiking", "surface": "gravel", "tracktype": "grade2"}}]}
"""#

    func testEdgesAreDecodedWithTheirOsmWayAndShape() throws {
        let (edges, shape) = try TrackAccessChecker.parseAttributes(Data(attributesJSON.utf8))
        XCTAssertEqual(edges.count, 9)
        XCTAssertTrue(edges.contains { $0.use == "track" } && edges.contains { $0.use == "road" })
        XCTAssertTrue(edges.contains { $0.lengthMeters > 100 }, "longueur en mètres (Valhalla donne des km)")
        XCTAssertTrue(edges.allSatisfy { Int($0.endShapeIndex) < shape.count }, "indices dans la forme")
    }

    func testWayTagsAreReadByOsmID() throws {
        let tags = try XCTUnwrap(TrackAccessChecker.parseWayTags(Data(tagsJSON.utf8)))
        XCTAssertEqual(tags[50316888]?["motor_vehicle"], "destination")
        XCTAssertEqual(tags[204998511]?["highway"], "track")
    }

    func testRealTracksAreFlaggedNeverCalledAllowed() throws {
        let (edges, _) = try TrackAccessChecker.parseAttributes(Data(attributesJSON.utf8))
        let rawTags = try XCTUnwrap(TrackAccessChecker.parseWayTags(Data(tagsJSON.utf8)))
        var tags: [KotlinLong: [String: String]] = [:]
        for (id, value) in rawTags { tags[KotlinLong(value: id)] = value }
        let segments = TrackAccess.shared.segments(edges: edges, tagsByWay: tags, vehicle: .motorcycle)
        XCTAssertFalse(segments.isEmpty)
        XCTAssertTrue(segments.allSatisfy { $0.verdict != .ok })
        XCTAssertTrue(segments.contains { $0.verdict == .restricted && $0.name == "Neuer Kastelbergweg" })
        XCTAssertTrue(segments.contains { $0.verdict == .toVerify })
    }

    func testSharedBlockageKindDefaultsToBlockedForOldServersAndCaches() throws {
        let old = #"{"id":"a","lat":47.5,"lon":7.5,"note":null,"created_at":"2026-10-01T10:00:00+00:00","last_confirmed_at":"2026-10-01T10:00:00+00:00"}"#
        let blockage = try SharedBlockageCoding.decoder.decode(SharedBlockage.self, from: Data(old.utf8))
        XCTAssertEqual(blockage.kind, .blocked)
        XCTAssertNil(blockage.wayID)
        let new = #"{"id":"b","lat":47.5,"lon":7.5,"note":null,"kind":"forbidden","way_id":50316888,"created_at":"2026-10-01T10:00:00+00:00","last_confirmed_at":"2026-10-01T10:00:00+00:00"}"#
        let forbidden = try SharedBlockageCoding.decoder.decode(SharedBlockage.self, from: Data(new.utf8))
        XCTAssertEqual(forbidden.kind, .forbidden)
        XCTAssertEqual(forbidden.wayID, 50316888)
    }

    func testForbiddenReportIsKeptLocallyEvenWithoutServerAndExcludedFromPlanning() {
        let suite = "TrackAccessTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let coordinator = SharedBlockageSyncCoordinator(store: SharedBlockageStore(directoryOverride: directory), defaults: defaults)
        let point = CLLocationCoordinate2D(latitude: 47.55, longitude: 7.55)
        coordinator.reportForbidden(coordinate: point, wayID: 42, serverURLString: "", isReachable: false, isEnabled: true)
        XCTAssertEqual(coordinator.blockages.count, 1, "enregistré sans serveur")
        XCTAssertTrue(coordinator.blockedOnly.isEmpty, "jamais montré comme obstacle dans le Ride")
        XCTAssertEqual(coordinator.exclusionLocations(inside: (47, 7, 48, 8)).count, 1, "mais évité par les itinéraires créés")
        XCTAssertTrue(coordinator.exclusionLocations(inside: (10, 10, 11, 11)).isEmpty, "hors zone : ignoré")
    }
}
