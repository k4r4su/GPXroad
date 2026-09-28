import SwiftUI

/// Pictogrammes DESSINÉS dédiés pour les 3 paliers route-aware (spec
/// "roadbook-route-aware-maneuvers", it24, point 2 — retour terrain explicite : "pas une flèche
/// courbe générique" pour le rond-point, "pictogramme en Y" pour la fourche, "pictogramme dédié
/// distinct du virage classique" pour la fusion/bretelle). Utilisés par `RoadBookTabView`/
/// `RoadbookFocusedView` (écran) ET repris en Core Graphics par `RoadbookPDFExporter` (PDF,
/// même géométrie via `RoadbookPictogramGeometry`) — jamais un simple SF Symbol générique pour
/// ces trois paliers sur les surfaces où l'utilisateur les LIT réellement (le repli SF Symbol de
/// `RoadbookTier.systemImageName` ne sert plus qu'aux pins carte, trop petits pour un dessin).
///
/// Teinte unique reprenant `RoadBookConstants.pdfAccentColorRGB` (esprit chevrons orange/rouge du
/// logo, déjà utilisé par le PDF depuis it23) pour l'élément mis en avant ; `.secondary` pour le
/// contexte discret (sorties non prises, branche non suivie) — jamais l'inverse, l'accent doit
/// toujours désigner SANS AMBIGUÏTÉ ce qu'il faut faire.
enum RoadbookPictogramStyle {
    static let accentColor = Color(
        red: RoadBookConstants.pdfAccentColorRGB.red,
        green: RoadBookConstants.pdfAccentColorRGB.green,
        blue: RoadBookConstants.pdfAccentColorRGB.blue
    )
}

/// Point d'entrée UNIQUE pour afficher le pictogramme d'un `Checkpoint` (spec it24, point 2) —
/// `.roundabout`/`.fork`/`.merge` obtiennent un dessin dédié, tout le reste retombe sur le SF
/// Symbol existant (`RoadbookTier.systemImageName`/`rotationDegrees`, inchangé depuis it23bis).
/// Remplace la paire `Image(systemName:)`/`.rotationEffect` dupliquée sur les 3 écrans
/// consommateurs (table/vue focalisée) par un seul appel.
struct RoadbookManeuverIcon: View {
    let checkpoint: Checkpoint
    let size: CGFloat

    var body: some View {
        switch checkpoint.tier {
        case .roundabout:
            RoadbookRoundaboutPictogram(checkpoint: checkpoint)
                .frame(width: size, height: size)
        case .fork:
            RoadbookForkPictogram(direction: checkpoint.direction)
                .frame(width: size, height: size)
        case .merge:
            RoadbookMergePictogram(direction: checkpoint.direction)
                .frame(width: size, height: size)
        case .light, .marked, .hard, .veryHard, .uTurn, .lightDirectionChange:
            Image(systemName: checkpoint.tier.systemImageName(direction: checkpoint.direction))
                .font(.system(size: size, weight: .semibold))
                .rotationEffect(.degrees(checkpoint.tier.rotationDegrees(direction: checkpoint.direction) ?? 0))
        }
    }
}

/// Rond-point dessiné (it33) : anneau discret, trajet en surbrillance de l'entrée (en bas) à la
/// sortie réellement prise, flèche au bout, sorties passées en traits fins, numéro de sortie au
/// centre — géométrie dans `RoadbookRoundaboutDrawing`, identique au PDF.
struct RoadbookRoundaboutPictogram: View {
    let checkpoint: Checkpoint

    var body: some View {
        Canvas { context, size in
            let drawing = RoadbookRoundaboutDrawing(checkpoint: checkpoint, in: CGRect(origin: .zero, size: size))
            let side = min(size.width, size.height)
            let thin = side * 0.045
            let bold = side * 0.085
            let accent = RoadbookPictogramStyle.accentColor

            var ring = Path()
            ring.addArc(center: drawing.center, radius: drawing.ringRadius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
            context.stroke(ring, with: .color(.secondary.opacity(0.6)), lineWidth: thin)

            for skipped in drawing.skippedExits {
                context.stroke(Path { $0.move(to: skipped.from); $0.addLine(to: skipped.to) }, with: .color(.secondary.opacity(0.7)), style: StrokeStyle(lineWidth: thin * 1.3, lineCap: .round))
            }

            var route = Path()
            route.move(to: drawing.entry.from)
            route.addLine(to: drawing.entry.to)
            for point in drawing.path { route.addLine(to: point) }
            route.addLine(to: drawing.exit.to)
            context.stroke(route, with: .color(accent), style: StrokeStyle(lineWidth: bold, lineCap: .butt, lineJoin: .round))

            let head = drawing.arrowhead(length: side * 0.13)
            context.fill(Path { $0.addLines(head); $0.closeSubpath() }, with: .color(accent))

            if let number = drawing.exitNumber {
                context.draw(
                    Text("\(number)").font(.system(size: side * 0.24, weight: .heavy, design: .rounded)).foregroundColor(.primary),
                    at: drawing.center
                )
            }
        }
        .accessibilityLabel(RoadbookRoundaboutPictogram.accessibilityText(exitCount: checkpoint.roundaboutExitCount))
    }

    static func accessibilityText(exitCount: Int?) -> String {
        guard let exitCount else { return String(localized: "Rond-point", bundle: .appLanguage) }
        return String(localized: "Rond-point, sortie \(exitCount)", bundle: .appLanguage)
    }
}

/// "Y" — tige commune puis deux branches divergentes, celle à suivre en surbrillance (accent +
/// trait plus épais), l'autre en trait fin discret. `.straight` met en avant la branche centrale
/// (l'axe qui continue tout droit à un embranchement, PAS un simple "tout droit" sans choix —
/// voir `ValhallaManeuverType.stayStraight`, toujours un vrai point de décision).
struct RoadbookForkPictogram: View {
    let direction: TurnDirection

    private var branchAngleDegrees: Double {
        switch direction {
        case .left: return -28
        case .right: return 28
        default: return 0
        }
    }

    var body: some View {
        Canvas { context, size in
            let bottom = CGPoint(x: size.width / 2, y: size.height * 0.92)
            let junction = CGPoint(x: size.width / 2, y: size.height * 0.5)
            let stem = Path { path in
                path.move(to: bottom)
                path.addLine(to: junction)
            }
            context.stroke(stem, with: .color(.secondary), style: StrokeStyle(lineWidth: size.width * 0.07, lineCap: .round))

            let branchLength = size.height * 0.42
            let dimAngle = branchAngleDegrees > 0 ? -22.0 : (branchAngleDegrees < 0 ? 22.0 : -26.0)
            let dimEnd = Self.point(from: junction, angleDegrees: dimAngle, length: branchLength)
            var dimBranch = Path()
            dimBranch.move(to: junction)
            dimBranch.addLine(to: dimEnd)
            context.stroke(dimBranch, with: .color(.secondary.opacity(0.4)), style: StrokeStyle(lineWidth: size.width * 0.05, lineCap: .round))

            let takenEnd = Self.point(from: junction, angleDegrees: branchAngleDegrees, length: branchLength * 1.05)
            var takenBranch = Path()
            takenBranch.move(to: junction)
            takenBranch.addLine(to: takenEnd)
            context.stroke(takenBranch, with: .color(RoadbookPictogramStyle.accentColor), style: StrokeStyle(lineWidth: size.width * 0.08, lineCap: .round))

            let headLength = size.width * 0.13
            let radians = branchAngleDegrees * .pi / 180
            let headAngle = Double.pi / 6.5
            var head = Path()
            head.move(to: takenEnd)
            head.addLine(to: CGPoint(x: takenEnd.x - headLength * sin(radians + headAngle), y: takenEnd.y - headLength * cos(radians + headAngle)))
            head.move(to: takenEnd)
            head.addLine(to: CGPoint(x: takenEnd.x - headLength * sin(radians - headAngle), y: takenEnd.y - headLength * cos(radians - headAngle)))
            context.stroke(head, with: .color(RoadbookPictogramStyle.accentColor), style: StrokeStyle(lineWidth: size.width * 0.08, lineCap: .round))
        }
    }

    /// `angleDegrees` : 0 = vers le haut, positif = vers la droite (même convention que le reste
    /// du roadbook).
    private static func point(from origin: CGPoint, angleDegrees: Double, length: CGFloat) -> CGPoint {
        let radians = angleDegrees * .pi / 180
        return CGPoint(x: origin.x + length * sin(radians), y: origin.y - length * cos(radians))
    }
}

/// Deux traits qui convergent vers une flèche unique en surbrillance — distinct du virage
/// classique (une seule flèche tournée) : montre explicitement "une autre voie rejoint la
/// tienne", esprit bretelle/fusion. `direction` incline le trait secondaire du côté d'où vient la
/// bretelle (`.straight`/`.merge`, sans variante directionnelle côté Valhalla : les deux traits
/// convergent symétriquement).
struct RoadbookMergePictogram: View {
    let direction: TurnDirection

    var body: some View {
        Canvas { context, size in
            let top = CGPoint(x: size.width / 2, y: size.height * 0.12)
            let junction = CGPoint(x: size.width / 2, y: size.height * 0.55)
            let mainStart: CGPoint
            let secondaryStart: CGPoint
            switch direction {
            case .right:
                mainStart = CGPoint(x: size.width * 0.28, y: size.height * 0.92)
                secondaryStart = CGPoint(x: size.width * 0.82, y: size.height * 0.92)
            case .left:
                mainStart = CGPoint(x: size.width * 0.72, y: size.height * 0.92)
                secondaryStart = CGPoint(x: size.width * 0.18, y: size.height * 0.92)
            default:
                mainStart = CGPoint(x: size.width * 0.32, y: size.height * 0.92)
                secondaryStart = CGPoint(x: size.width * 0.68, y: size.height * 0.92)
            }

            var secondary = Path()
            secondary.move(to: secondaryStart)
            secondary.addLine(to: junction)
            context.stroke(secondary, with: .color(.secondary.opacity(0.45)), style: StrokeStyle(lineWidth: size.width * 0.06, lineCap: .round))

            var main = Path()
            main.move(to: mainStart)
            main.addLine(to: junction)
            context.stroke(main, with: .color(RoadbookPictogramStyle.accentColor), style: StrokeStyle(lineWidth: size.width * 0.08, lineCap: .round))

            var trunk = Path()
            trunk.move(to: junction)
            trunk.addLine(to: top)
            context.stroke(trunk, with: .color(RoadbookPictogramStyle.accentColor), style: StrokeStyle(lineWidth: size.width * 0.08, lineCap: .round))

            let headLength = size.width * 0.14
            var head = Path()
            head.move(to: top)
            head.addLine(to: CGPoint(x: top.x - headLength * 0.6, y: top.y + headLength))
            head.move(to: top)
            head.addLine(to: CGPoint(x: top.x + headLength * 0.6, y: top.y + headLength))
            context.stroke(head, with: .color(RoadbookPictogramStyle.accentColor), style: StrokeStyle(lineWidth: size.width * 0.08, lineCap: .round))
        }
    }
}

/// Pictogramme de l'entrée d'agglomération (panneau blanc à bordure rouge, comme le vrai) —
/// volontairement DISTINCT de toute flèche : un repère n'est jamais un changement de direction.
struct RoadbookCitySignIcon: View {
    let size: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.12, style: .continuous)
                .fill(Color.white)
            RoundedRectangle(cornerRadius: size * 0.12, style: .continuous)
                .strokeBorder(Color(red: 0.85, green: 0.1, blue: 0.1), lineWidth: max(size * 0.1, 1.5))
            Image(systemName: "building.2.fill")
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(Color.black)
        }
        .frame(width: size * 1.4, height: size)
        .accessibilityHidden(true)
    }
}

/// Pictogramme d'un repère visible, par catégorie (itération "repères = uniquement ce que le
/// conducteur voit") : panneau dessiné pour l'entrée d'agglomération, emoji pour les autres.
struct RoadbookLandmarkIcon: View {
    let category: RoadbookLandmarkCategory
    let size: CGFloat

    var body: some View {
        if category == .citySign {
            RoadbookCitySignIcon(size: size)
        } else {
            Text(category.emoji)
                .font(.system(size: size))
                .accessibilityHidden(true)
        }
    }
}
