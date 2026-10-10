import SwiftUI

/// Réglages > Apparence : tout ce qui change l'aspect de l'app au même endroit — design (Clair, Sombre, Forêt), fond de carte,
/// trace, côté des contrôles, palette du Road Book.
struct AppearanceSettingsView: View {
    @EnvironmentObject private var settings: RideSettingsStore

    var body: some View {
        Form {
                // Design de l'app (10/10) : Forêt, Clair ou Sombre, appliqué tout de suite.
                Section {
                    DesignPickerRow(selection: $settings.appDesign)
                        .listRowInsets(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12))
                } header: {
                    Text("Design")
                } footer: {
                    Text("Automatique : suit le mode clair ou sombre de l'iPhone. Clair : gris et bleu façon iOS. Sombre : gris étagés pour la nuit. Forêt : nature et fraîcheur.")
                }

                Section {
                    Picker("Orientation", selection: $settings.mapOrientationNorthUp) {
                        Text("Cap en haut").tag(false)
                        Text("Nord en haut").tag(true)
                    }
                    // Spec "map-style-visual-picker" (it18-bis) : vignettes plutôt qu'une liste
                    // de texte — standard du marché pour un choix de fond de carte.
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Thème").font(.subheadline)
                        MapThemePickerView(selection: $settings.mapThemePreset)
                    }
                    Picker("Unité de vitesse", selection: $settings.speedUnit) {
                        ForEach(SpeedUnit.allCases) { unit in
                            Text(unit.label).tag(unit)
                        }
                    }
                } header: {
                    Text("Carte")
                } footer: {
                    // Fix "map-style-rotation-consistency" (it22, retour terrain : "certains
                    // styles affichent les labels à l'envers ou statiques quand on tourne la
                    // carte") — diagnostiqué : ce n'est PAS un bug de rotation (vérifié, le
                    // patch cap-en-haut s'applique de façon identique aux 3 palettes
                    // vectorielles), c'est Relief seul, en RASTER (image pré-rendue, aucune
                    // rotation de texte possible par nature) qui ne suit jamais la rotation,
                    // contrairement aux 3 autres thèmes (vectoriels). Documenté ici plutôt que
                    // "corrigé" — rien à corriger dans le mécanisme de rotation lui-même.
                    if settings.mapThemePreset == .relief {
                        Text("Relief est une carte pré-dessinée (raster) : les noms de rue ne pivotent pas avec la boussole en cap-en-haut, contrairement aux 3 autres thèmes.")
                    }
                }

                // Renommée "Trace" → "Apparence" (spec "controls-side-setting", it14, Bloc 2 :
                // "Réglages > Apparence > Position contrôles") — regroupe désormais aussi le
                // côté de la colonne de contrôles Ride, pas seulement le rendu de la trace.
                Section("Trace et contrôles") {
                    // Fix "settings-segmented-picker-missing-title" (bug terrain, it16) :
                    // .pickerStyle(.segmented) masque le titre du Picker par défaut (contrairement
                    // au style menu utilisé pour "Couleur" juste en dessous) — sans Text explicite
                    // au-dessus, impossible de deviner à quoi correspondent les segments.
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Épaisseur").font(.subheadline)
                        Picker("Épaisseur", selection: $settings.traceWidthPreset) {
                            ForEach(TraceWidthPreset.allCases) { preset in
                                Text(preset.label).tag(preset)
                            }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                    }

                    Picker("Couleur", selection: $settings.traceColorPreset) {
                        ForEach(TraceColorPreset.allCases) { preset in
                            Label {
                                Text(preset.label)
                            } icon: {
                                Circle().fill(Color(preset.color)).frame(width: 14, height: 14)
                            }
                            .tag(preset)
                        }
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Position contrôles").font(.subheadline)
                        Picker("Position contrôles", selection: $settings.controlsSide) {
                            ForEach(ControlsSide.allCases) { side in
                                Text(side.label).tag(side)
                            }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        .longPressTooltip(String(localized: "Colonne +/−/Stop/Bloqué et bannière roadbook, du côté choisi — le badge vitesse passe automatiquement de l'autre côté", bundle: .appLanguage))
                    }

                    // Spec "roadbook-ui-redesign" (it25, point 0) : palette PROPRE à l'écran Road
                    // Book, distincte du thème carte ci-dessus — jamais appliquée ailleurs.
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Palette Road Book").font(.subheadline)
                        Picker("Palette Road Book", selection: $settings.roadbookPaletteSetting) {
                            ForEach(RoadbookPaletteSetting.allCases) { setting in
                                Text(setting.label).tag(setting)
                            }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        .longPressTooltip("Automatique bascule entre un fond clair \"papier\" le jour et sombre la nuit selon le lever/coucher du soleil réel — force l'un ou l'autre en permanence si besoin (tunnel long, préférence)")
                    }
                }

        }
        .navigationTitle("Apparence")
        .navigationBarTitleDisplayMode(.inline)
    }
}
