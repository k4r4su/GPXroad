import Foundation
import GPXroadShared

/// Règle "Hors trace" UNIQUE de l'app (it30) — hystérésis à deux seuils (on SORT au-delà de 30 m,
/// on n'y REVIENT qu'en deçà de 25 m). Utilisée par le Ride ET le Road Book : même seuil, même
/// terminologie. Façade du module partagé (`shared/.../roadbook/RoadbookLiveProgress.kt`, it33) —
/// les seuils y vivent (`RoadbookConstants.OFF_TRACK_*`), `RideConstants.horsTrace*` les relit.
enum OffTrackDetector {
    static func isOffTrack(wasOffTrack: Bool, distanceToTrackMeters: Double) -> Bool {
        GPXroadShared.OffTrackDetector.shared.isOffTrack(wasOffTrack: wasOffTrack, distanceToTrackMeters: distanceToTrackMeters)
    }
}
