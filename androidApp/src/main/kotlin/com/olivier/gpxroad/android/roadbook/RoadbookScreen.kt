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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.data.LoadedTrack
import com.olivier.gpxroad.android.data.ReadingMode
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.location.LocationTracker
import com.olivier.gpxroad.android.ui.Accent
import com.olivier.gpxroad.android.ui.OffTrackOrange
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.roadbook.LiveTrackMatcher
import com.olivier.gpxroad.shared.roadbook.RoadbookExtractor
import com.olivier.gpxroad.shared.roadbook.RoadbookLiveProgress
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import com.olivier.gpxroad.shared.roadbook.TrackMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Onglet Road Book (équivalent de `RoadBookTabView` iOS) : la trace ACTIVE en liste de directions,
 * façon road book papier de rallye — liste complète, ou prochain élément en grand avec le GPS.
 * Toute la détection vient du module partagé ; l'écran reste allumé (comme sur iPhone).
 */
@Composable
fun RoadbookScreen(library: TrackLibrary, settings: AppSettings, location: LocationTracker, onOpenLibrary: () -> Unit) {
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
    val roadbookSettings = settings.roadbookSettings
    val maneuvers by produceState<List<RoadbookManeuver>?>(null, track.traversalKey, roadbookSettings) {
        value = null
        value = withContext(Dispatchers.Default) { RoadbookExtractor.maneuvers(track.latLons, roadbookSettings) }
    }

    Column(Modifier.fillMaxSize()) {
        Header(track, settings, onOpenLibrary)
        HorizontalDivider()
        val list = maneuvers
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.computing))
                }
            }
            list.isEmpty() -> CenteredMessage(stringResource(R.string.no_maneuver), null, null, null)
            settings.readingMode == ReadingMode.LIST -> ManeuverList(list, settings.distanceUnit)
            else -> GpsAssisted(track, list, settings.distanceUnit, location)
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

// MARK: Liste (roadbook classique)

@Composable
private fun ManeuverList(maneuvers: List<RoadbookManeuver>, unit: DistanceUnit) {
    val resources = LocalContext.current.resources
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(maneuvers, key = { _, m -> m.cumulativeDistanceMeters }) { index, maneuver ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(34.dp))
                ManeuverPictogram(maneuver.checkpoint, Accent, MaterialTheme.colorScheme.onSurface, Modifier.size(60.dp))
                Text(
                    RoadbookTexts.distance(maneuver.partialDistanceMeters, unit),
                    fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(130.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(RoadbookTexts.instruction(resources, maneuver.checkpoint), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    RoadbookTexts.roundaboutDetail(resources, maneuver.checkpoint)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                    Text(
                        stringResource(R.string.heading, maneuver.headingDegrees.roundToInt()) + " · " +
                            stringResource(R.string.cumulative, RoadbookTexts.distance(maneuver.cumulativeDistanceMeters, unit)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

// MARK: Assisté GPS

@Composable
private fun GpsAssisted(track: LoadedTrack, maneuvers: List<RoadbookManeuver>, unit: DistanceUnit, location: LocationTracker) {
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
        onDispose { location.stop() }
    }
    if (!location.isRunning) {
        CenteredMessage(stringResource(R.string.location_off), null, null, null)
        return
    }

    val matcher = remember(track.traversalKey) { LiveTrackMatcher() }
    val fix = location.location
    val match: TrackMatch? = remember(fix, track.traversalKey) {
        fix?.let { matcher.update(LatLon(it.latitude, it.longitude), track.latLons, track.cumulative) }
    }
    val positions = remember(maneuvers) { maneuvers.map { it.cumulativeDistanceMeters } }
    val progress = match?.cumulativeDistanceMeters?.let { RoadbookLiveProgress.next(positions, it) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Deux colonnes (prochaine direction à gauche, suivantes à droite pour anticiper) dès que la
        // largeur le permet : tablette dans les deux sens, téléphone en paysage.
        val twoColumns = landscape || maxWidth >= 600.dp
        val hero: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) {
                when {
                    match == null -> Text(stringResource(R.string.waiting_gps), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    match.isOffTrack -> OffTrackCard(match, unit)
                    progress == null -> Text(stringResource(R.string.finished), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    else -> BigManeuverCard(maneuvers[progress.index], progress.distanceRemainingMeters, unit)
                }
            }
        }
        // Sans avancement connu (jamais passé sur la trace), pas de « Ensuite » : le bandeau prend tout l'écran.
        val current = match?.cumulativeDistanceMeters
        val next = if (current == null || progress == null) emptyList() else maneuvers.drop(progress.index + 1).take(8)
        val upcoming: @Composable (Modifier) -> Unit = { modifier -> UpcomingList(next, current ?: 0.0, unit, modifier) }
        if (next.isEmpty()) {
            hero(Modifier.fillMaxSize().padding(16.dp))
        } else if (twoColumns) {
            Row(Modifier.fillMaxSize()) {
                hero(Modifier.weight(1.3f).fillMaxSize().padding(12.dp))
                VerticalDivider()
                upcoming(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                hero(Modifier.fillMaxWidth().weight(1.1f).padding(16.dp))
                HorizontalDivider()
                upcoming(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun BigManeuverCard(maneuver: RoadbookManeuver, remainingMeters: Double, unit: DistanceUnit) {
    val resources = LocalContext.current.resources
    val instruction = RoadbookTexts.instruction(resources, maneuver.checkpoint)
    val roundabout = RoadbookTexts.roundaboutDetail(resources, maneuver.checkpoint)
    val distance = RoadbookTexts.countdown(remainingMeters, unit)
    val heading = stringResource(R.string.heading, maneuver.headingDegrees.roundToInt())
    // Tailles proportionnelles à la place disponible : on doit lire la prochaine direction d'un coup d'œil.
    // Les textes rétrécissent (autoSize) plutôt que de passer à la ligne ou de déborder.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val tall = maxHeight >= maxWidth
        if (tall) {
            val pictogram = minOf(maxWidth * 0.85f, maxHeight * 0.42f)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ManeuverPictogram(maneuver.checkpoint, Accent, MaterialTheme.colorScheme.onSurface, Modifier.size(pictogram))
                Text(
                    distance, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = 150.sp),
                )
                Text(heading, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ManeuverInstruction(instruction, roundabout, maxFontSize = 52.sp)
            }
        } else {
            val pictogram = minOf(maxHeight * 0.62f, maxWidth * 0.42f)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    ManeuverPictogram(maneuver.checkpoint, Accent, MaterialTheme.colorScheme.onSurface, Modifier.size(pictogram))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            distance, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = 130.sp),
                        )
                        Text(heading, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ManeuverInstruction(instruction, roundabout, maxFontSize = 44.sp)
            }
        }
    }
}

@Composable
private fun ManeuverInstruction(instruction: String, roundabout: String?, maxFontSize: TextUnit) {
    Text(
        instruction, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth(),
        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = maxFontSize),
    )
    roundabout?.let {
        Text(
            it, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth(),
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = maxFontSize * 0.7f),
        )
    }
}

@Composable
private fun OffTrackCard(match: TrackMatch, unit: DistanceUnit) {
    Column(
        Modifier.background(OffTrackOrange.copy(alpha = 0.12f), RoundedCornerShape(20.dp)).padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(color = OffTrackOrange, shape = RoundedCornerShape(50)) {
            Text(stringResource(R.string.off_track), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        }
        Text(stringResource(R.string.off_track_distance, RoadbookTexts.countdown(match.distanceToTrackMeters, unit)), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UpcomingList(items: List<RoadbookManeuver>, currentCumulative: Double, unit: DistanceUnit, modifier: Modifier) {
    val resources = LocalContext.current.resources
    Column(modifier) {
        if (items.isNotEmpty()) {
            Text(stringResource(R.string.then_header), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
        }
        LazyColumn {
            itemsIndexed(items, key = { _, m -> m.cumulativeDistanceMeters }) { _, maneuver ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ManeuverPictogram(maneuver.checkpoint, Accent, MaterialTheme.colorScheme.onSurface, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(RoadbookTexts.instruction(resources, maneuver.checkpoint), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        RoadbookTexts.roundaboutDetail(resources, maneuver.checkpoint)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                    }
                    Text(RoadbookTexts.countdown(maneuver.cumulativeDistanceMeters - currentCumulative, unit), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
            }
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
