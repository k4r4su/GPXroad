package com.olivier.gpxroad.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olivier.gpxroad.shared.map.MapTheme
import com.olivier.gpxroad.shared.ride.DirectionChevrons
import com.olivier.gpxroad.shared.ride.RideCameraConstants
import com.olivier.gpxroad.shared.ride.ZoomPreset
import com.olivier.gpxroad.shared.ride.SlopeAnalyzer
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.recording.RecordingConstants
import com.olivier.gpxroad.shared.recording.RecordingDensity
import com.olivier.gpxroad.shared.roadbook.RoadbookConstants
import com.olivier.gpxroad.shared.roadbook.RoadbookSettings
import com.olivier.gpxroad.shared.roadbook.TierThresholds

enum class DistanceUnit { KM, MI }

/** Mode de lecture du Road Book (comme iOS) : liste papier, ou prochain élément en grand avec le GPS. */
enum class ReadingMode { LIST, GPS_ASSISTED }

/**
 * Réglages persistés (équivalent de `RideSettingsStore` iOS, partie Road Book) — valeurs par défaut
 * = celles du module partagé (`RoadbookConstants`), mêmes que l'iPhone.
 */
class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var distanceUnit by mutableStateOf(enumValueOrNull<DistanceUnit>(preferences.getString("distanceUnit", null)) ?: DistanceUnit.KM)
        private set
    var readingMode by mutableStateOf(enumValueOrNull<ReadingMode>(preferences.getString("readingMode", null)) ?: ReadingMode.GPS_ASSISTED)
        private set

    var lightThreshold by mutableDoubleStateOf(preferences.getDouble("light", RoadbookConstants.LIGHT_THRESHOLD_DEGREES_DEFAULT))
        private set
    var markedThreshold by mutableDoubleStateOf(preferences.getDouble("marked", RoadbookConstants.MARKED_THRESHOLD_DEGREES_DEFAULT))
        private set
    var hardThreshold by mutableDoubleStateOf(preferences.getDouble("hard", RoadbookConstants.HARD_THRESHOLD_DEGREES_DEFAULT))
        private set
    var veryHardThreshold by mutableDoubleStateOf(preferences.getDouble("veryHard", RoadbookConstants.VERY_HARD_THRESHOLD_DEGREES_DEFAULT))
        private set
    var windowBefore by mutableDoubleStateOf(preferences.getDouble("windowBefore", RoadbookConstants.WINDOW_BEFORE_METERS_DEFAULT))
        private set
    var windowAfter by mutableDoubleStateOf(preferences.getDouble("windowAfter", RoadbookConstants.WINDOW_AFTER_METERS_DEFAULT))
        private set
    var mergeDistance by mutableDoubleStateOf(preferences.getDouble("merge", RoadbookConstants.TURN_MERGE_MIN_DISTANCE_METERS_DEFAULT))
        private set

    /** Catégories de repères affichées (Réglages > Repères du Road Book) ; défaut : toutes sauf « Autres ». */
    var landmarkCategories by mutableStateOf(
        preferences.getStringSet("landmarkCategories", null)?.mapNotNull { LandmarkCategory.fromKey(it) }?.toSet()
            ?: LandmarkCategory.entries.filter { it.isEnabledByDefault }.toSet(),
    )
        private set

    fun updateLandmarkCategory(category: LandmarkCategory, enabled: Boolean) {
        landmarkCategories = if (enabled) landmarkCategories + category else landmarkCategories - category
        preferences.edit().putStringSet("landmarkCategories", landmarkCategories.map { it.key }.toSet()).apply()
    }

    /** Densité des points enregistrés (Réglages > Enregistrement de la sortie, comme l'iPhone). */
    var recordingDensity by mutableStateOf(enumValueOrNull<RecordingDensity>(preferences.getString("recordingDensity", null)) ?: RecordingDensity.PRECIS)
        private set
    /** Sauvegardes de secours conservées dans « Sorties non enregistrées ». */
    var unsavedRetention by mutableStateOf(
        preferences.getInt("unsavedRetention", RecordingConstants.UNSAVED_RETENTION_DEFAULT).takeIf { it in RecordingConstants.UNSAVED_RETENTION_OPTIONS }
            ?: RecordingConstants.UNSAVED_RETENTION_DEFAULT,
    )
        private set

    fun updateRecordingDensity(value: RecordingDensity) {
        recordingDensity = value
        preferences.edit().putString("recordingDensity", value.name).apply()
    }

    fun updateUnsavedRetention(value: Int) {
        unsavedRetention = value
        preferences.edit().putInt("unsavedRetention", value).apply()
    }

    /** Carte du Ride : nord en haut (sinon cap en haut, le défaut de l'iPhone). */
    var rideNorthUp by mutableStateOf(preferences.getBoolean("rideNorthUp", false))
        private set

    fun updateRideNorthUp(value: Boolean) {
        rideNorthUp = value
        preferences.edit().putBoolean("rideNorthUp", value).apply()
    }

    // MARK: Ride (mêmes défauts que l'iPhone)

    var traceColor by mutableStateOf(enumValueOrNull<TraceColor>(preferences.getString("traceColor", null)) ?: TraceColor.ORANGE)
        private set
    var traceWidth by mutableStateOf(enumValueOrNull<TraceWidth>(preferences.getString("traceWidth", null)) ?: TraceWidth.EPAIS)
        private set
    var chevronSpacing by mutableDoubleStateOf(preferences.getDouble("chevronSpacing", DirectionChevrons.SPACING_METERS_DEFAULT))
        private set
    var slopeWarningsEnabled by mutableStateOf(preferences.getBoolean("slopeWarnings", true))
        private set
    var slopeThreshold by mutableDoubleStateOf(preferences.getDouble("slopeThreshold", SlopeAnalyzer.THRESHOLD_PERCENT_DEFAULT))
        private set
    var controlsSide by mutableStateOf(enumValueOrNull<ControlsSide>(preferences.getString("controlsSide", null)) ?: ControlsSide.RIGHT)
        private set
    var keepScreenAwake by mutableStateOf(preferences.getBoolean("keepScreenAwake", true))
        private set

    // MARK: carte et caméra du Ride (mêmes défauts et plages que l'iPhone)

    var mapTheme by mutableStateOf(enumValueOrNull<MapTheme>(preferences.getString("mapTheme", null)) ?: MapTheme.STANDARD)
        private set
    var autoZoomEnabled by mutableStateOf(preferences.getBoolean("autoZoom", true))
        private set
    var zoomPreset by mutableStateOf(enumValueOrNull<ZoomPreset>(preferences.getString("zoomPreset", null)) ?: ZoomPreset.NORMAL)
        private set
    var autoZoomMin by mutableDoubleStateOf(preferences.getDouble("autoZoomMin", RideCameraConstants.AUTO_ZOOM_MIN_METERS_DEFAULT))
        private set
    var autoZoomMax by mutableDoubleStateOf(preferences.getDouble("autoZoomMax", RideCameraConstants.AUTO_ZOOM_MAX_METERS_DEFAULT))
        private set
    var defaultZoom by mutableDoubleStateOf(preferences.getDouble("defaultZoom", RideCameraConstants.DEFAULT_RIDE_ZOOM_METERS))
        private set
    var anchorY by mutableDoubleStateOf(preferences.getDouble("anchorY", RideCameraConstants.ANCHOR_Y_FRACTION_DEFAULT))
        private set

    fun updateMapTheme(value: MapTheme) {
        mapTheme = value
        preferences.edit().putString("mapTheme", value.name).apply()
    }

    /** Bornes toujours ordonnées (serré ≤ large), comme l'iPhone. */
    fun updateAutoZoom(enabled: Boolean, preset: ZoomPreset, min: Double, max: Double) {
        autoZoomEnabled = enabled
        zoomPreset = preset
        autoZoomMin = minOf(min, max)
        autoZoomMax = maxOf(min, max)
        preferences.edit().putBoolean("autoZoom", enabled).putString("zoomPreset", preset.name)
            .putDouble("autoZoomMin", autoZoomMin).putDouble("autoZoomMax", autoZoomMax).apply()
    }

    fun updateDefaultZoom(value: Double) {
        defaultZoom = value
        preferences.edit().putDouble("defaultZoom", value).apply()
    }

    fun updateAnchorY(value: Double) {
        anchorY = value
        preferences.edit().putDouble("anchorY", value).apply()
    }

    /** « Aller à » : annonces vocales (activées par défaut, comme l'iPhone) et leur volume. */
    var voiceEnabled by mutableStateOf(preferences.getBoolean("voiceEnabled", true))
        private set
    var voiceVolume by mutableDoubleStateOf(preferences.getDouble("voiceVolume", 1.0))
        private set

    fun updateVoice(enabled: Boolean, volume: Double) {
        voiceEnabled = enabled
        voiceVolume = volume
        preferences.edit().putBoolean("voiceEnabled", enabled).putDouble("voiceVolume", volume).apply()
    }

    fun updateTraceColor(value: TraceColor) {
        traceColor = value
        preferences.edit().putString("traceColor", value.name).apply()
    }

    fun updateTraceWidth(value: TraceWidth) {
        traceWidth = value
        preferences.edit().putString("traceWidth", value.name).apply()
    }

    fun updateChevronSpacing(value: Double) {
        chevronSpacing = value
        preferences.edit().putDouble("chevronSpacing", value).apply()
    }

    fun updateSlopeWarnings(enabled: Boolean, threshold: Double) {
        slopeWarningsEnabled = enabled
        slopeThreshold = threshold
        preferences.edit().putBoolean("slopeWarnings", enabled).putDouble("slopeThreshold", threshold).apply()
    }

    fun updateControlsSide(value: ControlsSide) {
        controlsSide = value
        preferences.edit().putString("controlsSide", value.name).apply()
    }

    fun updateKeepScreenAwake(value: Boolean) {
        keepScreenAwake = value
        preferences.edit().putBoolean("keepScreenAwake", value).apply()
    }

    val roadbookSettings: RoadbookSettings
        get() = RoadbookSettings(
            windowBeforeMeters = windowBefore,
            windowAfterMeters = windowAfter,
            thresholds = TierThresholds(lightThreshold, markedThreshold, hardThreshold, veryHardThreshold),
            mergeMinDistanceMeters = mergeDistance,
        )

    fun updateDistanceUnit(value: DistanceUnit) {
        distanceUnit = value
        preferences.edit().putString("distanceUnit", value.name).apply()
    }

    fun updateReadingMode(value: ReadingMode) {
        readingMode = value
        preferences.edit().putString("readingMode", value.name).apply()
    }

    /** Seuils d'angle : toujours croissants (léger < prononcé < fort < très serré). */
    fun updateThresholds(light: Double, marked: Double, hard: Double, veryHard: Double) {
        lightThreshold = light
        markedThreshold = maxOf(marked, light + 5)
        hardThreshold = maxOf(hard, markedThreshold + 5)
        veryHardThreshold = maxOf(veryHard, hardThreshold + 5)
        preferences.edit()
            .putDouble("light", lightThreshold).putDouble("marked", markedThreshold)
            .putDouble("hard", hardThreshold).putDouble("veryHard", veryHardThreshold).apply()
    }

    fun updateWindows(before: Double, after: Double, merge: Double) {
        windowBefore = before
        windowAfter = after
        mergeDistance = merge
        preferences.edit().putDouble("windowBefore", before).putDouble("windowAfter", after).putDouble("merge", merge).apply()
    }

    fun resetRoadbook() {
        updateThresholds(
            RoadbookConstants.LIGHT_THRESHOLD_DEGREES_DEFAULT, RoadbookConstants.MARKED_THRESHOLD_DEGREES_DEFAULT,
            RoadbookConstants.HARD_THRESHOLD_DEGREES_DEFAULT, RoadbookConstants.VERY_HARD_THRESHOLD_DEGREES_DEFAULT,
        )
        updateWindows(
            RoadbookConstants.WINDOW_BEFORE_METERS_DEFAULT, RoadbookConstants.WINDOW_AFTER_METERS_DEFAULT,
            RoadbookConstants.TURN_MERGE_MIN_DISTANCE_METERS_DEFAULT,
        )
    }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? = name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

private fun android.content.SharedPreferences.getDouble(key: String, default: Double): Double =
    if (contains(key)) java.lang.Double.longBitsToDouble(getLong(key, 0)) else default

private fun android.content.SharedPreferences.Editor.putDouble(key: String, value: Double): android.content.SharedPreferences.Editor =
    putLong(key, java.lang.Double.doubleToRawLongBits(value))
