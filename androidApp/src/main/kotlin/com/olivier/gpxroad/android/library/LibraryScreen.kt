package com.olivier.gpxroad.android.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.olivier.gpxroad.android.ui.Accent
import com.olivier.gpxroad.android.ui.ScreenHeader
import com.olivier.gpxroad.shared.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import com.olivier.gpxroad.android.data.TrackFolder
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.offline.OfflineMaps
import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.sync.SharedBlockageSync
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.plan.RoutePlannerScreen
import androidx.compose.material.icons.rounded.Route
import com.olivier.gpxroad.android.offline.TrackPreparer
import com.olivier.gpxroad.android.offline.readinessLabel
import com.olivier.gpxroad.shared.offline.ReadinessLevel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Warning
import com.olivier.gpxroad.android.recording.RideRecorder
import com.olivier.gpxroad.android.recording.UnsavedRide
import com.olivier.gpxroad.android.recording.shareGpx
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.shared.gpx.GpxParseException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Couleur des sorties à récupérer (ambre, comme l'iPhone). */
private val UnsavedAmber = Color(0xFFFF9500)

/**
 * Bibliothèque (équivalent de `LibraryView` iOS) : import GPX, trace active (la SEULE que suivent le
 * Ride et le Road Book — rond à gauche), dossiers purement organisationnels (« Non classé » virtuel),
 * sorties non enregistrées, et fiche d'une trace au toucher (statistiques, partage, renommage, sens,
 * dossier, suppression). [pendingImport] : fichier GPX ouvert depuis une autre app.
 */
@Composable
fun LibraryScreen(library: TrackLibrary, settings: AppSettings, recorder: RideRecorder, offline: OfflineMaps, preparer: TrackPreparer, servers: ServerSettings, routing: RoutingClient, overpass: OverpassClient, blockageSync: SharedBlockageSync, startPosition: LatLon?, pendingImport: Uri?, onImportHandled: () -> Unit) {
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
    var moving by remember { mutableStateOf<TrackEntry?>(null) }
    var opened by remember { mutableStateOf<String?>(null) }
    var folderDialog by remember { mutableStateOf<FolderDialog?>(null) }
    /** Changer de trace pendant une sortie en cours n'est jamais silencieux (it29 iOS). */
    var switching by remember { mutableStateOf<TrackEntry?>(null) }
    fun activate(entry: TrackEntry) {
        if (entry.id == library.activeTrackId) return
        if (recorder.isInProgress && library.activeTrackId != null) switching = entry else library.setActive(entry.id)
    }
    val unsaved = recorder.unsavedRides.rides.sortedByDescending { it.startedMillis }

    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var planning by rememberSaveable { mutableStateOf(false) }
    if (planning) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { planning = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { RoutePlannerScreen(library, servers, routing, overpass, blockageSync, startPosition) { planning = false } }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        // Les marges système sont déjà appliquées par la barre d'onglets de l'app.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { planning = true },
                    icon = { Icon(Icons.Rounded.Route, contentDescription = null) },
                    text = { Text(stringResource(R.string.plan_menu)) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.library_import)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(stringResource(R.string.library_title)) {
                IconButton(onClick = { folderDialog = FolderDialog.Create }) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.folder_new), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (library.tracks.isEmpty() && unsaved.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (unsaved.isNotEmpty()) {
                        item(key = "unsaved-header") { GroupTitle(stringResource(R.string.unsaved_title), UnsavedAmber, Icons.Rounded.Restore) }
                        items(unsaved, key = { "unsaved-" + it.id }) { ride ->
                            UnsavedCard(
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
                        }
                    }
                    // « Non classé » (masqué s'il est vide alors que des dossiers existent), puis les dossiers par nom.
                    val sections: List<TrackFolder?> = listOf<TrackFolder?>(null) + library.sortedFolders
                    for (folder in sections) {
                        val content = library.tracksIn(folder?.id)
                        if (folder == null && content.isEmpty() && library.folders.isNotEmpty()) continue
                        val key = folder?.id ?: "none"
                        val isCollapsed = key in collapsed
                        if (folder != null || library.folders.isNotEmpty()) {
                            item(key = "folder-$key") {
                                FolderHeader(
                                    folder, content.size, isCollapsed,
                                    onToggle = { collapsed = if (isCollapsed) collapsed - key else collapsed + key },
                                    onRename = { folderDialog = FolderDialog.Rename(it) }, onDelete = { folderDialog = FolderDialog.Delete(it) },
                                )
                            }
                        }
                        if (isCollapsed) continue
                        if (folder != null && content.isEmpty()) {
                            item(key = "empty-" + folder.id) {
                                Text(stringResource(R.string.folder_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            }
                        }
                        items(content, key = { it.id }) { entry ->
                            TrackCard(
                                entry = entry,
                                library = library,
                                isActive = entry.id == library.activeTrackId,
                                isOffline = offline.zoneForTrack(entry.id)?.isComplete == true,
                                readiness = traversalKey(library, entry)?.let { readinessLabel(preparer, entry.id, it) }
                                    ?.takeIf { entry.id == library.activeTrackId || it.second != ReadinessLevel.NONE },
                                settings = settings,
                                onActivate = { activate(entry) },
                                onOpen = { opened = entry.id },
                                onShare = { shareGpx(context, library.file(entry), entry.name) },
                                onReverse = { library.setReversed(entry.id, !entry.reversed) },
                                onRename = { renaming = entry },
                                onMove = { moving = entry },
                                onDelete = { deleting = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    opened?.let { id ->
        val current = library.tracks.firstOrNull { it.id == id }
        if (current == null) {
            opened = null
        } else {
            TrackSheet(
                entry = current,
                library = library,
                settings = settings,
                offline = offline,
                preparer = preparer,
                isActive = current.id == library.activeTrackId,
                onDismiss = { opened = null },
                onActivate = { activate(current) },
                onShare = { shareGpx(context, library.file(current), current.name) },
                onReverse = { library.setReversed(current.id, !current.reversed) },
                onRename = { renaming = current },
                onMove = { moving = current },
                onDelete = { deleting = current },
            )
        }
    }
    renaming?.let { entry ->
        TextInputDialog(stringResource(R.string.library_rename), entry.name, onDismiss = { renaming = null }) { name ->
            library.rename(entry.id, name)
            renaming = null
        }
    }
    moving?.let { entry ->
        MoveDialog(entry, library, onDismiss = { moving = null }) { folderId ->
            library.move(entry.id, folderId)
            moving = null
        }
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
            text = { Text(stringResource(R.string.library_delete_irreversible)) },
            confirmButton = {
                TextButton(onClick = { library.delete(entry.id); deleting = null }) {
                    Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    when (val dialog = folderDialog) {
        FolderDialog.Create -> TextInputDialog(stringResource(R.string.folder_new), "", label = stringResource(R.string.folder_name), onDismiss = { folderDialog = null }) { name ->
            if (library.createFolder(name) != null) folderDialog = null
        }
        is FolderDialog.Rename -> TextInputDialog(stringResource(R.string.folder_rename), dialog.folder.name, label = stringResource(R.string.folder_name), onDismiss = { folderDialog = null }) { name ->
            if (library.renameFolder(dialog.folder.id, name)) folderDialog = null
        }
        is FolderDialog.Delete -> AlertDialog(
            onDismissRequest = { folderDialog = null },
            title = { Text(stringResource(R.string.folder_delete_title)) },
            text = { Text(stringResource(R.string.folder_delete_message, dialog.folder.name, library.tracksIn(dialog.folder.id).size)) },
            confirmButton = {
                TextButton(onClick = { library.deleteFolder(dialog.folder.id); folderDialog = null }) {
                    Text(stringResource(R.string.folder_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderDialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: TrackFolder) : FolderDialog
    data class Delete(val folder: TrackFolder) : FolderDialog
}

@Composable
private fun GroupTitle(title: String, color: Color, icon: ImageVector) {
    Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** En-tête de dossier : repliable, nombre de traces, menu Renommer / Supprimer. */
@Composable
private fun FolderHeader(folder: TrackFolder?, count: Int, collapsed: Boolean, onToggle: () -> Unit, onRename: (TrackFolder) -> Unit, onDelete: (TrackFolder) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onToggle).padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (folder == null) Icons.Rounded.Inbox else Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(folder?.name ?: stringResource(R.string.folder_unfiled), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (folder != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.folder_actions, folder.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.folder_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename(folder) })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete(folder) },
                    )
                }
            }
        }
    }
}

/**
 * Carte d'une trace : aperçu dessiné, nom, distance et date, pastilles (active, sens inversé, hors
 * ligne). Le bouton rond la rend active (seul point d'activation) ; le reste ouvre sa fiche.
 */
@Composable
private fun TrackCard(
    entry: TrackEntry,
    library: TrackLibrary,
    isActive: Boolean,
    isOffline: Boolean,
    readiness: Pair<String, ReadinessLevel?>?,
    settings: AppSettings,
    onActivate: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReverse: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val preview by produceState(emptyList<LatLon>(), entry.id) { value = withContext(Dispatchers.IO) { library.preview(entry) } }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TrackThumbnail(preview, entry.reversed, Modifier.size(68.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.importDateMillis))
                Text(RoadbookTexts.distance(entry.lengthMeters, settings.distanceUnit) + " · " + date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isActive || entry.reversed || isOffline || readiness != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isActive) Pill(stringResource(R.string.track_active_short), MaterialTheme.colorScheme.primary, Icons.Rounded.Navigation)
                        if (entry.reversed) Pill(stringResource(R.string.library_reversed), MaterialTheme.colorScheme.secondary, Icons.Rounded.SwapVert)
                        if (isOffline) Pill(stringResource(R.string.track_offline_short), Color(0xFF007AFF), Icons.Rounded.DownloadDone)
                        readiness?.let { (label, level) ->
                            when (level) {
                                ReadinessLevel.READY -> Pill(label, Color(0xFF34A853), Icons.Rounded.CheckCircle)
                                ReadinessLevel.PARTIAL -> Pill(label, Color(0xFFF29900), Icons.Rounded.Warning)
                                else -> Pill(label, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.CloudDownload)
                            }
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onActivate) {
                    Icon(
                        if (isActive) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = stringResource(R.string.track_follow),
                        tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = stringResource(R.string.library_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_gpx)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_reverse)) }, leadingIcon = { Icon(Icons.Rounded.SwapVert, null) }, onClick = { menu = false; onReverse() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.folder_move)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, icon: ImageVector) {
    Row(
        Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Aperçu de la trace (`TrackThumbnailView` iOS) : tracé orange, départ vert, arrivée en damier. */
@Composable
fun TrackThumbnail(points: List<LatLon>, reversed: Boolean, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier.clip(MaterialTheme.shapes.medium).background(background)) {
        if (points.size < 2) return@Box
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val ordered = if (reversed) points.asReversed() else points
            val minLat = ordered.minOf { it.latitude }
            val maxLat = ordered.maxOf { it.latitude }
            val minLon = ordered.minOf { it.longitude }
            val maxLon = ordered.maxOf { it.longitude }
            val cos = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2))
            val spanX = ((maxLon - minLon) * cos).coerceAtLeast(1e-9)
            val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
            val scale = minOf(size.width / spanX, size.height / spanY)
            val offsetX = (size.width - spanX * scale) / 2
            val offsetY = (size.height - spanY * scale) / 2
            fun at(p: LatLon) = Offset((offsetX + (p.longitude - minLon) * cos * scale).toFloat(), (offsetY + (maxLat - p.latitude) * scale).toFloat())
            val path = Path().apply {
                moveTo(at(ordered.first()).x, at(ordered.first()).y)
                ordered.drop(1).forEach { lineTo(at(it).x, at(it).y) }
            }
            drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Color(0xFF34C759), 4.dp.toPx(), at(ordered.first()))
            drawCircle(Color.White, 4.dp.toPx(), at(ordered.last()))
            drawCircle(Color.Black, 4.dp.toPx(), at(ordered.last()), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** Sortie non enregistrée (copie de secours) : « Récupérer » l'ajoute à la Bibliothèque, ou la supprimer. */
@Composable
private fun UnsavedCard(ride: UnsavedRide, onRecover: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = UnsavedAmber.copy(alpha = 0.10f), border = BorderStroke(1.dp, UnsavedAmber.copy(alpha = 0.4f))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(RideRecorder.unsavedRideName(LocalContext.current, ride.startedMillis), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.unsaved_summary, ride.pointCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onRecover) { Text(stringResource(R.string.unsaved_recover), fontWeight = FontWeight.Bold) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.library_delete), tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TextInputDialog(title: String, initial: String, label: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = label?.let { { Text(it) } }) },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** « Déplacer vers » : « Non classé » ou un dossier ; le dossier actuel est coché. */
@Composable
private fun MoveDialog(entry: TrackEntry, library: TrackLibrary, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    val current = library.folderOf(entry.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_move_to)) },
        text = {
            Column {
                (listOf<TrackFolder?>(null) + library.sortedFolders).forEach { folder ->
                    Row(Modifier.fillMaxWidth().clickable { onMove(folder?.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current == folder?.id, onClick = { onMove(folder?.id) })
                        Text(folder?.name ?: stringResource(R.string.folder_unfiled))
                    }
                }
                if (library.folders.isEmpty()) {
                    Text(stringResource(R.string.folder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Clé du parcours d'une trace dans son sens choisi, lue hors du fil principal (voir `TrackLibrary.traversalKey`). */
@Composable
private fun traversalKey(library: TrackLibrary, entry: TrackEntry): String? {
    val key by produceState<String?>(null, entry.id, entry.reversed) { value = withContext(Dispatchers.IO) { library.traversalKey(entry) } }
    return key
}
