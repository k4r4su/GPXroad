package com.olivier.gpxroad.shared.roadbook

import com.olivier.gpxroad.shared.LatLon

/** Famille de repères : regroupement du menu Réglages ET priorité entre repères. */
enum class LandmarkGroup(val key: String) { SIGN("sign"), INFRASTRUCTURE("infrastructure"), BUILDING("building"), SERVICE("service"), OTHER("other") }

/**
 * CATALOGUE des repères du Road Book (jalon it28 : « uniquement ce que le conducteur voit ») —
 * l'ordre des entrées compte : c'est l'ordre de reconnaissance ([LandmarkCatalog.classify]) et de
 * priorité à famille égale. [key] = identifiant stable (celui des caches et réglages iOS) ;
 * [genericLabel] = clé française traduite à l'affichage, jamais affichée telle quelle.
 * Pictogrammes, libellés traduits et sélecteurs Overpass restent natifs.
 */
enum class LandmarkCategory(val key: String, val group: LandmarkGroup, val genericLabel: String, val visibilityRadiusMeters: Double) {
    CITY_SIGN("citySign", LandmarkGroup.SIGN, "Entrée d'agglomération", 30.0),
    STOP_SIGN("stopSign", LandmarkGroup.SIGN, "Stop", 20.0),
    GIVE_WAY_SIGN("giveWaySign", LandmarkGroup.SIGN, "Cédez-le-passage", 20.0),
    TRAFFIC_SIGNALS("trafficSignals", LandmarkGroup.SIGN, "Feux tricolores", 25.0),
    LEVEL_CROSSING("levelCrossing", LandmarkGroup.SIGN, "Passage à niveau", 20.0),
    SPEED_BUMP("speedBump", LandmarkGroup.INFRASTRUCTURE, "Ralentisseur", 12.0),
    BRIDGE("bridge", LandmarkGroup.INFRASTRUCTURE, "Pont", 8.0),
    TUNNEL("tunnel", LandmarkGroup.INFRASTRUCTURE, "Tunnel", 8.0),
    CHURCH("church", LandmarkGroup.BUILDING, "Église", 150.0),
    TOWN_HALL("townHall", LandmarkGroup.BUILDING, "Mairie", 60.0),
    WATER_TOWER("waterTower", LandmarkGroup.BUILDING, "Château d'eau", 200.0),
    MILL("mill", LandmarkGroup.BUILDING, "Moulin", 150.0),
    WAYSIDE_CROSS("waysideCross", LandmarkGroup.BUILDING, "Calvaire", 30.0),
    CASTLE("castle", LandmarkGroup.BUILDING, "Château", 250.0),
    FUEL("fuel", LandmarkGroup.SERVICE, "Station-service", 250.0),
    CHARGING_STATION("chargingStation", LandmarkGroup.SERVICE, "Borne de recharge", 250.0),
    PARKING("parking", LandmarkGroup.OTHER, "Parking", 40.0),
    REST_AREA("restArea", LandmarkGroup.OTHER, "Aire de repos", 80.0),
    DRINKING_WATER("drinkingWater", LandmarkGroup.OTHER, "Point d'eau", 20.0),
    RESTAURANT("restaurant", LandmarkGroup.OTHER, "Restaurant", 40.0),
    CAFE("cafe", LandmarkGroup.OTHER, "Café", 40.0),
    BAKERY("bakery", LandmarkGroup.OTHER, "Boulangerie", 30.0),
    SUPERMARKET("supermarket", LandmarkGroup.OTHER, "Supermarché", 80.0),
    PHARMACY("pharmacy", LandmarkGroup.OTHER, "Pharmacie", 30.0),
    HOTEL("hotel", LandmarkGroup.OTHER, "Hôtel", 60.0),
    CAMPSITE("campsite", LandmarkGroup.OTHER, "Camping", 150.0),
    TRAIN_STATION("trainStation", LandmarkGroup.OTHER, "Gare", 120.0),
    SCHOOL("school", LandmarkGroup.OTHER, "École", 60.0),
    CEMETERY("cemetery", LandmarkGroup.OTHER, "Cimetière", 100.0),
    MEMORIAL("memorial", LandmarkGroup.OTHER, "Monument", 30.0),
    WIND_TURBINE("windTurbine", LandmarkGroup.OTHER, "Éolienne", 500.0),
    ANTENNA("antenna", LandmarkGroup.OTHER, "Antenne", 300.0),
    LIGHTHOUSE("lighthouse", LandmarkGroup.OTHER, "Phare", 500.0),
    TOWER("tower", LandmarkGroup.OTHER, "Tour", 200.0);

    /** Un panneau ne vaut que pour le sens de circulation qu'il regarde (vu de dos : ignoré). */
    val isDirectional: Boolean
        get() = this == CITY_SIGN || this == STOP_SIGN || this == GIVE_WAY_SIGN || this == TRAFFIC_SIGNALS

    /** Posé SUR une chaussée : ne concerne le pilote que si cette chaussée est dans l'axe de sa trajectoire. */
    val requiresRoadAlignment: Boolean
        get() = isDirectional || this == LEVEL_CROSSING || this == SPEED_BUMP

    /** Activée par défaut (Réglages > Repères du Road Book) : toutes les familles sauf « Autres ». */
    val isEnabledByDefault: Boolean
        get() = group != LandmarkGroup.OTHER

    /** Traverse la chaussée : jamais de côté gauche/droite. */
    val isOnRoad: Boolean
        get() = group == LandmarkGroup.INFRASTRUCTURE || this == LEVEL_CROSSING

    companion object {
        fun fromKey(key: String): LandmarkCategory? = entries.firstOrNull { it.key == key }
    }
}

/** Côté du repère par rapport au SENS DE MARCHE. */
enum class LandmarkSide { LEFT, RIGHT }

/** Sens de circulation auquel un panneau s'applique, quand OSM le précise. */
sealed class LandmarkOrientation {
    /** `direction=forward|backward` résolu sur la route porteuse : cap de la circulation concernée. */
    data class AppliesToTravelBearing(val bearing: Double) : LandmarkOrientation()

    /** `direction=<degrés|cardinal>` : cap vers lequel le panneau FAIT FACE. */
    data class Faces(val bearing: Double) : LandmarkOrientation()
}

/** Élément OSM candidat repère — indépendant du sens de parcours. */
data class LandmarkCandidate(
    val category: LandmarkCategory,
    val label: String,
    val coordinate: LatLon,
    val orientation: LandmarkOrientation? = null,
    /** Axes des routes carrossables qui portent l'élément quand il est un nœud de chaussée. */
    val roadAxes: List<Double>? = null,
)

/** Zone bâtie traversée (anneaux EXTÉRIEURS) ; [name] seulement pour un polygone `place`. */
data class BuiltUpArea(val name: String?, val rings: List<List<LatLon>>)

enum class PlaceKind { CITY, TOWN, VILLAGE, SUBURB }

/** Localité nommée (nœud `place`). */
data class Place(val name: String, val kind: PlaceKind, val coordinate: LatLon)

/** Données de repères d'une trace (candidats + zones bâties et localités du repli « Entrée de »). */
data class LandmarkData(
    val candidates: List<LandmarkCandidate>,
    val builtUpAreas: List<BuiltUpArea> = emptyList(),
    val places: List<Place> = emptyList(),
)

/**
 * Repère affiché. [cityEntryName] non nul = entrée de localité CALCULÉE (repli it29) : le libellé
 * « Entrée de <nom> » est composé et traduit nativement ; sinon [label] = nom OSM ou clé générique.
 */
data class LandmarkInfo(
    val category: LandmarkCategory,
    val label: String,
    val side: LandmarkSide? = null,
    val lateralDistanceMeters: Double? = null,
    val cityEntryName: String? = null,
)

/** Repère en LIGNE DÉDIÉE du Road Book (entre deux changements de direction). */
data class LandmarkCheckpoint(val info: LandmarkInfo, val coordinate: LatLon, val cumulativeDistanceMeters: Double)

/**
 * Résultat de la sélection pour un parcours : repères affichés AVEC un changement de direction
 * (clé = rang de la manœuvre) et repères en ligne dédiée, dans l'ordre de la trace.
 */
data class LandmarkSelection(val attached: Map<Int, LandmarkInfo>, val standalone: List<LandmarkCheckpoint>) {
    /** [attached] en liste, dans l'ordre des manœuvres (les clés `Int` d'une Map arrivent mal en Swift). */
    val attachedList: List<AttachedLandmark>
        get() = attached.entries.sortedBy { it.key }.map { AttachedLandmark(it.key, it.value) }

    companion object {
        val EMPTY = LandmarkSelection(emptyMap(), emptyList())
    }
}

/** Repère rattaché à la manœuvre de rang [maneuverIndex]. */
data class AttachedLandmark(val maneuverIndex: Int, val info: LandmarkInfo)

/** Réglages de sélection des repères (valeurs du jalon it28). */
object LandmarkConstants {
    /** Priorité FIXE entre familles, de la plus forte à la plus faible. */
    val GROUP_PRIORITY = listOf(LandmarkGroup.SIGN, LandmarkGroup.INFRASTRUCTURE, LandmarkGroup.SERVICE, LandmarkGroup.BUILDING, LandmarkGroup.OTHER)
    const val SERVICE_MERGE_METERS = 100.0
    const val SERVICE_SHOW_DISTANCE_FROM_METERS = 30.0
    const val JUNCTION_RADIUS_METERS = 40.0
    const val MAX_PER_SEGMENT = 1
    const val MERGE_METERS = 150.0
    const val ROAD_ALIGNMENT_TOLERANCE_DEGREES = 30.0
    const val APPROACH_METERS = 30.0
    const val SIDE_MIN_OFFSET_METERS = 4.0
    const val SIGN_FACING_TOLERANCE_DEGREES = 80.0
    const val CITY_ENTRY_FALLBACK_ENABLED = true
    const val CITY_ENTRY_SAMPLE_METERS = 10.0
    const val CITY_ENTRY_MERGE_GAP_METERS = 300.0
    const val CITY_ENTRY_MIN_RUN_METERS = 150.0
    const val CITY_ENTRY_NAME_CHECK_METERS = 50.0
    const val CITY_ENTRY_NAME_MIN_STRETCH_METERS = 100.0
    const val CITY_ENTRY_PARENT_SEAT_METERS = 500.0
    const val CITY_ENTRY_SIGN_DEDUP_METERS = 400.0

    /** Portée d'un nœud `place` : il nomme une zone bâtie jusqu'à cette distance du point d'entrée. */
    fun placeReachMeters(kind: PlaceKind): Double = when (kind) {
        PlaceKind.CITY -> 5000.0
        PlaceKind.TOWN -> 3000.0
        PlaceKind.VILLAGE -> 1500.0
        PlaceKind.SUBURB -> 1500.0
    }
}

/** Classification PURE d'un élément OSM (ses tags) en repère du catalogue. */
object LandmarkCatalog {
    /** Libellés précis produits en plus des libellés génériques (clés françaises traduites à l'affichage). */
    val SPECIFIC_LABEL_KEYS = listOf("Clocher", "Chapelle", "Lieu de culte", "Oratoire")

    /** Valeurs `traffic_sign` d'un panneau d'entrée d'agglomération (préfixe, sans casse). */
    val CITY_SIGN_VALUES = listOf("city_limit", "FR:EB10", "DE:310")
    private val SPEED_BUMPS = setOf("bump", "hump", "table", "cushion")

    /** Première catégorie du catalogue qui reconnaît l'élément + libellé ; `null` = hors catalogue. */
    fun classify(tags: Map<String, String>): Pair<LandmarkCategory, String>? {
        val category = LandmarkCategory.entries.firstOrNull { matches(it, tags) } ?: return null
        return category to label(category, tags)
    }

    /** Panneau d'ENTRÉE d'agglomération — jamais le panneau de sortie (`city_limit=end`). */
    fun isCityEntrySign(tags: Map<String, String>): Boolean {
        if (tags["city_limit"] == "end") return false
        val values = listOfNotNull(tags["traffic_sign"], tags["traffic_sign:forward"], tags["traffic_sign:backward"])
            .flatMap { raw -> raw.split(';', ',').map { it.trim().lowercase() } }
        val isSign = values.any { value -> CITY_SIGN_VALUES.any { value.startsWith(it.lowercase()) } }
        return isSign || tags["highway"] == "city_limit" || tags["city_limit"] in setOf("begin", "both")
    }

    private fun matches(category: LandmarkCategory, t: Map<String, String>): Boolean = when (category) {
        LandmarkCategory.CITY_SIGN -> isCityEntrySign(t)
        LandmarkCategory.STOP_SIGN -> t["highway"] == "stop"
        LandmarkCategory.GIVE_WAY_SIGN -> t["highway"] == "give_way"
        LandmarkCategory.TRAFFIC_SIGNALS -> t["highway"] == "traffic_signals"
        LandmarkCategory.LEVEL_CROSSING -> t["railway"] == "level_crossing"
        LandmarkCategory.SPEED_BUMP -> (t["traffic_calming"] ?: "") in SPEED_BUMPS
        LandmarkCategory.BRIDGE -> t["bridge"] in setOf("yes", "viaduct") && t["highway"] != null
        LandmarkCategory.TUNNEL -> t["tunnel"] == "yes" && t["highway"] != null
        LandmarkCategory.CHURCH -> t["amenity"] == "place_of_worship" || t["building"] in setOf("church", "chapel") || t["man_made"] == "bell_tower"
        LandmarkCategory.TOWN_HALL -> t["amenity"] == "townhall"
        LandmarkCategory.WATER_TOWER -> t["man_made"] == "water_tower"
        LandmarkCategory.MILL -> t["man_made"] in setOf("windmill", "watermill")
        LandmarkCategory.WAYSIDE_CROSS -> t["historic"] in setOf("wayside_cross", "wayside_shrine")
        LandmarkCategory.CASTLE -> t["historic"] == "castle" || t["building"] == "castle"
        LandmarkCategory.FUEL -> t["amenity"] == "fuel"
        LandmarkCategory.CHARGING_STATION -> t["amenity"] == "charging_station"
        LandmarkCategory.PARKING -> t["amenity"] == "parking" && (t["access"] ?: "") !in setOf("private", "no")
        LandmarkCategory.REST_AREA -> t["highway"] in setOf("rest_area", "services")
        LandmarkCategory.DRINKING_WATER -> t["amenity"] in setOf("drinking_water", "water_point")
        LandmarkCategory.RESTAURANT -> t["amenity"] == "restaurant"
        LandmarkCategory.CAFE -> t["amenity"] == "cafe"
        LandmarkCategory.BAKERY -> t["shop"] == "bakery"
        LandmarkCategory.SUPERMARKET -> t["shop"] == "supermarket"
        LandmarkCategory.PHARMACY -> t["amenity"] == "pharmacy"
        LandmarkCategory.HOTEL -> t["tourism"] in setOf("hotel", "motel")
        LandmarkCategory.CAMPSITE -> t["tourism"] == "camp_site"
        LandmarkCategory.TRAIN_STATION -> t["railway"] in setOf("station", "halt")
        LandmarkCategory.SCHOOL -> t["amenity"] == "school"
        LandmarkCategory.CEMETERY -> t["landuse"] == "cemetery" || t["amenity"] == "grave_yard"
        LandmarkCategory.MEMORIAL -> t["historic"] in setOf("memorial", "monument")
        LandmarkCategory.WIND_TURBINE -> t["generator:source"] == "wind"
        LandmarkCategory.ANTENNA -> t["man_made"] in setOf("mast", "communications_tower") || (t["man_made"] == "tower" && t["tower:type"] == "communication")
        LandmarkCategory.LIGHTHOUSE -> t["man_made"] == "lighthouse"
        LandmarkCategory.TOWER -> t["man_made"] == "tower" && t["tower:type"] != "communication"
    }

    /**
     * Nom OSM s'il existe, sinon libellé générique PRÉCIS. Un panneau ou un aménagement de chaussée
     * n'a pas de nom propre ; un pont/tunnel routier porte le nom de SA route : seul
     * `bridge:name`/`tunnel:name` est un vrai nom d'ouvrage.
     */
    private fun label(category: LandmarkCategory, tags: Map<String, String>): String {
        val name = tags["name"]?.takeIf { it.isNotEmpty() }
        return when (category) {
            LandmarkCategory.STOP_SIGN, LandmarkCategory.GIVE_WAY_SIGN, LandmarkCategory.TRAFFIC_SIGNALS,
            LandmarkCategory.LEVEL_CROSSING, LandmarkCategory.SPEED_BUMP -> category.genericLabel
            LandmarkCategory.BRIDGE -> tags["bridge:name"] ?: category.genericLabel
            LandmarkCategory.TUNNEL -> tags["tunnel:name"] ?: category.genericLabel
            LandmarkCategory.CHURCH -> when {
                name != null -> name
                tags["man_made"] == "bell_tower" -> "Clocher"
                tags["building"] == "chapel" -> "Chapelle"
                tags["religion"].let { it != null && it != "christian" } -> "Lieu de culte"
                else -> category.genericLabel
            }
            LandmarkCategory.FUEL, LandmarkCategory.CHARGING_STATION -> name ?: tags["brand"] ?: tags["operator"] ?: category.genericLabel
            LandmarkCategory.WAYSIDE_CROSS -> name ?: if (tags["historic"] == "wayside_shrine") "Oratoire" else category.genericLabel
            else -> name ?: category.genericLabel
        }
    }
}
