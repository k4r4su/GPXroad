package com.olivier.gpxroad.android.roadbook

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.roadbook.LandmarkInfo
import com.olivier.gpxroad.shared.roadbook.RoadbookEntry
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import com.olivier.gpxroad.shared.roadbook.RoadbookTier
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Options de l'export PDF (`RoadbookPDFOptions` iOS), mêmes défauts. */
data class RoadbookPdfOptions(
    val landscape: Boolean = false,
    val compact: Boolean = false,
    val showCumulative: Boolean = true,
    val showNote: Boolean = true,
    val degrees: Boolean = false,
    val unit: DistanceUnit = DistanceUnit.KM,
    val fontSize: Float = FONT_MEDIUM,
) {
    val rowHeight: Float get() = if (compact) 22f else 32f

    fun toJson(): String = JSONObject().put("landscape", landscape).put("compact", compact).put("cumulative", showCumulative)
        .put("note", showNote).put("degrees", degrees).put("unit", unit.name).put("font", fontSize.toDouble()).toString()

    companion object {
        const val FONT_SMALL = 8f
        const val FONT_MEDIUM = 10f
        const val FONT_LARGE = 13f

        fun fromJson(json: String?): RoadbookPdfOptions = runCatching {
            val o = JSONObject(json!!)
            RoadbookPdfOptions(
                o.optBoolean("landscape"), o.optBoolean("compact"), o.optBoolean("cumulative", true), o.optBoolean("note", true),
                o.optBoolean("degrees"), runCatching { DistanceUnit.valueOf(o.optString("unit")) }.getOrDefault(DistanceUnit.KM),
                o.optDouble("font", FONT_MEDIUM.toDouble()).toFloat(),
            )
        }.getOrDefault(RoadbookPdfOptions())
    }
}

/**
 * Road Book imprimable (`RoadbookPDFExporter` iOS) : A4 portrait ou paysage, colonnes Partiel,
 * Cumulé, Cap (pictogramme ou degrés) et Note vierge lignée à annoter à la main ; mêmes entrées que
 * l'écran (virages ET repères en ligne), pagination selon la hauteur réelle des lignes. Pictogrammes
 * en orange plein (lisibles imprimés en niveaux de gris).
 */
object RoadbookPdf {
    private const val PAGE_WIDTH = 595.2f
    private const val PAGE_HEIGHT = 841.8f
    private const val MARGIN = 32f
    private const val HEADER_HEIGHT = 64f
    private const val PARTIAL_FRACTION = 0.16f
    private const val CUMULATIVE_FRACTION = 0.16f
    private const val HEADING_FRACTION = 0.18f
    private val ACCENT = android.graphics.Color.rgb(235, 89, 38)

    private class Columns(val partial: RectF, val cumulative: RectF?, val heading: RectF, val note: RectF?) {
        val right: Float get() = (note ?: heading).right
    }

    private fun columns(options: RoadbookPdfOptions, row: RectF): Columns {
        val keys = buildList {
            add("partial")
            if (options.showCumulative) add("cumulative")
            add("heading")
            if (options.showNote) add("note")
        }
        val fixed = mapOf("partial" to PARTIAL_FRACTION, "cumulative" to CUMULATIVE_FRACTION, "heading" to HEADING_FRACTION)
        val last = keys.last()
        val lastFraction = max(1f - keys.dropLast(1).sumOf { (fixed[it] ?: 0f).toDouble() }.toFloat(), 0.1f)
        var x = row.left
        val rects = HashMap<String, RectF>()
        for (key in keys) {
            val width = row.width() * if (key == last) lastFraction else fixed[key] ?: 0f
            rects[key] = RectF(x, row.top, x + width, row.bottom)
            x += width
        }
        return Columns(rects.getValue("partial"), rects["cumulative"], rects.getValue("heading"), rects["note"])
    }

    fun generate(
        context: Context,
        trackName: String,
        entries: List<RoadbookEntry>,
        attached: Map<Int, LandmarkInfo>,
        options: RoadbookPdfOptions,
        measurer: TextMeasurer,
    ): File {
        val resources = context.resources
        val (width, height) = if (options.landscape) PAGE_HEIGHT to PAGE_WIDTH else PAGE_WIDTH to PAGE_HEIGHT
        val content = RectF(MARGIN, MARGIN + HEADER_HEIGHT, width - MARGIN, height - MARGIN)
        // Ligne 0 de chaque page : titres des colonnes.
        val rowsPerPage = max((content.height() / options.rowHeight).toInt() - 1, 1)
        val pages = if (entries.isEmpty()) listOf(emptyList()) else entries.chunked(rowsPerPage)
        val document = PdfDocument()
        val density = Density(2f)
        pages.forEachIndexed { pageIndex, pageEntries ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(width.roundToInt(), height.roundToInt(), pageIndex + 1).create())
            val canvas = page.canvas
            drawHeader(canvas, resources, trackName, pageIndex, pages.size, width)
            if (entries.isEmpty()) {
                canvas.drawText(resources.getString(R.string.no_maneuver), content.left, content.top + 30, paint(options.fontSize + 1, italic = true, color = android.graphics.Color.DKGRAY))
            } else {
                val titles = columns(options, RectF(content.left, content.top, content.right, content.top + options.rowHeight))
                val titlePaint = paint(options.fontSize - 1, bold = true, color = android.graphics.Color.DKGRAY)
                centered(canvas, resources.getString(R.string.pdf_partial), titles.partial, titlePaint)
                titles.cumulative?.let { centered(canvas, resources.getString(R.string.pdf_cumulative), it, titlePaint) }
                centered(canvas, resources.getString(if (options.degrees) R.string.pdf_degrees else R.string.pdf_heading), titles.heading, titlePaint)
                titles.note?.let { centered(canvas, resources.getString(R.string.pdf_note), it, titlePaint) }
                canvas.drawLine(titles.partial.left, titles.partial.bottom - 2, titles.right, titles.partial.bottom - 2, stroke(android.graphics.Color.DKGRAY, 1f))
                pageEntries.forEachIndexed { rowIndex, entry ->
                    val top = content.top + options.rowHeight * (rowIndex + 1)
                    val row = columns(options, RectF(content.left, top, content.right, top + options.rowHeight))
                    when (entry) {
                        is RoadbookEntry.Maneuver -> drawManeuver(canvas, resources, entry.maneuver, attached[entry.index], row, options, density, measurer)
                        is RoadbookEntry.Landmark -> drawLandmark(canvas, resources, entry, row, options)
                    }
                    canvas.drawLine(row.partial.left, row.partial.bottom, row.right, row.partial.bottom, stroke(android.graphics.Color.rgb(217, 217, 217), 0.5f))
                }
            }
            document.finishPage(page)
        }
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val safe = trackName.replace(Regex("""[\\/:*?"<>|\n\r]"""), "-").trim().ifEmpty { "Road Book" }
        val file = File(directory, "$safe – Road Book.pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }

    fun share(context: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.pdf_export)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun drawHeader(canvas: android.graphics.Canvas, resources: Resources, trackName: String, pageIndex: Int, pageCount: Int, width: Float) {
        canvas.drawText(trackName.ifEmpty { "Road Book" }, MARGIN, MARGIN + 16, paint(16f, bold = true))
        val label = resources.getString(R.string.pdf_page, pageIndex + 1, pageCount)
        val small = paint(10f, color = android.graphics.Color.DKGRAY)
        canvas.drawText(label, width - MARGIN - small.measureText(label), MARGIN + 12, small)
        canvas.drawLine(MARGIN, MARGIN + 28, width - MARGIN, MARGIN + 28, stroke(android.graphics.Color.LTGRAY, 0.75f))
    }

    private fun drawManeuver(
        canvas: android.graphics.Canvas, resources: Resources, maneuver: RoadbookManeuver, landmark: LandmarkInfo?,
        row: Columns, options: RoadbookPdfOptions, density: Density, measurer: TextMeasurer,
    ) {
        val text = paint(options.fontSize)
        centered(canvas, RoadbookTexts.distance(maneuver.partialDistanceMeters, options.unit), row.partial, text)
        row.cumulative?.let { centered(canvas, RoadbookTexts.distance(maneuver.cumulativeDistanceMeters, options.unit), it, text) }
        if (options.degrees) {
            centered(canvas, "${maneuver.headingDegrees.roundToInt()}°", row.heading, text)
        } else {
            val side = min(row.heading.width(), row.heading.height()) * 0.86f
            val bitmap = pictogram(maneuver, side, density, measurer)
            val left = row.heading.centerX() - side / 2
            val top = row.heading.centerY() - side / 2
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + side, top + side), null)
        }
        landmark?.let {
            val emoji = paint(options.fontSize + 2)
            canvas.drawText(RoadbookTexts.emoji(it.category), row.heading.right - emoji.measureText(RoadbookTexts.emoji(it.category)) - 2, row.heading.bottom - 3, emoji)
        }
        row.note?.let { note ->
            var y = note.top + options.fontSize + 2
            if (maneuver.checkpoint.tier == RoadbookTier.ROUNDABOUT && maneuver.checkpoint.roundabout != null) {
                val line = listOfNotNull(RoadbookTexts.instruction(resources, maneuver.checkpoint), RoadbookTexts.roundaboutDetail(resources, maneuver.checkpoint)).joinToString(" ")
                canvas.drawText(ellipsize(line, paint(max(options.fontSize - 1, 6f), bold = true), note.width() - 8), note.left + 4, y, paint(max(options.fontSize - 1, 6f), bold = true))
                y += options.fontSize + 1
            }
            landmark?.let {
                val p = paint(max(options.fontSize - 1, 6f), italic = true, color = android.graphics.Color.DKGRAY)
                canvas.drawText(ellipsize(RoadbookTexts.landmarkDisplayLabel(resources, it), p, note.width() - 8), note.left + 4, min(y, note.bottom - 6), p)
            }
            canvas.drawLine(note.left + 4, note.bottom - 4, note.right - 4, note.bottom - 4, stroke(android.graphics.Color.LTGRAY, 0.5f))
        }
    }

    private fun drawLandmark(canvas: android.graphics.Canvas, resources: Resources, entry: RoadbookEntry.Landmark, row: Columns, options: RoadbookPdfOptions) {
        val landmark = entry.landmark
        val bold = paint(options.fontSize, bold = true)
        centered(canvas, RoadbookTexts.distance(landmark.cumulativeDistanceMeters, options.unit), row.cumulative ?: row.partial, bold)
        if (landmark.info.category == LandmarkCategory.CITY_SIGN) {
            val area = row.note?.let { RectF(row.heading.left, row.heading.top, it.right, it.bottom) } ?: row.heading
            val sign = RectF(area.left + 4, area.top + area.height() * 0.14f, area.right - 4, area.bottom - area.height() * 0.14f)
            val radius = min(sign.height() * 0.18f, 4f)
            canvas.drawRoundRect(sign, radius, radius, Paint().apply { color = android.graphics.Color.WHITE })
            canvas.drawRoundRect(sign, radius, radius, stroke(android.graphics.Color.rgb(217, 26, 26), 1.6f))
            centered(canvas, ellipsize(RoadbookTexts.landmarkDisplayLabel(resources, landmark.info), bold, sign.width() - 12), sign, paint(min(options.fontSize, sign.height() * 0.55f), bold = true))
        } else {
            val emoji = RoadbookTexts.emoji(landmark.info.category)
            val text = listOf(RoadbookTexts.landmarkLabel(resources, landmark.info), RoadbookTexts.landmarkDetail(resources, landmark.info)).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            val note = row.note
            if (note != null) {
                centered(canvas, emoji, row.heading, paint(options.fontSize + 4))
                canvas.drawText(ellipsize(text, bold, note.width() - 8), note.left + 4, note.centerY() + options.fontSize / 3, bold)
            } else {
                centered(canvas, ellipsize("$emoji $text", bold, row.heading.width() - 4), row.heading, bold)
            }
        }
    }

    private fun pictogram(maneuver: RoadbookManeuver, side: Float, density: Density, measurer: TextMeasurer): android.graphics.Bitmap {
        val px = (side * 3).roundToInt()
        val image = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat())) {
            drawManeuverPictogram(maneuver.checkpoint, Color(ACCENT), Color.DarkGray, measurer)
        }
        return image.asAndroidBitmap()
    }

    private fun paint(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int = android.graphics.Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        style = Paint.Style.STROKE
    }

    private fun centered(canvas: android.graphics.Canvas, text: String, rect: RectF, paint: Paint) {
        val fitted = ellipsize(text, paint, rect.width() - 4)
        val metrics = paint.fontMetrics
        canvas.drawText(fitted, rect.centerX() - paint.measureText(fitted) / 2, rect.centerY() - (metrics.ascent + metrics.descent) / 2, paint)
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > width) end--
        return text.substring(0, end) + "…"
    }
}
