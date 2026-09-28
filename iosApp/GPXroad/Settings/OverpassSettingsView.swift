import SwiftUI

/// Serveur Overpass du propriétaire (it33 bis) — MÊME structure que `ValhallaSettingsView` :
/// toggle (désactivé par défaut), adresses (UserDefaults via `RideSettingsStore`, vides par défaut),
/// identifiants Basic Auth en champs libres stockés dans le Trousseau (`ValhallaKeychainStore`,
/// service `OverpassConfiguration.keychainService`, jamais de valeur par défaut), test de connexion,
/// dernier serveur ayant répondu. Tout s'applique en direct.
struct OverpassSettingsView: View {
    @EnvironmentObject private var settings: RideSettingsStore
    @ObservedObject private var activityMonitor = OverpassActivityMonitor.shared

    @State private var username = ""
    @State private var password = ""
    @State private var lanResult: ConnectionTestResult?
    @State private var publicResult: ConnectionTestResult?
    @State private var isTestingConnection = false

    private enum ConnectionTestResult {
        case success(String)
        case failure(String)
    }

    var body: some View {
        Form {
            Section {
                Toggle("Utiliser mon serveur Overpass", isOn: $settings.overpassEnabled)
                    .longPressTooltip("Repères du Road Book et limites de vitesse depuis ton serveur — désactiver revient au serveur public OpenStreetMap")
                activityRow
            } footer: {
                Text("Désactivé par défaut. Ordre d'essai : réseau de la maison (en Wi-Fi), puis ton adresse publique, puis le serveur public OpenStreetMap en secours — qui ne reçoit jamais tes identifiants.")
            }

            Section {
                TextField("Adresse publique (ex. https://overpass.mondomaine.fr/api/interpreter)", text: $settings.overpassEndpointURLString)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                TextField("Nom d'utilisateur (Basic Auth)", text: $username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: username) { newValue in
                        ValhallaKeychainStore.save(username: newValue, password: password, service: OverpassConfiguration.keychainService)
                    }
                SecureField("Mot de passe (Basic Auth)", text: $password)
                    .onChange(of: password) { newValue in
                        ValhallaKeychainStore.save(username: username, password: newValue, service: OverpassConfiguration.keychainService)
                    }
            } header: {
                Text("Hors de la maison (HTTPS)")
            } footer: {
                Text("Les identifiants sont stockés dans le Trousseau iOS, jamais dans les réglages classiques.")
            }

            Section {
                TextField("Adresse locale (ex. http://192.168.1.10:8003/api/interpreter)", text: $settings.overpassLANEndpointURLString)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
            } header: {
                Text("À la maison (réseau local)")
            } footer: {
                Text("Sans authentification, essayée en premier en Wi-Fi ; laissée de côté quelques minutes si elle ne répond pas (hors de chez toi). Vide : jamais utilisée.")
            }

            Section {
                Button {
                    testConnection()
                } label: {
                    if isTestingConnection {
                        HStack {
                            ProgressView().controlSize(.small)
                            Text("Test en cours…")
                        }
                    } else {
                        Text("Tester la connexion (/status)")
                    }
                }
                .disabled(isTestingConnection)

                resultRow(title: String(localized: "À la maison", bundle: .appLanguage), result: lanResult)
                resultRow(title: String(localized: "Hors de la maison", bundle: .appLanguage), result: publicResult)
            }
        }
        .navigationTitle("Serveur Overpass")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            username = ValhallaKeychainStore.username(service: OverpassConfiguration.keychainService)
            password = ValhallaKeychainStore.password(service: OverpassConfiguration.keychainService)
        }
    }

    @ViewBuilder
    private func resultRow(title: String, result: ConnectionTestResult?) -> some View {
        switch result {
        case .success(let message):
            Label(title + " : " + message, systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failure(let message):
            Label(title + " : " + message, systemImage: "xmark.octagon.fill")
                .foregroundStyle(.red)
        case nil:
            EmptyView()
        }
    }

    /// Même rôle que la ligne « dernier service de routage » de Valhalla : quel serveur a vraiment
    /// répondu à la dernière requête de repères ou de limite de vitesse.
    private var activityRow: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Dernier serveur Overpass ayant répondu")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    Text(activityLabel)
                        .fontWeight(.semibold)
                        .foregroundStyle(activityColor)
                    if let date = activityMonitor.lastEvent?.date {
                        Text("· \(date.formatted(date: .omitted, time: .standard))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        } icon: {
            Image(systemName: activityMonitor.lastEvent == nil ? "questionmark.circle" : "checkmark.circle.fill")
                .foregroundStyle(activityColor)
        }
    }

    private var activityLabel: String {
        switch activityMonitor.lastEvent?.kind {
        case .lan: return String(localized: "Serveur de la maison", bundle: .appLanguage)
        case .own: return String(localized: "Ton serveur (hors de la maison)", bundle: .appLanguage)
        case .publicFallback: return String(localized: "Serveur public (secours)", bundle: .appLanguage)
        case nil: return String(localized: "Aucune requête récente", bundle: .appLanguage)
        }
    }

    private var activityColor: Color {
        switch activityMonitor.lastEvent?.kind {
        case .lan, .own: return .green
        case .publicFallback: return .orange
        case nil: return .secondary
        }
    }

    private func testConnection() {
        isTestingConnection = true
        lanResult = nil
        publicResult = nil
        let lanRequest = OverpassConfiguration.statusRequest(endpoint: settings.overpassLANEndpointURLString, username: "", password: "")
        let publicRequest = OverpassConfiguration.statusRequest(endpoint: settings.overpassEndpointURLString, username: username, password: password)
        Task {
            async let lan = Self.check(lanRequest)
            async let own = Self.check(publicRequest)
            let (lanOutcome, publicOutcome) = await (lan, own)
            await MainActor.run {
                lanResult = lanOutcome
                publicResult = publicOutcome
                if case .success = lanOutcome { OverpassConfiguration.resetLANAvailability() }
                isTestingConnection = false
            }
        }
    }

    private static func check(_ request: URLRequest?) async -> ConnectionTestResult {
        guard let request else { return .failure(String(localized: "Adresse vide ou invalide", bundle: .appLanguage)) }
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200: return .success(String(localized: "Connecté", bundle: .appLanguage))
            case 401, 403: return .failure(String(localized: "Identifiants refusés (HTTP \(status))", bundle: .appLanguage))
            default: return .failure(String(localized: "Réponse inattendue (HTTP \(status))", bundle: .appLanguage))
            }
        } catch {
            return .failure(error.localizedDescription)
        }
    }
}
