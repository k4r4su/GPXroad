import CoreGraphics
import Foundation
import GPXroadShared

/// Constantes du Road Book et de son export PDF (spec "roadbook-mode", it23) — domaine
/// entièrement nouveau, fichier dédié plutôt qu'ajouté à `RideConstants`/`NavigationConstants`
/// (même patron qu'`OfflineConstants`/`NavConstants` : chaque domaine porte les siennes,
/// "constantes localisables pour toute valeur sensible", demande explicite de la fiche).
enum RoadBookConstants {
    // MARK: - Lecture Assisté GPS

    /// Distance (m) après avoir ATTEINT une manœuvre pendant laquelle elle reste affichée
    /// (figée à "0 m") avant de basculer sur la suivante — fix "roadbook-live-progress-hold",
    /// retour terrain : "j'ai l'impression que la direction change 15/20 m avant le virage,
    /// j'aimerais qu'une fois arrivé à zéro, la direction reste encore 10/20 m après le virage".
    /// Root cause de l'ancien comportement (anticiper le changement 40 m AVANT d'arriver,
    /// `liveManeuverReachedRadiusMeters` ci-avant) : voir `RoadbookLiveProgress.nextManeuver`,
    /// qui bascule désormais sur la manœuvre atteinte elle-même puis la maintient cette durée —
    /// SAUF virages enchaînés (voir ce même fichier), où le maintien serait contre-productif.
    static let liveManeuverHoldAfterMeters: Double = GPXroadShared.RoadbookConstants.shared.LIVE_MANEUVER_HOLD_AFTER_METERS

    // MARK: - Overpass : instances et identifiants dans `OverpassConfiguration` (it33).

    // MARK: - Repères visibles (jalon it28 — "uniquement ce que le conducteur voit")

    /// Catégories ACTIVÉES par défaut (menu Réglages > Repères du Road Book, "Réinitialiser") —
    /// toutes les autres catégories du catalogue (`RoadbookLandmarkCategory`, famille "Autres")
    /// sont désactivées par défaut.
    /// Familles Panneaux, Infrastructure, Bâtiments et Services — règle du module partagé
    /// (`LandmarkCategory.isEnabledByDefault`, it33).
    static let landmarkDefaultEnabledCategories: Set<RoadbookLandmarkCategory> = Set(
        RoadbookLandmarkCategory.allCases.filter { SharedRoadbook.landmarkCategory($0).isEnabledByDefault }
    )
    /// Rayon de VISIBILITÉ par catégorie (m, distance à la trace) — défini dans le module partagé
    /// (`LandmarkCategory.visibilityRadiusMeters`, it33) avec toutes les règles de sélection
    /// (priorité, densité, rattachement au carrefour, côté : `LandmarkConstants`). Ici pour la
    /// requête Overpass, qui interroge ce rayon autour de la trace.
    static var landmarkVisibilityRadiusMeters: [RoadbookLandmarkCategory: Double] {
        Dictionary(uniqueKeysWithValues: RoadbookLandmarkCategory.allCases.map { ($0, SharedRoadbook.landmarkCategory($0).visibilityRadiusMeters) })
    }
    /// Priorité fixe entre familles (module partagé), de la plus forte à la plus faible.
    static var landmarkGroupPriority: [RoadbookLandmarkCategory.Group] {
        GPXroadShared.LandmarkConstants.shared.GROUP_PRIORITY.compactMap { RoadbookLandmarkCategory.Group(rawValue: $0.key) }
    }
    /// Au plus N repères en ligne dédiée par tronçon entre deux changements de direction (module partagé).
    static var landmarkMaxPerSegment: Int { Int(GPXroadShared.LandmarkConstants.shared.MAX_PER_SEGMENT) }
    /// Requête Overpass : trace échantillonnée tous les N m (au moins), plafonnée en points ; le
    /// rayon interrogé = pas + rayon de visibilité de la catégorie.
    static let landmarkQuerySampleSpacingMeters: Double = 250
    static let landmarkQueryMaxPolylinePoints = 600
    /// Téléchargement découpé en TRONÇONS de trace de cette longueur (une requête chacun) : c'est
    /// l'unité de la barre de progression, et les repères apparaissent au fur et à mesure.
    static let landmarkQueryChunkMeters: Double = 8000
    /// Débit affiché = octets reçus sur cette fenêtre glissante (lissage).
    static let landmarkProgressSpeedWindowSeconds: Double = 3
    /// Pas de débit affiché avant ça (valeur non significative au démarrage).
    static let landmarkProgressSpeedMinSeconds: Double = 1
    /// Temps restant affiché seulement après ce nombre de tronçons terminés (estimation stable).
    static let landmarkProgressMinChunksForEstimate = 1
    /// Le bandeau n'apparaît qu'après ce délai : un chargement quasi instantané ne clignote pas.
    static let landmarkProgressShowDelaySeconds: Double = 0.6
    /// Rafraîchissement du débit et du temps restant pendant le téléchargement.
    static let landmarkProgressRefreshSeconds: Double = 0.5
    /// Durée d'affichage de l'état "Terminé" avant que l'indicateur ne disparaisse.
    static let landmarkProgressDoneDisplaySeconds: Double = 2
    static let landmarkRequestTimeoutSeconds: Double = 90
    /// L'instance Overpass publique renvoie par intermittence 429/504 (mesuré au jalon it28 : un
    /// tronçon sur quatre en 504 après ~9 s, le même accepté à l'essai suivant) : nouveaux essais
    /// PAR TRONÇON après ces pauses, puis échec propre ("Réessayer" reprend à ce tronçon). Deux
    /// autres instances publiques testées (private.coffee, kumi.systems) : plus lentes, autant de
    /// 504 — pas de bascule d'instance.
    static let landmarkRetryDelaysSeconds: [Double] = [5, 15, 30]
    /// Repli "Entrée de <localité>" (it29) — ACTIF par défaut : l'entrée est placée au bord de la
    /// zone bâtie traversée, nommée d'après la localité (règles et réglages : module partagé,
    /// `CityEntryDetector`/`LandmarkConstants.CITY_ENTRY_*`).
    static var landmarkCityEntryFallbackEnabled: Bool { GPXroadShared.LandmarkConstants.shared.CITY_ENTRY_FALLBACK_ENABLED }
    /// Portée d'un nœud `place` (module partagé) — la requête Overpass des localités la couvre.
    static var landmarkCityEntryPlaceReachMeters: [RoadbookPlace.Kind: Double] {
        [.city: reach(.city), .town: reach(.town), .village: reach(.village), .suburb: reach(.suburb)]
    }
    private static func reach(_ kind: GPXroadShared.PlaceKind) -> Double {
        GPXroadShared.LandmarkConstants.shared.placeReachMeters(kind: kind)
    }
    /// La requête Overpass des nœuds `place` couvre ces portées (au-delà du pas d'échantillonnage).

    // MARK: - Export PDF

    /// A4 à 72 dpi (unité native PDFKit/UIGraphicsPDFRenderer, indépendante de l'orientation —
    /// `RoadbookPDFExporter` permute largeur/hauteur selon `PDFOrientation`).
    static let pdfPageWidthPoints: CGFloat = 595.2
    static let pdfPageHeightPoints: CGFloat = 841.8
    static let pdfMarginPoints: CGFloat = 32
    static let pdfHeaderHeightPoints: CGFloat = 64

    /// Hauteur de ligne selon la densité choisie — pilote directement le nombre de manœuvres
    /// par page (calculé depuis la hauteur de page réelle, jamais un nombre de lignes fixe codé
    /// en dur qui se désynchroniserait si la police ou les marges changent).
    static let pdfRowHeightCompact: CGFloat = 22
    static let pdfRowHeightComfortable: CGFloat = 32

    static let pdfFontSizeSmall: CGFloat = 8
    static let pdfFontSizeMedium: CGFloat = 10
    static let pdfFontSizeLarge: CGFloat = 13

    /// Largeurs de colonnes (fraction de la largeur de contenu disponible) — se répartissent le
    /// reste entre elles si une colonne optionnelle (cumulée/note) est masquée, voir
    /// `RoadbookPDFExporter.columnLayout(options:contentWidth:)`.
    static let pdfPartialColumnFraction: CGFloat = 0.16
    static let pdfCumulativeColumnFraction: CGFloat = 0.16
    static let pdfHeadingColumnFraction: CGFloat = 0.18
    // Le reste de la largeur disponible revient toujours à la colonne note (voir
    // columnLayout) — pas de fraction fixe ici, elle absorbe l'espace restant.

    /// Couleur d'accent des pictogrammes (spec : "réutiliser le style visuel du logo — chevrons,
    /// dégradé orange/rouge en accents — pas nécessairement en fond de page pour rester
    /// imprimable en niveaux de gris") — une seule teinte plate plutôt qu'un vrai dégradé par
    /// glyphe (un dégradé par petit pictogramme serait imperceptible et compliquerait le rendu
    /// PDF pour rien) ; rendu correctement en gris moyen sur une impression noir & blanc.
    /// PLUS seulement le PDF depuis it24 (point 2) — `RoadbookPictograms.swift` (écrans) réutilise
    /// EXACTEMENT la même valeur (`Color(red:green:blue:)`), un seul repère visuel dans toute
    /// l'app plutôt que deux teintes "orange" indépendantes qui pourraient dériver l'une de
    /// l'autre au fil des itérations.
    static let pdfAccentColorRGB: (red: CGFloat, green: CGFloat, blue: CGFloat) = (0.92, 0.35, 0.15)

    // Rond-point : sortie placée selon le virage réel de la trace depuis it33 (plus de convention
    // « 45° par sortie ») — voir `RoadbookRoundaboutDrawing` et `RoundaboutPictogram` (Kotlin).

    // MARK: - Palette jour/nuit (spec "roadbook-ui-redesign", it25, point 0)

    /// Repli SANS position GPS (permission refusée/pas encore de fix) — heuristique horaire
    /// simple, jamais une fausse précision astronomique qu'on ne peut pas calculer sans
    /// coordonnées. Plage large et prudente (7h-20h) plutôt que calée sur un lever/coucher moyen.
    static let paletteFallbackDayStartHour = 7
    static let paletteFallbackDayEndHour = 20
    /// Fréquence de réévaluation de la palette automatique pendant que l'écran est ouvert — le
    /// lever/coucher du soleil ne change jamais assez vite pour justifier plus fréquent, mais
    /// sans ce filet, un roadbook ouvert à cheval sur le coucher du soleil resterait figé sur la
    /// palette du moment de l'ouverture jusqu'au prochain changement de trace/onglet.
    static let paletteReevaluationIntervalSeconds: Double = 300

    // MARK: - Mode Assisté GPS : carte hero + liste (spec "roadbook-ui-redesign", it25, points 1/2)

    /// "Au moins la moitié de l'écran" (spec it23ter, réaffirmé it25) — fraction de la hauteur
    /// disponible en PORTRAIT. En paysage, hauteur FIXE bien plus compacte à la place (voir
    /// `focusedHeroLandscapeHeight`) : sur un écran deux fois moins haut, la même fraction
    /// laisserait beaucoup trop peu de place à la liste scrollable en dessous.
    static let focusedHeroHeightFraction: Double = 0.5
    static let focusedHeroMinHeight: Double = 220
    /// Hauteur FIXE de la carte hero en paysage — layout dédié horizontal (pictogramme à gauche,
    /// distance à droite), volontairement compact pour laisser de la place à la liste et ne
    /// jamais chevaucher la tab bar (retour terrain : "634 m"/"Virage prononcé" qui chevauchent").
    /// Augmentée de 170 à 210 une fois le sélecteur de mode déplacé dans une colonne à droite
    /// plutôt qu'une bande en haut (retour terrain : "donner la priorité à la direction et la
    /// distance") — la hauteur ainsi libérée revient à la carte hero plutôt que de rester
    /// inexploitée.
    static let focusedHeroLandscapeHeight: Double = 210
}
