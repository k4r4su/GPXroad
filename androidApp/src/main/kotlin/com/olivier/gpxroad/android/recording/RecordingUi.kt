package com.olivier.gpxroad.android.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.TrackLibrary
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ChipBackground = com.olivier.gpxroad.android.ui.MapPanelColor

/** Permissions nécessaires pour enregistrer : localisation (+ notifications à partir d'Android 13). */
private fun recordingPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * Démarre l'enregistrement en demandant d'abord l'autorisation si besoin. Localisation refusée :
 * rien ne démarre, un message l'explique (comme l'alerte « Localisation refusée » iOS).
 */
@Composable
fun rememberRecordingStarter(recorder: RideRecorder): () -> Unit {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation(context)) recorder.start() else denied = true
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(stringResource(R.string.recording_denied_title)) },
            text = { Text(stringResource(R.string.recording_denied_message)) },
            confirmButton = { TextButton(onClick = { denied = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    return {
        if (hasLocation(context)) recorder.start() else launcher.launch(recordingPermissions())
    }
}

/**
 * Bouton d'enregistrement (`RideRecordingControl` iOS), au-dessus de la vitesse : « Enregistrer »,
 * « REC · n pts » (tap = pause), « Pause · n pts » (tap = reprendre) ; « Terminer » dès qu'une
 * sortie est en cours.
 */
@Composable
fun RecordingControls(recorder: RideRecorder, onFinish: () -> Unit) {
    val start = rememberRecordingStarter(recorder)
    val (label, dot) = when (recorder.state) {
        RideRecorder.State.IDLE -> stringResource(R.string.recording_record) to Color.Red.copy(alpha = 0.85f)
        RideRecorder.State.RECORDING -> stringResource(R.string.recording_rec_format, recorder.pointCount) to Color.Red
        RideRecorder.State.PAUSED -> stringResource(R.string.recording_paused_format, recorder.pointCount) to Color(0xFFFF9500)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(label, dot) {
            if (recorder.state == RideRecorder.State.RECORDING) recorder.pause() else start()
        }
        if (recorder.isInProgress) Chip(stringResource(R.string.recording_finish), null, onFinish)
    }
}

@Composable
private fun Chip(label: String, dot: Color?, onClick: () -> Unit) {
    Row(
        Modifier.background(ChipBackground, RoundedCornerShape(50)).border(1.dp, com.olivier.gpxroad.android.ui.MapPanelBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        dot?.let { Box(Modifier.size(11.dp).background(it, CircleShape)) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * « Enregistrer cette sortie ? » (it31, `RecordingPromptPolicy` iOS) : au premier suivi d'une trace,
 * une fois par trace et par lancement, seulement si rien n'est en cours. Refuser ne change rien.
 */
@Composable
fun RecordingPrompt(recorder: RideRecorder, trackId: String?) {
    val start = rememberRecordingStarter(recorder)
    var visible by remember(trackId) {
        mutableStateOf(trackId != null && !recorder.isInProgress && trackId !in recorder.promptedTrackIds)
    }
    if (!visible || trackId == null) return
    fun close() {
        recorder.promptedTrackIds.add(trackId)
        visible = false
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(stringResource(R.string.recording_prompt_title)) },
        text = { Text(stringResource(R.string.recording_prompt_message)) },
        confirmButton = { TextButton(onClick = { close(); start() }) { Text(stringResource(R.string.recording_record), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.recording_not_now)) } },
    )
}

/**
 * « Terminer la sortie » (`EndRideView` iOS) : nom (par défaut « <trace suivie> – date ») et
 * commentaire, enregistrement dans la Bibliothèque SANS remplacer la trace suivie, puis partage du
 * GPX ; ou suppression sans enregistrer (confirmée).
 */
@Composable
fun EndRideDialog(recorder: RideRecorder, library: TrackLibrary, followedTrackName: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val defaultBase = followedTrackName ?: stringResource(R.string.recording_default_name)
    var name by remember { mutableStateOf("$defaultBase – ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())}") }
    var comment by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<File?>(null) }
    var savedName by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val pointCount = recorder.pointCount

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.recording_discard_title)) },
            text = { Text(stringResource(R.string.recording_discard_message)) },
            confirmButton = {
                TextButton(onClick = { recorder.finish(); confirmDiscard = false; onDismiss() }) {
                    Text(stringResource(R.string.recording_discard_confirm, pointCount), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recording_finish)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saved == null) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.recording_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text(stringResource(R.string.recording_comment_label)) }, placeholder = { Text(stringResource(R.string.recording_comment_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.recording_points_format, pointCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.recording_discard), color = MaterialTheme.colorScheme.error) }
                } else {
                    Text("✓ " + stringResource(R.string.recording_saved), color = Color(0xFF34A853), fontWeight = FontWeight.Bold)
                    Text(savedName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            val file = saved
            if (file == null) {
                TextButton(
                    enabled = pointCount >= 2 && name.isNotBlank(),
                    onClick = {
                        val trimmed = name.trim()
                        val result = library.importText(recorder.gpx(trimmed, comment.trim().ifEmpty { null }))
                        if (result is TrackLibrary.ImportResult.Success) {
                            // Proprement enregistrée : fin de l'enregistrement (GPS coupé, journal et secours effacés).
                            recorder.finish()
                            saved = library.file(result.entry)
                            savedName = result.entry.name
                        }
                    },
                ) { Text(stringResource(R.string.recording_save)) }
            } else {
                TextButton(onClick = { shareGpx(context, file, savedName) }) { Text(stringResource(R.string.share_gpx)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/**
 * Partage d'un GPX (`LibraryStore.exportURL` iOS) : COPIE temporaire nommée « <titre> JJ.MM.AAAA.gpx »,
 * contenu identique octet pour octet ; le fichier stocké n'est jamais renommé. Le partage Android
 * propose aussi l'enregistrement dans Fichiers/Drive.
 */
fun shareGpx(context: Context, source: File, title: String) {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    directory.listFiles()?.forEach { it.delete() }
    val safeTitle = title.replace(Regex("""[\\/:*?"<>|\n\r]"""), "-").trim().ifEmpty { "GPXroad" }
    val date = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date())
    val copy = File(directory, "$safeTitle $date.gpx")
    source.copyTo(copy, overwrite = true)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/gpx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_gpx)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
