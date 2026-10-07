import SwiftUI
import MapLibre
import CoreLocation

/// Carte de « Créer un itinéraire » : un tap pose un point, l'itinéraire calculé est tracé en orange.
struct PlannerMapView: UIViewRepresentable {
    let waypoints: [CLLocationCoordinate2D]
    let route: [CLLocationCoordinate2D]
    let fitToken: Int
    let startCenter: CLLocationCoordinate2D
    let onTap: (CLLocationCoordinate2D) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MLNMapView {
        let mapView = MLNMapView(frame: .zero, styleJSON: MapEngineConstants.buildStyleJSON(for: .vectorHosted(flavor: .standard)))
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = true
        mapView.showsCompassView = false
        mapView.logoView.isHidden = true
        mapView.isPitchEnabled = false
        mapView.setCenter(startCenter, zoomLevel: 9, animated: false)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        // Le double tap (zoom) garde la priorité : un tap simple ne pose un point que s'il n'est pas le début d'un double tap.
        for recognizer in mapView.gestureRecognizers ?? [] {
            if let doubleTap = recognizer as? UITapGestureRecognizer, doubleTap.numberOfTapsRequired == 2 { tap.require(toFail: doubleTap) }
        }
        mapView.addGestureRecognizer(tap)
        context.coordinator.mapView = mapView
        return mapView
    }

    func updateUIView(_ mapView: MLNMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.refresh(mapView)
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        var parent: PlannerMapView
        weak var mapView: MLNMapView?
        private var drawnSignature = ""
        private var fittedToken = -1

        init(_ parent: PlannerMapView) { self.parent = parent }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let mapView, gesture.state == .ended else { return }
            let point = gesture.location(in: mapView)
            // Un tap sur un point existant ne pose rien (il sert à lire son nom).
            for waypoint in parent.waypoints {
                let existing = mapView.convert(waypoint, toPointTo: mapView)
                if hypot(existing.x - point.x, existing.y - point.y) < 30 { return }
            }
            parent.onTap(mapView.convert(point, toCoordinateFrom: mapView))
        }

        func refresh(_ mapView: MLNMapView) {
            let signature = "\(parent.waypoints.map { "\($0.latitude),\($0.longitude)" }.joined(separator: ";"))|\(parent.route.count)|\(parent.route.last?.latitude ?? 0)"
            if signature != drawnSignature {
                drawnSignature = signature
                if let old = mapView.annotations { mapView.removeAnnotations(old) }
                var annotations: [MLNAnnotation] = []
                if parent.route.count > 1 {
                    var coordinates = parent.route
                    annotations.append(MLNPolyline(coordinates: &coordinates, count: UInt(coordinates.count)))
                }
                for (index, coordinate) in parent.waypoints.enumerated() {
                    let pin = MLNPointAnnotation()
                    pin.coordinate = coordinate
                    pin.title = Self.label(index: index, count: parent.waypoints.count)
                    annotations.append(pin)
                }
                mapView.addAnnotations(annotations)
            }
            if fittedToken != parent.fitToken {
                fittedToken = parent.fitToken
                if parent.route.count > 1 { fit(mapView) }
            }
        }

        private func fit(_ mapView: MLNMapView) {
            let latitudes = parent.route.map(\.latitude)
            let longitudes = parent.route.map(\.longitude)
            guard let minLat = latitudes.min(), let maxLat = latitudes.max(), let minLon = longitudes.min(), let maxLon = longitudes.max() else { return }
            let bounds = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: minLat, longitude: minLon), ne: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon))
            mapView.setVisibleCoordinateBounds(bounds, edgePadding: UIEdgeInsets(top: 60, left: 40, bottom: 260, right: 40), animated: true, completionHandler: nil)
        }

        static func label(index: Int, count: Int) -> String {
            if index == 0 { return String(localized: "Départ", bundle: .appLanguage) }
            if index == count - 1 { return String(localized: "Arrivée", bundle: .appLanguage) }
            return String(format: String(localized: "Étape %lld", bundle: .appLanguage), index)
        }

        func mapView(_ mapView: MLNMapView, strokeColorForShapeAnnotation annotation: MLNShape) -> UIColor {
            UIColor(red: 1.0, green: 0.55, blue: 0.0, alpha: 1)
        }

        func mapView(_ mapView: MLNMapView, lineWidthForPolylineAnnotation annotation: MLNPolyline) -> CGFloat { 5 }

        func mapView(_ mapView: MLNMapView, annotationCanShowCallout annotation: MLNAnnotation) -> Bool { true }
    }
}
