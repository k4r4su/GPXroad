import SwiftUI
import GPXroadShared

/// Pastille « Prête hors ligne » / « Incomplète » d'une trace (préparation de la trace, 06/10).
struct TrackReadinessBadge: View {
    let track: GPXTrack
    let isActive: Bool
    @EnvironmentObject private var preparer: TrackPreparer
    @EnvironmentObject private var settings: RideSettingsStore
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var trackRideSettings: TrackRideSettingsStore

    var body: some View {
        // Toujours dans le sens de parcours choisi pour CETTE trace, active ou non : le recalage Valhalla est mémorisé par sens,
        // une trace inversée serait sinon jugée « incomplète » dès qu'elle n'est plus active.
        let shown = track.reordered(using: trackRideSettings.settings(for: track.id))
        let state = preparer.readiness(for: shown, settings: settings)
        if !isActive && state.level == .none {
            EmptyView()
        } else if preparer.preparingTrackID == track.id {
            HStack(spacing: 4) {
                ProgressView().controlSize(.mini)
                Text("Préparation…")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        } else {
            switch state.level {
            case .ready:
                Label("Prête hors ligne", systemImage: "checkmark.seal.fill")
                    .font(.caption2)
                    .foregroundStyle(.green)
            case .partial:
                Label("Incomplète", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            default:
                Label("Non préparée", systemImage: "icloud.and.arrow.down")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// Section de la fiche d'une trace : ce qui est en local, et « Préparer maintenant ».
struct TrackReadinessSection: View {
    let track: GPXTrack
    @EnvironmentObject private var preparer: TrackPreparer
    @EnvironmentObject private var settings: RideSettingsStore
    @EnvironmentObject private var networkMonitor: NetworkMonitor
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var trackRideSettings: TrackRideSettingsStore

    private var shown: GPXTrack {
        track.reordered(using: trackRideSettings.settings(for: track.id))
    }

    private func name(_ part: ReadinessPart) -> String {
        switch part {
        case .map: return String(localized: "carte du couloir", bundle: .appLanguage)
        case .landmarks: return String(localized: "repères", bundle: .appLanguage)
        case .routeMatch: return String(localized: "recalage des virages", bundle: .appLanguage)
        default: return String(localized: "ronds-points", bundle: .appLanguage)
        }
    }

    var body: some View {
        let state = preparer.readiness(for: shown, settings: settings)
        let preparing = preparer.preparingTrackID == track.id
        VStack(alignment: .leading, spacing: 8) {
            Text("Préparation hors ligne")
                .font(.headline)
            if state.level == .ready {
                Text("Tout est en local : carte du couloir, repères, recalage des virages et ronds-points.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text(String(format: String(localized: "Manque : %@", bundle: .appLanguage), state.missing.map(name).joined(separator: ", ")))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button {
                    preparer.prepare(track: shown, settings: settings, network: networkMonitor, force: true)
                } label: {
                    Label("Préparer maintenant", systemImage: "icloud.and.arrow.down")
                }
                .buttonStyle(.bordered)
                .disabled(preparing || !networkMonitor.isReachable)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
    }
}
