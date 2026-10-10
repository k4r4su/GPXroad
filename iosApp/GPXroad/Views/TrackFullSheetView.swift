import SwiftUI

/// Fiche complète (spec "biblio-track-fullsheet", it13) — terrain : "Tap sur une ligne trace
/// = fiche complète". Contenu volontairement minimal (nom + longueur + infos dispo, rien de
/// plus) : 3 actions, pas une page de détail/carte — ça reste le rôle de TrackSettingsView
/// (sens/apparence/chevrons), ouvert ici via "Paramètres". Les swipes Biblio (Supprimer/
/// Renommer à droite, Paramétrer à gauche) restent des raccourcis inchangés — cette fiche est
/// un second point d'entrée vers les MÊMES actions, pas un remplacement.
struct TrackFullSheetView: View {
    let track: GPXTrack
    let isFullyOffline: Bool
    let isActive: Bool
    /// Spec "biblio-share-export"/"biblio-share-export-filename" (it19) : copie temporaire
    /// nommée d'après le titre de la trace (voir `LibraryStore.exportURL(for:)`), contenu
    /// identique octet pour octet au fichier stocké — passée par l'appelant plutôt que
    /// recalculée ici, cette vue n'ayant pas accès à `LibraryStore` autrement.
    let shareURL: URL
    let onDelete: () -> Void
    let onRename: () -> Void
    let onConfigure: () -> Void
    /// « Démarrer » : active la trace puis ouvre le Ride (fourni par la Bibliothèque).
    let onStart: () -> Void

    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var showDeleteConfirmation = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    TrackMiniMap(points: track.points)
                        .frame(height: 170)

                    Text(track.name)
                        .font(.title2.bold())
                        .foregroundStyle(theme.ink)

                    HStack(spacing: 6) {
                        if isActive { StatusPill(text: String(localized: "Active", bundle: .appLanguage), kind: .active) }
                        TrackReadinessBadge(track: track, isActive: true)
                        if isFullyOffline { StatusPill(text: String(localized: "Hors-ligne OK", bundle: .appLanguage), kind: .ready, systemImage: "checkmark.seal.fill") }
                    }

                    HStack(spacing: 10) {
                        StatItem(title: String(localized: "Distance", bundle: .appLanguage), value: String(format: "%.1f km", track.totalDistanceKm))
                        StatItem(title: String(localized: "Dénivelé +", bundle: .appLanguage), value: String(format: "%.0f m", track.elevationGainMeters))
                        StatItem(title: String(localized: "Points", bundle: .appLanguage), value: "\(track.pointCount)")
                    }

                    // « Prêt à partir ? » : ce qui est en local, et « Préparer maintenant ».
                    TrackReadinessSection(track: track)
                        .padding(.vertical, 14)
                        .themedCard(theme)

                    // Démarrer : active la trace (avec la confirmation habituelle pendant une sortie) et ouvre le Ride.
                    Button(action: onStart) {
                        Label("Démarrer", systemImage: "play.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryPillButtonStyle())

                    // Spec "track-geek-metrics" (it21) : replié par défaut, approfondissement OPT-IN.
                    if let metrics = TrackMetricsCalculator.compute(for: track.points) {
                        DisclosureGroup("Statistiques avancées") {
                            geekMetricsGrid(metrics)
                                .padding(.top, 8)
                        }
                        .font(.subheadline)
                        .tint(theme.inkSecondary)
                    } else {
                        Text("Statistiques avancées indisponibles — cette trace n'a pas d'horodatage exploitable (import externe sans temps réel).")
                            .font(.caption)
                            .foregroundStyle(theme.inkSecondary)
                    }

                    // Spec "biblio-share-export" (it19) : le partage système propose déjà « Enregistrer dans Fichiers ».
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                        Button { onConfigure() } label: {
                            Label("Paramètres", systemImage: "slider.horizontal.3").frame(maxWidth: .infinity)
                        }
                        ShareLink(item: shareURL) {
                            Label("Partager / Exporter", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                        }
                        Button { onRename() } label: {
                            Label("Renommer", systemImage: "pencil").frame(maxWidth: .infinity)
                        }
                        Button(role: .destructive) { showDeleteConfirmation = true } label: {
                            Label("Supprimer", systemImage: "trash").frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(TonalPillButtonStyle())
                    .font(.subheadline)
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
            .background(theme.ground.ignoresSafeArea())
            .navigationTitle("Trace")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }
                }
            }
            .confirmationDialog(
                "Supprimer « \(track.name) » ?",
                isPresented: $showDeleteConfirmation,
                titleVisibility: .visible
            ) {
                Button("Supprimer", role: .destructive) { onDelete() }
                Button("Annuler", role: .cancel) {}
            } message: {
                Text("Cette action est irréversible.")
            }
        }
    }

    private func geekMetricsGrid(_ metrics: TrackMetrics) -> some View {
        let columns = [GridItem(.flexible()), GridItem(.flexible())]
        return LazyVGrid(columns: columns, spacing: 16) {
            StatItem(title: String(localized: "Durée totale", bundle: .appLanguage), value: Self.durationText(metrics.durationSeconds))
            StatItem(title: String(localized: "Dont en mouvement", bundle: .appLanguage), value: Self.durationText(metrics.movingDurationSeconds))
            StatItem(title: String(localized: "Vitesse moyenne", bundle: .appLanguage), value: Self.speedText(metrics.averageSpeedKmh))
            StatItem(title: String(localized: "Moyenne en mouvement", bundle: .appLanguage), value: Self.speedText(metrics.averageMovingSpeedKmh))
            StatItem(title: String(localized: "Vitesse max", bundle: .appLanguage), value: Self.speedText(metrics.maxSpeedKmh))
            StatItem(title: String(localized: "Pente max", bundle: .appLanguage), value: String(format: "%.0f %%", metrics.maxGradePercent))
            StatItem(title: String(localized: "Dénivelé −", bundle: .appLanguage), value: String(format: "%.0f m", metrics.elevationLossMeters))
            StatItem(title: String(localized: "Altitude min/max", bundle: .appLanguage), value: "\(Int(metrics.minElevationMeters.rounded()))–\(Int(metrics.maxElevationMeters.rounded())) m")
        }
    }

    private static func durationText(_ seconds: Double) -> String {
        let minutes = Int((seconds / 60).rounded())
        guard minutes >= 60 else { return "\(minutes) min" }
        return "\(minutes / 60) h \(minutes % 60) min"
    }

    private static func speedText(_ kmh: Double) -> String {
        String(format: "%.0f km/h", kmh)
    }
}

private struct StatItem: View {
    let title: String
    let value: String
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(theme.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(title)
                .font(.caption)
                .foregroundStyle(theme.inkSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .themedCard(theme, cornerRadius: 16)
    }
}
