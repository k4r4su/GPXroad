package com.olivier.gpxroad.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Orange de l'app iOS (`AccentColor` : 0.957 / 0.435 / 0.086). */
val Accent = Color(0xFFF46F16)
val OffTrackOrange = Color(0xFFFF9500)

/**
 * Identité GPXroad (refonte du 01/10, « une interface dans l'ère du temps ») : orange de l'iPhone,
 * neutres chauds (jamais un gris pur), surfaces en paliers pour les cartes groupées, grands titres
 * gras, coins généreux. Le mode sombre est un vrai noir chaud, lisible la nuit.
 */
private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D1),
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Color(0xFF6D5A4C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E6DC),
    onSecondaryContainer = Color(0xFF261A11),
    tertiary = OffTrackOrange,
    background = Color(0xFFF7F4F1),
    onBackground = Color(0xFF1D1A17),
    surface = Color(0xFFF7F4F1),
    onSurface = Color(0xFF1D1A17),
    surfaceVariant = Color(0xFFEDE6E0),
    onSurfaceVariant = Color(0xFF6B625B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1ECE8),
    surfaceContainerHigh = Color(0xFFEBE5E0),
    surfaceContainerHighest = Color(0xFFE4DDD7),
    outline = Color(0xFFB9AFA7),
    outlineVariant = Color(0xFFE2DAD3),
    error = Color(0xFFD93025),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF3A1600),
    primaryContainer = Color(0xFF5A2A08),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFD9C3B3),
    onSecondary = Color(0xFF3C2D22),
    secondaryContainer = Color(0xFF3A3029),
    onSecondaryContainer = Color(0xFFF3E6DC),
    tertiary = OffTrackOrange,
    background = Color(0xFF0F0E0D),
    onBackground = Color(0xFFEDE7E2),
    surface = Color(0xFF0F0E0D),
    onSurface = Color(0xFFEDE7E2),
    surfaceVariant = Color(0xFF2A2623),
    onSurfaceVariant = Color(0xFFB8AEA6),
    surfaceContainerLowest = Color(0xFF0A0909),
    surfaceContainerLow = Color(0xFF1B1917),
    surfaceContainer = Color(0xFF211E1C),
    surfaceContainerHigh = Color(0xFF2A2724),
    surfaceContainerHighest = Color(0xFF34302D),
    outline = Color(0xFF6E655E),
    outlineVariant = Color(0xFF3A3531),
    error = Color(0xFFFF6B5E),
)

private val GPXroadShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val GPXroadTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
)

/** Chiffres de conduite (vitesse, distances) : très gras, chasse fixe pour ne pas « danser ». */
val RideNumberStyle = TextStyle(fontWeight = FontWeight.Black, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)

@Composable
fun GPXroadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = GPXroadShapes,
        typography = GPXroadTypography,
        content = content,
    )
}

/**
 * Palette du Road Book (`RoadbookPaletteColors` iOS) : papier crème le jour, noir la nuit — les
 * écrans du Road Book lisent ces couleurs via le thème Material.
 */
@Composable
fun RoadbookPaletteTheme(night: Boolean, content: @Composable () -> Unit) {
    val colors = if (night) {
        DarkColors.copy(
            background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF1C1C1E),
            surfaceContainerLow = Color(0xFF151413), surfaceContainer = Color(0xFF1C1B1A),
            onBackground = Color.White, onSurface = Color.White,
        )
    } else {
        LightColors.copy(
            background = Color(0xFFF7F1E0), surface = Color(0xFFF7F1E0), surfaceVariant = Color(0xFFFFFCF3),
            surfaceContainerLow = Color(0xFFFFFCF3), surfaceContainer = Color(0xFFF1EAD6),
            onBackground = Color.Black, onSurface = Color.Black,
        )
    }
    MaterialTheme(colorScheme = colors, shapes = GPXroadShapes, typography = GPXroadTypography) {
        androidx.compose.material3.Surface(color = colors.background, contentColor = colors.onBackground, content = content)
    }
}
