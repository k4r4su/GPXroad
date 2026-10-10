import SwiftUI

/// Barre d'onglets flottante du design (maquette du 10/10) : icônes seules, le nom n'apparaît que sur l'onglet actif,
/// dans une pastille de la couleur d'action. Remplace la barre native (masquée) ; même zone réservée en bas de l'écran.
struct ThemedTabBar: View {
    /// Hauteur réservée en bas de chaque onglet : barre (68) + marge basse (6).
    static let reservedHeight: CGFloat = 74

    @Binding var selection: AppTab
    @Environment(\.theme) private var theme

    private struct Item: Identifiable {
        let tab: AppTab
        let title: LocalizedStringKey
        let systemImage: String
        var id: AppTab { tab }
    }

    private let items: [Item] = [
        Item(tab: .ride, title: "Ride", systemImage: "location.north.fill"),
        Item(tab: .search, title: "Aller à", systemImage: "magnifyingglass"),
        Item(tab: .roadBook, title: "Road Book", systemImage: "list.bullet"),
        Item(tab: .library, title: "Biblio", systemImage: "map"),
        Item(tab: .settings, title: "Réglages", systemImage: "gearshape"),
    ]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                let isSelected = selection == item.tab
                Button {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { selection = item.tab }
                } label: {
                    HStack(spacing: 7) {
                        Image(systemName: item.systemImage)
                            .font(.system(size: 20, weight: .semibold))
                        if isSelected {
                            Text(item.title)
                                .font(.system(size: 15, weight: .heavy))
                                .lineLimit(1)
                                .fixedSize()
                        }
                    }
                    .foregroundStyle(isSelected ? theme.onAction : theme.inkSecondary)
                    .padding(.horizontal, isSelected ? 16 : 0)
                    .frame(height: 52)
                    .frame(minWidth: 44)
                    .background(Capsule().fill(isSelected ? theme.action : .clear))
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(item.title))
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                if item.id != items.last?.id { Spacer(minLength: 0) }
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 68)
        .background(
            Capsule()
                .fill(theme.surface)
                .overlay(Capsule().stroke(theme.hairline, lineWidth: theme.isDark ? 1 : 0))
                .shadow(color: .black.opacity(theme.isDark ? 0.5 : 0.2), radius: 12, y: 6)
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }
}
