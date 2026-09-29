import Foundation
import CoreLocation
import GPXroadShared

/// SEULE frontière Swift ↔ Kotlin du Road Book (it32-it33) : conversions entre les types Swift
/// (affichage, caches, SwiftUI) et ceux du module partagé `shared/`, où vit toute la logique —
/// détection des virages, fusion Valhalla, liste des manœuvres, progression en direct, hors-trace,
/// sélection des repères, entrées de localité. Dans ce fichier, un nom non qualifié désigne le
/// type Swift (le module courant masque le module importé) ; les types Kotlin sont préfixés
/// `GPXroadShared.`.
enum SharedRoadbook {

    // MARK: - Coordonnées

    static func latLon(_ coordinate: CLLocationCoordinate2D) -> GPXroadShared.LatLon {
        GPXroadShared.LatLon(latitude: coordinate.latitude, longitude: coordinate.longitude)
    }

    static func coordinate(_ latLon: GPXroadShared.LatLon) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latLon.latitude, longitude: latLon.longitude)
    }

    static func latLons(_ points: [GPXPoint]) -> [GPXroadShared.LatLon] {
        points.map { GPXroadShared.LatLon(latitude: $0.latitude, longitude: $0.longitude) }
    }

    static func doubleArray(_ values: [Double]) -> KotlinDoubleArray {
        let array = KotlinDoubleArray(size: Int32(values.count))
        for (index, value) in values.enumerated() { array.set(index: Int32(index), value: value) }
        return array
    }

    // MARK: - Paliers, sens, événements

    static func tier(_ tier: GPXroadShared.RoadbookTier) -> RoadbookTier {
        switch tier {
        case .light: return .light
        case .marked: return .marked
        case .hard: return .hard
        case .veryHard: return .veryHard
        case .uTurn: return .uTurn
        case .lightDirectionChange: return .lightDirectionChange
        case .roundabout: return .roundabout
        case .fork: return .fork
        case .merge: return .merge
        default: return .light
        }
    }

    static func sharedTier(_ tier: RoadbookTier) -> GPXroadShared.RoadbookTier {
        switch tier {
        case .light: return .light
        case .marked: return .marked
        case .hard: return .hard
        case .veryHard: return .veryHard
        case .uTurn: return .uTurn
        case .lightDirectionChange: return .lightDirectionChange
        case .roundabout: return .roundabout
        case .fork: return .fork
        case .merge: return .merge
        }
    }

    static func direction(_ direction: GPXroadShared.TurnDirection) -> TurnDirection {
        switch direction {
        case .left: return .left
        case .right: return .right
        case .uTurn: return .uTurn
        default: return .straight
        }
    }

    static func sharedDirection(_ direction: TurnDirection) -> GPXroadShared.TurnDirection {
        switch direction {
        case .left: return .left
        case .right: return .right
        case .straight: return .straight
        case .uTurn: return .uTurn
        }
    }

    static func checkpoint(_ shared: GPXroadShared.Checkpoint) -> Checkpoint {
        Checkpoint(
            coordinate: coordinate(shared.coordinate),
            turnAngleDegrees: shared.turnAngleDegrees,
            direction: direction(shared.direction),
            tier: tier(shared.tier),
            sequenceIndex: Int(shared.sequenceIndex),
            sourcePointIndex: Int(shared.sourcePointIndex),
            roundaboutExitCount: shared.roundaboutExitCount.map { Int($0.int32Value) },
            trackCumulativeDistanceMeters: shared.trackCumulativeDistanceMeters?.doubleValue,
            roundabout: shared.roundabout.map(roundabout)
        )
    }

    static func sharedCheckpoint(_ checkpoint: Checkpoint) -> GPXroadShared.Checkpoint {
        GPXroadShared.Checkpoint(
            coordinate: latLon(checkpoint.coordinate),
            turnAngleDegrees: checkpoint.turnAngleDegrees,
            direction: sharedDirection(checkpoint.direction),
            tier: sharedTier(checkpoint.tier),
            sequenceIndex: Int32(checkpoint.sequenceIndex),
            sourcePointIndex: Int32(checkpoint.sourcePointIndex),
            roundaboutExitCount: checkpoint.roundaboutExitCount.map { KotlinInt(int: Int32($0)) },
            trackCumulativeDistanceMeters: checkpoint.trackCumulativeDistanceMeters.map { KotlinDouble(double: $0) },
            roundabout: checkpoint.roundabout.map(sharedRoundabout)
        )
    }

    // MARK: - Ronds-points (it34)

    static func roundabout(_ shared: GPXroadShared.RoundaboutPassage) -> RoadbookRoundabout {
        RoadbookRoundabout(
            entryCumulativeMeters: shared.entryCumulativeMeters,
            exitCumulativeMeters: shared.exitCumulativeMeters,
            entryCoordinate: coordinate(shared.entryCoordinate),
            exitAngleDegrees: shared.exitAngleDegrees,
            exitNumber: shared.exitNumber.map { Int($0.int32Value) },
            exitRoadName: shared.exitRoadName,
            branches: shared.branches.map { RoadbookRoundabout.Branch(angleDegrees: $0.pictureAngleDegrees, kind: branchKind($0.kind)) },
            clockwise: shared.clockwise,
            thenExitNumber: shared.thenExitNumber.map { Int($0.int32Value) },
            thenExitAngleDegrees: shared.thenExitAngleDegrees?.doubleValue
        )
    }

    static func sharedRoundabout(_ roundabout: RoadbookRoundabout) -> GPXroadShared.RoundaboutPassage {
        GPXroadShared.RoundaboutPassage(
            entryCumulativeMeters: roundabout.entryCumulativeMeters,
            exitCumulativeMeters: roundabout.exitCumulativeMeters,
            entryCoordinate: latLon(roundabout.entryCoordinate),
            exitAngleDegrees: roundabout.exitAngleDegrees,
            exitNumber: roundabout.exitNumber.map { KotlinInt(int: Int32($0)) },
            exitRoadName: roundabout.exitRoadName,
            branches: roundabout.branches.map { GPXroadShared.RoundaboutBranch(pictureAngleDegrees: $0.angleDegrees, kind: sharedBranchKind($0.kind)) },
            clockwise: roundabout.clockwise,
            thenExitNumber: roundabout.thenExitNumber.map { KotlinInt(int: Int32($0)) },
            thenExitAngleDegrees: roundabout.thenExitAngleDegrees.map { KotlinDouble(double: $0) }
        )
    }

    private static func branchKind(_ kind: GPXroadShared.RoundaboutBranchKind) -> RoadbookRoundabout.BranchKind {
        switch kind {
        case .entry: return .entry
        case .takenExit: return .takenExit
        case .countedExit: return .countedExit
        case .noExit: return .noExit
        default: return .minor
        }
    }

    private static func sharedBranchKind(_ kind: RoadbookRoundabout.BranchKind) -> GPXroadShared.RoundaboutBranchKind {
        switch kind {
        case .entry: return .entry
        case .takenExit: return .takenExit
        case .countedExit: return .countedExit
        case .minor: return .minor
        case .noExit: return .noExit
        }
    }

    static func roundaboutMapData(_ data: RoadbookRoundaboutMapData) -> GPXroadShared.RoundaboutMapData {
        GPXroadShared.RoundaboutMapData(
            roads: data.roads.map { road in
                GPXroadShared.OsmRoad(
                    id: road.id,
                    nodeIds: road.nodeIDs.map { KotlinLong(longLong: $0) },
                    geometry: zip(road.latitudes, road.longitudes).map { GPXroadShared.LatLon(latitude: $0, longitude: $1) },
                    highway: road.highway,
                    junction: road.junction,
                    oneway: road.oneway,
                    access: road.access,
                    motorVehicle: road.motorVehicle,
                    name: road.name,
                    ref: road.ref,
                    destination: road.destination
                )
            },
            miniRoundabouts: data.miniRoundabouts.map {
                GPXroadShared.OsmMiniRoundabout(nodeId: $0.nodeID, coordinate: GPXroadShared.LatLon(latitude: $0.latitude, longitude: $0.longitude), clockwise: $0.clockwise)
            }
        )
    }

    private static var passagesMemo: [String: [GPXroadShared.RoundaboutPassage]] = [:]

    /// Passages de la trace (dans son sens de parcours) dans les ronds-points — mémorisés par
    /// parcours + données (l'analyse ré-échantillonne la trace autour de chaque anneau).
    static func roundaboutPassages(for track: GPXTrack, data: RoadbookRoundaboutMapData?) -> [GPXroadShared.RoundaboutPassage] {
        guard let data, !(data.roads.isEmpty && data.miniRoundabouts.isEmpty) else { return [] }
        let key = "\(track.traversalKey)|\(track.points.count)|\(data.fingerprint)"
        memoLock.lock()
        if let cached = passagesMemo[key] {
            memoLock.unlock()
            return cached
        }
        memoLock.unlock()
        let result = GPXroadShared.RoundaboutAnalyzer.shared.passages(points: latLons(track.points), data: roundaboutMapData(data))
        memoLock.lock()
        if passagesMemo.count >= memoLimit { passagesMemo.removeAll() }
        passagesMemo[key] = result
        memoLock.unlock()
        return result
    }

    static func maneuver(_ shared: GPXroadShared.RoadbookManeuver) -> RoadbookManeuver {
        RoadbookManeuver(
            checkpoint: checkpoint(shared.checkpoint),
            partialDistanceMeters: shared.partialDistanceMeters,
            cumulativeDistanceMeters: shared.cumulativeDistanceMeters,
            headingDegrees: shared.headingDegrees
        )
    }

    static func sharedManeuver(_ maneuver: RoadbookManeuver) -> GPXroadShared.RoadbookManeuver {
        GPXroadShared.RoadbookManeuver(
            checkpoint: sharedCheckpoint(maneuver.checkpoint),
            partialDistanceMeters: maneuver.partialDistanceMeters,
            cumulativeDistanceMeters: maneuver.cumulativeDistanceMeters,
            headingDegrees: maneuver.headingDegrees
        )
    }

    static func settings(
        windowBeforeMeters: Double,
        windowAfterMeters: Double,
        thresholds: RoadbookAnalyzer.TierThresholds,
        mergeMinDistanceMeters: Double
    ) -> GPXroadShared.RoadbookSettings {
        GPXroadShared.RoadbookSettings(
            windowBeforeMeters: windowBeforeMeters,
            windowAfterMeters: windowAfterMeters,
            thresholds: GPXroadShared.TierThresholds(light: thresholds.light, marked: thresholds.marked, hard: thresholds.hard, veryHard: thresholds.veryHard),
            mergeMinDistanceMeters: mergeMinDistanceMeters
        )
    }

    static func mapMatched(_ maneuver: MapMatchedManeuver) -> GPXroadShared.MapMatchedManeuver {
        GPXroadShared.MapMatchedManeuver(
            coordinate: latLon(maneuver.coordinate),
            type: GPXroadShared.ValhallaManeuverType.companion.fromRawValue(rawValue: Int32(maneuver.type.rawValue)),
            roundaboutExitCount: maneuver.roundaboutExitCount.map { KotlinInt(int: Int32($0)) },
            routeProgressFraction: maneuver.routeProgressFraction.map { KotlinDouble(double: $0) },
            streetNamesBefore: maneuver.streetNamesBefore,
            streetNamesAfter: maneuver.streetNamesAfter
        )
    }

    // MARK: - Couverture du map matching (it33 bis)

    /// Portions de la trace (m) réellement recalées par Valhalla — `MapMatchCoverage` (Kotlin).
    static func coverage(trackPoints: [GPXPoint], matchedShapes: [[CLLocationCoordinate2D]]) -> [ClosedRange<Double>] {
        GPXroadShared.MapMatchCoverage.shared
            .coveredRanges(trackPoints: latLons(trackPoints), matchedShapes: matchedShapes.map { $0.map(latLon) })
            .map { $0.startMeters...$0.endMeters }
    }

    static func sharedCoverage(_ coverage: [ClosedRange<Double>]?) -> [GPXroadShared.CoveredRange]? {
        coverage?.map { GPXroadShared.CoveredRange(startMeters: $0.lowerBound, endMeters: $0.upperBound) }
    }

    // MARK: - Manœuvres (mémorisées)

    /// Dernières manœuvres calculées, par parcours + réglages + manœuvres Valhalla : l'écran Road Book
    /// relit `maneuvers` à chaque rendu (chaque position GPS en mode Assisté) — sans ce cache, le
    /// calcul complet et la conversion des points se referaient à chaque fois.
    private static let memoLock = NSLock()
    private static var memo: [String: [RoadbookManeuver]] = [:]
    private static let memoLimit = 8

    static func maneuvers(
        for track: GPXTrack,
        settings: GPXroadShared.RoadbookSettings,
        mapMatchedManeuvers: [MapMatchedManeuver],
        coverage: [ClosedRange<Double>]? = nil,
        roundaboutData: RoadbookRoundaboutMapData? = nil
    ) -> [RoadbookManeuver] {
        let key = memoKey(track: track, settings: settings, mapMatched: mapMatchedManeuvers) + "|cov:\(coverage.map { $0.map { "\($0.lowerBound)-\($0.upperBound)" }.joined(separator: ",") } ?? "nil")" + "|rb:\(roundaboutData?.fingerprint ?? "-")"
        memoLock.lock()
        if let cached = memo[key] {
            memoLock.unlock()
            return cached
        }
        memoLock.unlock()

        let result = GPXroadShared.RoadbookExtractor.shared
            .maneuvers(points: latLons(track.points), settings: settings, mapMatchedManeuvers: mapMatchedManeuvers.map(mapMatched), coverage: sharedCoverage(coverage), roundabouts: roundaboutPassages(for: track, data: roundaboutData))
            .map(maneuver)

        memoLock.lock()
        if memo.count >= memoLimit { memo.removeAll() }
        memo[key] = result
        memoLock.unlock()
        return result
    }

    private static func memoKey(track: GPXTrack, settings: GPXroadShared.RoadbookSettings, mapMatched: [MapMatchedManeuver]) -> String {
        let last = track.points.last.map { "\($0.latitude),\($0.longitude)" } ?? "-"
        let t = settings.thresholds
        var key = "\(track.traversalKey)|\(track.points.count)|\(last)|\(settings.windowBeforeMeters)|\(settings.windowAfterMeters)|\(t.light)|\(t.marked)|\(t.hard)|\(t.veryHard)|\(settings.mergeMinDistanceMeters)"
        for m in mapMatched {
            key += "|\(m.coordinate.latitude),\(m.coordinate.longitude),\(m.type.rawValue),\(m.roundaboutExitCount ?? -1),\(m.routeProgressFraction ?? -1),\(m.streetNamesBefore),\(m.streetNamesAfter)"
        }
        return key
    }

    // MARK: - Repères

    static func landmarkCategory(_ category: RoadbookLandmarkCategory) -> GPXroadShared.LandmarkCategory {
        // Même clé des deux côtés (vérifié par test) : jamais nil pour une catégorie du catalogue.
        GPXroadShared.LandmarkCategory.companion.fromKey(key: category.rawValue) ?? .citySign
    }

    static func landmarkCategory(_ shared: GPXroadShared.LandmarkCategory) -> RoadbookLandmarkCategory {
        RoadbookLandmarkCategory(rawValue: shared.key) ?? .citySign
    }

    static func landmarkData(_ data: RoadbookLandmarkData) -> GPXroadShared.LandmarkData {
        GPXroadShared.LandmarkData(
            candidates: data.candidates.map { candidate in
                let orientation: GPXroadShared.LandmarkOrientation?
                switch candidate.orientation {
                case .appliesToTravelBearing(let bearing)?: orientation = GPXroadShared.LandmarkOrientation.AppliesToTravelBearing(bearing: bearing)
                case .faces(let bearing)?: orientation = GPXroadShared.LandmarkOrientation.Faces(bearing: bearing)
                case nil: orientation = nil
                }
                return GPXroadShared.LandmarkCandidate(
                    category: landmarkCategory(candidate.category),
                    label: candidate.label,
                    coordinate: latLon(candidate.coordinate),
                    orientation: orientation,
                    roadAxes: candidate.roadAxes?.map { KotlinDouble(double: $0) }
                )
            },
            builtUpAreas: data.builtUpAreas.map(builtUpArea),
            places: data.places.map(place)
        )
    }

    static func builtUpArea(_ area: RoadbookBuiltUpArea) -> GPXroadShared.BuiltUpArea {
        GPXroadShared.BuiltUpArea(
            name: area.name,
            rings: area.rings.map { ring in ring.map { GPXroadShared.LatLon(latitude: $0.latitude, longitude: $0.longitude) } }
        )
    }

    static func place(_ place: RoadbookPlace) -> GPXroadShared.Place {
        let kind: GPXroadShared.PlaceKind
        switch place.kind {
        case .city: kind = .city
        case .town: kind = .town
        case .village: kind = .village
        case .suburb: kind = .suburb
        }
        return GPXroadShared.Place(name: place.name, kind: kind, coordinate: latLon(place.coordinate))
    }

    static func landmarkInfo(_ shared: GPXroadShared.LandmarkInfo) -> RoadbookLandmarkInfo {
        let side: RoadbookLandmarkSide?
        switch shared.side {
        case .left?: side = .left
        case .right?: side = .right
        default: side = nil
        }
        return RoadbookLandmarkInfo(
            category: landmarkCategory(shared.category),
            // Entrée de localité calculée : libellé composé et traduit ici (« Entrée de Ferrette »).
            label: shared.cityEntryName.map(RoadbookCityEntry.label(for:)) ?? shared.label,
            side: side,
            lateralDistanceMeters: shared.lateralDistanceMeters?.doubleValue
        )
    }

    static func landmarkCheckpoint(_ shared: GPXroadShared.LandmarkCheckpoint) -> RoadbookLandmarkCheckpoint {
        RoadbookLandmarkCheckpoint(
            info: landmarkInfo(shared.info),
            latitude: shared.coordinate.latitude,
            longitude: shared.coordinate.longitude,
            cumulativeDistanceMeters: shared.cumulativeDistanceMeters
        )
    }

    static func sharedLandmarkCheckpoint(_ landmark: RoadbookLandmarkCheckpoint) -> GPXroadShared.LandmarkCheckpoint {
        // Seule la position compte pour l'ordre du Road Book (voir `RoadbookEntry.merge`).
        GPXroadShared.LandmarkCheckpoint(
            info: GPXroadShared.LandmarkInfo(category: landmarkCategory(landmark.info.category), label: landmark.info.label, side: nil, lateralDistanceMeters: nil, cityEntryName: nil),
            coordinate: GPXroadShared.LatLon(latitude: landmark.latitude, longitude: landmark.longitude),
            cumulativeDistanceMeters: landmark.cumulativeDistanceMeters
        )
    }

    // MARK: - Reprise de la trace (it33)

    /// Point de la trace le plus proche À VOL D'OISEAU parmi ceux DEVANT `fromCumulativeMeters`
    /// (voir `RejoinPlanner.nearestAhead`).
    static func rejoinTarget(
        from position: CLLocationCoordinate2D,
        points: [GPXPoint],
        cumulativeDistances: [Double],
        fromCumulativeMeters: Double
    ) -> GPXroadShared.RejoinTarget? {
        GPXroadShared.RejoinPlanner.shared.nearestAhead(
            position: latLon(position),
            points: latLons(points),
            cumulativeDistances: doubleArray(cumulativeDistances),
            fromCumulativeMeters: fromCumulativeMeters
        )
    }

    /// Virages du chemin de reprise, avec les réglages du Road Book.
    static func rejoinPlan(target: GPXroadShared.RejoinTarget, route: [CLLocationCoordinate2D], settings: GPXroadShared.RoadbookSettings) -> GPXroadShared.RejoinPlan {
        GPXroadShared.RejoinPlanner.shared.plan(target: target, routePoints: route.map(latLon), settings: settings)
    }

    static func rejoinProgress(_ plan: GPXroadShared.RejoinPlan, position: CLLocationCoordinate2D) -> GPXroadShared.RejoinProgress {
        GPXroadShared.RejoinPlanner.shared.progress(plan: plan, position: latLon(position))
    }

    /// Cible dépassée (derrière soi, en roulant, 10 s d'affilée) — `RejoinPassedDetector`.
    static func updatePassed(_ detector: GPXroadShared.RejoinPassedDetector, location: CLLocation, target: CLLocationCoordinate2D) -> Bool {
        detector.update(
            position: latLon(location.coordinate),
            courseDegrees: location.course >= 0 ? KotlinDouble(double: location.course) : nil,
            speedMetersPerSecond: max(location.speed, 0),
            timestampSeconds: location.timestamp.timeIntervalSinceReferenceDate,
            target: latLon(target)
        )
    }
}
