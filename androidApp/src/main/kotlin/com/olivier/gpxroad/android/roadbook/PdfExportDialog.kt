package com.olivier.gpxroad.android.roadbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.settings.SettingChoice
import com.olivier.gpxroad.android.settings.SettingToggle
import com.olivier.gpxroad.shared.roadbook.LandmarkInfo
import com.olivier.gpxroad.shared.roadbook.RoadbookEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** « Export PDF » (`RoadbookExportOptionsView` iOS) : mise en page, colonnes, puis génération et partage. */
@Composable
fun PdfExportDialog(trackName: String, entries: List<RoadbookEntry>, attached: Map<Int, LandmarkInfo>, settings: AppSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(RoadbookPdfOptions.fromJson(settings.pdfOptionsJson).let { if (settings.pdfOptionsJson == null) it.copy(unit = settings.distanceUnit) else it }) }
    var generating by remember { mutableStateOf(false) }
    fun update(value: RoadbookPdfOptions) {
        options = value
        settings.updatePdfOptionsJson(value.toJson())
    }
    val maneuverCount = entries.count { it is RoadbookEntry.Maneuver }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_export)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pdf_count, maneuverCount, trackName), style = MaterialTheme.typography.bodySmall)
                SettingChoice(stringResource(R.string.pdf_orientation), listOf(false, true), options.landscape, { stringResource(if (it) R.string.pdf_landscape else R.string.pdf_portrait) }) { update(options.copy(landscape = it)) }
                SettingChoice(stringResource(R.string.pdf_density), listOf(true, false), options.compact, { stringResource(if (it) R.string.pdf_compact else R.string.pdf_comfortable) }) { update(options.copy(compact = it)) }
                SettingChoice(stringResource(R.string.pdf_heading), listOf(false, true), options.degrees, { stringResource(if (it) R.string.pdf_degrees else R.string.pdf_pictogram) }) { update(options.copy(degrees = it)) }
                SettingChoice(stringResource(R.string.pdf_unit), DistanceUnit.entries, options.unit, { if (it == DistanceUnit.KM) "km" else "mi" }) { update(options.copy(unit = it)) }
                SettingChoice(
                    stringResource(R.string.pdf_font_size), listOf(RoadbookPdfOptions.FONT_SMALL, RoadbookPdfOptions.FONT_MEDIUM, RoadbookPdfOptions.FONT_LARGE), options.fontSize,
                    { stringResource(when (it) { RoadbookPdfOptions.FONT_SMALL -> R.string.pdf_small; RoadbookPdfOptions.FONT_LARGE -> R.string.pdf_large; else -> R.string.pdf_medium }) },
                ) { update(options.copy(fontSize = it)) }
                SettingToggle(stringResource(R.string.pdf_cumulative_column), options.showCumulative) { update(options.copy(showCumulative = it)) }
                SettingToggle(stringResource(R.string.pdf_note_column), options.showNote) { update(options.copy(showNote = it)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !generating, onClick = {
                generating = true
                scope.launch {
                    val file = withContext(Dispatchers.Default) { RoadbookPdf.generate(context, trackName, entries, attached, options, measurer) }
                    generating = false
                    RoadbookPdf.share(context, file, trackName)
                    onDismiss()
                }
            }) {
                if (generating) CircularProgressIndicator(strokeWidth = 2.dp) else Text(stringResource(R.string.pdf_generate))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
