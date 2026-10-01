package com.olivier.gpxroad.android.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.ui.mapPanel

private val DetourRed = Color(0xD9FF3B30)
private val Warning = Color(0xFFFFCC00)

/** Triangle d'avertissement (panneau de danger). */
@Composable
private fun WarningTriangle(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width / 2, size.height * 0.05f)
            lineTo(size.width, size.height * 0.95f)
            lineTo(0f, size.height * 0.95f)
            close()
        }
        drawPath(path, color)
        drawLine(Color.Black, Offset(size.width / 2, size.height * 0.38f), Offset(size.width / 2, size.height * 0.68f), strokeWidth = size.width * 0.11f)
        drawCircle(Color.Black, size.width * 0.06f, Offset(size.width / 2, size.height * 0.81f))
    }
}

/** Bouton « Bloqué » de la colonne (`BlockedPathButton` iOS) : rouge, grande cible. */
@Composable
fun BlockedButton(onClick: () -> Unit) {
    val label = stringResource(R.string.detour_title)
    Column(
        Modifier.size(64.dp).background(DetourRed, RoundedCornerShape(20.dp)).clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Block, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.detour_button), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** « Chemin bloqué » : contourner par la route, par la piste, ou rejoindre sans réseau. */
@Composable
fun DetourDialog(onDismiss: () -> Unit, onChoose: (DetourMode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detour_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.detour_message))
                OutlinedButton(onClick = { onChoose(DetourMode.ROAD) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detour_road)) }
                OutlinedButton(onClick = { onChoose(DetourMode.TRAIL) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detour_trail)) }
                OutlinedButton(onClick = { onChoose(DetourMode.DIRECT) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detour_direct)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** « Portion bloquée ? » (`BlockedPathBannerView` iOS) : hors trace qui s'éternise. */
@Composable
fun BlockedBanner(onBypass: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().mapPanel().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WarningTriangle(Warning, Modifier.size(22.dp))
        Text(stringResource(R.string.detour_blocked_question), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Button(onClick = onBypass, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500))) { Text(stringResource(R.string.detour_bypass), fontWeight = FontWeight.Bold) }
        TextButton(onClick = onDismiss) { Text("✕", color = Color.White, fontSize = 18.sp) }
    }
}

/** Détour actif (`DetourStatusView` iOS) : son mode, « la trace d'origine reste affichée », Annuler. */
@Composable
fun DetourBanner(detour: Detour?, isRequesting: Boolean, onCancel: () -> Unit) {
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().mapPanel(DetourRed).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val title = when (detour?.mode) {
                DetourMode.ROAD -> R.string.detour_active_road
                DetourMode.TRAIL -> R.string.detour_active_trail
                DetourMode.DIRECT -> R.string.detour_active_direct
                null -> R.string.resume_computing
            }
            Text(stringResource(title), color = Color.White, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.detour_keeps_track), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
        if (isRequesting) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

/** Point bloqué partagé près de la trace (`SharedBlockageAlertPillView` iOS). */
@Composable
fun SharedBlockageAlert(note: String?, onHide: () -> Unit) {
    val hide = stringResource(R.string.shared_hide)
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().mapPanel().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WarningTriangle(Color(0xFFFF9500), Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.shared_alert), color = Color.White, fontWeight = FontWeight.Bold)
            note?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        TextButton(onClick = onHide, modifier = Modifier.semantics { contentDescription = hide }) { Text("✕", color = Color.White, fontSize = 18.sp) }
    }
}
