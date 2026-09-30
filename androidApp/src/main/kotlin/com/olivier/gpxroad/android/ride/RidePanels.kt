package com.olivier.gpxroad.android.ride

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.recording.RideRecorder
import com.olivier.gpxroad.android.recording.rememberRecordingStarter
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.geodesicDistanceMeters
import com.olivier.gpxroad.shared.roadbook.RoadbookAnalyzer
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val KM_PER_MILE = 1.609344

fun speedValue(kmh: Double, unit: DistanceUnit): String = "${(if (unit == DistanceUnit.MI) kmh / KM_PER_MILE else kmh).roundToInt()}"

@Composable
fun speedUnitLabel(unit: DistanceUnit): String = if (unit == DistanceUnit.MI) "mph" else stringResource(R.string.ride_speed_unit)

/**
 * Panneau « Mesures » (`RideStatsPanel` iOS), ouvert en touchant la vitesse : vitesse, moyenne, max ;
 * restant, % parcouru, arrivée et durée restante ; enregistrement et « Terminer la sortie ».
 */
@Composable
fun RideStatsPanel(session: RideSession, recorder: RideRecorder, speedKmh: Double, unit: DistanceUnit, onCollapse: () -> Unit, onFinish: () -> Unit) {
    val start = rememberRecordingStarter(recorder)
    val speedUnit = speedUnitLabel(unit)
    val progress = session.progress
    Column(
        Modifier.widthIn(max = 272.dp).background(PanelBackground, PanelShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stats_title), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                Text("✕", color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat(stringResource(R.string.stats_speed), "${speedValue(speedKmh, unit)} $speedUnit", emphasized = true)
            Stat(stringResource(R.string.stats_average), "${speedValue(session.averageSpeedKmh, unit)} $speedUnit")
            Stat(stringResource(R.string.stats_max), "${speedValue(session.maxSpeedKmh, unit)} $speedUnit")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat(stringResource(R.string.stats_remaining), progress?.let { RoadbookTexts.distance(it.remainingMeters, unit) } ?: "—")
            Stat(stringResource(R.string.stats_covered), progress?.let { "${it.percentComplete.roundToInt()} %" } ?: "—")
            val seconds = progress?.remainingSeconds
            Stat(stringResource(R.string.stats_arrival), seconds?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (it * 1000).toLong())) } ?: "—")
        }
        progress?.remainingSeconds?.let { Stat(stringResource(R.string.stats_time_remaining), durationText(it)) }
        val (label, tint) = when (recorder.state) {
            RideRecorder.State.IDLE -> stringResource(R.string.stats_start_recording) to Color(0xFFFF3B30)
            RideRecorder.State.RECORDING -> stringResource(R.string.stats_pause_recording) to Color(0xFFFF9500)
            RideRecorder.State.PAUSED -> stringResource(R.string.stats_resume_recording) to Color(0xFFFF3B30)
        }
        OutlinedButton(onClick = { if (recorder.state == RideRecorder.State.RECORDING) recorder.pause() else start() }) {
            Text(label, color = tint, fontWeight = FontWeight.Bold)
        }
        if (recorder.isInProgress) {
            OutlinedButton(onClick = onFinish) {
                Text(stringResource(R.string.recording_finish) + " · " + stringResource(R.string.recording_points_format, recorder.pointCount), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: String, emphasized: Boolean = false) {
    Column {
        Text(value, color = Color.White, fontSize = if (emphasized) 20.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(title, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

private fun durationText(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

private val ResumeTint = Color(0xE01E5BC6)

/**
 * « Reprendre la trace ici ? » (`ResumeGuidanceCardView` iOS) : vol d'oiseau, distance par la route
 * une fois calculée ; « Reprendre ici » confirme, « Annuler » efface.
 */
@Composable
fun ResumeCard(resume: ManualResume, fix: Location?, course: Double?, unit: DistanceUnit, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val position = fix?.let { LatLon(it.latitude, it.longitude) }
    Column(
        modifier.widthIn(max = 480.dp).fillMaxWidth().background(ResumeTint, PanelShape).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val relative = if (position != null && resume.route.isEmpty()) {
                ((RoadbookAnalyzer.bearing(position, resume.target) - (course ?: 0.0)) % 360 + 540) % 360 - 180
            } else 0.0
            Canvas(Modifier.size(20.dp).rotate(relative.toFloat())) {
                val path = Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(size.width / 2, size.height * 0.72f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, Color.White)
            }
            Text(
                stringResource(if (resume.isActive) R.string.resume_title_active else R.string.resume_title_preview),
                color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            if (resume.isRequesting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        }
        val parts = buildList {
            position?.let { add(stringResource(R.string.resume_bird, RoadbookTexts.distance(geodesicDistanceMeters(it, resume.target), unit))) }
            when {
                resume.routeLengthMeters != null -> add(stringResource(R.string.resume_road, RoadbookTexts.distance(resume.routeLengthMeters, unit)))
                resume.routingFailed -> add(stringResource(R.string.resume_network_error))
                resume.isRequesting -> add(stringResource(R.string.resume_computing))
            }
        }
        Text(parts.joinToString(" · "), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (!resume.isActive) {
                Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ResumeTint)) {
                    Text(stringResource(R.string.resume_confirm), fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(if (resume.isActive) R.string.resume_cancel_active else R.string.cancel), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
