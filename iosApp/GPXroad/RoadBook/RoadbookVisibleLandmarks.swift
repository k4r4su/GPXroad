import Foundation
import CoreLocation
import GPXroadShared

/// Un élément OSM visible, candidat repère — indépendant du sens de parcours (mis en cache par
/// trace), la sélection se fait ensuite pour le parcours affiché (`RoadbookLandmarkSelector`).
struct RoadbookLandmarkCandidate: Codable, Equatable {
    /// Sens de circulation auquel un panneau s'applique, quand OSM le précise.
    enum Orientation: Codable, Equatable {
        /// `direction=forward|backward` ou `traffic_sign:forward|backward`, résolu sur la route
        /// porteuse : cap de la circulation concernée.
        case appliesToTravelBearing(Double)
        /// `direction=<degrés|cardinal>` : cap vers lequel le panneau FAIT FACE (il regarde les
        /// usagers qui arrivent en face de lui).
        case faces(Double)
    }

    let category: RoadbookLandmarkCategory
    let label: String
    let latitude: Double
    let longitude: Double
    let orientation: Orientation?
    /// Caps (axes, sens indifférent) des routes carrossables qui portent l'élément quand il est un
    /// nœud de chaussée — `nil`/vide si inconnu (panneau posé à côté de la route) : pas de filtre.
    let roadAxes: [Double]?
    /// "node/123" — identifiant OSM, dédoublonne un élément renvoyé par deux tronçons voisins.
    let osmID: String?

    init(category: RoadbookLandmarkCategory, label: String, coordinate: CLLocationCoordinate2D, orientation: Orientation? = nil, roadAxes: [Double]? = nil, osmID: String? = nil) {
        self.category = category
        self.label = label
        latitude = coordinate.latitude
        longitude = coordinate.longitude
        self.orientation = orientation
        self.roadAxes = roadAxes
        self.osmID = osmID
    }

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}

/// Tout ce qui est récupéré (et mis en cache) pour une trace — `fetchedCategories` dit quelles
/// catégories ont DÉJÀ été téléchargées : activer une catégorie absente ne télécharge qu'elle
/// (complément), en désactiver une ne fait que filtrer (aucun réseau).
struct RoadbookLandmarkData: Codable, Equatable {
    let candidates: [RoadbookLandmarkCandidate]
    /// Repli "Entrée de <localité>" (it29) : zones bâties et localités nommées, téléchargées avec
    /// la catégorie "Entrée d'agglomération".
    let builtUpAreas: [RoadbookBuiltUpArea]
    let places: [RoadbookPlace]
    let fetchedCategories: Set<RoadbookLandmarkCategory>

    init(candidates: [RoadbookLandmarkCandidate], builtUpAreas: [RoadbookBuiltUpArea] = [], places: [RoadbookPlace] = [], fetchedCategories: Set<RoadbookLandmarkCategory> = Set(RoadbookLandmarkCategory.allCases)) {
        self.candidates = candidates
        self.builtUpAreas = builtUpAreas
        self.places = places
        self.fetchedCategories = fetchedCategories
    }

    static let empty = RoadbookLandmarkData(candidates: [], fetchedCategories: [])

    /// Ajoute des candidats (dédoublonnés par identifiant OSM) et marque `categories` comme
    /// téléchargées.
    func adding(_ other: RoadbookLandmarkData, markingFetched categories: Set<RoadbookLandmarkCategory>) -> RoadbookLandmarkData {
        var known = Set(candidates.compactMap(\.osmID))
        var merged = candidates
        for candidate in other.candidates {
            if let id = candidate.osmID {
                guard known.insert(id).inserted else { continue }
            }
            merged.append(candidate)
        }
        return RoadbookLandmarkData(
            candidates: merged,
            builtUpAreas: Self.deduplicated(builtUpAreas + other.builtUpAreas, id: \.osmID),
            places: Self.deduplicated(places + other.places, id: \.osmID),
            fetchedCategories: fetchedCategories.union(categories)
        )
    }

    /// Un élément renvoyé par deux tronçons voisins n'est gardé qu'une fois (identifiant OSM).
    private static func deduplicated<T>(_ items: [T], id: (T) -> String?) -> [T] {
        var seen = Set<String>()
        return items.filter { item in id(item).map { seen.insert($0).inserted } ?? true }
    }
}

/// Zone bâtie traversée par la route (`landuse=residential`, ou polygone `place` qui porte alors
/// directement le nom) — l'entrée dans cette zone est là où se dresse le panneau d'agglomération.
struct RoadbookBuiltUpArea: Codable, Equatable {
    let osmID: String?
    /// Nom de la localité quand la zone est un polygone `place` ; `nil` pour une zone résidentielle.
    let name: String?
    /// Anneaux EXTÉRIEURS (les trous d'une zone résidentielle n'ont pas d'importance ici).
    let rings: [[CLLocationCoordinate2DCodable]]

    init(osmID: String? = nil, name: String? = nil, rings: [[CLLocationCoordinate2D]]) {
        self.osmID = osmID
        self.name = name
        self.rings = rings.map { $0.map(CLLocationCoordinate2DCodable.init) }
    }
}

/// Localité nommée (nœud `place`) : donne son nom à une zone bâtie.
struct RoadbookPlace: Codable, Equatable {
    enum Kind: String, Codable {
        case city, town, village, suburb
    }

    let osmID: String?
    let name: String
    let kind: Kind
    let latitude: Double
    let longitude: Double

    init(osmID: String? = nil, name: String, kind: Kind, coordinate: CLLocationCoordinate2D) {
        self.osmID = osmID
        self.name = name
        self.kind = kind
        latitude = coordinate.latitude
        longitude = coordinate.longitude
    }

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}

/// Repère affiché en LIGNE DÉDIÉE du Road Book, entre deux changements de direction — jamais un
/// `Checkpoint` (type partagé avec les pins/la bannière Ride), jamais dans `RoadbookLiveProgress`.
struct RoadbookLandmarkCheckpoint: Identifiable, Hashable, Codable {
    let info: RoadbookLandmarkInfo
    let latitude: Double
    let longitude: Double
    let cumulativeDistanceMeters: Double

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
    var id: String { "landmark-\(Int((cumulativeDistanceMeters * 10).rounded()))-\(info.category.rawValue)" }
}

/// Résultat de la sélection pour un parcours : repères affichés AVEC un changement de direction
/// (clé = `RoadbookManeuver.id`) et repères en ligne dédiée.
struct RoadbookLandmarkSelection: Equatable {
    let attached: [UUID: RoadbookLandmarkInfo]
    let standalone: [RoadbookLandmarkCheckpoint]

    static let empty = RoadbookLandmarkSelection(attached: [:], standalone: [])
}

/// Sélection PURE des repères visibles le long d'une trace DÉJÀ dans son sens de parcours —
/// visibilité (rayon par catégorie, sens des panneaux, axe de la route porteuse), côté, rattachement
/// au carrefour, priorité et densité, repli « Entrée de <localité> ». Façade du module partagé
/// (`shared/.../roadbook/LandmarkSelector.kt`, it33, règles du jalon it28 documentées là-bas).
enum RoadbookLandmarkSelector {
    static func select(
        _ data: RoadbookLandmarkData,
        points: [GPXPoint],
        maneuvers: [RoadbookManeuver],
        enabledCategories: Set<RoadbookLandmarkCategory> = Set(RoadbookLandmarkCategory.allCases),
        cityEntryFallbackEnabled: Bool = RoadBookConstants.landmarkCityEntryFallbackEnabled
    ) -> RoadbookLandmarkSelection {
        let selection = GPXroadShared.LandmarkSelector.shared.select(
            data: SharedRoadbook.landmarkData(data),
            points: SharedRoadbook.latLons(points),
            maneuvers: maneuvers.map(SharedRoadbook.sharedManeuver),
            enabledCategories: Set(enabledCategories.map(SharedRoadbook.landmarkCategory)),
            cityEntryFallbackEnabled: cityEntryFallbackEnabled
        )
        var attached: [UUID: RoadbookLandmarkInfo] = [:]
        for item in selection.attachedList {
            let rank = Int(item.maneuverIndex)
            guard maneuvers.indices.contains(rank) else { continue }
            attached[maneuvers[rank].id] = SharedRoadbook.landmarkInfo(item.info)
        }
        return RoadbookLandmarkSelection(attached: attached, standalone: selection.standalone.map(SharedRoadbook.landmarkCheckpoint))
    }
}

/// Une ligne du Road Book : changement de direction OU repère visible, dans l'ordre de
/// progression le long de la trace — seule façon dont les écrans/le PDF les mêlent.
enum RoadbookEntry: Identifiable {
    case maneuver(RoadbookManeuver, index: Int)
    case landmark(RoadbookLandmarkCheckpoint)

    var id: String {
        switch self {
        case .maneuver(let maneuver, _): return "maneuver-\(maneuver.id.uuidString)"
        case .landmark(let landmark): return landmark.id
        }
    }

    var cumulativeDistanceMeters: Double {
        switch self {
        case .maneuver(let maneuver, _): return maneuver.cumulativeDistanceMeters
        case .landmark(let landmark): return landmark.cumulativeDistanceMeters
        }
    }

    /// `index` = rang de la manœuvre dans la liste des manœuvres (numérotation affichée
    /// inchangée : un repère n'est pas une manœuvre numérotée). À distance égale, la manœuvre
    /// passe avant le repère. Ordre calculé par le module partagé (`RoadbookEntry.merge`, it33).
    static func merge(maneuvers: [RoadbookManeuver], landmarks: [RoadbookLandmarkCheckpoint]) -> [RoadbookEntry] {
        let sharedLandmarks = landmarks.map(SharedRoadbook.sharedLandmarkCheckpoint)
        let landmarkIndex = Dictionary(uniqueKeysWithValues: sharedLandmarks.enumerated().map { (ObjectIdentifier($0.element), $0.offset) })
        let merged = GPXroadShared.RoadbookEntry.companion.merge(
            maneuvers: maneuvers.map(SharedRoadbook.sharedManeuver),
            landmarks: sharedLandmarks
        )
        return merged.compactMap { entry -> RoadbookEntry? in
            if let maneuver = entry as? GPXroadShared.RoadbookEntry.Maneuver {
                let index = Int(maneuver.index)
                return maneuvers.indices.contains(index) ? .maneuver(maneuvers[index], index: index) : nil
            }
            guard let landmark = entry as? GPXroadShared.RoadbookEntry.Landmark,
                  let index = landmarkIndex[ObjectIdentifier(landmark.landmark)] else { return nil }
            return .landmark(landmarks[index])
        }
    }
}
