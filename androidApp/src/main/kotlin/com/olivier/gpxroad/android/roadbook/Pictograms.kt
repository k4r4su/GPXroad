package com.olivier.gpxroad.android.roadbook

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.olivier.gpxroad.shared.roadbook.Checkpoint
import com.olivier.gpxroad.shared.roadbook.RoadbookTier
import com.olivier.gpxroad.shared.roadbook.RoundaboutBranchKind
import com.olivier.gpxroad.shared.roadbook.RoundaboutPictogram
import com.olivier.gpxroad.shared.roadbook.TurnDirection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pictogramme d'un événement du Road Book (équivalent de `RoadbookManeuverIcon` iOS) : flèche dont
 * l'angle dépend du palier, demi-tour, fourche, fusion, et rond-point dessiné avec ses branches
 * (même géométrie que `RoadbookRoundaboutDrawing` iOS).
 */
@Composable
fun ManeuverPictogram(checkpoint: Checkpoint, accent: Color, secondary: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val side = min(size.width, size.height)
        val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
        when (checkpoint.tier) {
            RoadbookTier.ROUNDABOUT -> drawRoundabout(checkpoint, origin, side, accent, secondary) { number, center ->
                val layout = measurer.measure(number, TextStyle(fontSize = (side * 0.24f / density).sp, fontWeight = FontWeight.Black, color = secondary))
                drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
            }
            RoadbookTier.FORK -> drawFork(checkpoint.direction, origin, side, accent, secondary)
            RoadbookTier.MERGE -> drawMerge(checkpoint.direction, origin, side, accent, secondary)
            RoadbookTier.U_TURN -> drawUTurn(origin, side, accent)
            else -> drawTurn(turnAngle(checkpoint), origin, side, accent)
        }
    }
}

/** Angle de la flèche (0 = tout droit, droite > 0) selon le palier, comme les symboles iOS. */
private fun turnAngle(checkpoint: Checkpoint): Float {
    val magnitude = when (checkpoint.tier) {
        RoadbookTier.LIGHT -> 30f
        RoadbookTier.MARKED -> 60f
        RoadbookTier.HARD -> 90f
        RoadbookTier.VERY_HARD -> 135f
        RoadbookTier.LIGHT_DIRECTION_CHANGE -> 25f
        else -> 0f
    }
    return when (checkpoint.direction) {
        TurnDirection.LEFT -> -magnitude
        TurnDirection.RIGHT -> magnitude
        else -> 0f
    }
}

private fun point(origin: Offset, side: Float, x: Float, y: Float) = Offset(origin.x + x * side, origin.y + y * side)

private fun polar(center: Offset, degrees: Float, radius: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(center.x + (sin(radians) * radius).toFloat(), center.y - (cos(radians) * radius).toFloat())
}

private fun DrawScope.arrowHead(tip: Offset, degrees: Float, length: Float, color: Color) {
    val base = polar(tip, degrees + 180f, length)
    val left = polar(base, degrees - 90f, length * 0.62f)
    val right = polar(base, degrees + 90f, length * 0.62f)
    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }, color)
}

private fun DrawScope.drawTurn(angle: Float, origin: Offset, side: Float, accent: Color) {
    val bottom = point(origin, side, 0.5f, 0.92f)
    val elbow = point(origin, side, 0.5f, 0.52f)
    val headLength = side * 0.2f
    val end = polar(elbow, angle, side * 0.36f - headLength * 0.6f)
    val path = Path().apply { moveTo(bottom.x, bottom.y); lineTo(elbow.x, elbow.y); lineTo(end.x, end.y) }
    drawPath(path, accent, style = Stroke(width = side * 0.11f, cap = StrokeCap.Butt, join = StrokeJoin.Round))
    arrowHead(polar(end, angle, headLength * 0.7f), angle, headLength, accent)
}

private fun DrawScope.drawUTurn(origin: Offset, side: Float, accent: Color) {
    val stroke = side * 0.11f
    val right = side * 0.62f
    val left = side * 0.36f
    val top = side * 0.3f
    val radius = (right - left) / 2
    val path = Path().apply {
        moveTo(origin.x + right, origin.y + side * 0.9f)
        lineTo(origin.x + right, origin.y + top + radius)
        arcTo(androidx.compose.ui.geometry.Rect(Offset(origin.x + left, origin.y + top), Size(right - left, right - left)), 0f, -180f, false)
        lineTo(origin.x + left, origin.y + side * 0.62f)
    }
    drawPath(path, accent, style = Stroke(width = stroke, join = StrokeJoin.Round))
    arrowHead(Offset(origin.x + left, origin.y + side * 0.8f), 180f, side * 0.2f, accent)
}

private fun DrawScope.drawFork(direction: TurnDirection, origin: Offset, side: Float, accent: Color, secondary: Color) {
    val bottom = point(origin, side, 0.5f, 0.92f)
    val junction = point(origin, side, 0.5f, 0.52f)
    val branches = listOf(-28f, 28f)
    val chosen = when (direction) {
        TurnDirection.LEFT -> -28f
        TurnDirection.RIGHT -> 28f
        else -> 0f
    }
    drawLine(secondary, bottom, junction, strokeWidth = side * 0.09f)
    (branches + if (chosen == 0f) listOf(0f) else emptyList()).filter { it != chosen }.forEach {
        drawLine(secondary.copy(alpha = 0.5f), junction, polar(junction, it, side * 0.42f), strokeWidth = side * 0.05f, cap = StrokeCap.Round)
    }
    val end = polar(junction, chosen, side * 0.3f)
    drawLine(accent, junction, end, strokeWidth = side * 0.11f)
    arrowHead(polar(end, chosen, side * 0.12f), chosen, side * 0.2f, accent)
}

private fun DrawScope.drawMerge(direction: TurnDirection, origin: Offset, side: Float, accent: Color, secondary: Color) {
    val top = point(origin, side, 0.5f, 0.12f)
    val junction = point(origin, side, 0.5f, 0.5f)
    val mainBottom = point(origin, side, 0.5f, 0.92f)
    val sideAngle = if (direction == TurnDirection.LEFT) 208f else 152f
    drawLine(secondary.copy(alpha = 0.5f), junction, polar(junction, sideAngle, side * 0.45f), strokeWidth = side * 0.05f, cap = StrokeCap.Round)
    drawLine(accent, mainBottom, junction, strokeWidth = side * 0.11f)
    drawLine(accent, junction, point(origin, side, 0.5f, 0.3f), strokeWidth = side * 0.11f)
    arrowHead(top, 0f, side * 0.2f, accent)
}

/**
 * Rond-point : anneau, entrée en bas, trajet dans le sens de circulation jusqu'à la sortie ; avec
 * l'analyse OSM (`Checkpoint.roundabout`) toutes les branches (sorties comptées en trait normal,
 * petites voies en trait fin, sens interdits barrés) et le numéro au centre.
 */
private fun DrawScope.drawRoundabout(
    checkpoint: Checkpoint,
    origin: Offset,
    side: Float,
    accent: Color,
    secondary: Color,
    drawNumber: DrawScope.(String, Offset) -> Unit,
) {
    val center = Offset(origin.x + side / 2, origin.y + side / 2)
    val ring = side * 0.25f
    val outer = side * 0.40f
    val entryAngle = RoundaboutPictogram.ENTRY_ANGLE_DEGREES.toFloat()
    val passage = checkpoint.roundabout
    val clockwise: Boolean
    val sweep: Float
    if (passage != null) {
        clockwise = passage.clockwise
        val travelled = if (clockwise) passage.exitAngleDegrees - entryAngle else entryAngle - passage.exitAngleDegrees
        val modulo = (((travelled % 360) + 360) % 360).toFloat()
        sweep = if (modulo < 1f) 340f else modulo
    } else {
        val signed = when (checkpoint.direction) {
            TurnDirection.LEFT -> -checkpoint.turnAngleDegrees
            TurnDirection.RIGHT -> checkpoint.turnAngleDegrees
            TurnDirection.U_TURN -> 180.0
            else -> 0.0
        }
        val layout = RoundaboutPictogram.layout(signed, null)
        clockwise = false
        sweep = layout.pathSweepDegrees.toFloat()
    }
    val thin = side * 0.045f
    drawCircle(secondary.copy(alpha = 0.45f), radius = ring, center = center, style = Stroke(width = thin))
    passage?.branches?.filter { it.kind != RoundaboutBranchKind.ENTRY && it.kind != RoundaboutBranchKind.TAKEN_EXIT }?.forEach { branch ->
        val angle = branch.pictureAngleDegrees.toFloat()
        val counted = branch.kind == RoundaboutBranchKind.COUNTED_EXIT
        val length = if (counted) outer else side * 0.36f
        drawLine(
            if (counted) secondary.copy(alpha = 0.8f) else secondary.copy(alpha = 0.45f),
            polar(center, angle, ring), polar(center, angle, length),
            strokeWidth = if (counted) thin * 1.8f else thin, cap = StrokeCap.Round,
        )
        if (branch.kind == RoundaboutBranchKind.NO_EXIT) {
            drawLine(secondary.copy(alpha = 0.7f), polar(center, angle - 9f, length * 0.92f), polar(center, angle + 9f, length * 0.92f), strokeWidth = thin)
        }
    }
    val exitAngle = entryAngle + (if (clockwise) 1 else -1) * sweep
    val route = Path().apply {
        val start = polar(center, entryAngle, outer)
        moveTo(start.x, start.y)
        val steps = maxOf((sweep / 6).toInt(), 2)
        for (i in 0..steps) {
            val p = polar(center, entryAngle + (if (clockwise) 1 else -1) * sweep * i / steps, ring)
            lineTo(p.x, p.y)
        }
        val exit = polar(center, exitAngle, outer)
        lineTo(exit.x, exit.y)
    }
    drawPath(route, accent, style = Stroke(width = side * 0.085f, join = StrokeJoin.Round))
    arrowHead(polar(center, exitAngle, outer + side * 0.1f), exitAngle, side * 0.13f, accent)
    checkpoint.roundaboutExitCount?.let { drawNumber(it.toString(), center) }
}
