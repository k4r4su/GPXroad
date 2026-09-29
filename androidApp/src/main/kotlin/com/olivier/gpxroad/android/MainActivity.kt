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
import com.olivier.gpxroad.android.roadbook.RoadbookScreen
import com.olivier.gpxroad.android.settings.SettingsScreen
import com.olivier.gpxroad.android.ui.GPXroadTheme
import com.olivier.gpxroad.android.ui.LibraryIcon

/** Onglets de l'app Android (session 1 de la migration) — Ride, Aller à, etc. arrivent ensuite. */
enum class AppTab { ROADBOOK, LIBRARY, SETTINGS }

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
        setContent {
            GPXroadTheme {
                GPXroadApp(library, settings, location, incomingGpx) { incomingGpx = null }
            }
        }
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

@Composable
private fun GPXroadApp(library: TrackLibrary, settings: AppSettings, location: LocationTracker, incomingGpx: Uri?, onImportHandled: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (library.activeTrackId == null) AppTab.LIBRARY else AppTab.ROADBOOK) }
    if (incomingGpx != null) tab = AppTab.LIBRARY
    val items = remember {
        listOf(
            Triple(AppTab.ROADBOOK, R.string.tab_roadbook, Icons.AutoMirrored.Filled.List),
            Triple(AppTab.LIBRARY, R.string.tab_library, LibraryIcon),
            Triple(AppTab.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
        )
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { (item, label, icon: ImageVector) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.ROADBOOK -> RoadbookScreen(library, settings, location) { tab = AppTab.LIBRARY }
                AppTab.LIBRARY -> LibraryScreen(library, settings, incomingGpx, onImportHandled)
                AppTab.SETTINGS -> SettingsScreen(settings)
            }
        }
    }
}
