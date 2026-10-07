package com.olivier.gpxroad.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.data.TrackLibrary
import com.olivier.gpxroad.android.library.LibraryScreen
import com.olivier.gpxroad.android.location.LocationTracker
import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.recording.RideRecorder
import com.olivier.gpxroad.android.roadbook.data.RejoinController
import com.olivier.gpxroad.android.roadbook.data.RoadbookData
import android.content.Context
import com.olivier.gpxroad.android.data.AppLanguage
import com.olivier.gpxroad.android.onboarding.OnboardingScreen
import com.olivier.gpxroad.android.tutorial.TutorialScreen
import com.olivier.gpxroad.android.nav.GoToScreen
import com.olivier.gpxroad.android.nav.NavDestination
import com.olivier.gpxroad.android.nav.NavPlaces
import com.olivier.gpxroad.android.nav.NavSession
import com.olivier.gpxroad.android.net.NominatimClient
import com.olivier.gpxroad.android.sync.SharedBlockageSync
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.olivier.gpxroad.android.data.BatteryMonitor
import com.olivier.gpxroad.android.offline.OfflineMaps
import com.olivier.gpxroad.android.offline.TrackPreparer
import com.olivier.gpxroad.shared.LatLon
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.ride.RideCameraState
import com.olivier.gpxroad.android.ride.RideSession
import com.olivier.gpxroad.android.ride.RideScreen
import com.olivier.gpxroad.android.roadbook.RoadbookScreen
import com.olivier.gpxroad.android.settings.SettingsScreen
import com.olivier.gpxroad.android.ui.GPXroadTheme
import com.olivier.gpxroad.android.ui.LibraryIcon

/** Onglets de l'app Android (migration en cours) — Aller à arrivera ensuite, dans l'ordre de l'iPhone. */
enum class AppTab { RIDE, GOTO, ROADBOOK, LIBRARY, SETTINGS }

/**
 * Racine de l'app Android. Les services (bibliothèque, réglages, GPS) vivent le temps de l'activité ;
 * la rotation ne recrée pas l'activité (`configChanges` du manifeste), Compose s'adapte seul.
 * Un GPX ouvert depuis une autre app (« Ouvrir avec ») est importé dans la Bibliothèque.
 */
class MainActivity : ComponentActivity() {
    private var incomingGpx by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingGpx = gpxUri(intent)
        val library = TrackLibrary(applicationContext)
        val settings = AppSettings(applicationContext)
        val location = LocationTracker(applicationContext)
        val servers = ServerSettings(applicationContext)
        val overpass = OverpassClient(applicationContext, servers)
        val routing = RoutingClient()
        val nav = NavSession(applicationContext, routing, overpass)
        val roadbook = RoadbookData(applicationContext, servers, overpass, routing)
        val offline = OfflineMaps(applicationContext)
        battery = BatteryMonitor(applicationContext)
        val preparer = TrackPreparer(applicationContext, settings, servers, roadbook, offline) { battery.isLongRideActive(settings) }
        val services = AppServices(library, settings, location, servers, overpass, routing, roadbook, RejoinController(routing), RejoinController(routing), RideSession(routing), nav, NavPlaces(applicationContext), NominatimClient(), SharedBlockageSync(applicationContext), offline, RideCameraState(settings), RideRecorder.get(applicationContext), preparer, battery)
        setContent {
            GPXroadTheme {
                var showOnboarding by remember { mutableStateOf(!settings.hasSeenOnboarding && library.tracks.isEmpty() && incomingGpx == null) }
                if (showOnboarding) {
                    OnboardingScreen(library) {
                        settings.markOnboardingSeen()
                        showOnboarding = false
                    }
                } else {
                    GPXroadApp(services, incomingGpx, onLanguageChanged = { recreate() }) { incomingGpx = null }
                }
            }
        }
    }

    private lateinit var battery: BatteryMonitor

    override fun onDestroy() {
        if (::battery.isInitialized) battery.stop()
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gpxUri(intent)?.let { incomingGpx = it }
    }

    private fun gpxUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }
}

/** Services de l'app, créés une fois par activité. */
private class AppServices(
    val library: TrackLibrary,
    val settings: AppSettings,
    val location: LocationTracker,
    val servers: ServerSettings,
    val overpass: OverpassClient,
    val routing: RoutingClient,
    val roadbook: RoadbookData,
    val rejoin: RejoinController,
    /** Reprise automatique du Ride (100 m pendant 2 s), distincte de celle du Road Book. */
    val rideRejoin: RejoinController,
    val rideSession: RideSession,
    /** « Aller à » : guidage, Domicile/Travail et recherches récentes, recherche Nominatim. */
    val nav: NavSession,
    val places: NavPlaces,
    val nominatim: NominatimClient,
    /** Points bloqués partagés (serveur auto-hébergé, désactivé tant que l'adresse est vide). */
    val blockageSync: SharedBlockageSync,
    /** Zones de carte gardées hors ligne (MapLibre). */
    val offline: OfflineMaps,
    val rideCamera: RideCameraState,
    /** Enregistrement de la sortie : unique pour tout le processus, indépendant de l'activité. */
    val recorder: RideRecorder,
    /** Préparation hors ligne de la trace active (carte du couloir, repères, recalage, ronds-points). */
    val preparer: TrackPreparer,
    /** Niveau de batterie : mode longue sortie et alerte pendant l'enregistrement. */
    val battery: BatteryMonitor,
)

@Composable
private fun GPXroadApp(services: AppServices, incomingGpx: Uri?, onLanguageChanged: () -> Unit, onImportHandled: () -> Unit) {
    var tutorial by rememberSaveable { mutableStateOf(false) }
    if (tutorial) {
        TutorialScreen { tutorial = false }
        return
    }
    val library = services.library
    val settings = services.settings
    var tab by rememberSaveable { mutableStateOf(if (library.activeTrackId == null) AppTab.LIBRARY else AppTab.RIDE) }
    if (incomingGpx != null) tab = AppTab.LIBRARY
    // Préparation de la trace active : dès que le réseau est bon (réessayée chaque minute, le réseau n'étant pas observé).
    LaunchedEffect(library.activeTrackId, library.activeTrack?.traversalKey, settings.autoPrepareEnabled) {
        delay(5_000)   // laisse MapLibre relire les zones déjà téléchargées
        while (true) {
            library.activeTrack?.let { services.preparer.prepare(it) }
            delay(60_000)
        }
    }
    val items = remember {
        listOf(
            Triple(AppTab.RIDE, R.string.tab_ride, Icons.Rounded.Navigation),
            Triple(AppTab.GOTO, R.string.tab_goto, Icons.Rounded.Search),
            Triple(AppTab.ROADBOOK, R.string.tab_roadbook, Icons.AutoMirrored.Rounded.FormatListBulleted),
            Triple(AppTab.LIBRARY, R.string.tab_library, Icons.Rounded.CollectionsBookmark),
            Triple(AppTab.SETTINGS, R.string.tab_settings, Icons.Rounded.Settings),
        )
    }
    Scaffold(
        bottomBar = {
            // Barre d'onglets : surface claire, indicateur orange léger, libellés toujours visibles.
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                items.forEach { (item, label, icon: ImageVector) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.RIDE -> RideScreen(library, settings, services.servers, services.roadbook, services.rideCamera, services.location, services.recorder, services.rideRejoin, services.rideSession, services.nav, services.blockageSync, services.offline, services.battery) { tab = AppTab.LIBRARY }
                AppTab.GOTO -> GoToScreen(services.places, services.nominatim, services.location.location?.let { LatLon(it.latitude, it.longitude) }) { place, profile ->
                    services.rideSession.cancelResume()
                    services.nav.start(NavDestination(place.label, place.coordinate, profile), services.location.location, services.servers.valhalla)
                    tab = AppTab.RIDE
                }
                AppTab.ROADBOOK -> RoadbookScreen(library, settings, services.servers, services.roadbook, services.rejoin, services.location, onOpenLibrary = { tab = AppTab.LIBRARY }) { point ->
                    services.rideCamera.focusOn(point)
                    tab = AppTab.RIDE
                }
                AppTab.LIBRARY -> LibraryScreen(library, settings, services.recorder, services.offline, services.preparer, incomingGpx, onImportHandled)
                AppTab.SETTINGS -> SettingsScreen(settings, services.servers, services.overpass, services.routing, services.blockageSync, services.offline, services.location.location?.let { LatLon(it.latitude, it.longitude) }, onOpenTutorial = { tutorial = true }, onLanguageChanged = onLanguageChanged)
            }
        }
    }
}
