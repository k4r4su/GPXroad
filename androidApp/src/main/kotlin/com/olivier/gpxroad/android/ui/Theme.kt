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
