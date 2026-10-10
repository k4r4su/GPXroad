import SwiftUI
import CoreLocation

/// Aperçu d'une trace (Bibliothèque, fiche) : tracé orange sur fond du design, départ et arrivée marqués.
/// Dessiné à partir d'au plus 120 points répartis : aucun coût visible même pour une trace de 20 000 points.
struct TrackMiniMap: View {
    let points: [GPXPoint]
    @Environment(\.theme) private var theme

    var body: some View {
        Canvas { context, size in
            let sample = Self.sample(points, maxCount: 120)
            guard sample.count > 1 else { return }
            let latitudes = sample.map(\.latitude)
            let longitudes = sample.map(\.longitude)
            guard let minLat = latitudes.min(), let maxLat = latitudes.max(), let minLon = longitudes.min(), let maxLon = longitudes.max() else { return }
            // Projection équirectangulaire : un degré de longitude vaut cos(latitude) degrés de latitude.
            let midLat = (minLat + maxLat) / 2
            let width = max((maxLon - minLon) * cos(midLat * .pi / 180), 1e-6)
            let height = max(maxLat - minLat, 1e-6)
            let inset: CGFloat = 10
            let scale = min((size.width - 2 * inset) / width, (size.height - 2 * inset) / height)
            let offsetX = (size.width - width * scale) / 2
            let offsetY = (size.height - height * scale) / 2
            func point(_ p: GPXPoint) -> CGPoint {
                CGPoint(x: offsetX + (p.longitude - minLon) * cos(midLat * .pi / 180) * scale,
                        y: offsetY + (maxLat - p.latitude) * scale)
            }
            var path = Path()
            path.move(to: point(sample[0]))
            for p in sample.dropFirst() { path.addLine(to: point(p)) }
            context.stroke(path, with: .color(theme.trace), style: StrokeStyle(lineWidth: 3.5, lineCap: .round, lineJoin: .round))
            let start = point(sample[0]), end = point(sample[sample.count - 1])
            context.fill(Path(ellipseIn: CGRect(x: start.x - 5, y: start.y - 5, width: 10, height: 10)), with: .color(Color(hex: 0x2FA866)))
            context.fill(Path(ellipseIn: CGRect(x: end.x - 5, y: end.y - 5, width: 10, height: 10)), with: .color(theme.ink))
        }
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(theme.tonal))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityHidden(true)
    }

    static func sample(_ points: [GPXPoint], maxCount: Int) -> [GPXPoint] {
        guard points.count > maxCount else { return points }
        let step = Double(points.count - 1) / Double(maxCount - 1)
        return (0..<maxCount).map { points[Int((Double($0) * step).rounded())] }
    }
}
