package com.olivier.gpxroad.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppLanguage
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.offline.OfflineMaps
import com.olivier.gpxroad.android.offline.OfflineSection
import com.olivier.gpxroad.android.sync.SharedBlockageSync
import com.olivier.gpxroad.android.ui.GroupContent
import com.olivier.gpxroad.android.ui.GroupDivider
import com.olivier.gpxroad.android.ui.ScreenHeader
import com.olivier.gpxroad.android.ui.SettingsGroup
import com.olivier.gpxroad.android.ui.SettingsRow
import com.olivier.gpxroad.android.ui.ToggleRow
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.offline.AutoPrefetch
import com.olivier.gpxroad.shared.recording.RecordingConstants
import com.olivier.gpxroad.shared.recording.RecordingDensity
import com.olivier.gpxroad.shared.roadbook.RoadbookPaletteSetting
import kotlin.math.roundToInt

/** Pages des Réglages (sous-écrans de la liste principale). */
private enum class SettingsPage { MAIN, RIDE, MAP, OFFLINE, GOTO, ROADBOOK, LANDMARKS, RECORDING, VALHALLA, OVERPASS, COMMUNITY }

/** Couleurs des pastilles d'icônes (une par famille, comme les Réglages iOS). */
private object Tints {
    val language = Color(0xFF5E5CE6)
    val unit = Color(0xFF8E8E93)
    val tutorial = Color(0xFF34C759)
    val ride = Color(0xFFF46F16)
    val map = Color(0xFF30B0C7)
    val offline = Color(0xFF007AFF)
    val goTo = Color(0xFF0A84FF)
    val roadbook = Color(0xFFAF52DE)
    val landmarks = Color(0xFFFF2D55)
    val recording = Color(0xFFFF3B30)
    val servers = Color(0xFF636366)
    val community = Color(0xFF32ADE6)
    val about = Color(0xFF8E8E93)
}

/**
 * Réglages (`SettingsView` iOS) : liste groupée avec icônes, une page par thème ; tout s'applique
 * immédiatement. Le retour système ramène à la liste.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    servers: ServerSettings,
    overpass: OverpassClient,
    routing: RoutingClient,
    blockageSync: SharedBlockageSync,
    offline: OfflineMaps,
    position: LatLon?,
    onOpenTutorial: () -> Unit = {},
    onLanguageChanged: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }
    val back = { page = SettingsPage.MAIN }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (page) {
            SettingsPage.MAIN -> MainPage(settings, offline, onOpenTutorial, onLanguageChanged) { page = it }
            SettingsPage.RIDE -> SubPage(stringResource(R.string.settings_ride), back) { RideSettingsSection(settings) }
            SettingsPage.MAP -> SubPage(stringResource(R.string.settings_map), back) { MapCameraSection(settings) }
            SettingsPage.OFFLINE -> SubPage(stringResource(R.string.offline_title), back) {
                AutoMapGroup(settings)
                SettingsGroup { GroupContent { OfflineSection(offline, position) } }
            }
            SettingsPage.GOTO -> SubPage(stringResource(R.string.tab_goto), back) { GoToSettings(settings) }
            SettingsPage.ROADBOOK -> SubPage(stringResource(R.string.settings_roadbook), back) { RoadbookSettings(settings) }
            SettingsPage.LANDMARKS -> SubPage(stringResource(R.string.settings_landmarks), back) {
                SettingsGroup { GroupContent { LandmarkCategoriesSection(settings) } }
            }
            SettingsPage.RECORDING -> SubPage(stringResource(R.string.settings_recording), back) { RecordingSettings(settings) }
            SettingsPage.VALHALLA -> SubPage(stringResource(R.string.valhalla_title), back) {
                SettingsGroup { GroupContent { ValhallaSection(servers, routing) } }
            }
            SettingsPage.OVERPASS -> SubPage(stringResource(R.string.overpass_title), back) {
                SettingsGroup { GroupContent { OverpassSection(servers, overpass) } }
            }
            SettingsPage.COMMUNITY -> SubPage(stringResource(R.string.shared_title), back) { CommunitySettings(blockageSync) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    ScreenHeader(title, onBack = onBack)
    content()
}

@Composable
private fun MainPage(settings: AppSettings, offline: OfflineMaps, onOpenTutorial: () -> Unit, onLanguageChanged: () -> Unit, open: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    ScreenHeader(stringResource(R.string.settings_title))

    SettingsGroup(stringResource(R.string.settings_general)) {
        val currentLanguage = remember { AppLanguage.current(context) }
        var choosingLanguage by remember { mutableStateOf(false) }
        SettingsRow(
            stringResource(R.string.settings_language), Icons.Rounded.Language, Tints.language,
            value = if (currentLanguage == AppLanguage.AUTOMATIC) stringResource(R.string.language_auto) else currentLanguage.nativeName,
            onClick = { choosingLanguage = !choosingLanguage },
        )
        if (choosingLanguage) {
            AppLanguage.entries.forEach { language ->
                SettingsRow(
                    if (language == AppLanguage.AUTOMATIC) stringResource(R.string.language_auto) else language.nativeName,
                    onClick = {
                        choosingLanguage = false
                        if (language != currentLanguage) {
                            AppLanguage.save(context, language)
                            onLanguageChanged()
                        }
                    },
                    trailing = { RadioButton(selected = language == currentLanguage, onClick = null) },
                )
            }
        }
        GroupDivider()
        SettingsRow(
            stringResource(R.string.settings_unit), Icons.Rounded.Straighten, Tints.unit,
            value = stringResource(if (settings.distanceUnit == DistanceUnit.KM) R.string.unit_km else R.string.unit_mi),
            onClick = { settings.updateDistanceUnit(if (settings.distanceUnit == DistanceUnit.KM) DistanceUnit.MI else DistanceUnit.KM) },
            trailing = {},
        )
        GroupDivider()
        SettingsRow(stringResource(R.string.settings_tutorial), Icons.Rounded.School, Tints.tutorial, onClick = onOpenTutorial)
    }

    SettingsGroup(stringResource(R.string.settings_riding)) {
        SettingsRow(stringResource(R.string.settings_ride), Icons.Rounded.TwoWheeler, Tints.ride, onClick = { open(SettingsPage.RIDE) })
        GroupDivider()
        SettingsRow(stringResource(R.string.settings_map), Icons.Rounded.Map, Tints.map, onClick = { open(SettingsPage.MAP) })
        GroupDivider()
        val zones = offline.zones.size
        SettingsRow(stringResource(R.string.offline_title), Icons.Rounded.DownloadForOffline, Tints.offline, value = if (zones > 0) "$zones" else null, onClick = { open(SettingsPage.OFFLINE) })
        GroupDivider()
        SettingsRow(stringResource(R.string.settings_recording), Icons.Rounded.RadioButtonChecked, Tints.recording, onClick = { open(SettingsPage.RECORDING) })
        GroupDivider()
        SettingsRow(stringResource(R.string.tab_goto), Icons.Rounded.Search, Tints.goTo, onClick = { open(SettingsPage.GOTO) })
    }

    SettingsGroup(stringResource(R.string.tab_roadbook)) {
        SettingsRow(stringResource(R.string.settings_roadbook), Icons.Rounded.MenuBook, Tints.roadbook, onClick = { open(SettingsPage.ROADBOOK) })
        GroupDivider()
        SettingsRow(stringResource(R.string.settings_landmarks), Icons.Rounded.Place, Tints.landmarks, value = "${settings.landmarkCategories.size}", onClick = { open(SettingsPage.LANDMARKS) })
    }

    SettingsGroup(stringResource(R.string.settings_advanced)) {
        SettingsRow(stringResource(R.string.valhalla_title), Icons.Rounded.Navigation, Tints.servers, onClick = { open(SettingsPage.VALHALLA) })
        GroupDivider()
        SettingsRow(stringResource(R.string.overpass_title), Icons.Rounded.Cloud, Tints.servers, onClick = { open(SettingsPage.OVERPASS) })
        GroupDivider()
        SettingsRow(stringResource(R.string.shared_title), Icons.Rounded.Groups, Tints.community, onClick = { open(SettingsPage.COMMUNITY) })
    }

    SettingsGroup(stringResource(R.string.settings_about)) {
        SettingsRow(stringResource(R.string.settings_version, version), Icons.Rounded.Info, Tints.about)
    }
}

@Composable
private fun GoToSettings(settings: AppSettings) {
    SettingsGroup {
        ToggleRow(stringResource(R.string.nav_voice), settings.voiceEnabled) { settings.updateVoice(it, settings.voiceVolume) }
        if (settings.voiceEnabled) {
            GroupDivider(16.dp)
            GroupContent {
                Text(stringResource(R.string.nav_volume), style = MaterialTheme.typography.bodyLarge)
                Slider(value = settings.voiceVolume.toFloat(), onValueChange = { settings.updateVoice(true, it.toDouble()) }, valueRange = 0f..1f)
            }
        }
    }
    SettingsGroup(footer = stringResource(R.string.tuto_goto_6)) {
        GroupContent {
            SettingChoice(stringResource(R.string.nav_speed_margin), listOf(5, 10, 15), settings.speedMarginKmh, { "+$it km/h" }, settings::updateSpeedMargin)
        }
    }
}

@Composable
private fun RoadbookSettings(settings: AppSettings) {
    SettingsGroup {
        GroupContent {
            SettingChoice(
                stringResource(R.string.roadbook_palette), RoadbookPaletteSetting.entries, settings.roadbookPalette,
                { stringResource(when (it) { RoadbookPaletteSetting.AUTOMATIC -> R.string.palette_auto; RoadbookPaletteSetting.PAPER -> R.string.palette_paper; RoadbookPaletteSetting.NIGHT -> R.string.palette_night }) },
                settings::updateRoadbookPalette,
            )
        }
    }
    SettingsGroup(stringResource(R.string.settings_turn_thresholds)) {
        GroupContent {
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
        }
    }
    SettingsGroup(stringResource(R.string.settings_detection)) {
        GroupContent {
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
        }
    }
}

/** Curseur arrondi au degré / mètre près ; le Road Book se recalcule avec la nouvelle valeur. */
@Composable
private fun Setting(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().toDouble()) }, valueRange = range)
    }
}

/** Enregistrement de la sortie (comme l'iPhone) : densité des points, sauvegardes de secours. */
@Composable
private fun RecordingSettings(settings: AppSettings) {
    val labels = mapOf(
        RecordingDensity.PRECIS to R.string.density_precis,
        RecordingDensity.LEGER to R.string.density_leger,
        RecordingDensity.TRES_LEGER to R.string.density_tres_leger,
        RecordingDensity.ULTRA_LEGER to R.string.density_ultra_leger,
    )
    SettingsGroup(stringResource(R.string.settings_density)) {
        RecordingDensity.entries.forEachIndexed { index, density ->
            if (index > 0) GroupDivider(16.dp)
            SettingsRow(
                stringResource(labels.getValue(density)),
                subtitle = stringResource(R.string.density_detail, density.minIntervalSeconds, density.minDistanceMeters),
                onClick = { settings.updateRecordingDensity(density) },
                trailing = { RadioButton(selected = settings.recordingDensity == density, onClick = null) },
            )
        }
    }
    SettingsGroup(footer = stringResource(R.string.settings_record_prompt_hint)) {
        ToggleRow(stringResource(R.string.settings_record_prompt), settings.recordingPromptEnabled, onChange = settings::updateRecordingPromptEnabled)
    }
    SettingsGroup(footer = stringResource(R.string.settings_unsaved_footer)) {
        GroupContent {
            SettingChoice(stringResource(R.string.settings_unsaved_retention), RecordingConstants.UNSAVED_RETENTION_OPTIONS, settings.unsavedRetention, { "$it" }, settings::updateUnsavedRetention)
        }
    }
}

/** Communauté : partage anonyme des chemins bloqués, serveur auto-hébergé (vide = aucune requête). */
@Composable
private fun CommunitySettings(sync: SharedBlockageSync) {
    var url by remember { mutableStateOf(sync.serverUrl) }
    SettingsGroup(footer = stringResource(R.string.shared_footer)) {
        ToggleRow(stringResource(R.string.shared_share), sync.shareEnabled) { sync.update(it, url) }
    }
    SettingsGroup(footer = stringResource(R.string.shared_server_footer)) {
        GroupContent {
            OutlinedTextField(
                value = url, onValueChange = { url = it; sync.update(sync.shareEnabled, it) }, singleLine = true, modifier = Modifier,
                label = { Text(stringResource(R.string.shared_server)) },
            )
        }
    }
}

/** Carte automatique autour de soi : activée par défaut, rayon 10/15/20 km, avec ou sans données mobiles. */
@Composable
private fun AutoMapGroup(settings: AppSettings) {
    SettingsGroup(footer = stringResource(R.string.settings_auto_prepare_hint)) {
        ToggleRow(stringResource(R.string.settings_auto_prepare), settings.autoPrepareEnabled, onChange = settings::updateAutoPrepare)
    }
    SettingsGroup(stringResource(R.string.offline_auto_title), footer = stringResource(R.string.offline_auto_footer)) {
        ToggleRow(stringResource(R.string.offline_auto_enable), settings.autoMapEnabled) { settings.updateAutoMap(it, settings.autoMapRadiusKm, settings.autoMapCellular) }
        if (settings.autoMapEnabled) {
            GroupDivider(16.dp)
            GroupContent {
                SettingChoice(stringResource(R.string.offline_auto_radius), AutoPrefetch.RADIUS_OPTIONS_KM, settings.autoMapRadiusKm, { "$it km" }) { settings.updateAutoMap(true, it, settings.autoMapCellular) }
            }
            GroupDivider(16.dp)
            ToggleRow(stringResource(R.string.offline_auto_cellular), settings.autoMapCellular, subtitle = stringResource(R.string.offline_auto_cellular_hint)) { settings.updateAutoMap(true, settings.autoMapRadiusKm, it) }
        }
    }
}
