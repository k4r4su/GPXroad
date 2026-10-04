import Foundation
import Network
import CoreTelephony

/// Source unique de vérité sur la disponibilité réseau. Utilisé par le détour (contourner
/// en ligne vs guidage direct hors-ligne) et par le pré-cache de tuiles (axes suivants) —
/// pour que le Ride en mode avion reste bien silencieux radio.
@MainActor
final class NetworkMonitor: ObservableObject {
    @Published private(set) var isReachable = false
    @Published private(set) var isExpensiveOrConstrained = false
    /// Détail du réseau pour la carte automatique (voir `AutoMapNetworkPolicy`).
    @Published private(set) var isWifiOrEthernet = false
    @Published private(set) var isCellular = false
    @Published private(set) var isLowDataMode = false

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "GPXroad.NetworkMonitor")

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.isReachable = path.status == .satisfied
                self?.isExpensiveOrConstrained = path.isExpensive || path.isConstrained
                self?.isWifiOrEthernet = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet)
                self?.isCellular = path.usesInterfaceType(.cellular)
                self?.isLowDataMode = path.isConstrained
            }
        }
        monitor.start(queue: queue)
    }

    /// Technologies radio en cours (« CTRadioAccessTechnologyLTE », « …NR », « …NRNSA »…), sans permission.
    var cellularTechnologies: [String] {
        Array((CTTelephonyNetworkInfo().serviceCurrentRadioAccessTechnology ?? [:]).values)
    }

    /// Réseau assez bon pour télécharger des cartes d'avance (carte automatique).
    func isGoodForAutoMap(allowCellular: Bool) -> Bool {
        AutoMapNetworkPolicy.isGood(
            isSatisfied: isReachable, isWifiOrEthernet: isWifiOrEthernet, isCellular: isCellular,
            isLowDataMode: isLowDataMode, cellularTechnologies: cellularTechnologies, allowCellular: allowCellular
        )
    }

    deinit {
        monitor.cancel()
    }
}
