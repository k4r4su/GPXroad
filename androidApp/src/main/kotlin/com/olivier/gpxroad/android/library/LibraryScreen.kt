package com.olivier.gpxroad.android.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.TrackEntry
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.recording.RideRecorder
import com.olivier.gpxroad.android.recording.UnsavedRide
import com.olivier.gpxroad.android.recording.shareGpx
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.shared.gpx.GpxParseException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Bibliothèque (équivalent de `LibraryView` iOS) : import GPX, trace active (la SEULE que suivent
 * le Road Book et, plus tard, le Ride), sens de parcours, renommage, suppression.
 * [pendingImport] : fichier GPX ouvert depuis une autre app (« Ouvrir avec GPXroad »).
 */
@Composable
fun LibraryScreen(library: TrackLibrary, settings: AppSettings, recorder: RideRecorder, pendingImport: Uri?, onImportHandled: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun importAndReport(uri: Uri) {
        val message = when (val result = library.import(uri)) {
            is TrackLibrary.ImportResult.Success -> context.getString(R.string.library_imported, result.entry.name)
            is TrackLibrary.ImportResult.Failure -> context.getString(
                when (result.reason) {
                    GpxParseException.Reason.INVALID_XML -> R.string.error_invalid_gpx
                    GpxParseException.Reason.NO_TRACK_DATA -> R.string.error_no_track
                    null -> R.string.error_read
                },
            )
        }
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(pendingImport) {
        pendingImport?.let {
            importAndReport(it)
            onImportHandled()
        }
    }
    // Les fichiers .gpx n'ont pas de type MIME fiable selon l'app d'origine : tout est proposé, le
    // lecteur GPX refuse proprement ce qui n'en est pas un.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importAndReport) }

    var renaming by remember { mutableStateOf<TrackEntry?>(null) }
    var deleting by remember { mutableStateOf<TrackEntry?>(null) }
    /** Changer de trace pendant une sortie en cours n'est jamais silencieux (it29 iOS). */
    var switching by remember { mutableStateOf<TrackEntry?>(null) }
    fun activate(entry: TrackEntry) {
        if (entry.id == library.activeTrackId) return
        if (recorder.isInProgress && library.activeTrackId != null) switching = entry else library.setActive(entry.id)
    }
    val unsaved = recorder.unsavedRides.rides.sortedByDescending { it.startedMillis }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.library_import)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            if (library.tracks.isEmpty() && unsaved.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (unsaved.isNotEmpty()) {
                        item(key = "unsaved-header") {
                            Text(stringResource(R.string.unsaved_title), style = MaterialTheme.typography.titleSmall, color = UnsavedAmber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(unsaved, key = { "unsaved-" + it.id }) { ride ->
                            UnsavedRow(
                                ride = ride,
                                onRecover = {
                                    val text = runCatching { recorder.unsavedRides.file(ride).readText() }.getOrNull()
                                    val result = text?.let { library.importText(it) }
                                    if (result is TrackLibrary.ImportResult.Success) {
                                        recorder.unsavedRides.delete(ride)
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.library_imported, result.entry.name)) }
                                    }
                                },
                                onDelete = { recorder.unsavedRides.delete(ride) },
                            )
                            HorizontalDivider()
                        }
                    }
                    items(library.tracks, key = { it.id }) { entry ->
                        TrackRow(
                            entry = entry,
                            isActive = entry.id == library.activeTrackId,
                            settings = settings,
                            onActivate = { activate(entry) },
                            onShare = { shareGpx(context, library.file(entry), entry.name) },
                            onReverse = { library.setReversed(entry.id, !entry.reversed) },
                            onRename = { renaming = entry },
                            onDelete = { deleting = entry },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        var name by remember(entry.id) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.library_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { library.rename(entry.id, name); renaming = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    switching?.let { entry ->
        AlertDialog(
            onDismissRequest = { switching = null },
            title = { Text(stringResource(R.string.switch_track_title)) },
            text = { Text(stringResource(R.string.switch_track_message, recorder.pointCount, entry.name)) },
            confirmButton = { TextButton(onClick = { library.setActive(entry.id); switching = null }) { Text(stringResource(R.string.switch_track_confirm)) } },
            dismissButton = { TextButton(onClick = { switching = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.library_delete_confirm, entry.name)) },
            confirmButton = { TextButton(onClick = { library.delete(entry.id); deleting = null }) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TrackRow(
    entry: TrackEntry,
    isActive: Boolean,
    settings: AppSettings,
    onActivate: () -> Unit,
    onShare: () -> Unit,
    onReverse: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onActivate).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = isActive, onClick = onActivate)
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.importDateMillis))
            Text(
                stringResource(R.string.library_summary, RoadbookTexts.distance(entry.lengthMeters, settings.distanceUnit), entry.pointCount) + " · " + date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.reversed) Text(stringResource(R.string.library_reversed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.share_gpx)) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.library_reverse)) }, onClick = { menu = false; onReverse() })
                DropdownMenuItem(text = { Text(stringResource(R.string.library_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Couleur des sorties à récupérer (ambre, comme l'iPhone). */
private val UnsavedAmber = Color(0xFFFF9500)

/** Sortie non enregistrée (copie de secours) : « Récupérer » l'ajoute à la Bibliothèque, ou la supprimer. */
@Composable
private fun UnsavedRow(ride: UnsavedRide, onRecover: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(RideRecorder.unsavedRideName(LocalContext.current, ride.startedMillis), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.unsaved_summary, ride.pointCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRecover) { Text(stringResource(R.string.unsaved_recover), color = UnsavedAmber, fontWeight = FontWeight.Bold) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) }
    }
}
