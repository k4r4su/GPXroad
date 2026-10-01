package com.olivier.gpxroad.android.offline

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.offline.OfflineArea
import com.olivier.gpxroad.shared.offline.OfflineConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** Fiche d'une trace : garder sa carte hors ligne (couloir de ±1 km), progression, suppression. */
@Composable
fun TrackOfflineRow(trackId: String, name: String, points: List<LatLon>, offline: OfflineMaps) {
    val zone = offline.zoneForTrack(trackId)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            zone == null -> {
                val tiles by produceState<Int?>(null, points) {
                    value = withContext(Dispatchers.Default) {
                        OfflineArea.tileCount(OfflineArea.corridorBoxes(points), OfflineConstants.CORRIDOR_MIN_ZOOM, OfflineConstants.VECTOR_MAX_ZOOM)
                    }
                }
                OutlinedButton(onClick = { offline.downloadTrack(trackId, name, points) }, enabled = tiles != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.offline_track_download, tiles?.let { size(OfflineArea.estimatedBytes(it.toLong())) } ?: "…"))
                }
                Text(stringResource(R.string.offline_track_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> ZoneStatus(zone, onDelete = { offline.delete(zone.id) })
        }
    }
}

@Composable
private fun ZoneStatus(zone: OfflineZone, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    zone.failed -> stringResource(R.string.offline_failed)
                    zone.isComplete -> stringResource(R.string.offline_track_ready, size(zone.sizeBytes))
                    else -> stringResource(R.string.offline_downloading, (zone.progress * 100).roundToInt())
                },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) }
        }
        if (!zone.isComplete && !zone.failed) LinearProgressIndicator(progress = { zone.progress }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Réglages > Cartes hors ligne (`RegionDownloadView` iOS) : zones téléchargées (taille, suppression)
 * et « Télécharger une zone » choisie sur la carte ([RegionPicker], centrée sur la position).
 */
@Composable
fun OfflineSection(offline: OfflineMaps, position: LatLon?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.offline_zones), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (offline.zones.isEmpty()) Text(stringResource(R.string.offline_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        offline.zones.forEach { zone ->
            Column {
                Text(zone.name, style = MaterialTheme.typography.bodyLarge)
                if (zone.createdMillis > 0) {
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(zone.createdMillis)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ZoneStatus(zone) { offline.delete(zone.id) }
            }
        }
        var picking by remember { mutableStateOf(false) }
        Button(onClick = { picking = true }) { Text(stringResource(R.string.offline_pick_title)) }
        Text(stringResource(R.string.offline_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (picking) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { picking = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) { RegionPicker(offline, position) { picking = false } }
        }
        Text(stringResource(R.string.offline_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
