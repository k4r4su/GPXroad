import XCTest
import GPXroadShared
@testable import GPXroad

/// It33 : le catalogue des repères existe des deux côtés de la frontière Swift ↔ Kotlin — l'affichage
/// (emoji, libellés traduits, sélecteurs Overpass) en Swift, les règles (reconnaissance, rayon,
/// familles) dans le module partagé. Ils doivent rester alignés catégorie par catégorie.
final class SharedRoadbookCatalogTests: XCTestCase {
    func testEverySwiftCategoryExistsInTheSharedCatalogWithTheSameFamilyAndLabel() {
        XCTAssertEqual(GPXroad.RoadbookLandmarkCategory.allCases.map(\.rawValue), GPXroadShared.LandmarkCategory.entries.map(\.key),
                       "mêmes catégories, même ordre (l'ordre est celui de la reconnaissance et de la priorité)")
        for category in GPXroad.RoadbookLandmarkCategory.allCases {
            let shared = SharedRoadbook.landmarkCategory(category)
            XCTAssertEqual(shared.key, category.rawValue)
            XCTAssertEqual(shared.group.key, category.group.rawValue, "\(category)")
            XCTAssertEqual(shared.genericLabel, category.genericLabel, "\(category)")
        }
    }

    func testTiersAndDirectionsRoundTripThroughTheBridge() {
        for tier in [GPXroad.RoadbookTier.light, .marked, .hard, .veryHard, .uTurn, .lightDirectionChange, .roundabout, .fork, .merge] {
            XCTAssertEqual(SharedRoadbook.tier(SharedRoadbook.sharedTier(tier)), tier)
        }
        for direction in [GPXroad.TurnDirection.left, .right, .straight, .uTurn] {
            XCTAssertEqual(SharedRoadbook.direction(SharedRoadbook.sharedDirection(direction)), direction)
        }
    }

    func testEveryValhallaTypeKeepsItsRawValueAcrossTheBridge() {
        for type in GPXroad.ValhallaManeuverType.allCases {
            XCTAssertEqual(Int(GPXroadShared.ValhallaManeuverType.companion.fromRawValue(rawValue: Int32(type.rawValue)).rawValue), type.rawValue)
        }
    }
}
