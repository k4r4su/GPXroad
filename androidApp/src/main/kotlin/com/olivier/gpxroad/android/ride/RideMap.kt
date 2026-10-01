package com.olivier.gpxroad.android.ride

import android.graphics.Bitmap
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import com.olivier.gpxroad.shared.map.MapTheme
import com.olivier.gpxroad.shared.ride.DirectionChevrons
import com.olivier.gpxroad.shared.ride.RideCameraConstants
import com.olivier.gpxroad.shared.ride.SlopeWarning
import com.olivier.gpxroad.shared.ride.RideCameraMath
import com.olivier.gpxroad.shared.roadbook.RoadbookManeuver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

private val POSITION_COLOR = android.graphics.Color.rgb(0, 122, 255)
/** Chemin de reprise : bleu système en pointillé (comme l'iPhone), distinct de la trace. */
private val REJOIN_COLOR = android.graphics.Color.rgb(0, 122, 255)

private const val TRACK_SOURCE = "track-source"
private const val CHEVRON_SOURCE = "chevron-source"
private const val SLOPE_SOURCE = "slope-source"
private const val REJOIN_SOURCE = "rejoin-source"
private const val REJOIN_PIN_SOURCE = "rejoin-pin-source"
private const val NAV_SOURCE = "nav-source"
private const val GOTO_SOURCE = "goto-source"
private const val DETOUR_SOURCE = "detour-source"
private const val BLOCKAGE_SOURCE = "blockage-source"
/** Détour « Chemin bloqué » : rouge pointillé, comme l'iPhone. */
private val DETOUR_COLOR = android.graphics.Color.rgb(255, 59, 48)
/** « Aller à » riche : restant bleu, parcouru gris atténué ; simple : pointillé cyan (comme l'iPhone). */
private val NAV_COLOR = android.graphics.Color.rgb(10, 132, 255)
private val NAV_TRAVELED_COLOR = android.graphics.Color.argb(170, 142, 142, 147)
private val GOTO_COLOR = android.graphics.Color.rgb(50, 173, 230)
private const val PINS_SOURCE = "pins-source"
private const val POSITION_SOURCE = "position-source"
private const val CHEVRON_ICON = "chevron-icon"
private const val SLOPE_UP_ICON = "slope-up-icon"
private const val SLOPE_DOWN_ICON = "slope-down-icon"

/**
 * Apparence de la trace (`TraceAppearance` iOS) : couleur et épaisseur réglables, contour noir
 * 3 dp plus large ; le chemin de reprise est 1,5 fois plus épais que la trace.
 */
data class TraceStyle(val color: Int, val widthDp: Float) {
    val casingWidth: Float get() = widthDp + 3f
    val rejoinWidth: Float get() = widthDp * 1.5f
}

/** Chemin de reprise dessiné (pointillé bleu) et son point d'arrivée sur la trace. */
data class RejoinOverlay(val route: List<LatLon>, val target: LatLon)

/**
 * Carte du Ride (équivalent de `RideMapLibreView` iOS) : trace, chevrons de sens (plus espacés au
 * dézoom, jamais masqués), panneaux de pente, chemin de reprise, épingles des virages (mêmes
 * pictogrammes que le Road Book), position, caméra qui suit — cap en haut (point ancré aux 3/4 de
 * la hauteur) ou nord en haut (point au centre). Un geste suspend le suivi 5 s.
 */
@Composable
fun RideMap(
    trackKey: String?,
    trackPoints: List<LatLon>,
    maneuvers: List<RoadbookManeuver>,
    traceStyle: TraceStyle,
    mapTheme: MapTheme,
    /** Des zones hors ligne existent : sans réseau, garder le style vectoriel (tuiles gardées). */
    offlineAvailable: Boolean,
    /** Position du point en cap-en-haut (fraction de la hauteur depuis le haut). */
    anchorY: Double,
    chevronSpacingMeters: Double,
    slopeWarnings: List<SlopeWarning>,
    rejoin: RejoinOverlay?,
    navRoute: List<LatLon>?,
    navTraveledCount: Int,
    goToRoute: List<LatLon>?,
    detourRoute: List<LatLon>?,
    /** Points bloqués partagés : position et « ancien » (> 90 jours, estompé). */
    blockages: List<Pair<LatLon, Boolean>>,
    location: Location?,
    northUp: Boolean,
    camera: RideCameraState,
    modifier: Modifier = Modifier,
    /** Tap sur la carte : coordonnée et tolérance (m) équivalant à 36 dp au zoom courant. */
    onMapTap: (LatLon, Double) -> Unit = { _, _ -> },
    onMapLongTap: (LatLon) -> Unit = {},
) {
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnMapLongTap by rememberUpdatedState(onMapLongTap)
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
    /** Palier de zoom des chevrons (espacement minimal imposé par le zoom) : les chevrons ne sont recalculés que quand il change. */
    var chevronZoomFloor by remember { mutableStateOf(0.0) }

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
            // Logo et attribution (obligatoire) en bas au centre : jamais sous le compteur ni les boutons.
            loaded.uiSettings.logoGravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            loaded.uiSettings.attributionGravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            val margin = (8 * context.resources.displayMetrics.density).toInt()
            loaded.uiSettings.setLogoMargins(0, 0, (48 * context.resources.displayMetrics.density).toInt(), margin)
            loaded.uiSettings.setAttributionMargins((48 * context.resources.displayMetrics.density).toInt(), 0, 0, margin)
            loaded.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) camera.onUserGesture()
            }
            loaded.addOnMapClickListener { point ->
                val metersPerPixel = loaded.projection.getMetersPerPixelAtLatitude(point.latitude)
                currentOnMapTap(LatLon(point.latitude, point.longitude), metersPerPixel * RESUME_TAP_TOLERANCE_DP * density.density)
                false
            }
            loaded.addOnMapLongClickListener { point ->
                currentOnMapLongTap(LatLon(point.latitude, point.longitude))
                true
            }
            loaded.addOnCameraIdleListener {
                chevronZoomFloor = DirectionChevrons.adaptiveSpacingMeters(0.0, loaded.cameraPosition.zoom)
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

    // Style du thème (Réglages > Carte) : le changer recharge le style ; les calques de l'app sont
    // recréés et toutes les données ci-dessous réappliquées (elles dépendent de `style`).
    LaunchedEffect(map, mapTheme, offlineAvailable) {
        val loadedMap = map ?: return@LaunchedEffect
        style = null
        val online = Http.isOnline(context)
        val json = withContext(Dispatchers.IO) { RideMapStyle.forTheme(context, mapTheme, online, offlineAvailable) }
        loadedMap.setStyle(Style.Builder().fromJson(json)) { loadedStyle ->
            addOverlayLayers(loadedStyle, traceStyle, density)
            style = loadedStyle
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

    // Apparence réglable (Réglages > Apparence) appliquée sans recharger le style.
    LaunchedEffect(style, traceStyle) {
        val loadedStyle = style ?: return@LaunchedEffect
        (loadedStyle.getLayer("track-layer-casing") as? LineLayer)?.setProperties(PropertyFactory.lineWidth(traceStyle.casingWidth))
        (loadedStyle.getLayer("track-layer") as? LineLayer)?.setProperties(PropertyFactory.lineColor(traceStyle.color), PropertyFactory.lineWidth(traceStyle.widthDp))
        (loadedStyle.getLayer("rejoin-layer-casing") as? LineLayer)?.setProperties(PropertyFactory.lineWidth(traceStyle.casingWidth))
        (loadedStyle.getLayer("rejoin-layer") as? LineLayer)?.setProperties(PropertyFactory.lineWidth(traceStyle.rejoinWidth))
        loadedStyle.addImage(CHEVRON_ICON, chevronBitmap(traceStyle.color, density))
    }

    // Chevrons : seulement quand la trace ou l'espacement effectif change (jamais à chaque image).
    LaunchedEffect(style, trackKey, chevronSpacingMeters, chevronZoomFloor) {
        val source = style?.getSourceAs<GeoJsonSource>(CHEVRON_SOURCE) ?: return@LaunchedEffect
        val zoom = map?.cameraPosition?.zoom ?: 15.0
        val spacing = DirectionChevrons.adaptiveSpacingMeters(chevronSpacingMeters, zoom)
        val features = withContext(Dispatchers.Default) {
            DirectionChevrons.chevrons(trackPoints, spacing).map { chevron ->
                Feature.fromGeometry(Point.fromLngLat(chevron.coordinate.longitude, chevron.coordinate.latitude)).apply {
                    addNumberProperty("bearing", chevron.bearingDegrees)
                }
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Panneaux de pente (déjà calculés par l'appelant, seulement quand la trace ou le seuil change).
    LaunchedEffect(style, slopeWarnings) {
        val source = style?.getSourceAs<GeoJsonSource>(SLOPE_SOURCE) ?: return@LaunchedEffect
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                slopeWarnings.map { warning ->
                    Feature.fromGeometry(Point.fromLngLat(warning.coordinate.longitude, warning.coordinate.latitude)).apply {
                        addStringProperty("icon", if (warning.isClimbing) SLOPE_UP_ICON else SLOPE_DOWN_ICON)
                        addStringProperty("label", "${warning.roundedPercent} %")
                    }
                },
            ),
        )
    }

    // Chemin de reprise (pointillé bleu) et son point d'arrivée.
    LaunchedEffect(style, rejoin) {
        val loadedStyle = style ?: return@LaunchedEffect
        val route = rejoin?.route.orEmpty()
        loadedStyle.getSourceAs<GeoJsonSource>(REJOIN_SOURCE)?.setGeoJson(
            if (route.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude, it.latitude) }))),
        )
        loadedStyle.getSourceAs<GeoJsonSource>(REJOIN_PIN_SOURCE)?.setGeoJson(
            rejoin?.target?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
                ?: FeatureCollection.fromFeatures(emptyList()),
        )
    }

    // « Aller à » riche : deux morceaux (parcouru / restant) dans la même source, filtrés par couche.
    LaunchedEffect(style, navRoute, navTraveledCount) {
        val source = style?.getSourceAs<GeoJsonSource>(NAV_SOURCE) ?: return@LaunchedEffect
        val points = navRoute.orEmpty()
        if (points.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        fun line(part: List<LatLon>, traveled: Boolean) = Feature.fromGeometry(LineString.fromLngLats(part.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
            addBooleanProperty("traveled", traveled)
        }
        val count = navTraveledCount.coerceIn(0, points.size)
        val features = if (count <= 1 || count >= points.size) listOf(line(points, false))
        else listOf(line(points.subList(0, count), true), line(points.subList(count - 1, points.size), false))
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, detourRoute) {
        val source = style?.getSourceAs<GeoJsonSource>(DETOUR_SOURCE) ?: return@LaunchedEffect
        val points = detourRoute.orEmpty()
        source.setGeoJson(
            if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }))),
        )
    }

    LaunchedEffect(style, blockages) {
        val source = style?.getSourceAs<GeoJsonSource>(BLOCKAGE_SOURCE) ?: return@LaunchedEffect
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                blockages.map { (point, faded) ->
                    Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply { addNumberProperty("opacity", if (faded) 0.45 else 1.0) }
                },
            ),
        )
    }

    LaunchedEffect(style, goToRoute) {
        val source = style?.getSourceAs<GeoJsonSource>(GOTO_SOURCE) ?: return@LaunchedEffect
        val points = goToRoute.orEmpty()
        source.setGeoJson(
            if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }))),
        )
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
    LaunchedEffect(style, location, northUp, camera.commandToken, camera.effectiveDistanceMeters, anchorY, camera.focus) {
        val loadedMap = map ?: return@LaunchedEffect
        val loadedStyle = style ?: return@LaunchedEffect
        val position = location ?: camera.focus?.let { f -> android.location.Location("focus").apply { latitude = f.latitude; longitude = f.longitude } } ?: return@LaunchedEffect
        location?.let { loadedStyle.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.setGeoJson(Point.fromLngLat(it.longitude, it.latitude)) }
        // Pendant les 5 s qui suivent un geste, seule une commande (+/-, recentrer) bouge la caméra ;
        // un élément du Road Book montré sur la carte suspend le suivi jusqu'à « Me recentrer ».
        val command = camera.commandToken
        val focus = camera.focus
        if ((camera.isManualOverrideActive() || focus != null) && command == appliedCommand) return@LaunchedEffect
        appliedCommand = command
        val heightDp = mapView.height / density.density
        if (heightDp <= 0) return@LaunchedEffect
        if (focus != null) {
            val focusZoom = RideCameraMath.zoomLevel(camera.effectiveDistanceMeters, focus.latitude, heightDp.toDouble())
            loadedMap.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(focus.latitude, focus.longitude)).zoom(focusZoom).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build(),
                ),
                CAMERA_ANIMATION_MILLIS,
            )
            return@LaunchedEffect
        }
        val zoom = RideCameraMath.zoomLevel(camera.effectiveDistanceMeters, position.latitude, heightDp.toDouble())
        // Cap en haut : le point à 3/4 de la hauteur (marge haute = (2f - 1) × hauteur).
        val topPadding = if (northUp) 0.0 else (2 * anchorY - 1) * mapView.height
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
/** Tolérance du tap sur la trace (« Reprendre la trace ici »), comme les 36 points de l'iPhone. */
private const val RESUME_TAP_TOLERANCE_DP = 36
private const val CAMERA_ANIMATION_MILLIS = 900

private fun addOverlayLayers(style: Style, trace: TraceStyle, density: Density) {
    style.addSource(GeoJsonSource(TRACK_SOURCE))
    style.addLayer(
        LineLayer("track-layer-casing", TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.BLACK),
            PropertyFactory.lineWidth(trace.casingWidth),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("track-layer", TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(trace.color),
            PropertyFactory.lineWidth(trace.widthDp),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    // Chevrons : suivent la carte (cap de la trace), contrairement aux panneaux de pente.
    style.addImage(CHEVRON_ICON, chevronBitmap(trace.color, density))
    style.addSource(GeoJsonSource(CHEVRON_SOURCE))
    style.addLayer(
        SymbolLayer("chevron-layer", CHEVRON_SOURCE).withProperties(
            PropertyFactory.iconImage(CHEVRON_ICON),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
    style.addSource(GeoJsonSource(NAV_SOURCE))
    style.addLayer(
        LineLayer("nav-layer-casing", NAV_SOURCE).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.BLACK),
            PropertyFactory.lineWidth(trace.casingWidth),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("nav-layer-traveled", NAV_SOURCE).withProperties(
            PropertyFactory.lineColor(NAV_TRAVELED_COLOR),
            PropertyFactory.lineWidth(trace.widthDp),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(Expression.eq(Expression.get("traveled"), Expression.literal(true))),
    )
    style.addLayer(
        LineLayer("nav-layer", NAV_SOURCE).withProperties(
            PropertyFactory.lineColor(NAV_COLOR),
            PropertyFactory.lineWidth(trace.widthDp),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(Expression.eq(Expression.get("traveled"), Expression.literal(false))),
    )
    style.addSource(GeoJsonSource(GOTO_SOURCE))
    style.addLayer(
        LineLayer("goto-layer", GOTO_SOURCE).withProperties(
            PropertyFactory.lineColor(GOTO_COLOR),
            PropertyFactory.lineWidth(trace.widthDp * 1.2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1f)),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addSource(GeoJsonSource(DETOUR_SOURCE))
    style.addLayer(
        LineLayer("detour-layer", DETOUR_SOURCE).withProperties(
            PropertyFactory.lineColor(DETOUR_COLOR),
            PropertyFactory.lineWidth(trace.rejoinWidth),
            PropertyFactory.lineDasharray(arrayOf(10f / trace.rejoinWidth, 8f / trace.rejoinWidth)),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addSource(GeoJsonSource(BLOCKAGE_SOURCE))
    style.addLayer(
        CircleLayer("blockage-layer", BLOCKAGE_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(DETOUR_COLOR),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleOpacity(Expression.get("opacity")),
            PropertyFactory.circleStrokeOpacity(Expression.get("opacity")),
        ),
    )
    style.addSource(GeoJsonSource(REJOIN_SOURCE))
    style.addLayer(
        LineLayer("rejoin-layer-casing", REJOIN_SOURCE).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.BLACK),
            PropertyFactory.lineWidth(trace.casingWidth),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("rejoin-layer", REJOIN_SOURCE).withProperties(
            PropertyFactory.lineColor(REJOIN_COLOR),
            PropertyFactory.lineWidth(trace.rejoinWidth),
            PropertyFactory.lineDasharray(arrayOf(2f, 1f)),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addSource(GeoJsonSource(REJOIN_PIN_SOURCE))
    style.addLayer(
        CircleLayer("rejoin-pin-layer", REJOIN_PIN_SOURCE).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(REJOIN_COLOR),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    // Panneaux de pente : restent droits à l'écran, comme un vrai panneau au bord de la route.
    style.addImage(SLOPE_UP_ICON, slopeBitmap(true, density))
    style.addImage(SLOPE_DOWN_ICON, slopeBitmap(false, density))
    style.addSource(GeoJsonSource(SLOPE_SOURCE))
    style.addLayer(
        SymbolLayer("slope-layer", SLOPE_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
            PropertyFactory.textColor(android.graphics.Color.BLACK),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
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

/** Petit chevron plein pointant vers le haut (nord) : `icon-rotate` = cap de la trace. Couleur de la trace, liseré sombre. */
private fun chevronBitmap(color: Int, density: Density): Bitmap {
    val side = with(density) { 22.dp.toPx() }
    val image = ImageBitmap(side.toInt(), side.toInt())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(side, side)) {
        val path = Path().apply {
            moveTo(side / 2, side * 0.18f)
            lineTo(side * 0.82f, side * 0.78f)
            lineTo(side * 0.18f, side * 0.78f)
            close()
        }
        drawPath(path, Color(color))
        drawPath(path, Color.Black.copy(alpha = 0.55f), style = Stroke(width = with(density) { 1.5.dp.toPx() }))
    }
    return image.asAndroidBitmap()
}

/** Panneau de pente (triangle jaune cerclé de noir, rampe montante ou descendante), comme l'iPhone. */
private fun slopeBitmap(isClimbing: Boolean, density: Density): Bitmap {
    val side = with(density) { 32.dp.toPx() }
    val image = ImageBitmap(side.toInt(), side.toInt())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(side, side)) {
        val triangle = Path().apply {
            moveTo(side / 2, side * 0.06f)
            lineTo(side * 0.95f, side * 0.92f)
            lineTo(side * 0.05f, side * 0.92f)
            close()
        }
        drawPath(triangle, Color(0xFFFFCC00))
        drawPath(triangle, Color.Black, style = Stroke(width = with(density) { 2.5.dp.toPx() }))
        val (start, end) = if (isClimbing) Offset(side * 0.26f, side * 0.78f) to Offset(side * 0.74f, side * 0.40f)
        else Offset(side * 0.26f, side * 0.40f) to Offset(side * 0.74f, side * 0.78f)
        drawLine(Color.Black, start, end, strokeWidth = with(density) { 3.dp.toPx() }, cap = StrokeCap.Round)
    }
    return image.asAndroidBitmap()
}
