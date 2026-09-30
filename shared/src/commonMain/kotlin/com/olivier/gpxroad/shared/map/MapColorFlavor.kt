package com.olivier.gpxroad.shared.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Thème de carte (`MapThemePreset` iOS) : trois palettes du style vectoriel, et Relief (raster
 * OpenTopoMap, sans palette).
 */
enum class MapTheme(val flavor: MapColorFlavor?) {
    STANDARD(MapColorFlavor.STANDARD),
    HIGH_CONTRAST(MapColorFlavor.HIGH_CONTRAST),
    EARTHY(MapColorFlavor.EARTHY),
    RELIEF(null),
}

/** Palette appliquée à toutes les couleurs du style (`MapColorFlavor` iOS) : teinte, saturation, luminosité. */
enum class MapColorFlavor(val hueShiftDegrees: Double, val saturationBoostFraction: Double, val lightnessDelta: Double) {
    STANDARD(0.0, 0.0, 0.0),
    HIGH_CONTRAST(0.0, 0.45, -0.07),
    EARTHY(22.0, 0.30, -0.05);

    val isIdentity: Boolean get() = hueShiftDegrees == 0.0 && saturationBoostFraction == 0.0 && lightnessDelta == 0.0
}

data class Hsla(val h: Double, val s: Double, val l: Double, val a: Double)

/**
 * Retouche des couleurs d'un style MapLibre (portage de `ColorFlavorPatcher` iOS) : chaque chaîne
 * de couleur (`#rgb`, `#rrggbb`, `rgb()`, `rgba()`, `hsl()`, `hsla()`) devient un `hsla(...)`
 * décalé ; tout le reste (expressions, noms de polices, d'icônes) est laissé tel quel.
 */
object ColorFlavorPatcher {
    /** Luminosité toujours gardée dans cette plage : jamais d'écrêtage au blanc ou au noir. */
    private const val SAFE_LIGHTNESS_MIN = 0.10
    private const val SAFE_LIGHTNESS_MAX = 0.88

    fun transformedColorString(value: String, flavor: MapColorFlavor): String? {
        val color = parseColor(value) ?: return null
        if (color.s <= 0) {
            val lightness = min(max(color.l + flavor.lightnessDelta, 0.0), 1.0)
            return "hsla(0.0, 0.0%, ${fixed(lightness * 100, 1)}%, ${fixed(color.a, 3)})"
        }
        var hue = (color.h + flavor.hueShiftDegrees) % 360
        if (hue < 0) hue += 360
        val saturation = color.s + (1 - color.s) * flavor.saturationBoostFraction
        val lightness = min(max(color.l + flavor.lightnessDelta, SAFE_LIGHTNESS_MIN), SAFE_LIGHTNESS_MAX)
        return "hsla(${fixed(hue, 1)}, ${fixed(saturation * 100, 1)}%, ${fixed(lightness * 100, 1)}%, ${fixed(color.a, 3)})"
    }

    fun parseColor(raw: String): Hsla? {
        val value = raw.trim()
        return when {
            value.startsWith("#") -> parseHex(value)
            value.startsWith("hsla(") || value.startsWith("hsl(") -> parseHsl(value)
            value.startsWith("rgba(") || value.startsWith("rgb(") -> parseRgb(value)
            else -> null
        }
    }

    private fun parseHex(value: String): Hsla? {
        var hex = value.drop(1)
        if (hex.isEmpty() || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length != 6) return null
        val v = hex.toLong(16)
        return rgbToHsl((v shr 16 and 0xFF) / 255.0, (v shr 8 and 0xFF) / 255.0, (v and 0xFF) / 255.0, 1.0)
    }

    private fun inner(value: String): List<String>? {
        val open = value.indexOf('(')
        val close = value.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return value.substring(open + 1, close).split(',').map { it.trim() }
    }

    private fun parseHsl(value: String): Hsla? {
        val parts = inner(value)?.takeIf { it.size >= 3 } ?: return null
        val h = parts[0].toDoubleOrNull() ?: return null
        val s = parts[1].removeSuffix("%").toDoubleOrNull() ?: return null
        val l = parts[2].removeSuffix("%").toDoubleOrNull() ?: return null
        val a = if (parts.size >= 4) parts[3].toDoubleOrNull() ?: 1.0 else 1.0
        return Hsla(h, s / 100, l / 100, a)
    }

    private fun parseRgb(value: String): Hsla? {
        val parts = inner(value)?.takeIf { it.size >= 3 } ?: return null
        val r = parts[0].toDoubleOrNull() ?: return null
        val g = parts[1].toDoubleOrNull() ?: return null
        val b = parts[2].toDoubleOrNull() ?: return null
        val a = if (parts.size >= 4) parts[3].toDoubleOrNull() ?: 1.0 else 1.0
        return rgbToHsl(r / 255, g / 255, b / 255, a)
    }

    private fun rgbToHsl(r: Double, g: Double, b: Double, a: Double): Hsla {
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val l = (maxc + minc) / 2
        if (maxc == minc) return Hsla(0.0, 0.0, l, a)
        val d = maxc - minc
        val s = if (l > 0.5) d / (2 - maxc - minc) else d / (maxc + minc)
        val h = when (maxc) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return Hsla(h * 60, s, l, a)
    }

    /** `String(format: "%.Nf")` sans dépendance à la plateforme (point décimal). */
    private fun fixed(value: Double, decimals: Int): String {
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = (abs(value) * factor).roundToLong()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return sign + (scaled / factor) + "." + (scaled % factor).toString().padStart(decimals, '0')
    }
}
