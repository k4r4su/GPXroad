import Foundation

/// Liste ORDONNÉE des manœuvres d'un Road Book (spec "roadbook-mode", it23) — façade Swift du module
/// partagé (`shared/.../roadbook/RoadbookExtractor.kt`, it33). Pure : une trace (dans son sens de
/// parcours) en entrée, aucun état partagé lu ni écrit — c'est ce qui garantit le découplage Road
/// Book ↔ Ride actif (invariants it10/it22). Résultat mémorisé par parcours + réglages (voir
/// `SharedRoadbook.maneuvers`) : l'écran Road Book le relit à chaque rendu.
enum RoadbookExtractor {
    /// - Parameter mapMatchedManeuvers : manœuvres route-aware Valhalla déjà RETENUES ; `[]` =
    ///   Road Book géométrique seul (Valhalla désactivé, trace hors réseau).
    static func maneuvers(
        for track: GPXTrack,
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        lightThresholdDegrees: Double,
        markedThresholdDegrees: Double,
        hardThresholdDegrees: Double,
        veryHardThresholdDegrees: Double,
        mergeMinDistanceMeters: Double,
        mapMatchedManeuvers: [MapMatchedManeuver] = [],
        mapMatchCoverage: [ClosedRange<Double>]? = nil
    ) -> [RoadbookManeuver] {
        SharedRoadbook.maneuvers(
            for: track,
            settings: SharedRoadbook.settings(
                windowBeforeMeters: windowBeforeMeters,
                windowAfterMeters: windowAfterMeters,
                thresholds: RoadbookAnalyzer.TierThresholds(light: lightThresholdDegrees, marked: markedThresholdDegrees, hard: hardThresholdDegrees, veryHard: veryHardThresholdDegrees),
                mergeMinDistanceMeters: mergeMinDistanceMeters
            ),
            mapMatchedManeuvers: mapMatchedManeuvers,
            coverage: mapMatchCoverage
        )
    }
}
