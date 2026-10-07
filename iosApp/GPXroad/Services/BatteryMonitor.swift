import Foundation
import UIKit
import SwiftUI
import GPXroadShared

/// Mode longue sortie (idée du 06/10) : réglage, mêmes valeurs qu'Android (`shared/ride/LongRide`).
enum LongRideSetting: String, CaseIterable, Identifiable {
    case off, auto, always

    var id: String { rawValue }

    var label: String {
        switch self {
        case .off: return String(localized: "Désactivé", bundle: .appLanguage)
        case .auto: return String(localized: "Automatique (batterie ≤ 20 %)", bundle: .appLanguage)
        case .always: return String(localized: "Toujours", bundle: .appLanguage)
        }
    }

    var shared: LongRideMode {
        switch self {
        case .off: return .off
        case .auto: return .auto_
        case .always: return .always
        }
    }
}

/// Niveau de batterie et charge (publiés), pour le mode longue sortie et l'alerte « l'enregistrement risque d'être coupé ».
@MainActor
final class BatteryMonitor: ObservableObject {
    /// 0 à 100, `nil` si l'appareil ne le dit pas (simulateur).
    @Published private(set) var percent: Int?
    @Published private(set) var isCharging = false

    private var observers: [NSObjectProtocol] = []

    init() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        refresh()
        let center = NotificationCenter.default
        for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            })
        }
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    private func refresh() {
        let level = UIDevice.current.batteryLevel
        let newPercent = level < 0 ? nil : Int((level * 100).rounded())
        let state = UIDevice.current.batteryState
        let charging = state == .charging || state == .full
        if newPercent != percent { percent = newPercent }
        if charging != isCharging { isCharging = charging }
    }

    /// Économie active ? (téléchargements automatiques suspendus, carte à 30 images/s)
    func isLongRideActive(_ setting: LongRideSetting) -> Bool {
        LongRide.shared.isActive(mode: setting.shared, batteryPercent: percent.map { KotlinInt(int: Int32($0)) }, charging: isCharging)
    }
}

/// Alerte batterie du Ride (sortie de `RideView`, déjà à la limite du compilateur) : une fois par niveau franchi pendant un enregistrement.
struct BatteryAlertHook: ViewModifier {
    @ObservedObject var battery: BatteryMonitor
    let isRecording: Bool
    let onAlert: (Int) -> Void
    @State private var lastAlerted: Int?

    func body(content: Content) -> some View {
        content
            .onChange(of: battery.percent) { _ in evaluate() }
            .onChange(of: battery.isCharging) { _ in evaluate() }
            .onChange(of: isRecording) { _ in evaluate() }
    }

    private func evaluate() {
        // Batterie remontée (> 20 %) ou en charge : les prochaines descentes seront annoncées de nouveau.
        if battery.isCharging || (battery.percent ?? 0) > LongRide.shared.AUTO_THRESHOLD_PERCENT { lastAlerted = nil }
        let level = LongRide.shared.nextBatteryAlert(
            batteryPercent: battery.percent.map { KotlinInt(int: Int32($0)) },
            charging: battery.isCharging,
            recording: isRecording,
            lastAlerted: lastAlerted.map { KotlinInt(int: Int32($0)) }
        )
        if let level {
            lastAlerted = level.intValue
            onAlert(battery.percent ?? level.intValue)
        }
    }
}
