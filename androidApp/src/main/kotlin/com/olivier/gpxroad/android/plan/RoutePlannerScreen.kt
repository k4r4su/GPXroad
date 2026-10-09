package com.olivier.gpxroad.android.plan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.DistanceUnit
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.sync.SharedBlockageSync
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.ride.RideMapStyle
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.android.settings.SettingChoice
import com.olivier.gpxroad.android.ui.GroupContent
import com.olivier.gpxroad.android.ui.GroupDivider
import com.olivier.gpxroad.android.ui.ScreenHeader
import com.olivier.gpxroad.android.ui.SettingsGroup
import com.olivier.gpxroad.android.ui.ToggleRow
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.plan.AccessVerdict
import com.olivier.gpxroad.shared.plan.PlanOptions
import com.olivier.gpxroad.shared.plan.PlanVehicle
import com.olivier.gpxroad.shared.recording.GpxWriter
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val ROUTE_SOURCE = "plan-route"
private const val POINTS_SOURCE = "plan-points"
private const val FLAGGED_SOURCE = "plan-flagged"
private val ROUTE_COLOR = android.graphics.Color.rgb(255, 140, 0)
private val START_COLOR = android.graphics.Color.rgb(52, 168, 83)
private val END_COLOR = android.graphics.Color.rgb(217, 48, 37)
private val MID_COLOR = android.graphics.Color.rgb(0, 122, 255)
private val FLAG_VERIFY_COLOR = android.graphics.Color.rgb(242, 199, 0)
private val FLAG_RESTRICTED_COLOR = android.graphics.Color.rgb(242, 77, 26)
private val FLAG_FORBIDDEN_COLOR = android.graphics.Color.rgb(217, 31, 31)

/**
 * « Créer un itinéraire » (06/10, `RoutePlannerView` iOS) : on pose des points sur la carte, Valhalla les relie par les routes
 * existantes (autoroutes évitées par défaut), puis l'itinéraire est enregistré comme une trace de la Bibliothèque.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(library: TrackLibrary, servers: ServerSettings, routing: RoutingClient, overpass: OverpassClient, sync: SharedBlockageSync, start: LatLon?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember {
        PlannerModel(routing, TrackAccessChecker(routing, overpass), { servers.valhalla }, { minLat, minLon, maxLat, maxLon -> sync.exclusionLocations(minLat, minLon, maxLat, maxLon) }, scope)
    }
    var showFlagged by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    var routeName by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        mapView.onCreate(null)
        lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { loadedMap: MapLibreMap ->
            map = loadedMap
            loadedMap.uiSettings.isRotateGesturesEnabled = false
            loadedMap.uiSettings.isTiltGesturesEnabled = false
            val center = start ?: LatLon(46.603354, 1.888334)
            loadedMap.cameraPosition = CameraPosition.Builder().target(LatLng(center.latitude, center.longitude)).zoom(9.0).build()
            // Un tap pose un point, sauf à moins de 30 dp d'un point existant (il sert alors seulement à le repérer).
            loadedMap.addOnMapClickListener { latLng ->
                val tap = loadedMap.projection.toScreenLocation(latLng)
                val tooClose = model.waypoints.any {
                    val existing = loadedMap.projection.toScreenLocation(LatLng(it.latitude, it.longitude))
                    hypot((existing.x - tap.x).toDouble(), (existing.y - tap.y).toDouble()) < 30 * mapView.resources.displayMetrics.density
                }
                if (!tooClose) model.add(LatLon(latLng.latitude, latLng.longitude))
                true
            }
            loadedMap.setStyle(Style.Builder().fromJson(RideMapStyle.vector(context) ?: RideMapStyle.raster())) { loaded ->
                loaded.addSource(GeoJsonSource(ROUTE_SOURCE))
                loaded.addSource(GeoJsonSource(POINTS_SOURCE))
                loaded.addSource(GeoJsonSource(FLAGGED_SOURCE))
                loaded.addLayer(LineLayer("plan-route-line", ROUTE_SOURCE).withProperties(PropertyFactory.lineColor(ROUTE_COLOR), PropertyFactory.lineWidth(5f), PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")))
                // Pistes à vérifier / interdites : par-dessus l'itinéraire, en couleur selon le verdict.
                loaded.addLayer(
                    LineLayer("plan-flagged-line", FLAGGED_SOURCE).withProperties(
                        PropertyFactory.lineWidth(8f), PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
                        PropertyFactory.lineColor(Expression.match(Expression.get("verdict"), Expression.color(FLAG_VERIFY_COLOR), Expression.stop("forbidden", Expression.color(FLAG_FORBIDDEN_COLOR)), Expression.stop("restricted", Expression.color(FLAG_RESTRICTED_COLOR)))),
                    ),
                )
                loaded.addLayer(
                    CircleLayer("plan-points-circle", POINTS_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f), PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                        PropertyFactory.circleColor(Expression.match(Expression.get("kind"), Expression.color(MID_COLOR), Expression.stop("start", Expression.color(START_COLOR)), Expression.stop("end", Expression.color(END_COLOR)))),
                    ),
                )
                style = loaded
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    // Carte à jour : tracé orange, points colorés (départ vert, arrivée rouge).
    LaunchedEffect(style, model.waypoints, model.route, model.flagged) {
        val loaded = style ?: return@LaunchedEffect
        val line = model.route?.points?.takeIf { it.size > 1 }?.let { Feature.fromGeometry(LineString.fromLngLats(it.map { p -> Point.fromLngLat(p.longitude, p.latitude) })) }
        loaded.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(line)))
        val flaggedFeatures = model.flagged.filter { it.coordinates.size > 1 }.map { segment ->
            Feature.fromGeometry(LineString.fromLngLats(segment.coordinates.map { Point.fromLngLat(it.longitude, it.latitude) })).also {
                it.addStringProperty("verdict", when (segment.verdict) { AccessVerdict.FORBIDDEN -> "forbidden"; AccessVerdict.RESTRICTED -> "restricted"; else -> "to_verify" })
            }
        }
        loaded.getSourceAs<GeoJsonSource>(FLAGGED_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(flaggedFeatures))
        val last = model.waypoints.lastIndex
        val features = model.waypoints.mapIndexed { index, p ->
            Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).also { it.addStringProperty("kind", if (index == 0) "start" else if (index == last) "end" else "mid") }
        }
        loaded.getSourceAs<GeoJsonSource>(POINTS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    LaunchedEffect(model.fitToken) {
        val points = model.route?.points?.takeIf { it.size > 1 } ?: return@LaunchedEffect
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
        val density = mapView.resources.displayMetrics.density
        map?.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, (40 * density).toInt(), (60 * density).toInt(), (40 * density).toInt(), (260 * density).toInt()))
    }
    // Fenêtre plein écran : icônes de la barre d'état lisibles sur le fond clair (sombres en mode nuit).
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    LaunchedEffect(view, dark) {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            ScreenHeader(stringResource(R.string.plan_title), onBack = onClose)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                PlannerPanel(
                    model = model,
                    onFlagged = { showFlagged = true },
                    onOptions = { showOptions = true },
                    onSave = {
                        routeName = defaultName(context)
                        showName = true
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
    }

    if (showFlagged) {
        var pending by remember { mutableStateOf<FlaggedSegment?>(null) }
        // Ouverte en grand d'emblée : en paysage (tablette), la moitié de l'écran ne montre pas toute la liste.
        ModalBottomSheet(onDismissRequest = { showFlagged = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            FlaggedSegmentsSheet(model.flagged) { pending = it }
        }
        pending?.let { segment ->
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.plan_report_title)) },
                text = { Text(stringResource(R.string.plan_report_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        segment.midpoint?.let { sync.reportForbidden(it, segment.wayIds.firstOrNull()) }
                        pending = null
                        showFlagged = false
                        model.recompute()   // le chemin signalé est maintenant évité
                    }) { Text(stringResource(R.string.plan_report_forbidden), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.plan_cancel)) } },
            )
        }
    }
    if (showOptions) {
        // Ouverte en grand d'emblée et défilable : en paysage (tablette), la moitié de l'écran coupait « ferries » et « pistes ».
        ModalBottomSheet(onDismissRequest = { showOptions = false; model.optionsChanged() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            PlannerOptionsSheet(model.options, model::updateOptions)
        }
    }
    if (showName) {
        AlertDialog(
            onDismissRequest = { showName = false },
            title = { Text(stringResource(R.string.plan_name_title)) },
            text = { OutlinedTextField(value = routeName, onValueChange = { routeName = it }, label = { Text(stringResource(R.string.plan_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    showName = false
                    val route = model.route ?: return@TextButton
                    val name = routeName.trim().ifEmpty { defaultName(context) }
                    val gpx = GpxWriter.writeRoute(name, route.points, context.getString(R.string.plan_comment))
                    when (library.importText(gpx, fallbackName = name)) {
                        is TrackLibrary.ImportResult.Success -> {
                            android.widget.Toast.makeText(context, context.getString(R.string.plan_saved), android.widget.Toast.LENGTH_LONG).show()
                            onClose()
                        }
                        is TrackLibrary.ImportResult.Failure -> saveError = context.getString(R.string.plan_import_failed)
                    }
                }) { Text(stringResource(R.string.plan_save)) }
            },
            dismissButton = { TextButton(onClick = { showName = false }) { Text(stringResource(R.string.plan_cancel)) } },
        )
    }
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.plan_save_failed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text(stringResource(R.string.plan_ok)) } },
        )
    }
}

private fun defaultName(context: android.content.Context): String =
    context.getString(R.string.plan_default_name, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date()))

@Composable
private fun waypointLabel(index: Int, count: Int): String = when (index) {
    0 -> stringResource(R.string.plan_start)
    count - 1 -> stringResource(R.string.plan_finish)
    else -> stringResource(R.string.plan_stop, index)
}

private fun duration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return if (minutes >= 60) "%d h %02d".format(minutes / 60, minutes % 60) else "$minutes min"
}

@Composable
private fun PlannerPanel(model: PlannerModel, onFlagged: () -> Unit, onOptions: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.waypoints.isEmpty()) {
                Text(stringResource(R.string.plan_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.status == PlannerModel.Status.COMPUTING) {
                        CircularProgressIndicator(Modifier.padding(end = 2.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.plan_computing))
                    } else model.route?.let { route ->
                        Text(RoadbookTexts.distance(route.distanceMeters, DistanceUnit.KM), style = MaterialTheme.typography.titleMedium)
                        if (route.durationSeconds > 0) Text("· " + duration(route.durationSeconds), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AccessLine(model, onFlagged)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.waypoints.forEachIndexed { index, _ ->
                        AssistChip(
                            onClick = { model.remove(index) },
                            label = { Text(waypointLabel(index, model.waypoints.size)) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.plan_remove_point, waypointLabel(index, model.waypoints.size)), modifier = Modifier.padding(2.dp)) },
                        )
                    }
                }
            }
            when (model.status) {
                PlannerModel.Status.NOT_CONFIGURED -> Text(stringResource(R.string.plan_not_configured), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                PlannerModel.Status.FAILED -> Text(stringResource(R.string.plan_no_route), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = model::removeLast, enabled = model.waypoints.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.plan_undo)) }
                IconButton(onClick = model::clear, enabled = model.waypoints.isNotEmpty()) { Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.plan_clear), tint = MaterialTheme.colorScheme.error) }
                IconButton(onClick = onOptions) { Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.plan_options_title)) }
                Box(Modifier.weight(1f))
                Button(onClick = onSave, enabled = model.canSave) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.plan_save), maxLines = 1)
                }
            }
        }
    }
}

/** Fenêtre d'options : le but est de s'amuser, donc autoroutes et péages évités par défaut. */
@Composable
private fun PlannerOptionsSheet(options: PlanOptions, onChange: (PlanOptions) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.plan_options_title), style = MaterialTheme.typography.titleLarge)
        SettingsGroup {
            GroupContent {
                SettingChoice(
                    stringResource(R.string.plan_vehicle), PlanVehicle.entries.toList(), options.vehicle,
                    { stringResource(when (it) { PlanVehicle.MOTORCYCLE -> R.string.plan_motorcycle; PlanVehicle.CAR -> R.string.plan_car; PlanVehicle.BICYCLE -> R.string.plan_bicycle }) },
                ) { onChange(options.copy(vehicle = it)) }
            }
        }
        SettingsGroup(title = stringResource(R.string.plan_avoid), footer = stringResource(R.string.plan_options_footer)) {
            if (options.vehicle != PlanVehicle.BICYCLE) {
                ToggleRow(stringResource(R.string.plan_avoid_highways), options.avoidHighways) { onChange(options.copy(avoidHighways = it)) }
                GroupDivider(16.dp)
                ToggleRow(stringResource(R.string.plan_avoid_tolls), options.avoidTolls) { onChange(options.copy(avoidTolls = it)) }
                GroupDivider(16.dp)
            }
            ToggleRow(stringResource(R.string.plan_avoid_ferries), options.avoidFerries) { onChange(options.copy(avoidFerries = it)) }
            if (options.vehicle != PlanVehicle.BICYCLE) {
                GroupDivider(16.dp)
                ToggleRow(stringResource(R.string.plan_allow_tracks), options.allowTracks) { onChange(options.copy(allowTracks = it)) }
            }
        }
    }
}

/** Pistes : vérification de l'accès (seulement si « Autoriser les pistes » est actif). */
@Composable
private fun AccessLine(model: PlannerModel, onFlagged: () -> Unit) {
    when (model.accessStatus) {
        PlannerModel.AccessStatus.CHECKING -> Text(stringResource(R.string.plan_access_checking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlannerModel.AccessStatus.UNAVAILABLE -> Text(stringResource(R.string.plan_access_unavailable), style = MaterialTheme.typography.bodySmall, color = Color(0xFFF29900))
        PlannerModel.AccessStatus.CHECKED ->
            if (model.flagged.isEmpty()) {
                Text(stringResource(R.string.plan_access_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = onFlagged, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        stringResource(R.string.plan_access_flagged, model.flagged.size, RoadbookTexts.distance(model.flagged.sumOf { it.lengthMeters }, DistanceUnit.KM)),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color(0xFFF29900),
                    )
                }
            }
        PlannerModel.AccessStatus.IDLE -> Unit
    }
}

/** Liste des portions à vérifier : on peut en signaler une comme interdite (les prochains itinéraires l'évitent). */
@Composable
private fun FlaggedSegmentsSheet(segments: List<FlaggedSegment>, onReport: (FlaggedSegment) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.plan_flagged_title), style = MaterialTheme.typography.titleLarge)
        segments.forEach { segment ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(verdictColor(segment.verdict), CircleShape))
                    Text(segment.name ?: stringResource(R.string.plan_unnamed), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(RoadbookTexts.distance(segment.lengthMeters, DistanceUnit.KM), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(when (segment.verdict) { AccessVerdict.FORBIDDEN -> R.string.plan_verdict_forbidden; AccessVerdict.RESTRICTED -> R.string.plan_verdict_restricted; else -> R.string.plan_verdict_verify }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { onReport(segment) }) { Text(stringResource(R.string.plan_report_forbidden), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun verdictColor(verdict: AccessVerdict): Color = when (verdict) {
    AccessVerdict.FORBIDDEN -> Color(0xFFD91F1F)
    AccessVerdict.RESTRICTED -> Color(0xFFF24D1A)
    else -> Color(0xFFF2C700)
}
