import SwiftUI

/// Styles de sortie (maquette « Réglages par profil », 10/10) : un tap règle d'un coup les réglages qui dépendent de la façon de
/// rouler, sans toucher aux règles du Road Book (seuils d'angle validés sur le terrain). Chaque réglage reste modifiable un par un ;
/// dès qu'un réglage s'écarte du style, celui-ci s'affiche « Personnalisé ».
enum RideProfile: String, CaseIterable, Identifiable {
    case balade, trail, longueRoute

    var id: String { rawValue }

    var title: String {
        switch self {
        case .balade: return String(localized: "Balade", bundle: .appLanguage)
        case .trail: return String(localized: "Trail et pistes", bundle: .appLanguage)
        case .longueRoute: return String(localized: "Longue route", bundle: .appLanguage)
        }
    }

    var subtitle: String {
        switch self {
        case .balade: return String(localized: "Routes sinueuses, annonces calmes", bundle: .appLanguage)
        case .trail: return String(localized: "Virages précis, carte hors ligne large", bundle: .appLanguage)
        case .longueRoute: return String(localized: "Moins de bruit, batterie économisée", bundle: .appLanguage)
        }
    }

    var systemImage: String {
        switch self {
        case .balade: return "scribble.variable"
        case .trail: return "mountain.2"
        case .longueRoute: return "arrow.right"
        }
    }

    // Valeurs du style. Toujours choisies parmi les options existantes de chaque réglage.
    var flashCount: Int { self == .trail ? 5 : 3 }

    var turnMergeMeters: Double {
        switch self {
        case .balade: return 150
        case .trail: return 100
        case .longueRoute: return 250
        }
    }

    var autoMapRadiusKm: Int {
        switch self {
        case .balade: return 15
        case .trail: return 20
        case .longueRoute: return 10
        }
    }

    var longRide: LongRideSetting { self == .longueRoute ? .always : .auto }

    var recordingDensity: RecordingDensityPreset { self == .longueRoute ? .leger : .precis }

    @MainActor
    func apply(to settings: RideSettingsStore) {
        settings.flashCount = flashCount
        settings.turnMergeMinDistanceMeters = turnMergeMeters
        settings.autoMapRadiusKm = autoMapRadiusKm
        settings.autoMapCellular = true
        settings.longRideSetting = longRide
        settings.recordingDensityPreset = recordingDensity
    }

    @MainActor
    func matches(_ settings: RideSettingsStore) -> Bool {
        settings.flashCount == flashCount
            && settings.turnMergeMinDistanceMeters == turnMergeMeters
            && settings.autoMapRadiusKm == autoMapRadiusKm
            && settings.autoMapCellular
            && settings.longRideSetting == longRide
            && settings.recordingDensityPreset == recordingDensity
    }

    /// Style actuel d'après les réglages, `nil` = personnalisé.
    @MainActor
    static func current(for settings: RideSettingsStore) -> RideProfile? {
        allCases.first { $0.matches(settings) }
    }
}

/// Section du haut des Réglages : trois cartes de style + ce que le style change, en clair.
struct RideProfileSection: View {
    @EnvironmentObject private var settings: RideSettingsStore
    @Environment(\.theme) private var theme

    var body: some View {
        let current = RideProfile.current(for: settings)
        VStack(alignment: .leading, spacing: 10) {
            ForEach(RideProfile.allCases) { profile in
                let isSelected = current == profile
                Button { profile.apply(to: settings) } label: {
                    HStack(spacing: 14) {
                        Image(systemName: profile.systemImage)
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(theme.action)
                            .frame(width: 48, height: 48)
                            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(theme.tonal))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(profile.title).font(.system(size: 18, weight: .heavy)).foregroundStyle(theme.ink)
                            Text(profile.subtitle).font(.footnote).foregroundStyle(theme.inkSecondary)
                        }
                        Spacer(minLength: 0)
                        if isSelected {
                            Image(systemName: "checkmark.circle.fill").font(.title3).foregroundStyle(theme.action)
                        }
                    }
                    .padding(10)
                    .background(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(theme.surface)
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(isSelected ? theme.action : theme.hairline, lineWidth: isSelected ? 2 : 1))
                    )
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            }

            VStack(alignment: .leading, spacing: 8) {
                Text(summaryTitle(current))
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(theme.action)
                summaryRow(String(localized: "Flash avant un virage", bundle: .appLanguage), String(format: String(localized: "%lld flashs", bundle: .appLanguage), settings.flashCount))
                summaryRow(String(localized: "Fusion des virages rapprochés", bundle: .appLanguage), "\(Int(settings.turnMergeMinDistanceMeters)) m")
                summaryRow(String(localized: "Carte hors ligne autour de moi", bundle: .appLanguage), "\(settings.autoMapRadiusKm) km")
                summaryRow(String(localized: "Économie de batterie", bundle: .appLanguage), settings.longRideSetting.label)
                summaryRow(String(localized: "Densité d'enregistrement", bundle: .appLanguage), settings.recordingDensityPreset.label)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .themedCard(theme)
        }
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
        .listRowBackground(Color.clear)
    }

    private func summaryTitle(_ current: RideProfile?) -> String {
        guard let current else { return String(localized: "Personnalisé : tes réglages actuels", bundle: .appLanguage) }
        return String(format: String(localized: "Avec « %@ », ça donne :", bundle: .appLanguage), current.title)
    }

    private func summaryRow(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.subheadline).foregroundStyle(theme.ink)
            Spacer(minLength: 8)
            Text(value).font(.subheadline.weight(.bold)).foregroundStyle(theme.inkSecondary).multilineTextAlignment(.trailing)
        }
    }
}
