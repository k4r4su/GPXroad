import SwiftUI

/// Zoom +/- par paliers discrets + recentrage — cibles ≥56pt, coins arrondis, fond flouté,
/// pensés pour rester utilisables à une main, avec des gants, sans dépendre du pinch.
struct RideGlovedZoomControls: View {
    let onZoomIn: () -> Void
    let onZoomOut: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(spacing: 14) {
            glovedButton(systemImage: "plus", label: nil, action: onZoomIn)
            glovedButton(systemImage: "minus", label: nil, action: onZoomOut)
        }
    }

    private func glovedButton(systemImage: String, label: String?, action: @escaping () -> Void) -> some View {
        let explanation = systemImage == "plus" ? String(localized: "Zoomer", bundle: .appLanguage) : String(localized: "Dézoomer", bundle: .appLanguage)
        return Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(theme.action)
                .frame(width: RideConstants.glovedTapTargetSize, height: RideConstants.glovedTapTargetSize)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(theme.surface))
                .shadow(color: .black.opacity(theme.isDark ? 0.5 : 0.2), radius: 7, y: 3)
        }
        .longPressTooltip(explanation)
    }
}

/// Visible uniquement quand la caméra a dérivé de la position (drag/pinch) — masqué sinon
/// pour ne pas surcharger l'écran.
struct RideRecenterButton: View {
    let action: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: "scope")
                    .font(.system(size: 20, weight: .bold))
                Text("me recentrer")
                    .font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(theme.onAction)
            .frame(width: RideConstants.glovedTapTargetSize + 8, height: RideConstants.glovedTapTargetSize + 8)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(theme.action))
            .shadow(color: .black.opacity(theme.isDark ? 0.5 : 0.25), radius: 7, y: 3)
        }
        .accessibilityLabel("Me recentrer sur ma position, reprendre le cap")
        .transition(.scale.combined(with: .opacity))
    }
}
