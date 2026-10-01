package com.olivier.gpxroad.android.ride

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Terrain
import com.olivier.gpxroad.android.ui.ChoiceSheet
import com.olivier.gpxroad.android.ui.SheetOption
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import com.olivier.gpxroad.android.ui.MapButton
import com.olivier.gpxroad.android.ui.MapPanelBorder
import com.olivier.gpxroad.android.ui.MapPanelColor
import com.olivier.gpxroad.android.ui.RideNumberStyle
import androidx.compose.foundation.combinedClickable
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
import com.olivier.gpxroad.android.ui.mapPanel
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.ControlsSide
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.location.LocationTracker
import com.olivier.gpxroad.android.nav.GoToPill
import com.olivier.gpxroad.android.sync.SharedBlockageSync
import com.olivier.gpxroad.android.offline.OfflineMaps
import com.olivier.gpxroad.shared.ride.SharedBlockages
import com.olivier.gpxroad.android.nav.NavDestination
import com.olivier.gpxroad.android.nav.NavPanel
import com.olivier.gpxroad.android.nav.NavSession
import com.olivier.gpxroad.android.nav.NavThenBanner
import com.olivier.gpxroad.android.nav.profileLabel
import com.olivier.gpxroad.shared.nav.GoToProfile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
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
internal val PanelBackground = MapPanelColor
internal val PanelShape = RoundedCornerShape(20.dp)
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
    session: RideSession,
    nav: NavSession,
    blockageSync: SharedBlockageSync,
    offline: OfflineMaps,
    onOpenLibrary: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
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
        if (fix == null) return@LaunchedEffect
        session.onLocation(fix, track?.traversalKey, track?.cumulative?.lastOrNull(), projection?.cumulativeDistanceMeters)
        nav.onLocation(fix, settings.voiceEnabled, settings.voiceVolume.toFloat(), valhalla, settings.speedMarginKmh)
        if (track == null) return@LaunchedEffect
        session.updateResume(fix, projection?.distanceToTrackMeters)
        session.updateDetour(fix, projection?.distanceToTrackMeters, guidingTrace = !nav.isActive && session.manualResume == null)
        // Une reprise manuelle en cours prime : pas de reprise automatique en parallèle (comme l'iPhone) ;
        // un guidage « Aller à » suspend toute la reprise de trace (un seul guidage à la fois).
        divergent = session.manualResume == null && !nav.isActive && session.detour == null && !session.guidanceStopped &&
            if (divergent) offTrack.isOffTrack else (projection?.distanceToTrackMeters ?: 0.0) > REJOIN_DIVERGENCE_METERS
        rejoin.update(fix, track.latLons, track.cumulative, divergent, projection?.cumulativeDistanceMeters?.takeIf { !offTrack.isOffTrack }, roadbookSettings, valhalla)
    }
    DisposableEffect(track?.traversalKey) { onDispose { rejoin.reset() } }
    val rejoinDisplay = rejoin.display?.takeIf { divergent }
    val manualResume = session.manualResume
    val rejoinOverlay = when {
        // Aperçu non routé : vol d'oiseau jusqu'au point touché.
        manualResume != null -> RejoinOverlay(manualResume.route.ifEmpty { listOfNotNull(fix?.let { LatLon(it.latitude, it.longitude) }, manualResume.target) }, manualResume.target)
        else -> rejoinDisplay?.let { d -> d.targetCoordinate?.let { RejoinOverlay(d.routePoints, it) } }
    }

    val slopeWarnings = remember(track?.traversalKey, settings.slopeWarningsEnabled, settings.slopeThreshold) {
        if (track == null || !settings.slopeWarningsEnabled) emptyList()
        else SlopeAnalyzer.steepGradeWarnings(track.latLons, track.points.map { it.elevation }, settings.slopeThreshold)
    }
    val controlsOnRight = settings.controlsSide == ControlsSide.RIGHT

    // Guidage « Aller à » : un seul guidage à la fois, le Road Book de la trace se tait.
    val navTick = nav.tick
    val navTracker = nav.tracker
    val goTo = nav.goTo
    val guidingTrace = !nav.isActive && !session.guidanceStopped
    // Flash des 100 derniers mètres : une fois par virage.
    var flashToken by remember { mutableIntStateOf(0) }
    val flashed = remember(track?.traversalKey) { mutableSetOf<Int>() }
    var goHere by remember { mutableStateOf<LatLon?>(null) }
    var askDetour by remember { mutableStateOf(false) }
    val detour = session.detour
    // Points bloqués partagés : synchro (au plus une fois par jour) autour de la trace, alerte à 300 m.
    LaunchedEffect(track?.traversalKey, blockageSync.serverUrl, blockageSync.shareEnabled) { track?.let { blockageSync.syncIfNeeded(it.latLons) } }
    var hiddenBlockageId by remember { mutableStateOf<String?>(null) }
    val sharedAlert = remember(track?.traversalKey, blockageSync.blockages) {
        track?.let { SharedBlockages.nearestAlongTrack(blockageSync.blockages, it.latLons) }
    }?.takeIf { it.id != hiddenBlockageId }
    val now = System.currentTimeMillis()
    val blockagePins = remember(blockageSync.blockages) { blockageSync.blockages.map { it.coordinate to it.isFaded(now) } }

    val nextIndex = projection?.let { p -> maneuvers.indexOfFirst { it.cumulativeDistanceMeters > p.cumulativeDistanceMeters }.takeIf { it >= 0 } }
    val nextDistance = nextIndex?.let { maneuvers[it].cumulativeDistanceMeters - projection.cumulativeDistanceMeters }
    LaunchedEffect(nextIndex, nextDistance != null && nextDistance <= FLASH_METERS) {
        if (guidingTrace && settings.flashEnabled && nextIndex != null && nextDistance != null && nextDistance <= FLASH_METERS && flashed.add(nextIndex)) flashToken++
    }

    Box(Modifier.fillMaxSize()) {
        RideMap(
            trackKey = track?.traversalKey,
            trackPoints = track?.latLons.orEmpty(),
            maneuvers = maneuvers,
            traceStyle = TraceStyle(settings.traceColor.argb, settings.traceWidth.widthDp),
            mapTheme = settings.mapTheme,
            offlineAvailable = offline.hasZones,
            anchorY = settings.anchorY,
            chevronSpacingMeters = settings.chevronSpacing,
            slopeWarnings = slopeWarnings,
            rejoin = rejoinOverlay,
            navRoute = navTracker?.route?.points,
            navTraveledCount = navTracker?.traveledPointCount?.takeIf { navTick >= 0 } ?: 0,
            goToRoute = goTo?.points?.takeIf { it.size > 1 },
            detourRoute = detour?.route,
            blockages = blockagePins,
            location = fix,
            northUp = settings.rideNorthUp,
            camera = camera,
            modifier = Modifier.fillMaxSize(),
            onMapTap = { tap, tolerance ->
                if (track != null) {
                    // Taper sur la trace pendant un « Aller à » y revient (arrête ce guidage), comme l'iPhone.
                    val onTrack = TrackGeometry.project(tap, track.latLons, track.cumulative)?.let { it.distanceToTrackMeters <= tolerance } == true
                    if (onTrack && nav.isActive) nav.stop()
                    if (onTrack) session.resumeGuidance()
                    session.requestResume(tap, track.latLons, track.cumulative, tolerance, fix, valhalla)
                }
            },
            onMapLongTap = { goHere = it },
        )
        if (askDetour && track != null) {
            DetourDialog(onDismiss = { askDetour = false; session.dismissBlockedBanner() }) { mode ->
                askDetour = false
                session.cancelResume()
                session.requestDetour(mode, fix, track.latLons, track.cumulative, valhalla, onReport = blockageSync::report)
            }
        }
        goHere?.let { point ->
            GoHereDialog(onDismiss = { goHere = null }) { profile ->
                goHere = null
                session.cancelResume()
                nav.start(NavDestination(context.getString(R.string.nav_map_point), point, profile), fix, valhalla)
            }
        }
        // Bandeau du guidage « Aller à » (en haut, à la place de la carte « Reprendre »).
        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val destination = nav.destination
            if (navTracker != null && destination != null) {
                val stopLabel = stringResource(if (track != null) R.string.nav_back_to_track else R.string.nav_stop)
                NavPanel(navTracker.currentManeuver, navTracker.distanceToManeuverMeters, destination.label, nav.isRecomputing, stopLabel, onStop = nav::stop)
                navTracker.nextManeuverIfChained?.let { NavThenBanner(it) }
            } else if (goTo != null) {
                GoToPill(goTo, nav.goToRemainingMeters, nav.isRequesting, nav.failed, settings.distanceUnit, onCancel = nav::stop)
            } else if (detour != null || session.isRequestingDetour) {
                DetourBanner(detour, session.isRequestingDetour, onCancel = session::cancelDetour)
            } else if (session.isBlockedBannerVisible && track != null && manualResume == null) {
                BlockedBanner(onBypass = { askDetour = true }, onDismiss = session::dismissBlockedBanner)
            } else if (sharedAlert != null && manualResume == null) {
                SharedBlockageAlert(sharedAlert.note, onHide = { hiddenBlockageId = sharedAlert.id })
            } else if (nav.isRequesting && destination != null) {
                Box(Modifier.mapPanel().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.nav_requesting) + " · " + destination.label, color = Color.White, maxLines = 1)
                }
            }
        }
        manualResume?.let { resume ->
            ResumeCard(
                resume, fix, camera.courseDegrees, settings.distanceUnit,
                onConfirm = session::confirmResume, onCancel = session::cancelResume,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            )
        }

        when {
            !granted -> CenterCard {
                Text(stringResource(R.string.location_needed), color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                    Text(stringResource(R.string.location_allow))
                }
            }
            track == null -> Box(Modifier.align(Alignment.TopCenter).padding(12.dp).mapPanel().clickable(onClick = onOpenLibrary).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.ride_no_track), color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Colonne de contrôles (côté réglable) : panneau d'alerte au-dessus, jamais d'autre chose dans cette zone.
        Column(
            Modifier.align(if (controlsOnRight) Alignment.BottomEnd else Alignment.BottomStart).padding(horizontal = 16.dp).padding(bottom = 16.dp).width(ColumnWidth),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // « Me recentrer » EN HAUT de la colonne (ordre de l'iPhone) : son apparition ne décale
            // jamais +/− sous le doigt (sinon le 2e tap sur « − » tombait sur lui).
            val now by produceState(System.currentTimeMillis(), camera.lastGestureMillis) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(500)
                }
            }
            if (camera.isManualOverrideActive(now) || camera.manualDistanceMeters != null || camera.focus != null) {
                MapButton(Icons.Rounded.MyLocation, stringResource(R.string.ride_recenter)) {
                    camera.recenter()
                    session.resumeGuidance()
                }
            }
            if (track != null && fix != null && guidingTrace) {
                when {
                    rejoinDisplay != null -> RejoinBanner(rejoinDisplay, fix, camera.courseDegrees)
                    offTrack.isOffTrack -> OffTrackChip(offTrack, fix, track.latLons, track.cumulative, camera.courseDegrees)
                    nextIndex != null && nextDistance != null && nextDistance <= RideCameraConstants.BANNER_ALERT_START_METERS ->
                        LateralBanner(maneuvers[nextIndex], nextDistance, nextIndex + 1, maneuvers.size, DistanceUnit.KM)
                }
            }
            if (track != null) {
                GuidanceToggle(
                    stopped = session.guidanceStopped,
                    onPause = { nav.stop(); session.stopGuidance() },
                    onResume = session::resumeGuidance,
                    onStop = {
                        nav.stop()
                        session.stopGuidance()
                        android.widget.Toast.makeText(context, context.getString(R.string.guidance_stopped), android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
            MapButton(if (settings.rideNorthUp) Icons.Rounded.North else Icons.Rounded.Navigation, stringResource(if (settings.rideNorthUp) R.string.ride_north_up else R.string.ride_heading_up)) {
                settings.updateRideNorthUp(!settings.rideNorthUp)
                camera.recenter()
            }
            MapButton(Icons.Rounded.Add, stringResource(R.string.ride_zoom_in)) { camera.zoomIn() }
            MapButton(Icons.Rounded.Remove, stringResource(R.string.ride_zoom_out)) { camera.zoomOut() }
            if (track != null && guidingTrace) BlockedButton { askDetour = true }
        }

        // Vitesse (gauche), toujours du côté opposé aux contrôles, et au-dessus l'enregistrement de la
        // sortie (même calque, comme l'iPhone : aucune nouvelle zone d'overlay).
        var finishing by remember { mutableStateOf(false) }
        var statsExpanded by remember { mutableStateOf(false) }
        Column(
            Modifier.align(if (controlsOnRight) Alignment.BottomStart else Alignment.BottomEnd).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = if (controlsOnRight) Alignment.Start else Alignment.End,
        ) {
            nav.speedLimitKmh?.let { SpeedLimitBadge(it, nav.isOverSpeedLimit, settings.distanceUnit) }
            if (statsExpanded) {
                // Pendant un « Aller à », restant/arrivée le long de son itinéraire (comme l'iPhone).
                val progress = navTracker?.let { session.progressAlong(it.route.totalDistanceMeters, it.route.totalDistanceMeters - it.remainingMeters) }
                    ?: goTo?.let { g -> nav.goToRemainingMeters?.let { session.progressAlong(g.lengthMeters, g.lengthMeters - it) } }
                    ?: session.progress
                RideStatsPanel(session, progress, recorder, camera.rawSpeedKmh, settings.distanceUnit, onCollapse = { statsExpanded = false }, onFinish = { finishing = true })
            } else {
                RecordingControls(recorder) { finishing = true }
                val expandLabel = stringResource(R.string.stats_expand)
                // Compteur rond (`RideStatsBadge` iOS) : toucher ouvre les mesures.
                Column(
                    Modifier.size(84.dp).background(PanelBackground, CircleShape).border(1.dp, MapPanelBorder, CircleShape)
                        .clickable(onClickLabel = expandLabel) { statsExpanded = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(speedValue(camera.rawSpeedKmh, settings.distanceUnit), color = Color.White, fontSize = 32.sp, style = RideNumberStyle)
                    Text(speedUnitLabel(settings.distanceUnit), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (recorder.wasRestoredAfterInterruption) {
            Box(Modifier.align(Alignment.TopCenter).padding(12.dp).mapPanel().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.recording_restored), color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        FlashOverlay(flashToken, settings.flashCount)
        if (finishing) EndRideDialog(recorder, library, track?.entry?.name) { finishing = false }
        // Premier suivi d'une trace : « Enregistrer cette sortie ? » (une fois par trace et par lancement).
        if (granted && track != null) RecordingPrompt(recorder, track.entry.id)
    }
}

private const val FLASH_METERS = 100.0

/** Panneau de limitation (`SpeedLimitBadgeView` iOS) : cercle rouge, plus épais et lumineux en cas de dépassement. */
@Composable
private fun SpeedLimitBadge(limitKmh: Int, over: Boolean, unit: DistanceUnit) {
    val value = if (unit == DistanceUnit.MI) (limitKmh / 1.609344).roundToInt() else limitKmh
    Box(
        Modifier.size(58.dp)
            .background(if (over) Color(0x66FF3B30) else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
            .padding(2.dp)
            .background(Color.White, androidx.compose.foundation.shape.CircleShape)
            .border(if (over) 6.dp else 5.dp, Color(0xFFFF3B30), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$value", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

/** Flash blanc plein écran (`FlashOverlayView` iOS) : [count] éclairs de 0,12 s. */
@Composable
private fun FlashOverlay(token: Int, count: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        repeat(count) {
            visible = true
            delay(120)
            visible = false
            delay(120)
        }
    }
    if (visible) Box(Modifier.fillMaxSize().background(Color.White))
}

/** Pause / Reprendre le guidage (`RideGuidanceToggleButton` iOS) ; appui long : l'arrêter. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuidanceToggle(stopped: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val label = stringResource(if (stopped) R.string.guidance_resume else R.string.guidance_pause)
    Column(
        Modifier.size(64.dp)
            .mapPanel(if (stopped) Color(0xE634C759) else PanelBackground)
            .combinedClickable(onClickLabel = label, onClick = { if (stopped) onResume() else onPause() }, onLongClick = onStop),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (stopped) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Appui long sur la carte (`commitGoTo` iOS) : « Aller ici » en Itinéraire, Piste ou Mixte. */
@Composable
private fun GoHereDialog(onDismiss: () -> Unit, onGo: (GoToProfile) -> Unit) {
    ChoiceSheet(
        stringResource(R.string.nav_go_here), stringResource(R.string.nav_go_here_message),
        listOf(
            SheetOption(Icons.Rounded.DirectionsCar, Color(0xFF0A84FF), profileLabel(GoToProfile.ROUTE)) { onGo(GoToProfile.ROUTE) },
            SheetOption(Icons.Rounded.Terrain, Color(0xFF8E6E53), profileLabel(GoToProfile.OFFROAD)) { onGo(GoToProfile.OFFROAD) },
            SheetOption(Icons.Rounded.AltRoute, Color(0xFFF46F16), profileLabel(GoToProfile.MIXED)) { onGo(GoToProfile.MIXED) },
        ),
        onDismiss,
    )
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
        Modifier.width(ColumnWidth).mapPanel(RejoinTint).padding(vertical = 12.dp, horizontal = 6.dp),
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
            Modifier.mapPanel().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}


/** Prochain virage à moins de 600 m (`LateralCapBannerView` iOS) : pictogramme, compte à rebours (km, comme l'iPhone), rang. */
@Composable
private fun LateralBanner(maneuver: RoadbookManeuver, distanceMeters: Double, rank: Int, total: Int, unit: DistanceUnit) {
    Column(
        Modifier.width(ColumnWidth).mapPanel().padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ManeuverPictogram(maneuver.checkpoint, Accent, Color.White, Modifier.size(52.dp))
        Text(
            RoadbookTexts.countdown(distanceMeters, unit), color = Color.White, style = RideNumberStyle, maxLines = 1, softWrap = false,
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
        Modifier.width(ColumnWidth).mapPanel().padding(vertical = 12.dp, horizontal = 6.dp),
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
