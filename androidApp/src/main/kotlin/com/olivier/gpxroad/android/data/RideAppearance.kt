package com.olivier.gpxroad.android.data

import androidx.annotation.StringRes
import com.olivier.gpxroad.android.R

/** Couleur de la trace (`TraceColorPreset` iOS). */
enum class TraceColor(val argb: Int, @StringRes val label: Int) {
    ORANGE(0xFFFF9500.toInt(), R.string.color_orange),
    ROUGE(0xFFFF3B30.toInt(), R.string.color_red),
    CYAN(0xFF32ADE6.toInt(), R.string.color_cyan),
    JAUNE(0xFFFFCC00.toInt(), R.string.color_yellow),
    MAGENTA(0xFFED21B8.toInt(), R.string.color_magenta),
    VERT_LIME(0xFF8CED21.toInt(), R.string.color_lime),
}

/** Épaisseur de la trace (`TraceWidthPreset` iOS), en dp ; « Épais » par défaut (lisible avec des gants). */
enum class TraceWidth(val widthDp: Float, @StringRes val label: Int) {
    FINE(3f, R.string.width_fine),
    NORMALE(4.5f, R.string.width_normal),
    EPAIS(6f, R.string.width_thick),
}

/** Côté de la colonne de contrôles du Ride ; la vitesse passe toujours de l'autre côté. */
enum class ControlsSide(@StringRes val label: Int) {
    LEFT(R.string.side_left),
    RIGHT(R.string.side_right),
}
