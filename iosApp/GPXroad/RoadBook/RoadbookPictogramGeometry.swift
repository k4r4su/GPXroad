import Foundation
import CoreGraphics
import GPXroadShared

/// Géométrie PURE des pictogrammes enrichis (it24 ; rond-point redessiné it33) — partagée entre le
/// rendu SwiftUI (`RoadbookPictograms.swift`) et le rendu Core Graphics du PDF
/// (`RoadbookPDFExporter`), pour que les deux dessinent EXACTEMENT la même forme. Aucun dessin ici,
/// uniquement des points — testable sans instancier une vue.
enum RoadbookPictogramGeometry {}

/// Rond-point dessiné (it33 ; branches réelles depuis it34) : anneau, entrée en bas, trajet
/// parcouru dans le sens de circulation jusqu'à la sortie RÉELLEMENT prise, et — quand le rond-point
/// a été analysé sur OSM (`Checkpoint.roundabout`) — TOUTES ses branches sur 8 positions, façon
/// roadbook de rallye : on reconnaît la sortie sur le terrain sans avoir à compter. Sans analyse
/// OSM : entrée et sortie seules, sans numéro (jamais celui de Valhalla).
struct RoadbookRoundaboutDrawing {
    typealias Segment = (from: CGPoint, to: CGPoint)

    /// Branche non empruntée : sortie comptée, petite voie (service, chemin, privé) ou sens unique
    /// entrant (barré au bout, comme un sens interdit).
    struct Branch {
        let segment: Segment
        let kind: RoadbookRoundabout.BranchKind
        /// Petit trait perpendiculaire au bout (sens interdit), `nil` sinon.
        let bar: Segment?
    }

    let center: CGPoint
    let ringRadius: CGFloat
    /// Entrée : du bord extérieur (en bas) jusqu'à l'anneau.
    let entry: Segment
    /// Trajet sur l'anneau, de l'entrée à la sortie (polyligne fine).
    let path: [CGPoint]
    /// Sortie prise : de l'anneau vers l'extérieur (flèche au bout).
    let exit: Segment
    let branches: [Branch]
    let exitNumber: Int?
    /// Angle (radians, repère écran) de la flèche de sortie.
    let exitAngleRadians: Double

    init(checkpoint: Checkpoint, in rect: CGRect) {
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

        let exitAngle: Double
        let sweep: Double
        let clockwise: Bool
        if let roundabout = checkpoint.roundabout {
            exitAngle = roundabout.exitAngleDegrees
            clockwise = roundabout.clockwise
            let travelled = clockwise ? exitAngle - entryAngle : entryAngle - exitAngle
            let modulo = (travelled.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
            // Demi-tour : presque un tour complet, la flèche ressort juste à côté de l'entrée.
            sweep = modulo < 1 ? 340 : modulo
            branches = roundabout.branches
                .filter { $0.kind != .entry && $0.kind != .takenExit }
                .map { branch in
                    let length = branch.kind == .countedExit ? outer : side * 0.36
                    let end = point(branch.angleDegrees, length)
                    var bar: Segment?
                    if branch.kind == .noExit {
                        bar = (point(branch.angleDegrees - 9, length * 0.92), point(branch.angleDegrees + 9, length * 0.92))
                    }
                    return Branch(segment: (point(branch.angleDegrees, ring), end), kind: branch.kind, bar: bar)
                }
            exitNumber = roundabout.exitNumber
        } else {
            let signedTurn: Double
            switch checkpoint.direction {
            case .left: signedTurn = -checkpoint.turnAngleDegrees
            case .right: signedTurn = checkpoint.turnAngleDegrees
            case .straight, .uTurn: signedTurn = checkpoint.direction == .uTurn ? 180 : 0
            }
            let layout = GPXroadShared.RoundaboutPictogram.shared.layout(signedTurnDegrees: signedTurn, exitCount: nil)
            exitAngle = layout.exitAngleDegrees
            sweep = layout.pathSweepDegrees
            clockwise = false
            branches = []
            exitNumber = nil
        }
        let steps = max(Int(sweep / 6), 2)
        path = (0...steps).map { point(entryAngle + (clockwise ? 1 : -1) * sweep * Double($0) / Double(steps), ring) }
        let drawnExit = entryAngle + (clockwise ? 1 : -1) * sweep
        exit = (point(drawnExit, ring), point(drawnExit, outer))
        exitAngleRadians = drawnExit * .pi / 180
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
