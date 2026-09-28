import SwiftUI

/// Bannière latérale de guidage de liaison (spec "rejoin-trace-guidance-banner", it18, Bloc 5) —
/// affichée pendant un recalcul automatique (spec "link-recompute-on-divergence", Bloc 3,
/// `ResumeGuidance.isAutomatic`) : même design colonne/translucide que `LateralCapBannerView`
/// (largeur fixe 92 pt, ISOLÉE, fix "overlay-never-pushes"), teinte indigo-lite pour distinguer
/// visuellement "liaison temporaire vers la trace" de "virage sur la trace elle-même" (orange/
/// rouge selon palier) — REJOINDRE_GUIDANCE_BANNER (RideConstants) peut la désactiver sans
/// toucher au recalcul lui-même. Distance mise à jour EN CONTINU par l'appelant (jamais stale,
/// voir RideSessionManager.resumeGuidanceLiveDistanceMeters). Depuis it33 : prochain virage du
/// chemin de liaison (`RideSessionManager.rejoinNextStep`) et icône « hors trace ».
struct RejoinGuidanceBannerView: View {
    let distanceMeters: Double
    /// Fix "rejoin-icon-dynamic-bearing" (it22, retour terrain : "l'icône a un statique, elle
    /// doit devenir dynamique et refléter la vraie direction à prendre") — cap relatif (0° =
    /// droit devant l'écran, cohérent avec la caméra cap-en-haut) vers le point de jonction,
    /// même calcul/patron que `OffTrackChipView.relativeBearingDegrees`. `nil` tant qu'aucune
    /// position n'est disponible (repli sur l'icône fixe non tournée, jamais un crash).
    let relativeBearingDegrees: Double?
    /// It33 : prochain virage du chemin de reprise (mêmes pictogrammes que le Road Book) et
    /// distance restante jusqu'à la trace le long du chemin — `nil` tant que le chemin n'est pas
    /// calculé (repli sur la flèche orientée vers le point de retour et la distance à vol d'oiseau).
    var nextStep: RejoinNextStep?

    private static let width: CGFloat = 92

    var body: some View {
        VStack(spacing: 8) {
            // Même icône « hors trace » que le Road Book : on suit un chemin HORS de la trace.
            Image(systemName: "location.slash.fill")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.orange)
            if let checkpoint = nextStep?.checkpoint, let turnDistance = nextStep?.distanceToTurnMeters {
                RoadbookManeuverIcon(checkpoint: checkpoint, size: 40)
                    .foregroundStyle(.white)
                Text(Self.distanceText(turnDistance))
                    .font(.system(size: 20, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text("Trace à \(Self.distanceText(nextStep?.remainingToTrackMeters ?? distanceMeters))")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.85))
                    .monospacedDigit()
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.7)
            } else {
                Image(systemName: "arrow.triangle.merge")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(.white)
                    .rotationEffect(.degrees(nextStep == nil ? (relativeBearingDegrees ?? 0) : 0))
                Text("Rejoindre\nla trace")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                Text(Self.distanceText(nextStep?.remainingToTrackMeters ?? distanceMeters))
                    .font(.system(size: 18, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
        .frame(width: Self.width)
        .padding(.vertical, 12)
        .padding(.horizontal, 6)
        .ridePanelStyle(tint: .indigo, tintOpacity: 0.4)
        .accessibilityElement(children: .combine)
    }

    static func distanceText(_ meters: Double) -> String {
        meters < 1000 ? "\(Int(meters.rounded())) m" : String(format: "%.1f km", meters / 1000)
    }
}
