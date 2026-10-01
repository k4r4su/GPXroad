package com.olivier.gpxroad.android.offline

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.olivier.gpxroad.android.ride.RideMapStyle
import com.olivier.gpxroad.android.ui.ScreenHeader
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.offline.OfflineArea
import com.olivier.gpxroad.shared.offline.OfflineConstants
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.roundToInt

private const val CIRCLE_SOURCE = "circle-source"
private val CIRCLE_COLOR = android.graphics.Color.rgb(0, 122, 255)

/**
 * Zone hors ligne choisie sur la carte (`CircleRegionPickerView` iOS) : on déplace la carte, le
 * cercle reste au centre ; rayon et détail réglables, estimation avant téléchargement.
 */
@Composable
fun RegionPicker(offline: OfflineMaps, start: LatLon?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var center by remember { mutableStateOf(start ?: LatLon(46.603354, 1.888334)) }
    var radiusKm by remember { mutableStateOf(OfflineConstants.CIRCLE_DEFAULT_RADIUS_KM) }
    var maxZoom by remember { mutableStateOf(OfflineConstants.VECTOR_MAX_ZOOM) }
    var style by remember { mutableStateOf<Style?>(null) }
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
        mapView.getMapAsync { map: MapLibreMap ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.cameraPosition = CameraPosition.Builder().target(LatLng(center.latitude, center.longitude)).zoom(9.0).build()
            map.addOnCameraMoveListener { map.cameraPosition.target?.let { center = LatLon(it.latitude, it.longitude) } }
            map.setStyle(Style.Builder().fromJson(RideMapStyle.vector(context) ?: RideMapStyle.raster())) { loaded ->
                loaded.addSource(GeoJsonSource(CIRCLE_SOURCE))
                loaded.addLayer(FillLayer("circle-fill", CIRCLE_SOURCE).withProperties(PropertyFactory.fillColor(CIRCLE_COLOR), PropertyFactory.fillOpacity(0.12f)))
                loaded.addLayer(LineLayer("circle-line", CIRCLE_SOURCE).withProperties(PropertyFactory.lineColor(CIRCLE_COLOR), PropertyFactory.lineWidth(3f)))
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
    LaunchedEffect(style, center, radiusKm) {
        val ring = OfflineArea.circle(center, radiusKm * 1000).map { Point.fromLngLat(it.longitude, it.latitude) }
        style?.getSourceAs<GeoJsonSource>(CIRCLE_SOURCE)?.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
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
    val tiles = remember(center, radiusKm, maxZoom) { OfflineArea.circleTileCount(center, radiusKm * 1000, OfflineConstants.REGION_MIN_ZOOM, maxZoom) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            ScreenHeader(stringResource(R.string.offline_pick_title), onBack = onClose)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.offline_radius, radiusKm.roundToInt()), style = MaterialTheme.typography.bodyLarge)
                Slider(value = radiusKm.toFloat(), onValueChange = { radiusKm = OfflineArea.clampRadiusKm(it.roundToInt().toDouble()) }, valueRange = 1f..200f)
                Text(stringResource(R.string.offline_max_zoom, maxZoom), style = MaterialTheme.typography.bodyLarge)
                Slider(value = maxZoom.toFloat(), onValueChange = { maxZoom = it.roundToInt() }, valueRange = 10f..14f, steps = 3)
                Text(
                    tiles?.let { stringResource(R.string.offline_estimate, it.toInt(), Formatter.formatShortFileSize(context, OfflineArea.estimatedBytes(it))) }
                        ?: stringResource(R.string.offline_too_large),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val name = stringResource(R.string.offline_circle_name, "%.3f, %.3f".format(center.latitude, center.longitude), radiusKm.roundToInt())
                Button(
                    onClick = { offline.downloadCircle(name, center, radiusKm * 1000, maxZoom); onClose() },
                    enabled = tiles != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp).align(Alignment.CenterHorizontally),
                ) { Text(stringResource(R.string.offline_download)) }
            }
        }
    }
}
