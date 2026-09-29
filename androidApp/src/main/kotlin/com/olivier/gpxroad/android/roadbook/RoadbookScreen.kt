package com.olivier.gpxroad.android.roadbook

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.data.LoadedTrack
import com.olivier.gpxroad.android.data.ReadingMode
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.location.LocationTracker
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.roadbook.data.LandmarkPhase
import com.olivier.gpxroad.android.roadbook.data.OffTrackState
import com.olivier.gpxroad.android.roadbook.data.RejoinController
import com.olivier.gpxroad.android.roadbook.data.RejoinDisplay
import com.olivier.gpxroad.android.roadbook.data.RoadbookData
import com.olivier.gpxroad.android.ui.Accent
import com.olivier.gpxroad.android.ui.OffTrackOrange
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.LandmarkCheckpoint
import com.olivier.gpxroad.shared.roadbook.LandmarkInfo
import com.olivier.gpxroad.shared.roadbook.RoadbookEntry
import com.olivier.gpxroad.shared.roadbook.RoadbookExtractor
import com.olivier.gpxroad.shared.roadbook.RoadbookLiveProgress
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import com.olivier.gpxroad.shared.roadbook.RoundaboutAnalyzer
import com.olivier.gpxroad.shared.roadbook.TrackGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Onglet Road Book (équivalent de `RoadBookTabView` iOS) : la trace ACTIVE en liste de directions,
 * façon road book papier de rallye — liste complète, ou prochain élément en grand avec le GPS.
 * Détection partagée avec l'iPhone ; Valhalla (vrais carrefours), ronds-points OSM et repères
 * visibles arrivent en arrière-plan ([RoadbookData]). L'écran reste allumé.
 */
@Composable
fun RoadbookScreen(
    library: TrackLibrary,
    settings: AppSettings,
    servers: ServerSettings,
    data: RoadbookData,
    rejoin: RejoinController,
    location: LocationTracker,
    onOpenLibrary: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val track = library.activeTrack
    if (track == null) {
        CenteredMessage(stringResource(R.string.no_track), stringResource(R.string.no_track_hint), stringResource(R.string.open_library), onOpenLibrary)
        return
    }
    val valhalla = servers.valhalla
    LaunchedEffect(track.traversalKey, valhalla) {
        data.ensureMapMatch(track)
        data.ensureRoundabouts(track)
    }
    val roadbookSettings = settings.roadbookSettings
    val mapMatch = data.mapMatch
    val roundabouts = data.roundabouts
    // L'ancienne liste reste affichée pendant qu'elle est recalculée (arrivée de Valhalla, des ronds-points).
    var maneuvers by remember(track.traversalKey) { mutableStateOf<List<RoadbookManeuver>?>(null) }
    LaunchedEffect(track.traversalKey, roadbookSettings, mapMatch, roundabouts) {
        maneuvers = withContext(Dispatchers.Default) {
            val passages = roundabouts?.takeIf { it.roads.isNotEmpty() || it.miniRoundabouts.isNotEmpty() }
                ?.let { RoundaboutAnalyzer.passages(track.latLons, it) } ?: emptyList()
            RoadbookExtractor.maneuvers(track.latLons, roadbookSettings, mapMatch?.maneuvers ?: emptyList(), mapMatch?.coverage, passages)
        }
    }
    val enabledCategories = settings.landmarkCategories
    LaunchedEffect(maneuvers, enabledCategories) {
        maneuvers?.let { data.updateLandmarks(track, it, enabledCategories) }
    }
    val selection = data.landmarkSelection
    val list = maneuvers
    val entries = remember(list, selection) { list?.let { RoadbookEntry.merge(it, selection.standalone) } }

    Column(Modifier.fillMaxSize()) {
        Header(track, settings, onOpenLibrary)
        LandmarkBanner(data.landmarkPhase) { data.retryLandmarks() }
        HorizontalDivider()
        when {
            entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.computing))
                }
            }
            entries.isEmpty() -> CenteredMessage(stringResource(R.string.no_maneuver), null, null, null)
            settings.readingMode == ReadingMode.LIST -> EntryList(entries, selection.attached, settings.distanceUnit)
            else -> GpsAssisted(track, entries, selection.attached, settings, servers, rejoin, location)
        }
    }
}

@Composable
private fun Header(track: LoadedTrack, settings: AppSettings, onOpenLibrary: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            track.entry.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onOpenLibrary),
        )
        val modes = listOf(ReadingMode.GPS_ASSISTED to R.string.mode_gps, ReadingMode.LIST to R.string.mode_list)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = settings.readingMode == mode,
                    onClick = { settings.updateReadingMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) { Text(stringResource(label)) }
            }
        }
    }
}

/** Chargement des repères (équivalent de `RoadbookLandmarkProgressView`) — jamais bloquant. */
@Composable
private fun LandmarkBanner(phase: LandmarkPhase, onRetry: () -> Unit) {
    if (phase == LandmarkPhase.Idle) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val text = when (phase) {
                is LandmarkPhase.Downloading -> buildString {
                    append(stringResource(R.string.landmarks_downloading, phase.completedChunks, phase.totalChunks))
                    if (phase.receivedBytes > 0) append(" · ").append(stringResource(R.string.landmarks_received, (phase.receivedBytes / 1024).toInt()))
                    if (phase.retrying) append(" · ").append(stringResource(R.string.landmarks_retrying))
                }
                LandmarkPhase.Analyzing -> stringResource(R.string.landmarks_analyzing)
                LandmarkPhase.Finished -> stringResource(R.string.landmarks_done)
                LandmarkPhase.Failed -> stringResource(R.string.landmarks_failed)
                is LandmarkPhase.Offline -> stringResource(R.string.landmarks_offline)
                LandmarkPhase.Idle -> ""
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (phase == LandmarkPhase.Failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        if (phase is LandmarkPhase.Downloading && phase.totalChunks > 0) {
            LinearProgressIndicator(progress = { phase.completedChunks.toFloat() / phase.totalChunks }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// MARK: Liste (roadbook classique)

@Composable
private fun EntryList(entries: List<RoadbookEntry>, attached: Map<Int, LandmarkInfo>, unit: DistanceUnit) {
    val resources = LocalContext.current.resources
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is RoadbookEntry.Maneuver -> {
                    val maneuver = entry.maneuver
                    val landmark = attached[entry.index]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("${entry.index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(34.dp))
                        PictogramWithLandmark(maneuver, landmark, 60)
                        Text(RoadbookTexts.distance(maneuver.partialDistanceMeters, unit), fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(130.dp))
                        Column(Modifier.weight(1f)) {
                            Text(RoadbookTexts.instruction(resources, maneuver.checkpoint), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            RoadbookTexts.roundaboutDetail(resources, maneuver.checkpoint)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                            landmark?.let { Text(RoadbookTexts.landmarkDisplayLabel(resources, it), style = MaterialTheme.typography.bodyMedium) }
                            Text(
                                stringResource(R.string.heading, maneuver.headingDegrees.roundToInt()) + " · " +
                                    stringResource(R.string.cumulative, RoadbookTexts.distance(maneuver.cumulativeDistanceMeters, unit)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is RoadbookEntry.Landmark -> LandmarkRow(entry.landmark, RoadbookTexts.distance(entry.landmark.cumulativeDistanceMeters, unit))
            }
            HorizontalDivider()
        }
    }
}

private val RoadbookEntry.key: String
    get() = when (this) {
        is RoadbookEntry.Maneuver -> "m-${maneuver.cumulativeDistanceMeters}-$index"
        is RoadbookEntry.Landmark -> "l-${landmark.cumulativeDistanceMeters}-${landmark.info.label}"
    }

@Composable
private fun PictogramWithLandmark(maneuver: RoadbookManeuver, landmark: LandmarkInfo?, size: Int) {
    Box(contentAlignment = Alignment.BottomEnd) {
        ManeuverPictogram(maneuver.checkpoint, Accent, MaterialTheme.colorScheme.onSurface, Modifier.size(size.dp))
        landmark?.let { Text(RoadbookTexts.emoji(it.category), fontSize = (size * 0.35).sp) }
    }
}

/** Repère en ligne dédiée : pictogramme de la catégorie, nom, côté, fond teinté — jamais confondu avec un virage. */
@Composable
private fun LandmarkRow(landmark: LandmarkCheckpoint, distance: String) {
    val resources = LocalContext.current.resources
    Row(
        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.width(34.dp))
        Text(RoadbookTexts.emoji(landmark.info.category), fontSize = 30.sp)
        Column(Modifier.weight(1f)) {
            Text(RoadbookTexts.landmarkLabel(resources, landmark.info), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(RoadbookTexts.landmarkDetail(resources, landmark.info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(distance, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

// MARK: Assisté GPS

/** Élément de la liste « Ensuite » (portage de `RoadbookFocusedView.UpcomingStep`). */
private sealed interface UpcomingStep {
    val distance: Double

    data class Maneuver(val maneuver: RoadbookManeuver, val rank: Int, val landmark: LandmarkInfo?, override val distance: Double) : UpcomingStep
    data class Landmark(val landmark: LandmarkCheckpoint, override val distance: Double) : UpcomingStep
    data class RejoinManeuver(val maneuver: RoadbookManeuver, val rank: Int, override val distance: Double) : UpcomingStep
    data class RejoinArrival(override val distance: Double) : UpcomingStep
}

/** Tous les éléments après le prochain, dans l'ordre de la trace ; « +n » = n-ième virage après l'élément mis en avant. */
private fun upcomingSteps(entries: List<RoadbookEntry>, attached: Map<Int, LandmarkInfo>, currentIndex: Int, position: Double): List<UpcomingStep> {
    var rank = 1
    return entries.drop(currentIndex + 1).map { entry ->
        val distance = max(entry.cumulativeDistanceMeters - position, 0.0)
        when (entry) {
            is RoadbookEntry.Maneuver -> UpcomingStep.Maneuver(entry.maneuver, ++rank, attached[entry.index], distance)
            is RoadbookEntry.Landmark -> UpcomingStep.Landmark(entry.landmark, distance)
        }
    }
}

/** Hors trace avec un chemin : ses virages suivants, le retour sur la trace, puis le Road Book après le point de retour. */
private fun rejoinSteps(rejoin: RejoinDisplay, entries: List<RoadbookEntry>, attached: Map<Int, LandmarkInfo>): List<UpcomingStep> {
    val remaining = rejoin.remainingToTrackMeters ?: return emptyList()
    val target = rejoin.targetCumulativeDistanceMeters ?: return emptyList()
    if (rejoin.status != RejoinDisplay.Status.ROUTED) return emptyList()
    val steps = mutableListOf<UpcomingStep>()
    var rank = 1
    val firstFollowing = (rejoin.nextManeuverIndex ?: rejoin.maneuvers.size) + 1
    for (maneuver in rejoin.maneuvers.drop(firstFollowing)) {
        steps += UpcomingStep.RejoinManeuver(maneuver, ++rank, max(maneuver.cumulativeDistanceMeters - rejoin.routeCumulativeDistanceMeters, 0.0))
    }
    if (rejoin.nextManeuverIndex != null) steps += UpcomingStep.RejoinArrival(remaining)
    for (entry in entries.filter { it.cumulativeDistanceMeters > target }) {
        val distance = remaining + entry.cumulativeDistanceMeters - target
        steps += when (entry) {
            is RoadbookEntry.Maneuver -> UpcomingStep.Maneuver(entry.maneuver, ++rank, attached[entry.index], distance)
            is RoadbookEntry.Landmark -> UpcomingStep.Landmark(entry.landmark, distance)
        }
    }
    return steps
}

@Composable
private fun GpsAssisted(
    track: LoadedTrack,
    entries: List<RoadbookEntry>,
    attached: Map<Int, LandmarkInfo>,
    settings: AppSettings,
    servers: ServerSettings,
    rejoin: RejoinController,
    location: LocationTracker,
) {
    var permissionRequests by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRequests++ }
    val granted = remember(permissionRequests) { location.hasPermission }
    if (!granted) {
        CenteredMessage(stringResource(R.string.location_needed), null, stringResource(R.string.location_allow)) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        return
    }
    DisposableEffect(granted) {
        location.start()
        onDispose {
            location.stop()
            rejoin.reset()
        }
    }
    if (!location.isRunning) {
        CenteredMessage(stringResource(R.string.location_off), null, null, null)
        return
    }

    val unit = settings.distanceUnit
    val fix = location.location
    // Comme l'iPhone : position projetée sur la trace à chaque fix (point le plus proche), sans mémoire.
    val current = remember(fix, track.traversalKey) {
        fix?.let { TrackGeometry.project(LatLon(it.latitude, it.longitude), track.latLons, track.cumulative)?.cumulativeDistanceMeters }
    }
    var offTrack by remember(track.traversalKey) { mutableStateOf(OffTrackState()) }
    DisposableEffect(track.traversalKey) { onDispose { rejoin.reset() } }
    LaunchedEffect(fix, track.traversalKey) {
        val position = fix ?: return@LaunchedEffect
        offTrack = offTrack.updated(position, track.latLons, track.cumulative)
        rejoin.update(position, track.latLons, track.cumulative, offTrack.isOffTrack, current, settings.roadbookSettings, servers.valhalla)
    }
    val positions = remember(entries) { entries.map { it.cumulativeDistanceMeters } }
    val progress = current?.let { RoadbookLiveProgress.next(positions, it) }
    val rejoinDisplay = rejoin.display
    val upcoming = when {
        offTrack.isOffTrack && rejoinDisplay?.status == RejoinDisplay.Status.ROUTED -> rejoinSteps(rejoinDisplay, entries, attached)
        progress != null && current != null -> upcomingSteps(entries, attached, progress.index, current)
        else -> emptyList()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Deux colonnes (prochaine direction à gauche, suivantes à droite pour anticiper) dès que la
        // largeur le permet : tablette dans les deux sens, téléphone en paysage.
        val twoColumns = landscape || maxWidth >= 600.dp
        val hero: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) {
                when {
                    offTrack.isOffTrack -> RejoinHero(offTrack, rejoinDisplay, unit)
                    progress != null -> when (val entry = entries[progress.index]) {
                        is RoadbookEntry.Maneuver -> BigManeuverCard(entry.maneuver, progress.distanceRemainingMeters, unit, attached[entry.index])
                        is RoadbookEntry.Landmark -> BigLandmarkCard(entry.landmark, progress.distanceRemainingMeters, unit)
                    }
                    fix == null -> Text(stringResource(R.string.waiting_gps), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    else -> Text(stringResource(R.string.finished), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                }
            }
        }
        val list: @Composable (Modifier) -> Unit = { modifier -> UpcomingList(upcoming, unit, modifier) }
        when {
            upcoming.isEmpty() -> hero(Modifier.fillMaxSize().padding(16.dp))
            twoColumns -> Row(Modifier.fillMaxSize()) {
                hero(Modifier.weight(1.3f).fillMaxSize().padding(12.dp))
                VerticalDivider()
                list(Modifier.weight(1f).fillMaxSize())
            }
            else -> Column(Modifier.fillMaxSize()) {
                hero(Modifier.fillMaxWidth().weight(1.1f).padding(16.dp))
                HorizontalDivider()
                list(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun BigManeuverCard(maneuver: RoadbookManeuver, remainingMeters: Double, unit: DistanceUnit, landmark: LandmarkInfo?) {
    val resources = LocalContext.current.resources
    val details = listOfNotNull(
        RoadbookTexts.roundaboutDetail(resources, maneuver.checkpoint),
        landmark?.let { RoadbookTexts.landmarkDisplayLabel(resources, it) },
    )
    BigCard(
        pictogram = { modifier ->
            Box(modifier, contentAlignment = Alignment.BottomEnd) {
                ManeuverPictogram(maneuver.checkpoint, Accent, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxSize())
                landmark?.let { Text(RoadbookTexts.emoji(it.category), fontSize = 44.sp) }
            }
        },
        distance = RoadbookTexts.countdown(remainingMeters, unit),
        subtitle = stringResource(R.string.heading, maneuver.headingDegrees.roundToInt()),
        title = RoadbookTexts.instruction(resources, maneuver.checkpoint),
        details = details,
    )
}

/** Prochain élément = un REPÈRE : même place et même hiérarchie que la carte d'un virage. */
@Composable
private fun BigLandmarkCard(landmark: LandmarkCheckpoint, remainingMeters: Double, unit: DistanceUnit) {
    val resources = LocalContext.current.resources
    BigCard(
        pictogram = { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(RoadbookTexts.emoji(landmark.info.category), autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = 220.sp), maxLines = 1)
            }
        },
        distance = RoadbookTexts.countdown(remainingMeters, unit),
        subtitle = null,
        title = RoadbookTexts.landmarkLabel(resources, landmark.info),
        details = listOf(RoadbookTexts.landmarkDetail(resources, landmark.info)),
    )
}

/**
 * Carte du prochain élément : tailles proportionnelles à la place disponible (lecture d'un coup
 * d'œil) ; les textes rétrécissent plutôt que de passer à la ligne ou de déborder.
 */
@Composable
private fun BigCard(pictogram: @Composable (Modifier) -> Unit, distance: String, subtitle: String?, title: String, details: List<String>) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val tall = maxHeight >= maxWidth
        if (tall) {
            val size = minOf(maxWidth * 0.85f, maxHeight * 0.42f)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pictogram(Modifier.size(size))
                Text(
                    distance, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = 150.sp),
                )
                subtitle?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                CardTexts(title, details, maxFontSize = 52.sp)
            }
        } else {
            val size = minOf(maxHeight * 0.62f, maxWidth * 0.42f)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    pictogram(Modifier.size(size))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(distance, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = 130.sp))
                        subtitle?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                CardTexts(title, details, maxFontSize = 44.sp)
            }
        }
    }
}

@Composable
private fun CardTexts(title: String, details: List<String>, maxFontSize: TextUnit) {
    Text(
        title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth(),
        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = maxFontSize),
    )
    details.forEach {
        Text(
            it, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth(),
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = maxFontSize * 0.7f),
        )
    }
}

/**
 * Hors trace : prochain virage du chemin de reprise (ou le retour sur la trace) avec le badge « Hors
 * trace » ; sans chemin (attente, calcul, réseau), la carte hors trace.
 */
@Composable
private fun RejoinHero(offTrack: OffTrackState, rejoin: RejoinDisplay?, unit: DistanceUnit) {
    val remaining = rejoin?.remainingToTrackMeters
    if (rejoin?.status == RejoinDisplay.Status.ROUTED && remaining != null) {
        Box(Modifier.fillMaxSize().background(OffTrackOrange.copy(alpha = 0.10f), RoundedCornerShape(20.dp)).padding(12.dp)) {
            val index = rejoin.nextManeuverIndex
            val distance = rejoin.distanceToNextManeuverMeters
            if (index != null && index in rejoin.maneuvers.indices && distance != null) {
                BigManeuverCard(rejoin.maneuvers[index], distance, unit, null)
            } else {
                BigCard(
                    pictogram = { modifier -> Box(modifier, contentAlignment = Alignment.Center) { Text("⤴", autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = 220.sp), color = OffTrackOrange) } },
                    distance = RoadbookTexts.countdown(remaining, unit),
                    subtitle = null,
                    title = stringResource(R.string.back_on_track),
                    details = emptyList(),
                )
            }
            OffTrackBadge(Modifier.align(Alignment.TopCenter))
        }
    } else {
        OffTrackCard(offTrack, rejoin?.status, unit)
    }
}

@Composable
private fun OffTrackBadge(modifier: Modifier = Modifier) {
    Surface(color = OffTrackOrange, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(stringResource(R.string.off_track), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

/** « Hors trace » (même règle que le Ride), distance de reprise après 30 s, état du chemin de retour. */
@Composable
private fun OffTrackCard(offTrack: OffTrackState, status: RejoinDisplay.Status?, unit: DistanceUnit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val showsDistance = offTrack.showsRejoinDistance(now) && offTrack.rejoinDistanceMeters != null
    Column(
        Modifier.background(OffTrackOrange.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(color = OffTrackOrange, shape = RoundedCornerShape(50)) {
            Text(stringResource(R.string.off_track), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        }
        if (showsDistance) {
            Text(stringResource(R.string.off_track_distance, RoadbookTexts.countdown(offTrack.rejoinDistanceMeters!!, unit)), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        val message = when {
            status == RejoinDisplay.Status.COMPUTING -> stringResource(R.string.rejoin_computing)
            status == RejoinDisplay.Status.UNAVAILABLE -> stringResource(R.string.rejoin_unavailable)
            !showsDistance -> stringResource(R.string.directions_resume)
            else -> null
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun UpcomingList(steps: List<UpcomingStep>, unit: DistanceUnit, modifier: Modifier) {
    val resources = LocalContext.current.resources
    Column(modifier) {
        Text(stringResource(R.string.then_header), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
        LazyColumn {
            items(steps) { step ->
                when (step) {
                    is UpcomingStep.Maneuver -> UpcomingManeuverRow(step.maneuver, step.rank, step.landmark, step.distance, unit, null)
                    is UpcomingStep.RejoinManeuver -> UpcomingManeuverRow(step.maneuver, step.rank, null, step.distance, unit, OffTrackOrange.copy(alpha = 0.08f))
                    is UpcomingStep.Landmark -> LandmarkRow(step.landmark, RoadbookTexts.countdown(step.distance, unit))
                    is UpcomingStep.RejoinArrival -> Row(
                        Modifier.fillMaxWidth().background(OffTrackOrange.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Spacer(Modifier.width(34.dp))
                        Text("⤴", fontSize = 30.sp, color = OffTrackOrange)
                        Text(stringResource(R.string.back_on_track), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(RoadbookTexts.countdown(step.distance, unit), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun UpcomingManeuverRow(maneuver: RoadbookManeuver, rank: Int, landmark: LandmarkInfo?, distance: Double, unit: DistanceUnit, tint: Color?) {
    val resources = LocalContext.current.resources
    Row(
        Modifier.fillMaxWidth().then(if (tint != null) Modifier.background(tint) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("+${rank - 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(34.dp))
        PictogramWithLandmark(maneuver, landmark, 52)
        Column(Modifier.weight(1f)) {
            Text(RoadbookTexts.instruction(resources, maneuver.checkpoint), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            RoadbookTexts.roundaboutDetail(resources, maneuver.checkpoint)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
            landmark?.let { Text(RoadbookTexts.landmarkDisplayLabel(resources, it), style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(RoadbookTexts.countdown(distance, unit), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("${maneuver.headingDegrees.roundToInt()}°", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CenteredMessage(title: String, detail: String?, action: String?, onAction: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}
