package com.olivier.gpxroad.android.ride

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.ControlsSide
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.location.LocationTracker
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.recording.EndRideDialog
import com.olivier.gpxroad.android.recording.RecordingControls
import com.olivier.gpxroad.android.recording.RecordingPrompt
import com.olivier.gpxroad.android.recording.RideRecorder
import com.olivier.gpxroad.android.roadbook.ManeuverPictogram
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.android.roadbook.data.OffTrackState
import com.olivier.gpxroad.android.roadbook.data.RejoinController
import com.olivier.gpxroad.android.roadbook.data.RejoinDisplay
import com.olivier.gpxroad.android.roadbook.data.RoadbookData
import com.olivier.gpxroad.android.ui.Accent
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.ride.SlopeAnalyzer
import com.olivier.gpxroad.shared.ride.RideCameraConstants
import com.olivier.gpxroad.shared.roadbook.RejoinPlanner
import com.olivier.gpxroad.shared.roadbook.RoadbookAnalyzer
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Fond des panneaux du Ride (équivalent de `ridePanelStyle` iOS) : sombre translucide, lisible au soleil. */
private val PanelBackground = Color(0xD9202020)
private val PanelShape = RoundedCornerShape(16.dp)
private val ColumnWidth: Dp = 92.dp

/**
 * Onglet Ride (session 2 de la migration, équivalent réduit de `RideView` iOS) : la trace active sur
 * la carte, la position, la caméra qui suit ; à droite la colonne de contrôles avec, au-dessus, le
 * prochain virage à moins de 600 m (bannière latérale) ou « Hors trace » ; à gauche la vitesse.
 * Mêmes virages que le Road Book ([RoadbookData.maneuvers]).
 */
@Composable
fun RideScreen(
    library: TrackLibrary,
    settings: AppSettings,
    servers: ServerSettings,
    data: RoadbookData,
    camera: RideCameraState,
    location: LocationTracker,
    recorder: RideRecorder,
    rejoin: RejoinController,
    onOpenLibrary: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(settings.keepScreenAwake) {
        view.keepScreenOn = settings.keepScreenAwake
        onDispose { view.keepScreenOn = false }
    }
    val track = library.activeTrack
    val roadbookSettings = settings.roadbookSettings
    val valhalla = servers.valhalla
    LaunchedEffect(track?.traversalKey, valhalla) {
        if (track != null) {
            data.ensureMapMatch(track)
            data.ensureRoundabouts(track)
        }
    }
    val mapMatch = data.mapMatch
    val roundabouts = data.roundabouts
    LaunchedEffect(track?.traversalKey, roadbookSettings, mapMatch, roundabouts) { track?.let { data.refreshManeuvers(it, roadbookSettings) } }
    val maneuvers = if (track != null) data.maneuvers.orEmpty() else emptyList()

    var permissionRequests by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRequests++ }
    val granted = remember(permissionRequests) { location.hasPermission }
    DisposableEffect(granted) {
        if (granted) location.start()
        onDispose { location.stop() }
    }
    val fix = location.location
    LaunchedEffect(fix) { fix?.let(camera::onLocation) }

    // Progression le long de la trace : même projection sans mémoire que le Road Book.
    val projection = remember(fix, track?.traversalKey) {
        if (track == null || fix == null) null else TrackGeometry.project(LatLon(fix.latitude, fix.longitude), track.latLons, track.cumulative)
    }
    var offTrack by remember(track?.traversalKey) { mutableStateOf(OffTrackState()) }
    LaunchedEffect(fix, track?.traversalKey) {
        if (track != null && fix != null) offTrack = offTrack.updated(fix, track.latLons, track.cumulative)
    }
    // Reprise automatique (it18/it33 iOS) : à plus de 100 m de la trace pendant 2 s, chemin par les
    // routes vers le point le plus proche DEVANT soi, dessiné en pointillé bleu ; fini dès le retour
    // sur la trace (hystérésis du hors trace).
    var divergent by remember(track?.traversalKey) { mutableStateOf(false) }
    LaunchedEffect(fix, track?.traversalKey) {
        if (track == null || fix == null) return@LaunchedEffect
        divergent = if (divergent) offTrack.isOffTrack else (projection?.distanceToTrackMeters ?: 0.0) > REJOIN_DIVERGENCE_METERS
        rejoin.update(fix, track.latLons, track.cumulative, divergent, projection?.cumulativeDistanceMeters?.takeIf { !offTrack.isOffTrack }, roadbookSettings, valhalla)
    }
    DisposableEffect(track?.traversalKey) { onDispose { rejoin.reset() } }
    val rejoinDisplay = rejoin.display?.takeIf { divergent }
    val rejoinOverlay = rejoinDisplay?.let { d -> d.targetCoordinate?.let { RejoinOverlay(d.routePoints, it) } }

    val slopeWarnings = remember(track?.traversalKey, settings.slopeWarningsEnabled, settings.slopeThreshold) {
        if (track == null || !settings.slopeWarningsEnabled) emptyList()
        else SlopeAnalyzer.steepGradeWarnings(track.latLons, track.points.map { it.elevation }, settings.slopeThreshold)
    }
    val controlsOnRight = settings.controlsSide == ControlsSide.RIGHT

    val nextIndex = projection?.let { p -> maneuvers.indexOfFirst { it.cumulativeDistanceMeters > p.cumulativeDistanceMeters }.takeIf { it >= 0 } }
    val nextDistance = nextIndex?.let { maneuvers[it].cumulativeDistanceMeters - projection.cumulativeDistanceMeters }

    Box(Modifier.fillMaxSize()) {
        RideMap(
            trackKey = track?.traversalKey,
            trackPoints = track?.latLons.orEmpty(),
            maneuvers = maneuvers,
            traceStyle = TraceStyle(settings.traceColor.argb, settings.traceWidth.widthDp),
            chevronSpacingMeters = settings.chevronSpacing,
            slopeWarnings = slopeWarnings,
            rejoin = rejoinOverlay,
            location = fix,
            northUp = settings.rideNorthUp,
            camera = camera,
            modifier = Modifier.fillMaxSize(),
        )

        when {
            !granted -> CenterCard {
                Text(stringResource(R.string.location_needed), color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                    Text(stringResource(R.string.location_allow))
                }
            }
            track == null -> Box(Modifier.align(Alignment.TopCenter).padding(12.dp).background(PanelBackground, PanelShape).clickable(onClick = onOpenLibrary).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.ride_no_track), color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Colonne de contrôles (côté réglable) : panneau d'alerte au-dessus, jamais d'autre chose dans cette zone.
        Column(
            Modifier.align(if (controlsOnRight) Alignment.BottomEnd else Alignment.BottomStart).padding(horizontal = 16.dp).padding(bottom = 16.dp).width(ColumnWidth),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (track != null && fix != null) {
                when {
                    rejoinDisplay != null -> RejoinBanner(rejoinDisplay, fix, camera.courseDegrees)
                    offTrack.isOffTrack -> OffTrackChip(offTrack, fix, track.latLons, track.cumulative, camera.courseDegrees)
                    nextIndex != null && nextDistance != null && nextDistance <= RideCameraConstants.BANNER_ALERT_START_METERS ->
                        LateralBanner(maneuvers[nextIndex], nextDistance, nextIndex + 1, maneuvers.size, DistanceUnit.KM)
                }
            }
            ControlButton(if (settings.rideNorthUp) "N" else "▲", stringResource(if (settings.rideNorthUp) R.string.ride_north_up else R.string.ride_heading_up)) {
                settings.updateRideNorthUp(!settings.rideNorthUp)
                camera.recenter()
            }
            ControlButton("+", stringResource(R.string.ride_zoom_in)) { camera.zoomIn() }
            ControlButton("−", stringResource(R.string.ride_zoom_out)) { camera.zoomOut() }
            val now by produceState(System.currentTimeMillis(), camera.lastGestureMillis) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(500)
                }
            }
            if (camera.isManualOverrideActive(now) || camera.manualDistanceMeters != null) {
                ControlButton("◎", stringResource(R.string.ride_recenter)) { camera.recenter() }
            }
        }

        // Vitesse (gauche), toujours du côté opposé aux contrôles, et au-dessus l'enregistrement de la
        // sortie (même calque, comme l'iPhone : aucune nouvelle zone d'overlay).
        var finishing by remember { mutableStateOf(false) }
        Column(
            Modifier.align(if (controlsOnRight) Alignment.BottomStart else Alignment.BottomEnd).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = if (controlsOnRight) Alignment.Start else Alignment.End,
        ) {
            RecordingControls(recorder) { finishing = true }
            Column(
                Modifier.background(PanelBackground, PanelShape).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val mph = settings.distanceUnit == DistanceUnit.MI
                Text("${(if (mph) camera.rawSpeedKmh / 1.609344 else camera.rawSpeedKmh).roundToInt()}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(if (mph) "mph" else stringResource(R.string.ride_speed_unit), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (recorder.wasRestoredAfterInterruption) {
            Box(Modifier.align(Alignment.TopCenter).padding(12.dp).background(PanelBackground, PanelShape).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.recording_restored), color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        if (finishing) EndRideDialog(recorder, library, track?.entry?.name) { finishing = false }
        // Premier suivi d'une trace : « Enregistrer cette sortie ? » (une fois par trace et par lancement).
        if (granted && track != null) RecordingPrompt(recorder, track.entry.id)
    }
}

/** Écart à la trace au-delà duquel la reprise automatique se déclenche (après 2 s), comme l'iPhone. */
private const val REJOIN_DIVERGENCE_METERS = 100.0
private val RejoinTint = Color(0xD93A3480)

/**
 * Reprise automatique (`RejoinGuidanceBannerView` iOS), prioritaire dans la colonne : prochain virage
 * du chemin et « Trace à … », ou « Rejoindre la trace » avec une flèche vers le point de retour.
 */
@Composable
private fun RejoinBanner(display: RejoinDisplay, fix: android.location.Location, course: Double?) {
    Column(
        Modifier.width(ColumnWidth).background(RejoinTint, PanelShape).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val index = display.nextManeuverIndex
        val turnDistance = display.distanceToNextManeuverMeters
        val remaining = display.remainingToTrackMeters
        if (index != null && turnDistance != null) {
            ManeuverPictogram(display.maneuvers[index].checkpoint, Accent, Color.White, Modifier.size(40.dp))
            Text(RoadbookTexts.countdown(turnDistance, DistanceUnit.KM), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1)
            if (remaining != null) {
                Text(stringResource(R.string.rejoin_track_at, RoadbookTexts.countdown(remaining, DistanceUnit.KM)), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        } else {
            val target = display.targetCoordinate
            val position = LatLon(fix.latitude, fix.longitude)
            val relative = target?.let { ((RoadbookAnalyzer.bearing(position, it) - (course ?: 0.0)) % 360 + 540) % 360 - 180 } ?: 0.0
            Canvas(Modifier.size(28.dp).rotate(relative.toFloat())) {
                val path = Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(size.width / 2, size.height * 0.72f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, Color.White)
            }
            Text(
                stringResource(R.string.rejoin_track),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            val distance = remaining ?: target?.let { geodesicDistanceMeters(position, it) }
            if (distance != null) Text(RoadbookTexts.countdown(distance, DistanceUnit.KM), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(PanelBackground, PanelShape).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** Bouton de la colonne (grande cible, utilisable avec des gants). */
@Composable
private fun ControlButton(symbol: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).background(PanelBackground, PanelShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

/** Prochain virage à moins de 600 m (`LateralCapBannerView` iOS) : pictogramme, compte à rebours (km, comme l'iPhone), rang. */
@Composable
private fun LateralBanner(maneuver: RoadbookManeuver, distanceMeters: Double, rank: Int, total: Int, unit: DistanceUnit) {
    Column(
        Modifier.width(ColumnWidth).background(PanelBackground, PanelShape).padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ManeuverPictogram(maneuver.checkpoint, Accent, Color.White, Modifier.size(52.dp))
        Text(
            RoadbookTexts.countdown(distanceMeters, unit), color = Color.White, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 30.sp),
        )
        Text("⚑ $rank/$total", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/**
 * « Hors trace » (`OffTrackChipView` iOS) : flèche vers le point de retour (le plus proche DEVANT la
 * dernière position sur la trace), relative au cap ; distance après 30 s, comme sur l'iPhone.
 */
@Composable
private fun OffTrackChip(offTrack: OffTrackState, fix: android.location.Location, points: List<LatLon>, cumulative: DoubleArray, course: Double?) {
    val position = LatLon(fix.latitude, fix.longitude)
    val target = remember(fix, offTrack.lastOnTrackCumulativeMeters) {
        RejoinPlanner.nearestAhead(position, points, cumulative, offTrack.lastOnTrackCumulativeMeters ?: 0.0)
    }
    val relative = target?.let { ((RoadbookAnalyzer.bearing(position, it.coordinate) - (course ?: 0.0)) % 360 + 540) % 360 - 180 } ?: 0.0
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    Column(
        Modifier.width(ColumnWidth).background(PanelBackground, PanelShape).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(30.dp).rotate(relative.toFloat())) {
            val path = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2, size.height * 0.72f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.9f))
        }
        Text(stringResource(R.string.off_track), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        val distance = offTrack.rejoinDistanceMeters
        if (offTrack.showsRejoinDistance(now) && distance != null) {
            Text(RoadbookTexts.countdown(distance, DistanceUnit.KM), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
    }
}
