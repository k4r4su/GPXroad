package com.olivier.gpxroad.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.DistanceUnit
import kotlin.math.roundToInt

/** Réglages (équivalent partiel de `SettingsView` iOS) : ceux dont le Road Book a besoin aujourd'hui. */
@Composable
fun SettingsScreen(settings: AppSettings) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section(stringResource(R.string.settings_unit))
        val units = listOf(DistanceUnit.KM to R.string.unit_km, DistanceUnit.MI to R.string.unit_mi)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            units.forEachIndexed { index, (unit, label) ->
                SegmentedButton(
                    selected = settings.distanceUnit == unit,
                    onClick = { settings.updateDistanceUnit(unit) },
                    shape = SegmentedButtonDefaults.itemShape(index, units.size),
                ) { Text(stringResource(label)) }
            }
        }

        HorizontalDivider()
        Section(stringResource(R.string.settings_roadbook))
        Setting(stringResource(R.string.settings_light, settings.lightThreshold.roundToInt()), settings.lightThreshold, 10f..60f) {
            settings.updateThresholds(it, settings.markedThreshold, settings.hardThreshold, settings.veryHardThreshold)
        }
        Setting(stringResource(R.string.settings_marked, settings.markedThreshold.roundToInt()), settings.markedThreshold, 30f..90f) {
            settings.updateThresholds(settings.lightThreshold, it, settings.hardThreshold, settings.veryHardThreshold)
        }
        Setting(stringResource(R.string.settings_hard, settings.hardThreshold.roundToInt()), settings.hardThreshold, 60f..130f) {
            settings.updateThresholds(settings.lightThreshold, settings.markedThreshold, it, settings.veryHardThreshold)
        }
        Setting(stringResource(R.string.settings_very_hard, settings.veryHardThreshold.roundToInt()), settings.veryHardThreshold, 100f..170f) {
            settings.updateThresholds(settings.lightThreshold, settings.markedThreshold, settings.hardThreshold, it)
        }
        Setting(stringResource(R.string.settings_window_before, settings.windowBefore.roundToInt()), settings.windowBefore, 20f..150f) {
            settings.updateWindows(it, settings.windowAfter, settings.mergeDistance)
        }
        Setting(stringResource(R.string.settings_window_after, settings.windowAfter.roundToInt()), settings.windowAfter, 20f..150f) {
            settings.updateWindows(settings.windowBefore, it, settings.mergeDistance)
        }
        Setting(stringResource(R.string.settings_merge, settings.mergeDistance.roundToInt()), settings.mergeDistance, 50f..400f) {
            settings.updateWindows(settings.windowBefore, settings.windowAfter, it)
        }
        OutlinedButton(onClick = settings::resetRoadbook) { Text(stringResource(R.string.settings_reset)) }

        HorizontalDivider()
        Section(stringResource(R.string.settings_about))
        Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_android_status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

/** Curseur arrondi au degré / mètre près ; le Road Book se recalcule avec la nouvelle valeur. */
@Composable
private fun Setting(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().toDouble()) }, valueRange = range)
    }
}
