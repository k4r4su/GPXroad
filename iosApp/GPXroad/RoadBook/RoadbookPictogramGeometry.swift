import Foundation
import CoreGraphics
import GPXroadShared

/// Géométrie PURE des pictogrammes enrichis (it24 ; rond-point redessiné it33) — partagée entre le
/// rendu SwiftUI (`RoadbookPictograms.swift`) et le rendu Core Graphics du PDF
/// (`RoadbookPDFExporter`), pour que les deux dessinent EXACTEMENT la même forme. Aucun dessin ici,
/// uniquement des points — testable sans instancier une vue.
enum RoadbookPictogramGeometry {}

/// Rond-point dessiné (it33, retour terrain : « il faudrait dessiner le rond-point pour indiquer
/// clairement la sortie, l'image générique n'est pas du tout claire ») : anneau, entrée en bas,
/// trajet parcouru dans le sens de circulation (par la droite) jusqu'à la sortie RÉELLEMENT prise —
/// placée selon le virage net de la trace, pas une convention par rang —, sorties passées en
/// discret, numéro de la sortie au centre. Angles : module partagé (`RoundaboutPictogram`).
struct RoadbookRoundaboutDrawing {
    typealias Segment = (from: CGPoint, to: CGPoint)

    let center: CGPoint
    let ringRadius: CGFloat
    /// Entrée : du bord extérieur (en bas) jusqu'à l'anneau.
    let entry: Segment
    /// Trajet sur l'anneau, de l'entrée à la sortie (polyligne fine).
    let path: [CGPoint]
    /// Sortie prise : de l'anneau vers l'extérieur (flèche au bout).
    let exit: Segment
    /// Sorties passées avant la bonne (quand leur nombre est connu).
    let skippedExits: [Segment]
    let exitNumber: Int?
    /// Angle (radians, repère écran) de la flèche de sortie.
    let exitAngleRadians: Double

    init(checkpoint: Checkpoint, in rect: CGRect) {
        let signedTurn: Double
        switch checkpoint.direction {
        case .left: signedTurn = -checkpoint.turnAngleDegrees
        case .right: signedTurn = checkpoint.turnAngleDegrees
        case .straight, .uTurn: signedTurn = checkpoint.direction == .uTurn ? 180 : 0
        }
        let layout = GPXroadShared.RoundaboutPictogram.shared.layout(
            signedTurnDegrees: signedTurn,
            exitCount: checkpoint.roundaboutExitCount.map { KotlinInt(int: Int32($0)) }
        )
        let side = min(rect.width, rect.height)
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let ring = side * 0.25
        let outer = side * 0.40
        func point(_ degrees: Double, _ radius: CGFloat) -> CGPoint {
            let radians = degrees * .pi / 180
            return CGPoint(x: center.x + CGFloat(sin(radians)) * radius, y: center.y - CGFloat(cos(radians)) * radius)
        }
        self.center = center
        ringRadius = ring
        let entryAngle = GPXroadShared.RoundaboutPictogram.shared.ENTRY_ANGLE_DEGREES
        entry = (point(entryAngle, outer), point(entryAngle, ring))
        let steps = max(Int(layout.pathSweepDegrees / 6), 2)
        path = (0...steps).map { point(entryAngle - layout.pathSweepDegrees * Double($0) / Double(steps), ring) }
        exit = (point(layout.exitAngleDegrees, ring), point(layout.exitAngleDegrees, outer))
        skippedExits = layout.intermediateExitAngles.map { (point($0.doubleValue, ring), point($0.doubleValue, side * 0.42)) }
        exitNumber = checkpoint.roundaboutExitCount
        exitAngleRadians = layout.exitAngleDegrees * .pi / 180
    }

    /// Pointe de flèche PLEINE au bout de la sortie : sommet (au-delà du trait), puis les deux coins
    /// de la base (posée sur le bout du trait).
    func arrowhead(length: CGFloat) -> [CGPoint] {
        let base = exit.to
        let dx = CGFloat(sin(exitAngleRadians)), dy = -CGFloat(cos(exitAngleRadians))
        let tip = CGPoint(x: base.x + dx * length, y: base.y + dy * length)
        let half = length * 0.62
        return [tip, CGPoint(x: base.x - dy * half, y: base.y + dx * half), CGPoint(x: base.x + dy * half, y: base.y - dx * half)]
    }
}
