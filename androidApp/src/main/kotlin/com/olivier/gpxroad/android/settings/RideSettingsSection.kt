package com.olivier.gpxroad.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.ControlsSide
import com.olivier.gpxroad.android.data.TraceColor
import com.olivier.gpxroad.android.data.TraceWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import com.olivier.gpxroad.android.ui.GroupContent
import com.olivier.gpxroad.android.ui.GroupDivider
import com.olivier.gpxroad.android.ui.SettingsGroup
import com.olivier.gpxroad.android.ui.ToggleRow
import com.olivier.gpxroad.shared.map.MapTheme
import com.olivier.gpxroad.shared.ride.DirectionChevrons
import com.olivier.gpxroad.shared.ride.ZoomPreset
import com.olivier.gpxroad.shared.ride.SlopeAnalyzer
import kotlin.math.roundToInt

/**
 * Réglages du Ride (sections « Apparence », « Pentes » et « Écran » de l'iPhone) : couleur et
 * épaisseur de la trace, espacement des chevrons, côté des contrôles, avertissements de pente,
 * écran toujours allumé.
 */
@Composable
fun RideSettingsSection(settings: AppSettings) {
    SettingsGroup(stringResource(R.string.settings_trace)) {
        GroupContent {
            Text(stringResource(R.string.settings_trace_color), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TraceColor.entries.forEach { color ->
                    val selected = settings.traceColor == color
                    val label = stringResource(color.label)
                    Box(
                        Modifier.size(40.dp)
                            .background(Color(color.argb), CircleShape)
                            .border(if (selected) 4.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.2f), CircleShape)
                            .clickable { settings.updateTraceColor(color) }
                            .semantics { contentDescription = label },
                    )
                }
            }
            SettingChoice(stringResource(R.string.settings_trace_width), TraceWidth.entries, settings.traceWidth, { stringResource(it.label) }, settings::updateTraceWidth)
            SettingChoice(
                stringResource(R.string.settings_chevron_spacing), DirectionChevrons.SPACING_OPTIONS, settings.chevronSpacing,
                { if (it >= 1000) "1 km" else "${it.roundToInt()} m" }, settings::updateChevronSpacing,
            )
        }
    }
    SettingsGroup(stringResource(R.string.settings_screen)) {
        GroupContent {
            SettingChoice(stringResource(R.string.settings_controls_side), ControlsSide.entries, settings.controlsSide, { stringResource(it.label) }, settings::updateControlsSide)
        }
        GroupDivider(16.dp)
        ToggleRow(stringResource(R.string.settings_keep_awake), settings.keepScreenAwake, onChange = settings::updateKeepScreenAwake)
    }
    SettingsGroup(stringResource(R.string.settings_alerts)) {
        ToggleRow(stringResource(R.string.settings_flash), settings.flashEnabled) { settings.updateFlash(it, settings.flashCount) }
        if (settings.flashEnabled) {
            GroupContent { SettingChoice(stringResource(R.string.settings_flash_count), listOf(3, 5), settings.flashCount, { "$it" }) { settings.updateFlash(true, it) } }
        }
        GroupDivider(16.dp)
        ToggleRow(stringResource(R.string.settings_slope_warnings), settings.slopeWarningsEnabled) { settings.updateSlopeWarnings(it, settings.slopeThreshold) }
        if (settings.slopeWarningsEnabled) {
            GroupContent {
                SettingChoice(
                    stringResource(R.string.settings_slope_threshold), SlopeAnalyzer.THRESHOLD_OPTIONS, settings.slopeThreshold,
                    { "${it.roundToInt()} %" }, { settings.updateSlopeWarnings(true, it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingChoice(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.bodyLarge)
        // Libellés longs (4 thèmes…) : des pastilles qui passent à la ligne plutôt qu'un texte tronqué.
        if (options.size >= 4 && options.any { label(it).length > 8 }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
                }
            }
            return@Column
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label(option), maxLines = 1) }
            }
        }
    }
}

@Composable
fun SettingToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Carte et caméra (`MapThemePickerView`, `NavigationSettingsView` iOS) : thème, point, zooms. */
@Composable
fun MapCameraSection(settings: AppSettings) {
    SettingsGroup(stringResource(R.string.map_theme), footer = stringResource(R.string.offline_footer)) {
        GroupContent {
            SettingChoice(
                "", MapTheme.entries, settings.mapTheme,
                {
                    stringResource(
                        when (it) {
                            MapTheme.STANDARD -> R.string.theme_standard
                            MapTheme.HIGH_CONTRAST -> R.string.theme_high_contrast
                            MapTheme.EARTHY -> R.string.theme_earthy
                            MapTheme.RELIEF -> R.string.theme_relief
                        },
                    )
                },
                settings::updateMapTheme,
            )
        }
    }
    SettingsGroup(stringResource(R.string.settings_camera), footer = stringResource(R.string.camera_anchor_footer)) {
        GroupContent {
            Text(stringResource(R.string.camera_anchor, (settings.anchorY * 100).roundToInt()), style = MaterialTheme.typography.bodyLarge)
            Slider(value = settings.anchorY.toFloat(), onValueChange = { settings.updateAnchorY((it * 100).roundToInt() / 100.0) }, valueRange = 0.55f..0.85f)
        }
    }
    SettingsGroup(footer = stringResource(R.string.camera_default_zoom_footer)) {
        GroupContent {
            Text(stringResource(R.string.camera_default_zoom, meters(settings.defaultZoom)), style = MaterialTheme.typography.bodyLarge)
            Slider(value = settings.defaultZoom.toFloat(), onValueChange = { settings.updateDefaultZoom((it / 50).roundToInt() * 50.0) }, valueRange = 300f..6000f)
        }
    }
    SettingsGroup {
        ToggleRow(stringResource(R.string.camera_auto_zoom), settings.autoZoomEnabled) {
            settings.updateAutoZoom(it, settings.zoomPreset, settings.autoZoomMin, settings.autoZoomMax)
        }
        if (settings.autoZoomEnabled) {
            GroupContent {
                SettingChoice(
                    "", ZoomPreset.entries, settings.zoomPreset,
                    {
                        stringResource(
                            when (it) {
                                ZoomPreset.PRUDENT -> R.string.zoom_prudent
                                ZoomPreset.NORMAL -> R.string.zoom_normal
                                ZoomPreset.RAPIDE -> R.string.zoom_fast
                            },
                        )
                    },
                ) { settings.updateAutoZoom(true, it, settings.autoZoomMin, settings.autoZoomMax) }
                Text(stringResource(R.string.zoom_min, meters(settings.autoZoomMin)), style = MaterialTheme.typography.bodyMedium)
                Slider(value = settings.autoZoomMin.toFloat(), onValueChange = { settings.updateAutoZoom(true, settings.zoomPreset, (it / 50).roundToInt() * 50.0, settings.autoZoomMax) }, valueRange = 100f..3000f)
                Text(stringResource(R.string.zoom_max, meters(settings.autoZoomMax)), style = MaterialTheme.typography.bodyMedium)
                Slider(value = settings.autoZoomMax.toFloat(), onValueChange = { settings.updateAutoZoom(true, settings.zoomPreset, settings.autoZoomMin, (it / 50).roundToInt() * 50.0) }, valueRange = 100f..3000f)
            }
        }
    }
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun meters(value: Double): String = if (value >= 1000) "${"%.1f".format(value / 1000)} km" else "${value.roundToInt()} m"
