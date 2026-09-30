package com.olivier.gpxroad.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icône de la Bibliothèque (livres sur une étagère) — absente des icônes Material de base. */
val LibraryIcon: ImageVector = ImageVector.Builder("Library", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        // Trois dos de livres et une étagère.
        moveTo(4f, 4f); lineTo(7f, 4f); lineTo(7f, 18f); lineTo(4f, 18f); close()
        moveTo(8.5f, 6f); lineTo(11.5f, 6f); lineTo(11.5f, 18f); lineTo(8.5f, 18f); close()
        moveTo(13.2f, 5.2f); lineTo(16.1f, 4.4f); lineTo(19.6f, 17.3f); lineTo(16.7f, 18.1f); close()
        moveTo(3f, 19.5f); lineTo(21f, 19.5f); lineTo(21f, 21f); lineTo(3f, 21f); close()
    }
}.build()

/** Icône « Travail » (mallette) — absente des icônes Material de base. */
val WorkIcon: ImageVector = ImageVector.Builder("Work", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        // Poignée.
        moveTo(9f, 3.5f); lineTo(15f, 3.5f); lineTo(15f, 7f); lineTo(13.5f, 7f); lineTo(13.5f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 7f); lineTo(9f, 7f); close()
        // Corps.
        moveTo(3f, 7.5f); lineTo(21f, 7.5f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
    }
}.build()
