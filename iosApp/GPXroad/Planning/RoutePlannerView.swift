import SwiftUI
import CoreLocation
import GPXroadShared

/// « Créer un itinéraire » (06/10) : on pose des points sur la carte, Valhalla les relie par les routes existantes
/// (autoroutes évitées par défaut), puis l'itinéraire est enregistré comme une trace de la Bibliothèque.
struct RoutePlannerView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: RideSettingsStore
    @EnvironmentObject private var sharedBlockages: SharedBlockageSyncCoordinator
    @EnvironmentObject private var networkMonitor: NetworkMonitor
    @StateObject private var model = RoutePlannerModel()
    @State private var showFlagged = false
    @StateObject private var locationManager = LocationManager()
    @State private var showOptions = false
    @State private var showNamePrompt = false
    @State private var routeName = ""
    @State private var showSaved = false
    @State private var saveError: String?

    private var startCenter: CLLocationCoordinate2D {
        locationManager.currentLocation?.coordinate ?? OfflineConstants.franceCenterCoordinate
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                PlannerMapView(
                    waypoints: model.waypoints,
                    route: model.route?.points ?? [],
                    flagged: model.flagged,
                    fitToken: model.fitToken,
                    startCenter: startCenter,
                    onTap: { model.add($0) }
                )
                .ignoresSafeArea(edges: .bottom)
                panel
            }
            .navigationTitle("Créer un itinéraire")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Fermer") { dismiss() }
                }
            }
        }
        .onAppear {
            model.configuration = { settings.valhallaConfigurationIfEnabled }
            // Chemins déjà signalés bloqués ou interdits (cache local de la base partagée + signalements faits ici) : évités.
            model.exclusions = { sharedBlockages.exclusionLocations(inside: $0) }
        }
        .sheet(isPresented: $showFlagged) {
            FlaggedSegmentsSheet(segments: model.flagged) { segment in
                guard let point = segment.midpoint else { return }
                sharedBlockages.reportForbidden(
                    coordinate: point, wayID: segment.wayIDs.first,
                    serverURLString: settings.sharedBlockageServerURLString,
                    isReachable: networkMonitor.isReachable, isEnabled: settings.shareBlockagesAnonymously
                )
                model.recompute()   // le chemin signalé est maintenant évité
                showFlagged = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showOptions, onDismiss: { model.optionsChanged() }) {
            PlannerOptionsView(options: $model.options)
                .presentationDetents([.medium])
        }
        .alert("Nom de l'itinéraire", isPresented: $showNamePrompt) {
            TextField("Nom", text: $routeName)
            Button("Enregistrer") { save() }
            Button("Annuler", role: .cancel) {}
        }
        .alert("Itinéraire enregistré dans la Bibliothèque.", isPresented: $showSaved) {
            Button("OK") { dismiss() }
        }
        .alert("Enregistrement impossible", isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(saveError ?? "")
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.waypoints.isEmpty {
                Text("Touche la carte pour poser le départ, puis les étapes et l'arrivée. Valhalla relie les points par les routes.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                summary
                accessLine
                waypointChips
            }
            if case .failed(let message) = model.status {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
            HStack(spacing: 12) {
                Button { model.removeLast() } label: { Image(systemName: "arrow.uturn.backward") }
                    .disabled(model.waypoints.isEmpty)
                    .accessibilityLabel("Annuler le dernier point")
                Button(role: .destructive) { model.clear() } label: { Image(systemName: "trash") }
                    .disabled(model.waypoints.isEmpty)
                    .accessibilityLabel("Tout effacer")
                Button { showOptions = true } label: { Image(systemName: "slider.horizontal.3") }
                    .accessibilityLabel("Options de l'itinéraire")
                Spacer()
                Button {
                    routeName = defaultName()
                    showNamePrompt = true
                } label: {
                    Label("Enregistrer", systemImage: "square.and.arrow.down")
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                        .fixedSize()
                }
                .buttonStyle(.borderedProminent)
                .disabled(!model.canSave)
            }
            .buttonStyle(.bordered)
            .font(.title3)
        }
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    private var summary: some View {
        HStack(spacing: 8) {
            if model.status == .computing {
                ProgressView().controlSize(.small)
                Text("Calcul…")
            } else if let route = model.route {
                Text(DistanceUnit.km.displayString(fromMeters: route.distanceMeters))
                    .font(.headline)
                if route.durationSeconds > 0 {
                    Text("· " + Self.durationString(route.durationSeconds))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .font(.subheadline)
    }

    /// Pistes : vérification de l'accès (seulement si « Autoriser les pistes » est actif).
    @ViewBuilder
    private var accessLine: some View {
        switch model.accessStatus {
        case .checking:
            Label("Vérification des accès…", systemImage: "magnifyingglass")
                .font(.footnote)
                .foregroundStyle(.secondary)
        case .unavailable:
            Label("Vérification des accès impossible (réseau ou serveur).", systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .foregroundStyle(.orange)
        case .checked:
            if model.flagged.isEmpty {
                Label("Aucune restriction d'accès signalée dans OpenStreetMap sur ce tracé.", systemImage: "checkmark.seal")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Button { showFlagged = true } label: {
                    Label(
                        String(format: String(localized: "%lld portions à vérifier · %@", bundle: .appLanguage), model.flagged.count,
                               DistanceUnit.km.displayString(fromMeters: model.flagged.reduce(0) { $0 + $1.lengthMeters })),
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.orange)
                }
                .buttonStyle(.plain)
            }
        case .idle:
            EmptyView()
        }
    }

    private var waypointChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(model.waypoints.enumerated()), id: \.offset) { index, _ in
                    Button { model.remove(at: index) } label: {
                        HStack(spacing: 4) {
                            Text(PlannerMapView.Coordinator.label(index: index, count: model.waypoints.count))
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                        .font(.caption)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color(.secondarySystemBackground), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(format: String(localized: "Supprimer le point : %@", bundle: .appLanguage), PlannerMapView.Coordinator.label(index: index, count: model.waypoints.count)))
                }
            }
        }
    }

    private func defaultName() -> String {
        let formatter = DateFormatter()
        formatter.locale = AppLanguageBundle.locale
        formatter.setLocalizedDateFormatFromTemplate("d MMM")
        return String(format: String(localized: "Itinéraire du %@", bundle: .appLanguage), formatter.string(from: Date()))
    }

    private static func durationString(_ seconds: Double) -> String {
        let minutes = Int((seconds / 60).rounded())
        return minutes >= 60 ? String(format: "%d h %02d", minutes / 60, minutes % 60) : "\(minutes) min"
    }

    private func save() {
        let name = routeName.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalName = name.isEmpty ? defaultName() : name
        guard let data = model.gpxData(named: finalName) else { return }
        let safe = finalName.components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|")).joined(separator: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(safe).gpx")
        do {
            try data.write(to: url)
            defer { try? FileManager.default.removeItem(at: url) }
            if library.importTrack(from: url) != nil {
                showSaved = true
            } else {
                saveError = library.lastError ?? String(localized: "Import impossible.", bundle: .appLanguage)
            }
        } catch {
            saveError = error.localizedDescription
        }
    }
}

/// Fenêtre d'options de l'itinéraire : le but est de s'amuser, donc autoroutes et péages évités par défaut.
struct PlannerOptionsView: View {
    @Binding var options: PlanOptions
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Véhicule") {
                    Picker("Véhicule", selection: vehicleBinding) {
                        Text("Moto").tag(PlanVehicle.motorcycle)
                        Text("Voiture").tag(PlanVehicle.car)
                        Text("Vélo").tag(PlanVehicle.bicycle)
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    if options.vehicle != .bicycle {
                        Toggle("Éviter les autoroutes", isOn: toggle(\.avoidHighways) { PlanOptions(vehicle: $0.vehicle, avoidHighways: $1, avoidTolls: $0.avoidTolls, avoidFerries: $0.avoidFerries, allowTracks: $0.allowTracks) })
                        Toggle("Éviter les péages", isOn: toggle(\.avoidTolls) { PlanOptions(vehicle: $0.vehicle, avoidHighways: $0.avoidHighways, avoidTolls: $1, avoidFerries: $0.avoidFerries, allowTracks: $0.allowTracks) })
                    }
                    Toggle("Éviter les ferries", isOn: toggle(\.avoidFerries) { PlanOptions(vehicle: $0.vehicle, avoidHighways: $0.avoidHighways, avoidTolls: $0.avoidTolls, avoidFerries: $1, allowTracks: $0.allowTracks) })
                    if options.vehicle != .bicycle {
                        Toggle("Autoriser les pistes", isOn: toggle(\.allowTracks) { PlanOptions(vehicle: $0.vehicle, avoidHighways: $0.avoidHighways, avoidTolls: $0.avoidTolls, avoidFerries: $0.avoidFerries, allowTracks: $1) })
                    }
                } header: {
                    Text("Éviter")
                } footer: {
                    Text("Éviter n'est pas interdire : si aucune autre route n'existe, l'itinéraire peut quand même passer par là. Les pistes ne sont proposées que si les données OpenStreetMap les disent accessibles ; l'interdiction réelle sur le terrain peut différer.")
                }
            }
            .navigationTitle("Options de l'itinéraire")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } }
            }
        }
    }

    private var vehicleBinding: Binding<PlanVehicle> {
        Binding(
            get: { options.vehicle },
            set: { options = PlanOptions(vehicle: $0, avoidHighways: options.avoidHighways, avoidTolls: options.avoidTolls, avoidFerries: options.avoidFerries, allowTracks: options.allowTracks) }
        )
    }

    private func toggle(_ keyPath: KeyPath<PlanOptions, Bool>, rebuild: @escaping (PlanOptions, Bool) -> PlanOptions) -> Binding<Bool> {
        Binding(get: { options[keyPath: keyPath] }, set: { options = rebuild(options, $0) })
    }
}

/// Présentation plein écran de « Créer un itinéraire » (sortie de `LibraryView`, déjà à la limite du compilateur).
struct RoutePlannerCover: ViewModifier {
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        content.fullScreenCover(isPresented: $isPresented) { RoutePlannerView() }
    }
}

/// Liste des portions à vérifier : on peut en signaler une comme interdite (les prochains itinéraires l'évitent).
struct FlaggedSegmentsSheet: View {
    let segments: [FlaggedSegment]
    let onReportForbidden: (FlaggedSegment) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pending: FlaggedSegment?

    var body: some View {
        NavigationStack {
            List(segments) { segment in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Circle().fill(color(segment.verdict)).frame(width: 10, height: 10)
                        Text(segment.name ?? String(localized: "Chemin sans nom", bundle: .appLanguage)).font(.headline)
                        Spacer()
                        Text(DistanceUnit.km.displayString(fromMeters: segment.lengthMeters)).foregroundStyle(.secondary)
                    }
                    Text(explanation(segment.verdict)).font(.footnote).foregroundStyle(.secondary)
                    Button("Signaler interdit", role: .destructive) { pending = segment }
                        .font(.footnote)
                        .buttonStyle(.bordered)
                }
                .padding(.vertical, 4)
            }
            .navigationTitle("Pistes à vérifier")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } } }
            .confirmationDialog("Signaler ce chemin comme interdit ?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
                Button("Signaler interdit", role: .destructive) {
                    if let pending { onReportForbidden(pending) }
                    pending = nil
                }
                Button("Annuler", role: .cancel) { pending = nil }
            } message: {
                Text("Les prochains itinéraires l'éviteront. Le signalement est envoyé à la base partagée si elle est configurée.")
            }
        }
    }

    private func color(_ verdict: AccessVerdict) -> Color {
        switch verdict {
        case .forbidden: return .red
        case .restricted: return .orange
        default: return .yellow
        }
    }

    private func explanation(_ verdict: AccessVerdict) -> String {
        switch verdict {
        case .forbidden: return String(localized: "Interdit d'après OpenStreetMap", bundle: .appLanguage)
        case .restricted: return String(localized: "Accès réservé aux riverains ou à la destination", bundle: .appLanguage)
        default: return String(localized: "À vérifier : OpenStreetMap n'indique pas l'accès", bundle: .appLanguage)
        }
    }
}
