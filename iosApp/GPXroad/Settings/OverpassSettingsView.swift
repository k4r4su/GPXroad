import SwiftUI

/// Serveur Overpass (it33) : adresse de l'instance du propriétaire et identifiants Basic Auth
/// (Trousseau), test de connexion. Appliqué en direct, comme le reste des réglages. Voir
/// `OverpassConfiguration` (instance publique en secours, jamais avec les identifiants).
struct OverpassSettingsView: View {
    @AppStorage(OverpassConfiguration.endpointDefaultsKey) private var endpoint = OverpassConfiguration.defaultEndpoint
    @State private var username = ""
    @State private var password = ""
    @State private var result: (success: Bool, message: String)?
    @State private var isTesting = false

    var body: some View {
        Form {
            Section {
                TextField("URL du serveur Overpass", text: $endpoint)
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
                Text("Serveur et identifiants")
            } footer: {
                Text("Sert aux repères du Road Book et aux limites de vitesse. Si ce serveur ne répond pas, l'app utilise le serveur public, sans jamais lui envoyer tes identifiants. Identifiants stockés dans le Trousseau iOS.")
            }

            Section {
                Button {
                    testConnection()
                } label: {
                    if isTesting {
                        HStack {
                            ProgressView().controlSize(.small)
                            Text("Test en cours…")
                        }
                    } else {
                        Text("Tester la connexion (/status)")
                    }
                }
                .disabled(isTesting)
                if let result {
                    Label(result.message, systemImage: result.success ? "checkmark.circle.fill" : "xmark.octagon.fill")
                        .foregroundStyle(result.success ? .green : .red)
                }
            }
        }
        .navigationTitle("Serveur Overpass")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            username = ValhallaKeychainStore.username(service: OverpassConfiguration.keychainService)
            password = ValhallaKeychainStore.password(service: OverpassConfiguration.keychainService)
        }
    }

    private func testConnection() {
        guard let request = OverpassConfiguration.statusRequest(endpoint: endpoint, username: username, password: password) else {
            result = (false, String(localized: "Adresse invalide", bundle: .appLanguage))
            return
        }
        isTesting = true
        result = nil
        Task {
            let outcome: (Bool, String)
            do {
                let (_, response) = try await URLSession.shared.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                switch status {
                case 200: outcome = (true, String(localized: "Connecté", bundle: .appLanguage))
                case 401, 403: outcome = (false, String(localized: "Identifiants refusés (HTTP \(status))", bundle: .appLanguage))
                default: outcome = (false, String(localized: "Réponse inattendue (HTTP \(status))", bundle: .appLanguage))
                }
            } catch {
                outcome = (false, error.localizedDescription)
            }
            await MainActor.run {
                result = outcome
                isTesting = false
            }
        }
    }
}
