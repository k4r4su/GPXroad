package com.olivier.gpxroad.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R

/** Grand titre d'écran (comme les grands titres iOS), actions à droite. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
        }
        Text(
            title,
            style = if (onBack != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Groupe de réglages (liste groupée iOS) : titre discret, carte arrondie, pied de note. */
@Composable
fun SettingsGroup(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        title?.let {
            Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, shadowElevation = 0.dp) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

/** Séparateur entre deux lignes d'un groupe (aligné après l'icône). */
@Composable
fun GroupDivider(inset: Dp = 60.dp) {
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Pastille d'icône colorée (comme les réglages iOS). */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 30.dp) {
    Box(Modifier.size(size).background(tint, RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/** Ligne d'un groupe : icône, titre, valeur, chevron si elle ouvre un sous-écran. */
@Composable
fun SettingsRow(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = Accent,
    value: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon?.let { IconBadge(it, iconTint) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        trailing?.invoke()
        if (onClick != null && trailing == null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Ligne avec interrupteur (toute la ligne est cliquable). */
@Composable
fun ToggleRow(title: String, checked: Boolean, icon: ImageVector? = null, iconTint: Color = Accent, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon?.let { IconBadge(it, iconTint) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Contenu libre dans un groupe (sélecteurs, curseurs), avec les marges des lignes. */
@Composable
fun GroupContent(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

// MARK: surcouches de la carte (Ride)

/** Fond des panneaux posés sur la carte : sombre translucide, liseré clair, lisible au soleil. */
val MapPanelColor = Color(0xE61C1A18)
val MapPanelBorder = Color(0x26FFFFFF)

/** Bouton de carte (grande cible, utilisable avec des gants) : icône et, au besoin, un libellé court. */
@Composable
fun MapButton(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, label: String? = null, background: Color = MapPanelColor, onClick: () -> Unit) {
    Column(
        modifier.size(64.dp)
            .background(background, RoundedCornerShape(20.dp))
            .border(1.dp, MapPanelBorder, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = if (label == null) contentDescription else null, tint = Color.White, modifier = Modifier.size(if (label == null) 30.dp else 24.dp))
        label?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

/** Panneau posé sur la carte (bandeaux, mesures). */
fun Modifier.mapPanel(color: Color = MapPanelColor, radius: Dp = 20.dp): Modifier =
    this.background(color, RoundedCornerShape(radius)).border(1.dp, MapPanelBorder, RoundedCornerShape(radius))

/** Petit titre de section dans une page (hors groupe). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}
