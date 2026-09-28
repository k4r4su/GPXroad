import SwiftUI

/// Vue "focus" du mode Assisté GPS (spec "roadbook-focused-next-turn", it23ter ; refonte
/// UI/UX "roadbook-ui-redesign", it25). Lit EXACTEMENT la même liste `[RoadbookManeuver]`/le
/// même calcul `RoadbookLiveProgress` que le mode Classique — seule la présentation change,
/// jamais une deuxième source de données (voir RoadBook/CLAUDE.md).
///
/// Layout PORTRAIT (it23ter, "au moins la moitié de l'écran... et en dessous les suivants") :
/// carte hero fixe en haut (`RoadBookConstants.focusedHeroHeightFraction`), liste SCROLLABLE de
/// TOUTES les manœuvres restantes en dessous (it25, point 1 — retour terrain : "seuls 2 éléments
/// s'affichent avant d'être coupés par la tab bar", plus de limite à `.prefix(2)`).
///
/// Layout PAYSAGE (it25, point 2 — retour terrain détaillé : mini-carte en bande illisible,
/// texte qui chevauche la tab bar) : hero à hauteur FIXE et COMPACTE
/// (`focusedHeroLandscapeHeight`, pas la moitié de l'écran — un écran deux fois moins haut ne
/// laisserait sinon presque rien à la liste), disposition HORIZONTALE dédiée (pictogramme à
/// gauche, distance à droite) plutôt que le portrait simplement compressé.
struct RoadbookFocusedView: View {
    /// Virages ET repères en ligne dédiée, dans l'ordre de la trace (`RoadbookEntry.merge`) —
    /// it30 : le "prochain élément" mis en avant est le plus proche, QUEL QUE SOIT son type
    /// (un stop à 200 m passe avant un virage à 300 m).
    let entries: [RoadbookEntry]
    /// Index du prochain élément dans `entries` (`RoadbookLiveProgress.nextEntry`).
    let currentEntryIndex: Int?
    let distanceRemainingMeters: Double?
    /// Position actuelle projetée sur la trace — distance "dans combien" des éléments suivants.
    let currentCumulativeDistanceMeters: Double?
    let unit: DistanceUnit
    /// Distingue "pas encore de position GPS" de "trace terminée" (les deux se traduisent par
    /// `currentEntryIndex == nil`, mais méritent un message différent).
    let hasLocationFix: Bool
    /// Repère affiché AVEC chaque virage (clé = `RoadbookManeuver.id`).
    let landmarks: [UUID: RoadbookLandmarkInfo?]
    /// Hors trace (it30) : la carte principale le dit à la place du prochain élément, la liste
    /// des éléments suivants reste affichée ; retour automatique à la normale sur la trace.
    var offTrack = RoadbookOffTrackState()
    /// Hors trace (it33) : chemin pour rejoindre la trace — ses virages prennent la carte principale
    /// et la tête de la liste (badge « Hors trace » toujours visible), puis la suite du Road Book à
    /// partir du point de retour. `nil` sur la trace.
    var rejoin: RoadbookRejoinDisplay?

    /// `.compact` = paysage sur iPhone (TARGETED_DEVICE_FAMILY "1", pas d'iPad à gérer) — signal
    /// natif SwiftUI, se met à jour automatiquement à la rotation, jamais besoin d'observer
    /// `UIDevice.orientation` à la main.
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    private var isLandscape: Bool { verticalSizeClass == .compact }

    enum UpcomingStep: Identifiable, Equatable {
        case maneuver(RoadbookManeuver, rank: Int, distanceFromNowMeters: Double)
        case landmark(RoadbookLandmarkCheckpoint, distanceFromNowMeters: Double)
        /// Virage du chemin de reprise (it33) — identifiant distinct de ceux de la trace.
        case rejoinManeuver(RoadbookManeuver, rank: Int, distanceFromNowMeters: Double)
        /// Fin du chemin de reprise : retour sur la trace.
        case rejoinArrival(distanceFromNowMeters: Double)

        var id: String {
            switch self {
            case .maneuver(let maneuver, _, _): return "maneuver-\(maneuver.id.uuidString)"
            case .landmark(let landmark, _): return landmark.id
            case .rejoinManeuver(let maneuver, _, _): return "rejoin-\(maneuver.id.uuidString)"
            case .rejoinArrival: return "rejoin-arrival"
            }
        }

        var distanceFromNowMeters: Double {
            switch self {
            case .maneuver(_, _, let distance), .landmark(_, let distance), .rejoinManeuver(_, _, let distance), .rejoinArrival(let distance): return distance
            }
        }
    }

    /// Hors trace avec un chemin de reprise (it33) : les virages du chemin après celui mis en avant,
    /// le retour sur la trace, puis les éléments du Road Book situés APRÈS le point de retour —
    /// distance = reste du chemin + distance le long de la trace depuis le point de retour.
    static func rejoinSteps(rejoin: RoadbookRejoinDisplay, entries: [RoadbookEntry]) -> [UpcomingStep] {
        guard rejoin.status == .routed, let remaining = rejoin.remainingToTrackMeters, let target = rejoin.targetCumulativeDistanceMeters else { return [] }
        var steps: [UpcomingStep] = []
        var rank = 1
        let firstFollowing = (rejoin.nextManeuverIndex ?? rejoin.maneuvers.count) + 1
        if firstFollowing < rejoin.maneuvers.count {
            for maneuver in rejoin.maneuvers[firstFollowing...] {
                rank += 1
                steps.append(.rejoinManeuver(maneuver, rank: rank, distanceFromNowMeters: max(maneuver.cumulativeDistanceMeters - rejoin.routeCumulativeDistanceMeters, 0)))
            }
        }
        if rejoin.nextManeuverIndex != nil {
            steps.append(.rejoinArrival(distanceFromNowMeters: remaining))
        }
        for entry in entries where entry.cumulativeDistanceMeters > target {
            let distance = remaining + entry.cumulativeDistanceMeters - target
            switch entry {
            case .maneuver(let maneuver, _):
                rank += 1
                steps.append(.maneuver(maneuver, rank: rank, distanceFromNowMeters: distance))
            case .landmark(let landmark):
                steps.append(.landmark(landmark, distanceFromNowMeters: distance))
            }
        }
        return steps
    }

    /// TOUS les éléments après le prochain, dans l'ordre de la trace (donc de distance croissante
    /// depuis la position actuelle), virages et repères mêlés sans priorité de catégorie.
    /// "+n" (affiché `rank - 1`) = n-ième virage APRÈS l'élément mis en avant : "+1" pour le
    /// premier virage de la liste, que l'élément mis en avant soit un virage ou un repère.
    static func upcomingSteps(entries: [RoadbookEntry], currentEntryIndex: Int?, distanceRemainingMeters: Double?, currentCumulativeDistanceMeters: Double?) -> [UpcomingStep] {
        guard let currentEntryIndex, let distanceRemainingMeters, entries.indices.contains(currentEntryIndex) else { return [] }
        let current = entries[currentEntryIndex]
        let position = currentCumulativeDistanceMeters ?? (current.cumulativeDistanceMeters - distanceRemainingMeters)
        var maneuverRank = 1
        return entries[(currentEntryIndex + 1)...].map { entry in
            let distance = max(entry.cumulativeDistanceMeters - position, 0)
            switch entry {
            case .maneuver(let maneuver, _):
                maneuverRank += 1
                return .maneuver(maneuver, rank: maneuverRank, distanceFromNowMeters: distance)
            case .landmark(let landmark):
                return .landmark(landmark, distanceFromNowMeters: distance)
            }
        }
    }

    private var upcoming: [UpcomingStep] {
        if offTrack.isOffTrack, let rejoin, rejoin.status == .routed {
            return Self.rejoinSteps(rejoin: rejoin, entries: entries)
        }
        return Self.upcomingSteps(entries: entries, currentEntryIndex: currentEntryIndex, distanceRemainingMeters: distanceRemainingMeters, currentCumulativeDistanceMeters: currentCumulativeDistanceMeters)
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                heroContent
                    .frame(maxWidth: .infinity)
                    .frame(height: heroHeight(availableHeight: geometry.size.height))

                if !upcoming.isEmpty {
                    Divider()
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(upcoming) { step in
                                switch step {
                                case .maneuver(let maneuver, let rank, let distance):
                                    RoadbookUpcomingRow(
                                        maneuver: maneuver,
                                        distanceFromNowMeters: distance,
                                        unit: unit,
                                        rank: rank,
                                        landmark: landmarks[maneuver.id] ?? nil
                                    )
                                case .landmark(let landmark, let distance):
                                    RoadbookUpcomingLandmarkRow(landmark: landmark, distanceFromNowMeters: distance, unit: unit)
                                case .rejoinManeuver(let maneuver, let rank, let distance):
                                    RoadbookUpcomingRow(maneuver: maneuver, distanceFromNowMeters: distance, unit: unit, rank: rank, landmark: nil)
                                        .background(Color.orange.opacity(0.08))
                                case .rejoinArrival(let distance):
                                    RoadbookRejoinArrivalRow(distanceFromNowMeters: distance, unit: unit)
                                }
                                Divider().padding(.leading, 16)
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var heroContent: some View {
        if offTrack.isOffTrack {
            rejoinHero
        } else if let currentEntryIndex, let distanceRemainingMeters, entries.indices.contains(currentEntryIndex) {
            switch entries[currentEntryIndex] {
            case .maneuver(let current, _):
                if isLandscape {
                    RoadbookBigManeuverCardLandscape(maneuver: current, distanceRemainingMeters: distanceRemainingMeters, unit: unit, landmark: landmarks[current.id] ?? nil)
                } else {
                    RoadbookBigManeuverCard(maneuver: current, distanceRemainingMeters: distanceRemainingMeters, unit: unit, landmark: landmarks[current.id] ?? nil)
                }
            case .landmark(let landmark):
                RoadbookBigLandmarkCard(landmark: landmark, distanceRemainingMeters: distanceRemainingMeters, unit: unit, isLandscape: isLandscape)
            }
        } else if !hasLocationFix {
            RoadbookFocusStatusView(systemImage: "location.slash", message: String(localized: "En attente d'une position GPS…", bundle: .appLanguage))
        } else {
            RoadbookFocusStatusView(systemImage: "checkered.flag", message: String(localized: "Toutes les manœuvres de cette trace ont été passées.", bundle: .appLanguage))
        }
    }

    /// Hors trace : prochain virage du chemin de reprise (ou le retour sur la trace), badge
    /// « Hors trace » par-dessus ; sans chemin (en attente, calcul, réseau), la carte hors trace.
    @ViewBuilder
    private var rejoinHero: some View {
        if let rejoin, rejoin.status == .routed, let remaining = rejoin.remainingToTrackMeters {
            Group {
                if let index = rejoin.nextManeuverIndex, rejoin.maneuvers.indices.contains(index), let distance = rejoin.distanceToNextManeuverMeters {
                    if isLandscape {
                        RoadbookBigManeuverCardLandscape(maneuver: rejoin.maneuvers[index], distanceRemainingMeters: distance, unit: unit, landmark: nil)
                    } else {
                        RoadbookBigManeuverCard(maneuver: rejoin.maneuvers[index], distanceRemainingMeters: distance, unit: unit, landmark: nil)
                    }
                } else {
                    RoadbookRejoinArrivalCard(remainingMeters: remaining, unit: unit, isLandscape: isLandscape)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.orange.opacity(0.10))
            .overlay(alignment: .top) {
                RoadbookOffTrackBadge()
                    .padding(.top, 8)
            }
        } else {
            RoadbookOffTrackCard(offTrack: offTrack, unit: unit, isLandscape: isLandscape, rejoinStatus: rejoin?.status)
        }
    }

    private func heroHeight(availableHeight: CGFloat) -> CGFloat {
        if isLandscape {
            return min(CGFloat(RoadBookConstants.focusedHeroLandscapeHeight), availableHeight)
        }
        return max(availableHeight * RoadBookConstants.focusedHeroHeightFraction, RoadBookConstants.focusedHeroMinHeight)
    }
}

extension RoadbookEntry {
    var isManeuver: Bool {
        if case .maneuver = self { return true }
        return false
    }
}

/// Prochain élément = un REPÈRE (it30, priorité par ordre d'arrivée) : même place et même
/// hiérarchie que la carte d'un virage — pictogramme de la catégorie, distance en très grand,
/// nom et côté. Portrait et paysage.
private struct RoadbookBigLandmarkCard: View {
    let landmark: RoadbookLandmarkCheckpoint
    let distanceRemainingMeters: Double
    let unit: DistanceUnit
    let isLandscape: Bool

    @EnvironmentObject private var navigationState: AppNavigationState

    var body: some View {
        Button {
            navigationState.focusRideMap(on: landmark.coordinate)
        } label: {
            if isLandscape {
                HStack(spacing: 24) {
                    RoadbookLandmarkIcon(category: landmark.info.category, size: 90)
                    labels(alignment: .leading)
                    Spacer(minLength: 12)
                    distanceText(size: 60)
                }
                .padding(.horizontal, 20)
            } else {
                VStack(spacing: 16) {
                    RoadbookLandmarkIcon(category: landmark.info.category, size: 110)
                    distanceText(size: 64)
                    labels(alignment: .center)
                }
                .padding(.horizontal, 24)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Prochain repère : \(landmark.info.displayLabel), dans \(unit.displayString(fromMeters: distanceRemainingMeters))", bundle: .appLanguage))
    }

    private func distanceText(size: CGFloat) -> some View {
        Text(unit.displayString(fromMeters: distanceRemainingMeters))
            .font(.system(size: size, weight: .heavy, design: .rounded))
            .monospacedDigit()
            .minimumScaleFactor(0.5)
            .lineLimit(1)
    }

    private func labels(alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 4) {
            Text(landmark.info.localizedLabel)
                .font(.title3.bold())
                .multilineTextAlignment(alignment == .center ? .center : .leading)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            Text(RoadbookLandmarkRowText.detail(landmark.info))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

/// "Hors trace" dans le Road Book (it30) — même terminologie et même seuil que la puce du Ride
/// (`OffTrackDetector`), distance de reprise après le même délai.
private struct RoadbookOffTrackCard: View {
    let offTrack: RoadbookOffTrackState
    let unit: DistanceUnit
    let isLandscape: Bool
    /// État du chemin de reprise (it33) : calcul en cours ou indisponible (réseau).
    var rejoinStatus: RoadbookRejoinDisplay.Status?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let layout = isLandscape ? AnyLayout(HStackLayout(spacing: 24)) : AnyLayout(VStackLayout(spacing: 14))
            layout {
                Image(systemName: "location.slash.fill")
                    .font(.system(size: isLandscape ? 64 : 84, weight: .bold))
                    .foregroundStyle(.orange)
                VStack(spacing: 6) {
                    Text("Hors trace")
                        .font(.system(size: isLandscape ? 40 : 48, weight: .heavy, design: .rounded))
                    if offTrack.showsRejoinDistance(now: context.date), let rejoin = offTrack.rejoinDistanceMeters {
                        Text("Trace à \(unit.displayString(fromMeters: rejoin))")
                            .font(.title3.bold().monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    if rejoinStatus == .computing {
                        Text("Calcul du chemin pour rejoindre la trace…")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    } else if rejoinStatus == .unavailable {
                        Text("Rejoindre la trace — itinéraire indisponible")
                            .font(.subheadline.bold())
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    } else if !offTrack.showsRejoinDistance(now: context.date) || offTrack.rejoinDistanceMeters == nil {
                        Text("Les directions reprennent au retour sur la trace.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
            }
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.orange.opacity(0.10))
            .accessibilityElement(children: .combine)
        }
    }
}

/// Carte plein écran de la manœuvre EN COURS, layout PORTRAIT — pictogramme et distance très
/// larges, lisibles d'un coup d'œil bref (esprit "au moins la moitié de l'écran").
private struct RoadbookBigManeuverCard: View {
    let maneuver: RoadbookManeuver
    let distanceRemainingMeters: Double
    let unit: DistanceUnit
    let landmark: RoadbookLandmarkInfo?

    /// Spec "roadbook-jump-to-map" — retour terrain : "clic sur un virage... aller dans l'onglet
    /// Ride pour voir de quel virage on parle". `AppNavigationState` reste le SEUL point de
    /// passage vers l'onglet Ride (jamais un accès direct à `RideSessionManager` depuis ce
    /// module, invariant "découplé de l'état de Ride actif").
    @EnvironmentObject private var navigationState: AppNavigationState

    var body: some View {
        Button {
            navigationState.focusRideMap(on: maneuver.checkpoint.coordinate)
        } label: {
            cardContent
        }
        .buttonStyle(.plain)
    }

    private var cardContent: some View {
        VStack(spacing: 16) {
            // Pictogramme emoji du repère À CÔTÉ de la flèche (retour terrain it23sexies :
            // "à côté de la flèche il y ait des pictogrammes afin d'augmenter l'aide au niveau
            // du prochain virage") — HStack pour rester bien lisible même en très grande taille.
            HStack(alignment: .center, spacing: 12) {
                RoadbookManeuverIcon(checkpoint: maneuver.checkpoint, size: 120)
                    .foregroundStyle(Color.accentColor)
                if let landmark {
                    Text(landmark.category.emoji)
                        .font(.system(size: 64))
                }
            }
            Text("Cap \(Int(maneuver.headingDegrees.rounded()))°")
                .font(.headline.monospacedDigit())
                .foregroundStyle(.secondary)
            Text(unit.displayString(fromMeters: distanceRemainingMeters))
                .font(.system(size: 64, weight: .heavy, design: .rounded))
                .monospacedDigit()
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            Text(maneuver.checkpoint.tier.label)
                .font(.title3.bold())
                .foregroundStyle(.secondary)
            if let landmark {
                Text(landmark.displayLabel)
                    .font(.subheadline.bold())
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 24)
    }
}

/// Équivalent PAYSAGE (spec "roadbook-ui-redesign", it25, point 2, demande explicite) :
/// pictogramme à GAUCHE, distance à DROITE — jamais le portrait simplement compressé (root cause
/// du bug terrain : les mêmes tailles de police qu'en portrait, sur un écran deux fois moins
/// haut, débordaient jusqu'à chevaucher la tab bar). Agrandi une seconde fois (retour terrain :
/// "augmenter encore plus la taille de la flèche... priorité à la direction et la distance")
/// une fois le sélecteur de mode déplacé dans une colonne à droite (voir `RoadBookTabView.
/// landscapeModeColumn`) — la hauteur ainsi libérée (`RoadBookConstants.
/// focusedHeroLandscapeHeight`, 170→210) permet un pictogramme et un chiffre de distance
/// nettement plus imposants sans déborder.
private struct RoadbookBigManeuverCardLandscape: View {
    let maneuver: RoadbookManeuver
    let distanceRemainingMeters: Double
    let unit: DistanceUnit
    let landmark: RoadbookLandmarkInfo?

    /// Spec "roadbook-jump-to-map" — voir `RoadbookBigManeuverCard` (portrait) pour le détail.
    @EnvironmentObject private var navigationState: AppNavigationState

    var body: some View {
        Button {
            navigationState.focusRideMap(on: maneuver.checkpoint.coordinate)
        } label: {
            cardContent
        }
        .buttonStyle(.plain)
    }

    private var cardContent: some View {
        HStack(spacing: 24) {
            VStack(spacing: 4) {
                RoadbookManeuverIcon(checkpoint: maneuver.checkpoint, size: 120)
                    .foregroundStyle(Color.accentColor)
                if let landmark {
                    Text(landmark.category.emoji)
                        .font(.system(size: 40))
                }
            }

            // Fix "roadbook-landscape-tier-label-truncated" (it25, retour terrain avec capture :
            // "Virage prononcé" tronqué en "Virage pr...") — agrandi une seconde fois par erreur
            // en même temps que le pictogramme/la distance ; la demande portait explicitement sur
            // "la flèche" et "la distance", pas ce texte. Revenu à sa taille d'origine
            // (`.headline`, qui tenait déjà correctement) + `minimumScaleFactor` en filet de
            // sécurité plutôt qu'une troncature "..." si jamais l'espace redevient juste.
            VStack(alignment: .leading, spacing: 2) {
                Text(maneuver.checkpoint.tier.label)
                    .font(.headline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if let landmark {
                    Text(landmark.displayLabel)
                        .font(.caption.bold())
                        .foregroundStyle(.orange)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Text("Cap \(Int(maneuver.headingDegrees.rounded()))°")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 12)

            Text(unit.displayString(fromMeters: distanceRemainingMeters))
                .font(.system(size: 60, weight: .heavy, design: .rounded))
                .monospacedDigit()
                .minimumScaleFactor(0.5)
                .lineLimit(1)
        }
        .padding(.leading, 20)
        .padding(.trailing, 20)
    }
}

private struct RoadbookUpcomingRow: View {
    let maneuver: RoadbookManeuver
    let distanceFromNowMeters: Double
    let unit: DistanceUnit
    /// "+2"/"+3" — position relative à la manœuvre en cours, jamais l'index absolu dans la
    /// trace (ce que voit le pilote, c'est "dans 2 manœuvres", pas "la 7e de la liste").
    let rank: Int
    let landmark: RoadbookLandmarkInfo?

    /// Spec "roadbook-jump-to-map" — voir `RoadbookBigManeuverCard` pour le détail.
    @EnvironmentObject private var navigationState: AppNavigationState

    var body: some View {
        Button {
            navigationState.focusRideMap(on: maneuver.checkpoint.coordinate)
        } label: {
            rowContent
        }
        .buttonStyle(.plain)
    }

    private var rowContent: some View {
        HStack(spacing: 16) {
            Text("+\(rank - 1)")
                .font(.caption.bold().monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 28)

            HStack(spacing: 4) {
                RoadbookManeuverIcon(checkpoint: maneuver.checkpoint, size: 28)
                    .foregroundStyle(.primary)
                if let landmark {
                    Text(landmark.category.emoji)
                        .font(.system(size: 22))
                }
            }
            .frame(width: 60)

            VStack(alignment: .leading, spacing: 1) {
                Text(maneuver.checkpoint.tier.label)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let landmark {
                    Text(landmark.displayLabel)
                        .font(.caption2)
                        .foregroundStyle(.orange)
                        .lineLimit(1)
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 1) {
                Text(unit.displayString(fromMeters: distanceFromNowMeters))
                    .font(.headline.monospacedDigit())
                Text("\(Int(maneuver.headingDegrees.rounded()))°")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }
}

/// Repère visible dans la liste des étapes à venir — pictogramme de la catégorie + nom, côté,
/// fond teinté : jamais confondu avec un virage.
private struct RoadbookUpcomingLandmarkRow: View {
    let landmark: RoadbookLandmarkCheckpoint
    let distanceFromNowMeters: Double
    let unit: DistanceUnit

    @EnvironmentObject private var navigationState: AppNavigationState

    var body: some View {
        Button {
            navigationState.focusRideMap(on: landmark.coordinate)
        } label: {
            HStack(spacing: 16) {
                Color.clear.frame(width: 28, height: 1)
                RoadbookLandmarkIcon(category: landmark.info.category, size: 22)
                    .frame(width: 60)
                VStack(alignment: .leading, spacing: 1) {
                    Text(landmark.info.localizedLabel)
                        .font(.subheadline.bold())
                        .lineLimit(1)
                    Text(RoadbookLandmarkRowText.detail(landmark.info))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(unit.displayString(fromMeters: distanceFromNowMeters))
                    .font(.headline.monospacedDigit())
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(Color.accentColor.opacity(0.06))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Repère : \(landmark.info.displayLabel), dans \(unit.displayString(fromMeters: distanceFromNowMeters))", bundle: .appLanguage))
    }
}

/// Badge « Hors trace » posé sur le prochain virage du chemin de reprise (it33) : on suit un chemin
/// hors de la trace, jamais confondu avec la trace elle-même.
private struct RoadbookOffTrackBadge: View {
    var body: some View {
        Label("Hors trace", systemImage: "location.slash.fill")
            .font(.subheadline.bold())
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.orange, in: Capsule())
            .accessibilityLabel(String(localized: "Hors trace : chemin pour rejoindre la trace", bundle: .appLanguage))
    }
}

/// Plus aucun virage sur le chemin de reprise : tout droit jusqu'à la trace (it33).
private struct RoadbookRejoinArrivalCard: View {
    let remainingMeters: Double
    let unit: DistanceUnit
    let isLandscape: Bool

    var body: some View {
        let layout = isLandscape ? AnyLayout(HStackLayout(spacing: 24)) : AnyLayout(VStackLayout(spacing: 14))
        layout {
            Image(systemName: "arrow.triangle.merge")
                .font(.system(size: isLandscape ? 80 : 110, weight: .bold))
                .foregroundStyle(Color.accentColor)
            VStack(spacing: 6) {
                Text(unit.displayString(fromMeters: remainingMeters))
                    .font(.system(size: isLandscape ? 60 : 64, weight: .heavy, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                Text("Retour sur la trace")
                    .font(.title3.bold())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 24)
        .accessibilityElement(children: .combine)
    }
}

/// Ligne « Retour sur la trace » à la fin des virages du chemin de reprise (it33).
private struct RoadbookRejoinArrivalRow: View {
    let distanceFromNowMeters: Double
    let unit: DistanceUnit

    var body: some View {
        HStack(spacing: 16) {
            Color.clear.frame(width: 28, height: 1)
            Image(systemName: "arrow.triangle.merge")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 60)
            Text("Retour sur la trace")
                .font(.subheadline.bold())
            Spacer()
            Text(unit.displayString(fromMeters: distanceFromNowMeters))
                .font(.headline.monospacedDigit())
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(Color.orange.opacity(0.08))
        .accessibilityElement(children: .combine)
    }
}

private struct RoadbookFocusStatusView: View {
    let systemImage: String
    let message: String

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text(message)
                .font(.title3.bold())
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }
}
