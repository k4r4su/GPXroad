package com.olivier.gpxroad.android.ride

import android.graphics.Bitmap
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.olivier.gpxroad.android.net.Http
import com.olivier.gpxroad.android.roadbook.drawManeuverPictogram
import com.olivier.gpxroad.android.ui.Accent
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.ride.RideCameraConstants
import com.olivier.gpxroad.shared.ride.RideCameraMath
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Couleurs de la trace, comme `TraceAppearance` iOS (orange système, contour noir, épais). */
private val TRACK_COLOR = android.graphics.Color.rgb(255, 149, 0)
private const val TRACK_WIDTH = 6f
private const val TRACK_CASING_WIDTH = TRACK_WIDTH + 3f
private val POSITION_COLOR = android.graphics.Color.rgb(0, 122, 255)

private const val TRACK_SOURCE = "track-source"
private const val PINS_SOURCE = "pins-source"
private const val POSITION_SOURCE = "position-source"

/**
 * Carte du Ride (équivalent de `RideMapLibreView` iOS) : trace, épingles des virages (mêmes
 * pictogrammes que le Road Book), position, caméra qui suit — cap en haut (point ancré aux 3/4 de
 * la hauteur) ou nord en haut (point au centre). Un geste suspend le suivi 5 s.
 */
@Composable
fun RideMap(
    trackKey: String?,
    trackPoints: List<LatLon>,
    maneuvers: List<RoadbookManeuver>,
    location: Location?,
    northUp: Boolean,
    camera: RideCameraState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var appliedCommand by remember { mutableStateOf(-1) }

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
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        mapView.getMapAsync { loaded ->
            loaded.uiSettings.isTiltGesturesEnabled = false
            loaded.uiSettings.isCompassEnabled = false
            loaded.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) camera.onUserGesture()
            }
            val json = (if (Http.isOnline(context)) RideMapStyle.vector(context) else null) ?: RideMapStyle.raster()
            loaded.setStyle(Style.Builder().fromJson(json)) { loadedStyle ->
                addOverlayLayers(loadedStyle)
                style = loadedStyle
            }
            map = loaded
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Trace : redessinée seulement quand le parcours change.
    LaunchedEffect(style, trackKey) {
        val source = style?.getSourceAs<GeoJsonSource>(TRACK_SOURCE) ?: return@LaunchedEffect
        if (trackPoints.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(trackPoints.map { Point.fromLngLat(it.longitude, it.latitude) })))
        }
    }

    // Épingles : une image par virage (le pictogramme d'un rond-point dépend de ses branches).
    LaunchedEffect(style, maneuvers) {
        val loadedStyle = style ?: return@LaunchedEffect
        val size = with(density) { PIN_SIZE_DP.dp.toPx() }
        val features = maneuvers.mapIndexed { index, maneuver ->
            val name = "pin-$trackKey-$index"
            if (loadedStyle.getImage(name) == null) loadedStyle.addImage(name, pinBitmap(maneuver, size, density, measurer))
            Feature.fromGeometry(Point.fromLngLat(maneuver.checkpoint.coordinate.longitude, maneuver.checkpoint.coordinate.latitude)).apply {
                addStringProperty("icon", name)
            }
        }
        loadedStyle.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Position et caméra, à chaque fix (et à chaque commande +/- / recentrage / orientation).
    LaunchedEffect(style, location, northUp, camera.commandToken, camera.effectiveDistanceMeters) {
        val loadedMap = map ?: return@LaunchedEffect
        val loadedStyle = style ?: return@LaunchedEffect
        val position = location ?: return@LaunchedEffect
        loadedStyle.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.setGeoJson(Point.fromLngLat(position.longitude, position.latitude))
        // Pendant les 5 s qui suivent un geste, seule une commande (+/-, recentrer) bouge la caméra.
        val command = camera.commandToken
        if (camera.isManualOverrideActive() && command == appliedCommand) return@LaunchedEffect
        appliedCommand = command
        val heightDp = mapView.height / density.density
        if (heightDp <= 0) return@LaunchedEffect
        val zoom = RideCameraMath.zoomLevel(camera.effectiveDistanceMeters, position.latitude, heightDp.toDouble())
        // Cap en haut : le point à 3/4 de la hauteur (marge haute = (2f - 1) × hauteur).
        val topPadding = if (northUp) 0.0 else (2 * RideCameraConstants.ANCHOR_Y_FRACTION_DEFAULT - 1) * mapView.height
        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(position.latitude, position.longitude))
            .zoom(zoom)
            .bearing(if (northUp) 0.0 else camera.courseDegrees ?: loadedMap.cameraPosition.bearing)
            .tilt(0.0)
            .padding(0.0, topPadding, 0.0, 0.0)
            .build()
        loadedMap.easeCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), CAMERA_ANIMATION_MILLIS)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val PIN_SIZE_DP = 40
private const val CAMERA_ANIMATION_MILLIS = 900

private fun addOverlayLayers(style: Style) {
    style.addSource(GeoJsonSource(TRACK_SOURCE))
    style.addLayer(
        LineLayer("track-layer-casing", TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.BLACK),
            PropertyFactory.lineWidth(TRACK_CASING_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("track-layer", TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(TRACK_COLOR),
            PropertyFactory.lineWidth(TRACK_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addSource(GeoJsonSource(PINS_SOURCE))
    style.addLayer(
        SymbolLayer("pins-layer", PINS_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
        ),
    )
    style.addSource(GeoJsonSource(POSITION_SOURCE))
    style.addLayer(
        CircleLayer("position-layer", POSITION_SOURCE).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(POSITION_COLOR),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

/** Épingle d'un virage : disque blanc cerclé de noir, pictogramme du Road Book dedans. */
private fun pinBitmap(maneuver: RoadbookManeuver, sizePx: Float, density: Density, measurer: TextMeasurer): Bitmap {
    val side = sizePx.toInt()
    val image = ImageBitmap(side, side)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(sizePx, sizePx)) {
        val radius = sizePx / 2
        drawCircle(Color.White, radius - 1, Offset(radius, radius))
        drawCircle(Color.Black, radius - 2, Offset(radius, radius), style = Stroke(width = 3f))
        inset(sizePx * 0.16f) { drawManeuverPictogram(maneuver.checkpoint, Accent, Color.DarkGray, measurer) }
    }
    return image.asAndroidBitmap()
}
