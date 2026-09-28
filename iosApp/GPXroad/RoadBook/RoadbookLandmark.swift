import Foundation
import GPXroadShared

/// CATALOGUE des repères du Road Book — seule liste de ce qui peut apparaître (jalon it28,
/// "repères = uniquement ce que le conducteur voit"). Principe produit non négociable : un repère
/// n'apparaît que si le pilote peut le VOIR en roulant (panneau, infrastructure, bâtiment ou
/// ouvrage remarquable, service identifiable) ; jamais une frontière abstraite ni une donnée
/// administrative. Tout élément OSM hors catalogue est ignoré.
///
/// Chaque catégorie déclare AU MÊME ENDROIT (`definition`) sa famille, son libellé, son
/// pictogramme, ses sélecteurs Overpass et sa règle de reconnaissance — ajouter une catégorie =
/// un `case` + une entrée dans `definition` + son rayon dans `RoadBookConstants`. Activation par
/// défaut : `RoadBookConstants.landmarkDefaultEnabledCategories` ; choix de l'utilisateur :
/// Réglages > Repères du Road Book (`RideSettingsStore.roadbookLandmarkCategories`).
///
/// Rond-point et mini rond-point volontairement ABSENTS : ce sont déjà des manœuvres (palier
/// `.roundabout`), jamais dupliqués en repère. Passage piéton RETIRÉ (it28) : trop fréquent en
/// agglomération, bruit plus que repère.
enum RoadbookLandmarkCategory: String, Codable, Equatable, CaseIterable, Identifiable {
    // Panneaux
    case citySign, stopSign, giveWaySign, trafficSignals, levelCrossing
    // Infrastructure
    case speedBump, bridge, tunnel
    // Bâtiments et ouvrages remarquables
    case church, townHall, waterTower, mill, waysideCross, castle
    // Services
    case fuel, chargingStation
    // Autres (désactivées par défaut)
    case parking, restArea, drinkingWater, restaurant, cafe, bakery, supermarket, pharmacy, hotel,
         campsite, trainStation, school, cemetery, memorial, windTurbine, antenna, lighthouse, tower

    var id: String { rawValue }

    /// Famille : regroupement du menu Réglages ET priorité entre repères
    /// (`RoadBookConstants.landmarkGroupPriority`).
    enum Group: String, Codable, CaseIterable, Identifiable {
        case sign, infrastructure, building, service, other

        var id: String { rawValue }

        var label: String {
            switch self {
            case .sign: return String(localized: "Panneaux", bundle: .appLanguage)
            case .infrastructure: return String(localized: "Infrastructure", bundle: .appLanguage)
            case .building: return String(localized: "Bâtiments", bundle: .appLanguage)
            case .service: return String(localized: "Services", bundle: .appLanguage)
            case .other: return String(localized: "Autres", bundle: .appLanguage)
            }
        }

        var categories: [RoadbookLandmarkCategory] { RoadbookLandmarkCategory.allCases.filter { $0.group == self } }
    }

    struct Definition {
        let group: Group
        let genericLabel: String
        /// Emoji Unicode natif — rendu direct dans `Text` (SwiftUI) ET `NSString.draw` (PDF).
        let emoji: String
        /// Filtres Overpass QL (type d'élément + filtres de tags), sans la clause `around` —
        /// ajoutée par `RoadbookLandmarkOverpassService.query` avec le rayon de la catégorie.
        let overpassSelectors: [String]
    }

    var definition: Definition {
        switch self {
        case .citySign:
            let values = RoadbookLandmark.citySignValues.joined(separator: "|")
            return Definition(group: .sign, genericLabel: "Entrée d'agglomération", emoji: "🏘️",
                              overpassSelectors: [
                                "node[\"traffic_sign\"~\"\(values)\",i]", "node[\"traffic_sign:forward\"~\"\(values)\",i]",
                                "node[\"traffic_sign:backward\"~\"\(values)\",i]", "node[\"highway\"=\"city_limit\"]",
                                "node[\"city_limit\"~\"^(begin|both)$\"]",
                              ])
        case .stopSign:
            return Definition(group: .sign, genericLabel: "Stop", emoji: "🛑", overpassSelectors: ["node[\"highway\"=\"stop\"]"])
        case .giveWaySign:
            return Definition(group: .sign, genericLabel: "Cédez-le-passage", emoji: "🔻", overpassSelectors: ["node[\"highway\"=\"give_way\"]"])
        case .trafficSignals:
            return Definition(group: .sign, genericLabel: "Feux tricolores", emoji: "🚦", overpassSelectors: ["node[\"highway\"=\"traffic_signals\"]"])
        case .levelCrossing:
            return Definition(group: .sign, genericLabel: "Passage à niveau", emoji: "🚂", overpassSelectors: ["node[\"railway\"=\"level_crossing\"]"])
        case .speedBump:
            return Definition(group: .infrastructure, genericLabel: "Ralentisseur", emoji: "〰️",
                              overpassSelectors: ["node[\"traffic_calming\"~\"^(bump|hump|table|cushion)$\"]"])
        case .bridge:
            return Definition(group: .infrastructure, genericLabel: "Pont", emoji: "🌉",
                              overpassSelectors: ["way[\"highway\"][\"bridge\"~\"^(yes|viaduct)$\"]"])
        case .tunnel:
            return Definition(group: .infrastructure, genericLabel: "Tunnel", emoji: "🚇",
                              overpassSelectors: ["way[\"highway\"][\"tunnel\"=\"yes\"]"])
        case .church:
            return Definition(group: .building, genericLabel: "Église", emoji: "⛪",
                              overpassSelectors: ["nwr[\"amenity\"=\"place_of_worship\"]", "nwr[\"building\"~\"^(church|chapel)$\"]", "nwr[\"man_made\"=\"bell_tower\"]"])
        case .townHall:
            return Definition(group: .building, genericLabel: "Mairie", emoji: "🏛️", overpassSelectors: ["nwr[\"amenity\"=\"townhall\"]"])
        case .waterTower:
            return Definition(group: .building, genericLabel: "Château d'eau", emoji: "💧", overpassSelectors: ["nwr[\"man_made\"=\"water_tower\"]"])
        case .mill:
            return Definition(group: .building, genericLabel: "Moulin", emoji: "🌬️",
                              overpassSelectors: ["nwr[\"man_made\"~\"^(windmill|watermill)$\"]"])
        case .waysideCross:
            return Definition(group: .building, genericLabel: "Calvaire", emoji: "✝️",
                              overpassSelectors: ["nwr[\"historic\"~\"^(wayside_cross|wayside_shrine)$\"]"])
        case .castle:
            return Definition(group: .building, genericLabel: "Château", emoji: "🏰",
                              overpassSelectors: ["nwr[\"historic\"=\"castle\"]", "nwr[\"building\"=\"castle\"]"])
        case .fuel:
            return Definition(group: .service, genericLabel: "Station-service", emoji: "⛽", overpassSelectors: ["nwr[\"amenity\"=\"fuel\"]"])
        case .chargingStation:
            return Definition(group: .service, genericLabel: "Borne de recharge", emoji: "🔌", overpassSelectors: ["nwr[\"amenity\"=\"charging_station\"]"])
        case .parking:
            return Definition(group: .other, genericLabel: "Parking", emoji: "🅿️",
                              overpassSelectors: ["nwr[\"amenity\"=\"parking\"]"])
        case .restArea:
            return Definition(group: .other, genericLabel: "Aire de repos", emoji: "🚻",
                              overpassSelectors: ["nwr[\"highway\"~\"^(rest_area|services)$\"]"])
        case .drinkingWater:
            return Definition(group: .other, genericLabel: "Point d'eau", emoji: "🚰",
                              overpassSelectors: ["node[\"amenity\"~\"^(drinking_water|water_point)$\"]"])
        case .restaurant:
            return Definition(group: .other, genericLabel: "Restaurant", emoji: "🍽️", overpassSelectors: ["nwr[\"amenity\"=\"restaurant\"]"])
        case .cafe:
            return Definition(group: .other, genericLabel: "Café", emoji: "☕", overpassSelectors: ["nwr[\"amenity\"=\"cafe\"]"])
        case .bakery:
            return Definition(group: .other, genericLabel: "Boulangerie", emoji: "🥖", overpassSelectors: ["nwr[\"shop\"=\"bakery\"]"])
        case .supermarket:
            return Definition(group: .other, genericLabel: "Supermarché", emoji: "🛒", overpassSelectors: ["nwr[\"shop\"=\"supermarket\"]"])
        case .pharmacy:
            return Definition(group: .other, genericLabel: "Pharmacie", emoji: "💊", overpassSelectors: ["nwr[\"amenity\"=\"pharmacy\"]"])
        case .hotel:
            return Definition(group: .other, genericLabel: "Hôtel", emoji: "🏨",
                              overpassSelectors: ["nwr[\"tourism\"~\"^(hotel|motel)$\"]"])
        case .campsite:
            return Definition(group: .other, genericLabel: "Camping", emoji: "⛺", overpassSelectors: ["nwr[\"tourism\"=\"camp_site\"]"])
        case .trainStation:
            return Definition(group: .other, genericLabel: "Gare", emoji: "🚉",
                              overpassSelectors: ["nwr[\"railway\"~\"^(station|halt)$\"]"])
        case .school:
            return Definition(group: .other, genericLabel: "École", emoji: "🏫", overpassSelectors: ["nwr[\"amenity\"=\"school\"]"])
        case .cemetery:
            return Definition(group: .other, genericLabel: "Cimetière", emoji: "🪦",
                              overpassSelectors: ["nwr[\"landuse\"=\"cemetery\"]", "nwr[\"amenity\"=\"grave_yard\"]"])
        case .memorial:
            return Definition(group: .other, genericLabel: "Monument", emoji: "🎖️",
                              overpassSelectors: ["nwr[\"historic\"~\"^(memorial|monument)$\"]"])
        case .windTurbine:
            return Definition(group: .other, genericLabel: "Éolienne", emoji: "🌀", overpassSelectors: ["nwr[\"generator:source\"=\"wind\"]"])
        case .antenna:
            return Definition(group: .other, genericLabel: "Antenne", emoji: "📡",
                              overpassSelectors: ["nwr[\"man_made\"~\"^(mast|communications_tower)$\"]", "nwr[\"man_made\"=\"tower\"][\"tower:type\"=\"communication\"]"])
        case .lighthouse:
            return Definition(group: .other, genericLabel: "Phare", emoji: "🔦", overpassSelectors: ["nwr[\"man_made\"=\"lighthouse\"]"])
        case .tower:
            return Definition(group: .other, genericLabel: "Tour", emoji: "🗼",
                              overpassSelectors: ["nwr[\"man_made\"=\"tower\"]"])
        }
    }

    var group: Group { definition.group }
    /// Clé française stable (stockée dans les données de repères et le cache) — pour l'AFFICHAGE,
    /// `localizedGenericLabel`.
    var genericLabel: String { definition.genericLabel }
    var localizedGenericLabel: String { L10n.dynamic(genericLabel) }
    var emoji: String { definition.emoji }
    var isEnabledByDefault: Bool { RoadBookConstants.landmarkDefaultEnabledCategories.contains(self) }

    /// Posé SUR une chaussée : ne concerne le pilote que si cette chaussée est dans l'axe de sa
    /// trajectoire (règle du module partagé ; ici pour la requête Overpass, qui charge alors la
    /// géométrie des routes porteuses).
    var requiresRoadAlignment: Bool { SharedRoadbook.landmarkCategory(self).requiresRoadAlignment }
    /// Un panneau ne vaut que pour le sens de circulation qu'il regarde (module partagé).
    var isDirectional: Bool { SharedRoadbook.landmarkCategory(self).isDirectional }
}

/// Côté du repère par rapport au SENS DE MARCHE.
enum RoadbookLandmarkSide: String, Codable, Equatable {
    case left, right

    var label: String { self == .left ? String(localized: "à gauche", bundle: .appLanguage) : String(localized: "à droite", bundle: .appLanguage) }
}

/// Repère affiché (à côté d'un virage, ou en ligne dédiée) — catégorie (pictogramme), libellé
/// (nom OSM s'il existe, sinon libellé générique), côté quand il est déductible, et pour un
/// service la distance à la trace (il peut être un peu en retrait : détour à prévoir).
struct RoadbookLandmarkInfo: Codable, Equatable, Hashable {
    let category: RoadbookLandmarkCategory
    let label: String
    let side: RoadbookLandmarkSide?
    let lateralDistanceMeters: Double?

    init(category: RoadbookLandmarkCategory, label: String, side: RoadbookLandmarkSide? = nil, lateralDistanceMeters: Double? = nil) {
        self.category = category
        self.label = label
        self.side = side
        self.lateralDistanceMeters = lateralDistanceMeters
    }

    /// "à droite, 120 m" — distance seulement pour un service en retrait de la route.
    var sideDescription: String? {
        let distance = lateralDistanceMeters.map { "\(Int(($0 / 10).rounded()) * 10) m" }
        switch (side?.label, distance) {
        case let (side?, distance?): return "\(side), \(distance)"
        case let (side?, nil): return side
        case let (nil, distance?): return String(localized: "à \(distance)", bundle: .appLanguage)
        case (nil, nil): return nil
        }
    }

    /// Libellé traduit : les libellés génériques ("Pont", "Chapelle") sont des clés françaises,
    /// un nom propre OSM reste tel quel.
    var localizedLabel: String { L10n.dynamic(label) }

    /// "Église Saint-Martin à droite" — le côté seulement s'il est connu.
    var displayLabel: String {
        sideDescription.map { "\(localizedLabel) \($0)" } ?? localizedLabel
    }
}

/// Classification PURE d'un élément OSM en repère du catalogue — `nil` = hors catalogue (ou
/// panneau de SORTIE d'agglomération). Règles de reconnaissance et libellés précis dans le module
/// partagé (`shared/.../roadbook/RoadbookLandmarks.kt`, `LandmarkCatalog`, it33). Aucun réseau ici,
/// voir `RoadbookLandmarkOverpassService`.
enum RoadbookLandmark {
    /// Libellés précis produits en plus des libellés génériques (clés françaises traduites à
    /// l'affichage, voir `L10n.dynamic`).
    static var specificLabelKeys: [String] { GPXroadShared.LandmarkCatalog.shared.SPECIFIC_LABEL_KEYS }

    /// Valeurs de `traffic_sign` d'un panneau d'entrée d'agglomération (sélecteurs Overpass).
    static var citySignValues: [String] { GPXroadShared.LandmarkCatalog.shared.CITY_SIGN_VALUES }

    /// Panneau d'ENTRÉE d'agglomération — jamais le panneau de sortie (`city_limit=end`).
    static func isCityEntrySign(_ tags: [String: String]) -> Bool {
        GPXroadShared.LandmarkCatalog.shared.isCityEntrySign(tags: tags)
    }

    /// Catégorie (première du catalogue qui reconnaît l'élément) + libellé affiché (nom OSM, ou clé
    /// générique précise).
    static func classify(_ tags: [String: String]) -> (category: RoadbookLandmarkCategory, label: String)? {
        guard let result = GPXroadShared.LandmarkCatalog.shared.classify(tags: tags),
              let category = result.first, let label = result.second
        else { return nil }
        return (SharedRoadbook.landmarkCategory(category), label as String)
    }
}
