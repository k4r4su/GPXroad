import SwiftUI

/// Page unique, liste plate, aucune navigation en sous-menu — conforme à la philosophie
/// "simplicité radicale" de l'app (guidon, gants, soleil). 14 réglages max au complet
/// (itération Nav, Bloc 5) ; épaisseur/couleur de trace ajoutés ici (Bloc 3, #13/#14).
struct SettingsView: View {
    @EnvironmentObject private var settings: RideSettingsStore
    @State private var showOnboarding = false

    var body: some View {
        NavigationStack {
            Form {
                // Style de sortie : un tap règle d'un coup flashs, fusion des virages, carte hors ligne, batterie, enregistrement.
                Section {
                    RideProfileSection()
                } header: {
                    Text("Mon style de sortie")
                } footer: {
                    Text("Chaque réglage reste modifiable un par un plus bas ; dès que l'un s'écarte du style, celui-ci devient « Personnalisé ». Les règles du Road Book (angles) ne changent pas.")
                }

                // Tout ce qui touche à l'interface est regroupé dans « Apparence » : design, fond de carte, trace, contrôles.
                Section {
                    NavigationLink {
                        AppearanceSettingsView()
                    } label: {
                        HStack {
                            Label("Apparence", systemImage: "paintpalette")
                            Spacer()
                            Text("\(settings.appDesign.label) · \(settings.mapThemePreset.label)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }

                // It31 : langue de l'app — automatique (langue de l'appareil si supportée, sinon
                // français) ou forcée. Appliquée tout de suite, sans relancer.
                Section {
                    Picker("Langue", selection: $settings.appLanguage) {
                        ForEach(AppLanguage.allCases) { language in
                            Text(language.nativeName).tag(language)
                        }
                    }
                } footer: {
                    Text("Français, English, Deutsch, Español, Italiano. En automatique, une langue d'appareil non prise en charge affiche l'app en français.")
                }

                // Spec it14 (Blocs 1/6/7) : l'ancienne section "Zoom automatique" à plat
                // déménage dans ce nouvel écran, avec Position point bleu et Zoom par défaut —
                // les trois ont besoin d'un aperçu carte en direct, impossible à faire
                // proprement dans une simple ligne de Form.
                Section {
                    NavigationLink {
                        NavigationSettingsView()
                    } label: {
                        Label("Navigation", systemImage: "location.north.line.fill")
                    }
                }

                // Renouvelée intégralement (spec "roadbook-settings-wired", it14, Bloc 5 :
                // "L'ancien panneau Réglages > Roadbook existant n'agit pas") — chaque contrôle
                // ci-dessous pilote directement RoadbookAnalyzer.buildRoadbookEvents via
                // RideSessionManager.rebuildCheckpoints, appelé par le .onChange en bas de
                // RideView pour chacun de ces réglages (bascule live, sans kill app).
                Section {
                    Toggle("Activé", isOn: $settings.roadbookEnabled)
                    if settings.roadbookEnabled {
                        Toggle("Flash (100 derniers mètres)", isOn: $settings.roadbookFlashEnabled)
                        Picker("Nombre de flashs", selection: $settings.flashCount) {
                            ForEach(RideConstants.flashCountOptions, id: \.self) { value in
                                Text("\(value)").tag(value)
                            }
                        }
                        Picker("Fusion des virages rapprochés", selection: $settings.turnMergeMinDistanceMeters) {
                            ForEach(RideConstants.turnMergeMinDistanceMetersOptions, id: \.self) { value in
                                Text("\(Int(value)) m").tag(value)
                            }
                        }
                        .longPressTooltip(String(localized: "Deux virages détectés à moins de cette distance sont fusionnés en un seul — utile sur piste qui zigzague", bundle: .appLanguage))

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Fenêtre de mesure : \(Int(settings.roadbookWindowBeforeMeters)) m avant / \(Int(settings.roadbookWindowAfterMeters)) m après")
                                .font(.subheadline)
                            Text("Avant").font(.caption).foregroundStyle(.secondary)
                            Slider(value: $settings.roadbookWindowBeforeMeters, in: NavigationConstants.roadbookWindowRange, step: 5)
                            Text("Après").font(.caption).foregroundStyle(.secondary)
                            Slider(value: $settings.roadbookWindowAfterMeters, in: NavigationConstants.roadbookWindowRange, step: 5)
                        }
                        .longPressTooltip(String(localized: "Distance avant/après chaque point de la trace sur laquelle l'angle est mesuré (±40-80 m)", bundle: .appLanguage))

                        Toggle("Seuils personnalisés", isOn: Binding(
                            get: { settings.roadbookUseCustomThresholds },
                            set: { isCustom in
                                settings.roadbookUseCustomThresholds = isCustom
                                if !isCustom { settings.resetRoadbookThresholdsToDefaults() }
                            }
                        ))
                        if settings.roadbookUseCustomThresholds {
                            roadbookThresholdStepper(String(localized: "Léger dès", bundle: .appLanguage), value: $settings.roadbookLightThresholdDegrees)
                            roadbookThresholdStepper(String(localized: "Prononcé dès", bundle: .appLanguage), value: $settings.roadbookMarkedThresholdDegrees)
                            roadbookThresholdStepper(String(localized: "Fort dès", bundle: .appLanguage), value: $settings.roadbookHardThresholdDegrees)
                            roadbookThresholdStepper(String(localized: "Très serré dès", bundle: .appLanguage), value: $settings.roadbookVeryHardThresholdDegrees)
                        } else {
                            Text("Standard : léger 25° · prononcé 45° · fort 90° · très serré 135°. Pas de vrai changement de cap = pas de checkpoint. Demi-tour seulement si la trace repart sur la même route.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("Roadbook")
                } footer: {
                    Text("Mesure l'angle de la trace autour de chaque point (fenêtre avant/après) et le classe en 4 paliers — léger, prononcé, fort, demi-tour.")
                }

                // Jalon it28 : catégories de repères affichées dans l'onglet Road Book.
                Section {
                    NavigationLink {
                        RoadbookLandmarkSettingsView()
                    } label: {
                        Label("Repères du Road Book", systemImage: "signpost.right")
                    }
                }

                // Spec "slope-warning-native" (it19) : nouvelle option d'apparence — symboles
                // ponctuels aux endroits de forte pente, jamais un dégradé continu sur la trace.
                Section {
                    Toggle("Avertissements de pente", isOn: $settings.slopeWarningsEnabled)
                    if settings.slopeWarningsEnabled {
                        Picker("Seuil de déclenchement", selection: $settings.slopeWarningThresholdPercent) {
                            ForEach(RideConstants.slopeWarningThresholdPercentOptions, id: \.self) { value in
                                Text("\(Int(value)) %").tag(value)
                            }
                        }
                    }
                } header: {
                    Text("Pente")
                } footer: {
                    Text("Un triangle apparaît sur la carte quand la pente dépasse le seuil choisi, en montée comme en descente.")
                }
                // Spec "map-color-flavors" (it19) : le thème Sombre (seul à avoir un accroc
                // hors-ligne, documenté en it18-bis) a été retiré — les 3 palettes restantes
                // fonctionnent identiquement hébergé/hors-ligne (paquet local), Relief a toujours
                // eu le même comportement raster-only qu'avant (rien de nouveau à signaler) :
                // plus besoin de footer d'avertissement ici.

                Section("Mode Nav") {
                    Picker("Seuil dépassement vitesse", selection: $settings.speedLimitAlertThresholdKmh) {
                        ForEach(NavConstants.speedLimitAlertThresholdOptionsKmh, id: \.self) { value in
                            Text("+\(value) km/h").tag(value)
                        }
                    }
                    Toggle("Guidage vocal", isOn: $settings.voiceGuidanceEnabled)
                    if settings.voiceGuidanceEnabled {
                        VStack(alignment: .leading) {
                            Text("Volume")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Slider(value: $settings.voiceGuidanceVolume, in: 0...1)
                        }
                    }
                    Toggle("Trafic", isOn: $settings.trafficEnabled)
                    // Nouvel écran (spec "home-work-favorites", it13) : "Domicile"/"Travail"
                    // étaient déjà suggérés par la recherche Ride, sans nulle part où les
                    // définir — voir FavoriteAddressesView.
                    NavigationLink {
                        FavoriteAddressesView()
                    } label: {
                        Label("Adresses favoris", systemImage: "house.and.flag.fill")
                    }
                }

                Section("Écran") {
                    Toggle("Empêcher la mise en veille en Ride", isOn: $settings.keepScreenAwakeInRide)
                }

                // Spec "recording-density-setting" (it19, retour terrain "alléger le fichier
                // GPX final") — `précis` (défaut) reproduit exactement le comportement d'avant
                // ce réglage, live comme le reste (pas de bouton Sauvegarder).
                Section {
                    Picker("Densité d'enregistrement", selection: $settings.recordingDensityPreset) {
                        ForEach(RecordingDensityPreset.allCases) { preset in
                            Text(preset.label).tag(preset)
                        }
                    }
                } header: {
                    Text("Enregistrement de la sortie")
                } footer: {
                    Text("\(settings.recordingDensityPreset.detail). Un enregistrement plus léger produit un fichier GPX exporté plus petit, mais moins fidèle au tracé réel.")
                }

                Section {
                    Toggle("Proposer d'enregistrer au départ", isOn: $settings.recordingPromptEnabled)
                } footer: {
                    Text("Désactivé : plus de question au début de la trace ; le bouton Enregistrer reste disponible.")
                }

                Section {
                    Picker("Mode longue sortie", selection: $settings.longRideSetting) {
                        ForEach(LongRideSetting.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                } footer: {
                    Text("Quand le mode est actif : les téléchargements automatiques de cartes sont suspendus et l'animation de la carte est limitée à 30 images par seconde, pour économiser la batterie. Une alerte te prévient à 15 % puis 5 % pendant un enregistrement.")
                }

                // Spec "unsaved-ride-recovery" (it19, retour terrain "cleanup au bout de 10 ou
                // 20 traces, réglable") — nombre de sauvegardes de secours conservées dans
                // Biblio > "Sorties non enregistrées" avant purge automatique des plus anciennes.
                Section {
                    Picker("Sauvegardes de secours conservées", selection: $settings.unsavedRideRetentionLimit) {
                        ForEach(RideConstants.unsavedRideRetentionLimitOptions, id: \.self) { count in
                            Text("\(count)").tag(count)
                        }
                    }
                } footer: {
                    Text("Pendant l'enregistrement, une copie de secours de la sortie en cours est sauvegardée automatiquement — récupérable dans Biblio si tu oublies de faire \"Terminer la sortie\". Les plus anciennes au-delà de ce nombre sont supprimées automatiquement.")
                }

                Section {
                    Toggle("Partager mes signalements anonymement", isOn: $settings.shareBlockagesAnonymously)
                        .longPressTooltip(String(localized: "Envoie uniquement un point GPS, une date et une note optionnelle — aucune donnée nominative, aucun compte", bundle: .appLanguage))
                } header: {
                    Text("Communauté")
                } footer: {
                    Text("Un chemin bloqué que tu signales est ajouté à une base partagée anonyme, pour alerter les autres utilisateurs qui passent par là.")
                }

                // Section repliée par défaut (Bloc 5, "cachée avancé") : URL du serveur
                // auto-hébergé des points bloqués partagés — vide par défaut (voir
                // SharedBlockageConstants, server/README.md).
                DisclosureGroup("Avancé") {
                    TextField("URL du serveur (points bloqués)", text: $settings.sharedBlockageServerURLString)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    Text("Laisser vide désactive toute tentative réseau vers cette fonctionnalité.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    NavigationLink {
                        ValhallaSettingsView()
                    } label: {
                        Label("Routage Valhalla (bêta)", systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                    }

                    NavigationLink {
                        OverpassSettingsView()
                    } label: {
                        Label("Serveur Overpass", systemImage: "server.rack")
                    }

                    #if DEBUG
                    DebugReplaySection()
                    #endif
                }

                Section {
                    // It31 : tutoriel intégré, une page par onglet (contenu embarqué, hors-ligne).
                    NavigationLink {
                        TutorialView()
                    } label: {
                        Label("Tutoriel", systemImage: "book.pages")
                    }
                    Button {
                        showOnboarding = true
                    } label: {
                        Label("Revoir le didacticiel", systemImage: "graduationcap.fill")
                    }
                }
            }
            .navigationTitle("Réglages")
        }
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView(isPresented: $showOnboarding)
        }
    }

    /// Un seuil personnalisé = un `Stepper` degré par degré, borné [10°, 179°] (au-delà l'ordre
    /// light < marked < hard < uTurn n'est plus garanti — pas de validation croisée ici, le
    /// propriétaire reste libre de l'ordre exact qu'il veut tester).
    private func roadbookThresholdStepper(_ title: String, value: Binding<Double>) -> some View {
        Stepper(value: value, in: 10...179, step: 1) {
            HStack {
                Text(title)
                Spacer()
                Text("\(Int(value.wrappedValue))°").foregroundStyle(.secondary).monospacedDigit()
            }
        }
    }
}
