import SwiftUI

/// Toujours visible en Mode Nav ET Mode Trace actifs (spec Bloc 4). Action critique : label
/// texte permanent. La confirmation (1 geste) est portée par l'appelant (RideView).
struct RideStopButton: View {
    let action: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: "stop.fill")
                    .font(.system(size: 18, weight: .bold))
                Text("Stop")
                    .font(.system(size: 9, weight: .semibold))
            }
            .foregroundStyle(theme.ink)
            .frame(width: RideConstants.glovedTapTargetSize, height: RideConstants.glovedTapTargetSize)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(theme.surface))
            .shadow(color: .black.opacity(theme.isDark ? 0.5 : 0.2), radius: 7, y: 3)
        }
        .accessibilityLabel("Arrêter le guidage")
    }
}
