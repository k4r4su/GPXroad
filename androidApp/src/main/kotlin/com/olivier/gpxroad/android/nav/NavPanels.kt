package com.olivier.gpxroad.android.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.roadbook.ManeuverPictogram
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.android.ui.Accent
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.nav.GoToGuidance
import com.olivier.gpxroad.shared.nav.NavManeuver
import com.olivier.gpxroad.shared.roadbook.Checkpoint
import com.olivier.gpxroad.shared.roadbook.RoadbookTier
import com.olivier.gpxroad.shared.roadbook.TurnDirection
import com.olivier.gpxroad.shared.roadbook.ValhallaManeuverType as T
import kotlin.math.max
import kotlin.math.roundToInt

private val NavTint = Color(0xE00A5FC8)
private val GoToTint = Color(0xE0167E9E)
private val PanelShape = RoundedCornerShape(16.dp)

/**
 * Pictogramme d'une manœuvre « Aller à » : MÊMES dessins que le Road Book (virages par palier,
 * rond-point avec le numéro de sortie de Valhalla, fourche, bretelle, demi-tour) ; arrivée = drapeau.
 */
@Composable
fun NavManeuverIcon(maneuver: NavManeuver?, modifier: Modifier = Modifier) {
    if (maneuver == null || maneuver.isArrival) {
        Canvas(modifier) {
            val w = size.width
            val h = size.height
            drawLine(Color.White, Offset(w * 0.3f, h * 0.1f), Offset(w * 0.3f, h * 0.92f), strokeWidth = w * 0.08f)
            val flag = Path().apply {
                moveTo(w * 0.3f, h * 0.12f); lineTo(w * 0.85f, h * 0.3f); lineTo(w * 0.3f, h * 0.5f); close()
            }
            drawPath(flag, Accent)
        }
        return
    }
    val (tier, direction) = when (maneuver.type) {
        T.SLIGHT_LEFT -> RoadbookTier.LIGHT to TurnDirection.LEFT
        T.SLIGHT_RIGHT -> RoadbookTier.LIGHT to TurnDirection.RIGHT
        T.LEFT -> RoadbookTier.HARD to TurnDirection.LEFT
        T.RIGHT -> RoadbookTier.HARD to TurnDirection.RIGHT
        T.SHARP_LEFT -> RoadbookTier.VERY_HARD to TurnDirection.LEFT
        T.SHARP_RIGHT -> RoadbookTier.VERY_HARD to TurnDirection.RIGHT
        T.UTURN_LEFT, T.UTURN_RIGHT -> RoadbookTier.U_TURN to TurnDirection.U_TURN
        T.ROUNDABOUT_ENTER, T.ROUNDABOUT_EXIT -> RoadbookTier.ROUNDABOUT to TurnDirection.STRAIGHT
        T.STAY_LEFT -> RoadbookTier.FORK to TurnDirection.LEFT
        T.STAY_RIGHT -> RoadbookTier.FORK to TurnDirection.RIGHT
        T.RAMP_LEFT, T.EXIT_LEFT -> RoadbookTier.MERGE to TurnDirection.LEFT
        T.RAMP_RIGHT, T.EXIT_RIGHT -> RoadbookTier.MERGE to TurnDirection.RIGHT
        T.MERGE -> RoadbookTier.MERGE to TurnDirection.STRAIGHT
        else -> RoadbookTier.LIGHT to TurnDirection.STRAIGHT
    }
    val angle = when (direction) {
        TurnDirection.LEFT -> -90.0
        TurnDirection.RIGHT -> 90.0
        else -> 0.0
    }
    val checkpoint = Checkpoint(LatLon(0.0, 0.0), if (tier == RoadbookTier.LIGHT && direction == TurnDirection.STRAIGHT) 0.0 else angle, direction, tier, 0, 0, maneuver.roundaboutExitCount)
    ManeuverPictogram(checkpoint, Accent, Color.White, modifier)
}

/**
 * Bandeau du guidage riche (`NavGuidancePanelView` iOS) : à gauche le pictogramme et la distance, à
 * droite la consigne de Valhalla puis la rue sur sa propre ligne ; bouton d'arrêt.
 */
@Composable
fun NavPanel(
    maneuver: NavManeuver?,
    distanceMeters: Double?,
    destinationLabel: String,
    isRecomputing: Boolean,
    stopLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.widthIn(max = 560.dp).fillMaxWidth().height(IntrinsicSize.Min).background(NavTint, PanelShape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NavManeuverIcon(maneuver, Modifier.size(48.dp))
            Text(distanceMeters?.let { RoadbookTexts.countdown(it, DistanceUnit.KM) } ?: "—", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.25f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(maneuver?.instruction ?: stringResource(R.string.nav_towards, destinationLabel), color = Color.White.copy(alpha = 0.95f), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            maneuver?.streetNames?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isRecomputing) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        CloseButton(stopLabel, onStop)
    }
}


/** « Puis… » (`NavSecondaryBannerView` iOS) : manœuvre suivante quand Valhalla les dit enchaînées. */
@Composable
fun NavThenBanner(maneuver: NavManeuver, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 520.dp).background(NavTint.copy(alpha = 0.75f), PanelShape).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.nav_then), color = Color.White.copy(alpha = 0.75f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        NavManeuverIcon(maneuver, Modifier.size(24.dp))
        Text(maneuver.instruction, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Guidage simple (`GoToStatusPillView` iOS) : profil → destination, distance restante, résumé. */
@Composable
fun GoToPill(guidance: GoToGuidance, remainingMeters: Double?, isRequesting: Boolean, failed: Boolean, unit: DistanceUnit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 560.dp).fillMaxWidth().background(GoToTint, PanelShape).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${profileLabel(guidance.profile)} → ${guidance.label}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(remainingMeters?.let { RoadbookTexts.distance(it, unit) } ?: "—", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            val summary = if (failed) stringResource(R.string.nav_goto_direct)
            else stringResource(R.string.nav_goto_summary, RoadbookTexts.distance(guidance.lengthMeters, unit), max((guidance.estimatedSeconds(guidance.lengthMeters) / 60).roundToInt(), 1))
            Text(summary, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
        if (isRequesting) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        CloseButton(stringResource(R.string.nav_cancel_goto), onCancel)
    }
}

@Composable
private fun CloseButton(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(26.dp)) {
            drawCircle(Color.White.copy(alpha = 0.85f))
            val inset = size.width * 0.32f
            drawLine(NavTint, Offset(inset, inset), Offset(size.width - inset, size.height - inset), strokeWidth = size.width * 0.1f)
            drawLine(NavTint, Offset(size.width - inset, inset), Offset(inset, size.height - inset), strokeWidth = size.width * 0.1f)
        }
    }
}
