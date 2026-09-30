package com.olivier.gpxroad.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Orange de l'app iOS (`AccentColor` : 0.957 / 0.435 / 0.086). */
val Accent = Color(0xFFF46F16)
val OffTrackOrange = Color(0xFFFF9500)

@Composable
fun GPXroadTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Accent, secondary = Accent, tertiary = OffTrackOrange)
    } else {
        lightColorScheme(primary = Accent, secondary = Accent, tertiary = OffTrackOrange)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Palette du Road Book (`RoadbookPaletteColors` iOS) : papier crème le jour, noir la nuit — les
 * écrans du Road Book lisent ces couleurs via le thème Material.
 */
@Composable
fun RoadbookPaletteTheme(night: Boolean, content: @Composable () -> Unit) {
    val colors = if (night) {
        darkColorScheme(
            primary = Accent, secondary = Accent, tertiary = OffTrackOrange,
            background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF1C1C1E),
            onBackground = Color.White, onSurface = Color.White,
        )
    } else {
        lightColorScheme(
            primary = Accent, secondary = Accent, tertiary = OffTrackOrange,
            background = Color(0xFFF7F1E0), surface = Color(0xFFF7F1E0), surfaceVariant = Color(0xFFFFFCF3),
            onBackground = Color.Black, onSurface = Color.Black,
        )
    }
    MaterialTheme(colorScheme = colors) {
        androidx.compose.material3.Surface(color = colors.background, contentColor = colors.onBackground, content = content)
    }
}
