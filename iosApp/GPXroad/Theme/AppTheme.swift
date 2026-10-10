import SwiftUI

/// Les trois designs au choix (Réglages > Apparence) : Clair (défaut, gris et bleu façon iOS), Sombre (nuit), Forêt (nature).
/// Maquettes validées le 10/10, palettes ajustées le 10/10 (Clair en nuances de gris) ; mêmes noms et couleurs sur Android.
enum AppDesign: String, CaseIterable, Identifiable {
    case clair, sombre, foret

    var id: String { rawValue }

    var label: String {
        switch self {
        case .foret: return String(localized: "Forêt", bundle: .appLanguage)
        case .clair: return String(localized: "Clair", bundle: .appLanguage)
        case .sombre: return String(localized: "Sombre", bundle: .appLanguage)
        }
    }

    var palette: ThemePalette {
        switch self {
        case .foret: return .foret
        case .clair: return .clair
        case .sombre: return .sombre
        }
    }
}

extension Color {
    /// `Color(hex: 0x1F6B4A)` — sRGB, sans transparence.
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// Jeu de couleurs d'un design. Règles communes : la trace est toujours orange (jamais du texte), la position bleue,
/// « bloqué » rouge ; le texte courant garde un contraste d'au moins 4,5 pour 1.
struct ThemePalette {
    /// Fond des écrans.
    let ground: Color
    /// Cartes et panneaux.
    let surface: Color
    /// Fond d'une sélection ou d'un bouton discret.
    let tonal: Color
    /// Texte discret sur `tonal` ou `ground`.
    let onTonal: Color
    let ink: Color
    let inkSecondary: Color
    let hairline: Color
    /// Boutons principaux, onglet actif, interrupteurs.
    let action: Color
    let onAction: Color
    /// Trace sur la carte (graphisme uniquement).
    let trace: Color
    /// Position de l'utilisateur.
    let position: Color
    let danger: Color
    let onDanger: Color
    let warning: Color
    /// Pastilles d'état : prête / incomplète / neutre.
    let readyBackground: Color
    let readyText: Color
    let incompleteBackground: Color
    let incompleteText: Color
    let neutralBackground: Color
    let neutralText: Color
    let isDark: Bool
    /// Police arrondie (Forêt) ou standard.
    let rounded: Bool

    static let foret = ThemePalette(
        ground: Color(hex: 0xF3F6EF), surface: Color(hex: 0xFFFFFF), tonal: Color(hex: 0xDCEFE2), onTonal: Color(hex: 0x1F6B4A),
        ink: Color(hex: 0x1C2A22), inkSecondary: Color(hex: 0x5B6B60), hairline: Color(hex: 0xE1E8DE),
        action: Color(hex: 0x1F6B4A), onAction: .white, trace: Color(hex: 0xF26B1D), position: Color(hex: 0x2F80C8),
        danger: Color(hex: 0xD2453D), onDanger: .white, warning: Color(hex: 0xE6A21A),
        readyBackground: Color(hex: 0xDCEFE2), readyText: Color(hex: 0x17583B),
        incompleteBackground: Color(hex: 0xFBEED0), incompleteText: Color(hex: 0x7A5200),
        neutralBackground: Color(hex: 0xE7EDE4), neutralText: Color(hex: 0x4A5A4F),
        isDark: false, rounded: true
    )

    static let clair = ThemePalette(
        ground: Color(hex: 0xF2F2F7), surface: Color(hex: 0xFFFFFF), tonal: Color(hex: 0xE3ECFA), onTonal: Color(hex: 0x0A66D6),
        ink: Color(hex: 0x1C1C1E), inkSecondary: Color(hex: 0x636366), hairline: Color(hex: 0xD1D1D6),
        action: Color(hex: 0x0A66D6), onAction: .white, trace: Color(hex: 0xF26B1D), position: Color(hex: 0x0A84FF),
        danger: Color(hex: 0xD93A32), onDanger: .white, warning: Color(hex: 0xE6A21A),
        readyBackground: Color(hex: 0xE1F3E6), readyText: Color(hex: 0x1C6B3A),
        incompleteBackground: Color(hex: 0xFDF0D5), incompleteText: Color(hex: 0x7A5200),
        neutralBackground: Color(hex: 0xE5E5EA), neutralText: Color(hex: 0x48484A),
        isDark: false, rounded: false
    )

    static let sombre = ThemePalette(
        ground: Color(hex: 0x101312), surface: Color(hex: 0x1B201E), tonal: Color(hex: 0x262B29), onTonal: Color(hex: 0xD6DBD8),
        ink: Color(hex: 0xF2F4F3), inkSecondary: Color(hex: 0x9AA39F), hairline: Color(hex: 0x2A302E),
        action: Color(hex: 0xFFB547), onAction: Color(hex: 0x1A1300), trace: Color(hex: 0xFF7A2F), position: Color(hex: 0x5AC8FA),
        danger: Color(hex: 0xFF5A52), onDanger: Color(hex: 0x1A0503), warning: Color(hex: 0xFFB547),
        readyBackground: Color(hex: 0x1F3A2B), readyText: Color(hex: 0x8FE0B0),
        incompleteBackground: Color(hex: 0x3A2E12), incompleteText: Color(hex: 0xFFD27A),
        neutralBackground: Color(hex: 0x262B29), neutralText: Color(hex: 0xB7BFBB),
        isDark: true, rounded: false
    )
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue = ThemePalette.clair
}

extension EnvironmentValues {
    var theme: ThemePalette {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

/// Applique un design à toute une hiérarchie : palette dans l'environnement, teinte, schéma clair/sombre, police.
struct ThemedRoot: ViewModifier {
    let design: AppDesign

    func body(content: Content) -> some View {
        let palette = design.palette
        content
            .environment(\.theme, palette)
            .tint(palette.action)
            .preferredColorScheme(palette.isDark ? .dark : .light)
            .modifier(RoundedFontIfNeeded(rounded: palette.rounded))
    }
}

private struct RoundedFontIfNeeded: ViewModifier {
    let rounded: Bool

    func body(content: Content) -> some View {
        if rounded, #available(iOS 16.1, *) {
            content.fontDesign(.rounded)
        } else {
            content
        }
    }
}

// MARK: - Boutons

/// Bouton principal : pastille de 56 pt, couleur d'action (« Démarrer », « Enregistrer »).
struct PrimaryPillButtonStyle: ButtonStyle {
    @Environment(\.theme) private var theme
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 18, weight: .heavy))
            .foregroundStyle(theme.onAction)
            .padding(.horizontal, 28)
            .frame(minHeight: 56)
            .background(Capsule().fill(theme.action).opacity(isEnabled ? 1 : 0.4))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Bouton discret : fond tonal (« Préparer », « Partager »).
struct TonalPillButtonStyle: ButtonStyle {
    @Environment(\.theme) private var theme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .heavy))
            .foregroundStyle(theme.onTonal)
            .padding(.horizontal, 24)
            .frame(minHeight: 52)
            .background(Capsule().fill(theme.tonal))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Bouton d'alerte (« Bloqué »).
struct DangerPillButtonStyle: ButtonStyle {
    @Environment(\.theme) private var theme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .heavy))
            .foregroundStyle(theme.onDanger)
            .padding(.horizontal, 20)
            .frame(minHeight: 56)
            .background(Capsule().fill(theme.danger))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Commande de carte (+, −, pause…) : carré arrondi de 56 pt.
struct MapControlButtonStyle: ButtonStyle {
    @Environment(\.theme) private var theme
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 22, weight: .bold))
            .foregroundStyle(prominent ? theme.onAction : theme.action)
            .frame(width: 56, height: 56)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(prominent ? theme.action : theme.surface))
            .shadow(color: .black.opacity(theme.isDark ? 0.5 : 0.18), radius: 7, y: 3)
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - Cartes et pastilles

extension View {
    /// Carte du design : fond de surface, coins arrondis, filet discret.
    func themedCard(_ theme: ThemePalette, cornerRadius: CGFloat = 22) -> some View {
        background(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(theme.surface)
                .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).stroke(theme.hairline, lineWidth: 1))
        )
    }
}

/// Pastille d'état (« Prête hors ligne », « Active »…).
struct StatusPill: View {
    enum Kind { case ready, incomplete, neutral, active, danger }

    let text: String
    let kind: Kind
    var systemImage: String?
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage { Image(systemName: systemImage).font(.system(size: 11, weight: .bold)) }
            Text(text).font(.system(size: 13, weight: .bold))
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 5)
        .foregroundStyle(foreground)
        .background(Capsule().fill(background))
    }

    private var foreground: Color {
        switch kind {
        case .ready: return theme.readyText
        case .incomplete: return theme.incompleteText
        case .neutral: return theme.neutralText
        case .active: return theme.onAction
        case .danger: return theme.onDanger
        }
    }

    private var background: Color {
        switch kind {
        case .ready: return theme.readyBackground
        case .incomplete: return theme.incompleteBackground
        case .neutral: return theme.neutralBackground
        case .active: return theme.action
        case .danger: return theme.danger
        }
    }
}

/// Choix du design : trois cartes avec un aperçu des couleurs.
struct DesignPickerRow: View {
    @Binding var selection: AppDesign
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 10) {
            ForEach(AppDesign.allCases) { design in
                let palette = design.palette
                let isSelected = selection == design
                Button { selection = design } label: {
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(palette.ground)
                            VStack(spacing: 6) {
                                Capsule().fill(palette.trace).frame(width: 44, height: 5)
                                RoundedRectangle(cornerRadius: 6, style: .continuous).fill(palette.surface).frame(width: 44, height: 16)
                                Capsule().fill(palette.action).frame(width: 36, height: 10)
                            }
                        }
                        .frame(height: 76)
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(palette.hairline, lineWidth: 1))
                        Text(design.label)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(theme.ink)
                    }
                    .padding(6)
                    .background(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(isSelected ? theme.action : .clear, lineWidth: 2.5)
                    )
                    .overlay(alignment: .topTrailing) {
                        if isSelected {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(theme.action)
                                .background(Circle().fill(theme.surface))
                                .offset(x: -2, y: 2)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(design.label))
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            }
        }
    }
}
