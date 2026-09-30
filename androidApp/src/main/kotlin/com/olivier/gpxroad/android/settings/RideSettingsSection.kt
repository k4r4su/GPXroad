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
import com.olivier.gpxroad.shared.ride.DirectionChevrons
import com.olivier.gpxroad.shared.ride.SlopeAnalyzer
import kotlin.math.roundToInt

/**
 * Réglages du Ride (sections « Apparence », « Pentes » et « Écran » de l'iPhone) : couleur et
 * épaisseur de la trace, espacement des chevrons, côté des contrôles, avertissements de pente,
 * écran toujours allumé.
 */
@Composable
fun RideSettingsSection(settings: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_trace_color), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TraceColor.entries.forEach { color ->
                val selected = settings.traceColor == color
                val label = stringResource(color.label)
                Box(
                    Modifier.size(40.dp)
                        .background(Color(color.argb), CircleShape)
                        .border(if (selected) 4.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.3f), CircleShape)
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
        SettingChoice(stringResource(R.string.settings_controls_side), ControlsSide.entries, settings.controlsSide, { stringResource(it.label) }, settings::updateControlsSide)
        SettingToggle(stringResource(R.string.settings_slope_warnings), settings.slopeWarningsEnabled) { settings.updateSlopeWarnings(it, settings.slopeThreshold) }
        if (settings.slopeWarningsEnabled) {
            SettingChoice(
                stringResource(R.string.settings_slope_threshold), SlopeAnalyzer.THRESHOLD_OPTIONS, settings.slopeThreshold,
                { "${it.roundToInt()} %" }, { settings.updateSlopeWarnings(true, it) },
            )
        }
        SettingToggle(stringResource(R.string.settings_keep_awake), settings.keepScreenAwake, settings::updateKeepScreenAwake)
    }
}

@Composable
fun <T> SettingChoice(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
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
