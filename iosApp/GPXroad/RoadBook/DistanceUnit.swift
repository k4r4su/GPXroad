import Foundation
import GPXroadShared

/// Unité de distance du Road Book et de son export PDF (spec "roadbook-mode", it23, point 2)
/// — DISTINCT de `SpeedUnit` (vitesses uniquement, voir `SpeedUnit.swift`) : aucune unité de
/// distance globale n'existait ailleurs dans l'app avant cette feature (RideStatsPanel etc.
/// restent en km, explicitement hors périmètre de `SpeedUnit`, voir son commentaire de tête).
/// Introduite ici, scopée au Road Book — ne change rien à l'affichage des distances existant
/// ailleurs dans l'app.
enum DistanceUnit: String, CaseIterable, Identifiable, Codable {
    case km, mi

    var id: String { rawValue }
    var label: String { self == .km ? "km" : "mi" }

    private static let kmToMiles = 0.621371

    func value(fromMeters meters: Double) -> Double {
        let km = meters / 1000
        return self == .km ? km : km * Self.kmToMiles
    }

    /// Formatage court adapté à une colonne étroite (écran ou PDF) : en mètres si la distance
    /// reste sous 1 km (reste précis sur des manœuvres rapprochées), sinon 1 décimale.
    func displayString(fromMeters meters: Double) -> String {
        if self == .km, meters < 1000 {
            return "\(Int(meters.rounded())) m"
        }
        return String(format: "%.1f %@", value(fromMeters: meters), label)
    }

    /// Distance EN DIRECT jusqu'au prochain virage / repère / point de reprise (demande du
    /// propriétaire, 29/09) : paliers ronds de `DistanceCountdown` (Kotlin partagé) — … 2 km,
    /// 1,5 km, 1 km, 900 m … 200 m, 150 m, 100 m, 90 m … 10 m, 0 m — pour que le chiffre ne
    /// change pas à chaque fix GPS. Séparateur décimal de la langue de l'app.
    func countdownString(fromMeters meters: Double) -> String {
        if self == .km {
            let stepped = DistanceCountdown.shared.steppedMeters(rawMeters: meters)
            if stepped < 1000 { return "\(Int(stepped)) m" }
            return "\(Self.countdownNumber(stepped / 1000)) km"
        }
        return "\(Self.countdownNumber(DistanceCountdown.shared.steppedMiles(rawMiles: value(fromMeters: meters)))) mi"
    }

    private static func countdownNumber(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(0...1)).locale(AppLanguageBundle.locale))
    }
}
