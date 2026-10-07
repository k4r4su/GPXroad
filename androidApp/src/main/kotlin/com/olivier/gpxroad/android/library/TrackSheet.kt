package com.olivier.gpxroad.android.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import com.olivier.gpxroad.android.ui.GroupDivider
import com.olivier.gpxroad.android.ui.GroupContent
import com.olivier.gpxroad.android.settings.SettingChoice
import com.olivier.gpxroad.android.data.TraceColor
import com.olivier.gpxroad.android.data.TraceWidth
import com.olivier.gpxroad.shared.ride.DirectionChevrons
import com.olivier.gpxroad.android.ui.SettingsGroup
import com.olivier.gpxroad.android.ui.SettingsRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.data.TrackEntry
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.offline.OfflineMaps
import com.olivier.gpxroad.android.offline.TrackOfflineRow
import com.olivier.gpxroad.android.offline.TrackPreparer
import com.olivier.gpxroad.android.offline.TrackReadinessRow
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.track.TrackMetrics
import com.olivier.gpxroad.shared.track.TrackSummary
import com.olivier.gpxroad.shared.gpx.GpxPoint
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.olivier.gpxroad.shared.track.TrackMetricsCalculator
import kotlin.math.roundToInt

/**
 * Fiche d'une trace (`TrackFullSheetView` iOS) : nom, trace active, distance / points / dénivelé,
 * statistiques avancées (seulement avec un horodatage réel), puis les actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSheet(
    entry: TrackEntry,
    library: TrackLibrary,
    settings: AppSettings,
    offline: OfflineMaps,
    preparer: TrackPreparer,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onActivate: () -> Unit,
    onShare: () -> Unit,
    onReverse: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    // Lecture du GPX et calculs hors du fil principal : la feuille s'ouvre tout de suite.
    val loaded by produceState<Triple<List<GpxPoint>, TrackSummary, TrackMetrics?>?>(null, entry.id) {
        value = withContext(Dispatchers.Default) {
            val points = library.document(entry)?.points.orEmpty()
            Triple(points, TrackMetricsCalculator.summary(points), TrackMetricsCalculator.compute(points))
        }
    }
    val points = loaded?.first.orEmpty()
    val summary = loaded?.second ?: TrackSummary(entry.lengthMeters, 0.0)
    val metrics = loaded?.third
    var showAdvanced by remember { mutableStateOf(false) }
    val unit = settings.distanceUnit

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(entry.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (isActive) {
                Text("✓ " + stringResource(R.string.track_active_for_ride), color = Color(0xFF34A853), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (entry.reversed) Text(stringResource(R.string.library_reversed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(stringResource(R.string.track_distance), RoadbookTexts.distance(summary.lengthMeters, unit))
                Stat(stringResource(R.string.track_points), entry.pointCount.toString())
                Stat(stringResource(R.string.track_elevation_gain), elevation(summary.elevationGainMeters, unit))
            }
            if (metrics != null) {
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text((if (showAdvanced) "▾ " else "▸ ") + stringResource(R.string.track_advanced))
                }
                if (showAdvanced) AdvancedGrid(metrics, unit)
            } else if (loaded != null) {
                Text(stringResource(R.string.track_advanced_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (loaded != null) {
                val latLons = remember(entry.id, points.size) { points.map { LatLon(it.latitude, it.longitude) } }
                TrackOfflineRow(entry.id, entry.name, latLons, offline)
                if (isActive) library.activeTrack?.let { TrackReadinessRow(preparer, it) }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isActive) {
                    Button(onClick = { onActivate(); onDismiss() }, Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Rounded.Navigation, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.track_follow))
                    }
                }
                TrackAppearanceGroup(current = entry, settings = settings, onChange = { c, w, sp -> library.setAppearance(entry.id, c, w, sp) })
                SettingsGroup {
                    SettingsRow(stringResource(R.string.share_gpx), Icons.Rounded.Share, Color(0xFF007AFF), onClick = onShare, trailing = {})
                    GroupDivider()
                    SettingsRow(stringResource(R.string.library_reverse), Icons.Rounded.SwapVert, Color(0xFF8E8E93), onClick = onReverse, trailing = {})
                    GroupDivider()
                    SettingsRow(stringResource(R.string.library_rename), Icons.Rounded.Edit, Color(0xFF8E8E93), onClick = onRename, trailing = {})
                    GroupDivider()
                    SettingsRow(stringResource(R.string.folder_move), Icons.AutoMirrored.Rounded.DriveFileMove, Color(0xFF8E8E93), onClick = onMove, trailing = {})
                    GroupDivider()
                    SettingsRow(stringResource(R.string.library_delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error, onClick = onDelete, trailing = {})
                }
            }
        }
    }
}

@Composable
private fun AdvancedGrid(m: TrackMetrics, unit: DistanceUnit) {
    val cells = listOf(
        stringResource(R.string.track_total_duration) to duration(m.durationSeconds),
        stringResource(R.string.track_moving_duration) to duration(m.movingDurationSeconds),
        stringResource(R.string.track_average_speed) to speed(m.averageSpeedKmh, unit),
        stringResource(R.string.track_moving_average) to speed(m.averageMovingSpeedKmh, unit),
        stringResource(R.string.track_max_speed) to speed(m.maxSpeedKmh, unit),
        stringResource(R.string.track_max_slope) to "${m.maxGradePercent.roundToInt()} %",
        stringResource(R.string.track_elevation_gain) to elevation(m.elevationGainMeters, unit),
        stringResource(R.string.track_elevation_loss) to elevation(m.elevationLossMeters, unit),
        stringResource(R.string.track_min_altitude) to elevation(m.minElevationMeters, unit),
        stringResource(R.string.track_max_altitude) to elevation(m.maxElevationMeters, unit),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            cells.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (title, value) -> Stat(title, value, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun duration(seconds: Double): String {
    val total = seconds.roundToInt()
    val h = total / 3600
    val min = total % 3600 / 60
    return if (h > 0) "$h h ${min.toString().padStart(2, '0')}" else "$min min"
}

private fun speed(kmh: Double, unit: DistanceUnit): String =
    if (unit == DistanceUnit.KM) "${kmh.roundToInt()} km/h" else "${(kmh / 1.609344).roundToInt()} mph"

private fun elevation(meters: Double, unit: DistanceUnit): String =
    if (unit == DistanceUnit.KM) "${meters.roundToInt()} m" else "${(meters * 3.28084).roundToInt()} ft"

/** Apparence de cette trace (`TrackSettingsView` iOS) : couleur, épaisseur, chevrons ; « Par défaut » = Réglages. */
@Composable
private fun TrackAppearanceGroup(current: TrackEntry, settings: AppSettings, onChange: (TraceColor?, TraceWidth?, Double?) -> Unit) {
    val byDefault = stringResource(R.string.track_default)
    SettingsGroup(stringResource(R.string.track_appearance), footer = stringResource(R.string.track_appearance_footer)) {
        GroupContent {
            SettingChoice(stringResource(R.string.settings_trace_color), listOf<TraceColor?>(null) + TraceColor.entries, current.colorOverride, { it?.let { c -> stringResource(c.label) } ?: byDefault }) {
                onChange(it, current.widthOverride, current.chevronSpacingOverride)
            }
            SettingChoice(stringResource(R.string.settings_trace_width), listOf<TraceWidth?>(null) + TraceWidth.entries, current.widthOverride, { it?.let { w -> stringResource(w.label) } ?: byDefault }) {
                onChange(current.colorOverride, it, current.chevronSpacingOverride)
            }
            SettingChoice(
                stringResource(R.string.settings_chevron_spacing), listOf<Double?>(null) + DirectionChevrons.SPACING_OPTIONS, current.chevronSpacingOverride,
                { it?.let { v -> if (v >= 1000) "1 km" else "${v.roundToInt()} m" } ?: byDefault },
            ) { onChange(current.colorOverride, current.widthOverride, it) }
        }
    }
}
