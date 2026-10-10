import SwiftUI

struct RootView: View {
    @EnvironmentObject private var navigationState: AppNavigationState
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var trackRideSettings: TrackRideSettingsStore
    @EnvironmentObject private var settings: RideSettingsStore
    @EnvironmentObject private var networkMonitor: NetworkMonitor
    @EnvironmentObject private var trackPreparer: TrackPreparer
    @EnvironmentObject private var battery: BatteryMonitor

    /// Préparation de la trace active (06/10) : carte du couloir, repères, recalage, ronds-points, dès que le réseau est bon.
    private func prepareActiveTrack() {
        guard let track = RoadbookTrackSource.displayedTrack(library: library, trackRideSettings: trackRideSettings) else { return }
        trackPreparer.prepare(track: track, settings: settings, network: networkMonitor, suspended: battery.isLongRideActive(settings.longRideSetting))
    }

    var body: some View {
        TabView(selection: $navigationState.selectedTab) {
            RideView()
                .toolbar(.hidden, for: .tabBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: ThemedTabBar.reservedHeight) }
                .tabItem { Label("Ride", systemImage: "location.north.line.fill") }
                .tag(AppTab.ride)

            // Spec "search-as-tab" (it19) : recherche de destination sortie de la carte Ride
            // (bouton flottant retiré) pour devenir son propre onglet, entre Ride et Biblio
            // comme demandé.
            DestinationSearchTabView()
                .toolbar(.hidden, for: .tabBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: ThemedTabBar.reservedHeight) }
                .tabItem { Label("Aller à", systemImage: "magnifyingglass") }
                .tag(AppTab.search)

            // Spec "roadbook-mode" (it23) : nouvel onglet, lecture d'une trace en liste de
            // directions pures — totalement découplé de l'état de Ride actif, voir
            // RoadBook/CLAUDE.md.
            RoadBookTabView()
                .toolbar(.hidden, for: .tabBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: ThemedTabBar.reservedHeight) }
                .tabItem { Label("Road Book", systemImage: "list.bullet.rectangle.portrait") }
                .tag(AppTab.roadBook)

            LibraryView()
                .toolbar(.hidden, for: .tabBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: ThemedTabBar.reservedHeight) }
                .tabItem { Label("Biblio", systemImage: "map") }
                .tag(AppTab.library)

            SettingsView()
                .toolbar(.hidden, for: .tabBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: ThemedTabBar.reservedHeight) }
                .tabItem { Label("Réglages", systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
        // Barre du bas du design (icônes seules, nom sur l'onglet actif) : la barre native est masquée ; chaque onglet réserve sa
        // hauteur (ci-dessus) pour que listes, formulaires et le Ride (qui lit sa vraie marge basse) ne passent jamais dessous.
        .overlay(alignment: .bottom) {
            ThemedTabBar(selection: $navigationState.selectedTab)
        }
        .task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)   // laisse MapLibre charger les zones déjà sur disque
            prepareActiveTrack()
        }
        .onChange(of: library.activeTrackID) { _ in prepareActiveTrack() }
        .onChange(of: networkMonitor.isReachable) { _ in prepareActiveTrack() }
        .onChange(of: networkMonitor.isWifiOrEthernet) { _ in prepareActiveTrack() }
        .onChange(of: settings.autoPrepareEnabled) { _ in prepareActiveTrack() }
        .onChange(of: battery.isCharging) { _ in prepareActiveTrack() }   // branché : les téléchargements reprennent
    }
}
