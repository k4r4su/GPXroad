import Foundation
import CoreTelephony
import GPXroadShared

/// Carte automatique autour de soi (retour terrain du 04/10) — réglages communs, mêmes valeurs
/// qu'Android (`shared/offline/AutoPrefetch`, relues ici plutôt que redéfinies).
enum AutoMapConstants {
    static let radiusOptionsKm: [Int] = [10, 15, 20]
    static let defaultRadiusKm = 15
    /// Zones automatiques gardées (la courante + les deux précédentes).
    static let maxAutoPacks = 3
    /// Cache ambiant (tuiles vues en roulant) : 200 Mo au lieu de 50, comme Android.
    static let ambientCacheBytes: UInt = 200 * 1024 * 1024
    /// Style de référence du téléchargement : « Liberty » publié par OpenFreeMap, dont dérive le style embarqué —
    /// mêmes adresses de tuiles, glyphes et icônes (vérifié le 01/10), donc tout ce qui est téléchargé sert la carte.
    static let referenceStyleURL = URL(string: "https://tiles.openfreemap.org/styles/liberty")!
    /// Au-delà de cette distance à la trace suivie, on ne télécharge que le disque (pas de couloir devant).
    static let maxDistanceToTrackMeters = 3_000.0
    /// Un téléchargement qui dure plus de 20 min est abandonné (réseau perdu en route).
    static let stuckAfterSeconds: TimeInterval = 20 * 60
    static let minZoom = 5.0
    /// Couloir d'une trace : niveaux 10 à 14, comme le téléchargement manuel et Android (constante partagée).
    static let corridorMinZoom = Double(GPXroadShared.OfflineConstants.shared.CORRIDOR_MIN_ZOOM)
    static let maxZoom = 14.0
}

/// Réseau « bon » pour télécharger d'avance : Wi-Fi/Ethernet, ou (si permis) données mobiles 4G/5G —
/// jamais en mode économie de données. Fonction pure, testée ; iOS n'expose pas l'itinérance sans API
/// dépréciée (écart assumé avec Android, qui l'exclut).
enum AutoMapNetworkPolicy {
    static func isGood(
        isSatisfied: Bool,
        isWifiOrEthernet: Bool,
        isCellular: Bool,
        isLowDataMode: Bool,
        cellularTechnologies: [String],
        allowCellular: Bool
    ) -> Bool {
        guard isSatisfied, !isLowDataMode else { return false }
        if isWifiOrEthernet { return true }
        guard allowCellular, isCellular else { return false }
        return cellularTechnologies.contains(where: isFastCellular)
    }

    /// 4G (LTE) ou 5G (NR, NRNSA) ; 2G/3G jamais.
    static func isFastCellular(_ technology: String) -> Bool {
        technology == CTRadioAccessTechnologyLTE
            || technology == CTRadioAccessTechnologyNR
            || technology == CTRadioAccessTechnologyNRNSA
    }
}
