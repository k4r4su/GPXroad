package com.olivier.gpxroad.android.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.shared.track.TrackMetrics
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
    isActive: Boolean,
    onDismiss: () -> Unit,
    onActivate: () -> Unit,
    onShare: () -> Unit,
    onReverse: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val points = remember(entry.id) { library.document(entry)?.points.orEmpty() }
    val summary = remember(entry.id) { TrackMetricsCalculator.summary(points) }
    val metrics = remember(entry.id) { TrackMetricsCalculator.compute(points) }
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
            } else {
                Text(stringResource(R.string.track_advanced_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isActive) Button(onClick = { onActivate(); onDismiss() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.track_follow)) }
                OutlinedButton(onClick = onShare, Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_gpx)) }
                OutlinedButton(onClick = onReverse, Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_reverse)) }
                OutlinedButton(onClick = onRename, Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_rename)) }
                OutlinedButton(onClick = onMove, Modifier.fillMaxWidth()) { Text(stringResource(R.string.folder_move)) }
                OutlinedButton(onClick = onDelete, Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) }
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
