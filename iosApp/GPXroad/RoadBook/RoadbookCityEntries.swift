import Foundation
import CoreLocation
import GPXroadShared

/// Entrée de localité calculée (repli it29) — là où la trace entre dans une zone bâtie nommée.
struct RoadbookCityEntry: Equatable {
    let name: String
    let coordinate: CLLocationCoordinate2D
    let cumulativeDistanceMeters: Double

    /// "Entrée de Ferrette", "Entrée d'Illtal" — position ESTIMÉE (bord de la zone bâtie), d'où un
    /// libellé distinct du nom seul affiché pour un vrai panneau cartographié.
    var label: String { Self.label(for: name) }

    static func label(for name: String) -> String {
        let elides = name.first.map { "AEIOUYÂÀÉÈÊËÎÏÔÖÛÜŒaeiouyâàéèêëîïôöûüœ".contains($0) } ?? false
        return elides ? String(localized: "Entrée d’\(name)", bundle: .appLanguage) : String(localized: "Entrée de \(name)", bundle: .appLanguage)
    }

    static func == (lhs: RoadbookCityEntry, rhs: RoadbookCityEntry) -> Bool {
        lhs.name == rhs.name && lhs.cumulativeDistanceMeters == rhs.cumulativeDistanceMeters
    }
}

/// Repli "Entrée de <localité>" (it29) — PUR, aucun réseau : l'entrée est placée là où la trace
/// entre dans une zone bâtie (panneaux `city_limit` rarement cartographiés, zones bâties partout),
/// nommée d'après le polygone `place` traversé ou le nœud `place` le plus proche ; un panneau
/// cartographié gagne. Façade du module partagé (`shared/.../roadbook/LandmarkSelector.kt`,
/// `CityEntryDetector`, it33, règles documentées là-bas) — appelée en production par
/// `RoadbookLandmarkSelector`, ici pour les tests et le diagnostic.
enum RoadbookCityEntryDetector {
    static func entries(
        areas: [RoadbookBuiltUpArea],
        places: [RoadbookPlace],
        mappedSigns: [(cumulative: Double, name: String)],
        points: [GPXPoint],
        cumulative: [Double]
    ) -> [RoadbookCityEntry] {
        GPXroadShared.CityEntryDetector.shared.entries(
            areas: areas.map(SharedRoadbook.builtUpArea),
            places: places.map(SharedRoadbook.place),
            mappedSigns: mappedSigns.map { KotlinPair(first: KotlinDouble(double: $0.cumulative), second: $0.name as NSString) },
            points: SharedRoadbook.latLons(points),
            cumulative: SharedRoadbook.doubleArray(cumulative)
        ).map { RoadbookCityEntry(name: $0.name, coordinate: SharedRoadbook.coordinate($0.coordinate), cumulativeDistanceMeters: $0.cumulativeDistanceMeters) }
    }

    /// Localité la plus proche du point d'entrée (quartier seulement hors de portée d'une ville ;
    /// siège d'une commune nouvelle écarté au profit de ses anciens villages).
    static func placeName(near coordinate: CLLocationCoordinate2D, places: [RoadbookPlace]) -> String? {
        GPXroadShared.CityEntryDetector.shared.placeName(coordinate: SharedRoadbook.latLon(coordinate), places: places.map(SharedRoadbook.place))
    }
}
