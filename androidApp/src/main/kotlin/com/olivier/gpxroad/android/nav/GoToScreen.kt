package com.olivier.gpxroad.android.nav

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.net.NominatimClient
import com.olivier.gpxroad.android.net.Place
import com.olivier.gpxroad.android.ui.WorkIcon
import com.olivier.gpxroad.shared.LatLon
import com.olivier.gpxroad.shared.nav.GoToProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun profileLabel(profile: GoToProfile): String = stringResource(
    when (profile) {
        GoToProfile.ROUTE -> R.string.nav_profile_route
        GoToProfile.OFFROAD -> R.string.nav_profile_offroad
        GoToProfile.MIXED -> R.string.nav_profile_mixed
    },
)

/**
 * Onglet « Aller à » (`NavDestinationSearchView` iOS) : Domicile/Travail, recherches récentes,
 * recherche Nominatim (3 caractères, 0,4 s après la frappe) ; chaque résultat se lance en
 * Itinéraire, Piste ou Mixte, puis le Ride s'ouvre. Appui long : le définir comme Domicile/Travail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GoToScreen(places: NavPlaces, nominatim: NominatimClient, position: LatLon?, onGo: (Place, GoToProfile) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 3) {
            results = emptyList()
            error = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        val found = withContext(Dispatchers.IO) { runCatching { nominatim.search(trimmed, position, Locale.getDefault().toLanguageTag()) } }
        results = found.getOrDefault(emptyList())
        error = when {
            found.isFailure -> R.string.nav_search_failed
            results.isEmpty() -> R.string.nav_no_result
            else -> null
        }
        searching = false
    }

    fun go(place: Place, profile: GoToProfile) {
        places.record(place)
        onGo(place, profile)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.tab_goto), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.nav_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = RoundedCornerShape(28.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FavoriteTile(stringResource(R.string.nav_home), Icons.Filled.Home, places.home, position, Modifier.weight(1f), onGo = { go(it, GoToProfile.ROUTE) }, onSet = places::updateHome, setLabel = R.string.nav_use_position_home)
                FavoriteTile(stringResource(R.string.nav_work), WorkIcon, places.work, position, Modifier.weight(1f), onGo = { go(it, GoToProfile.ROUTE) }, onSet = places::updateWork, setLabel = R.string.nav_use_position_work)
            }
        }
        if (query.isBlank() && places.history.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.nav_recent)) }
            items(places.history, key = { "h-" + it.label }) { place ->
                PlaceRow(place, places, onGo = ::go, onForget = { places.forget(place) })
            }
        }
        if (searching) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        error?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(results, key = { "r-" + it.label + it.coordinate }) { place -> PlaceRow(place, places, onGo = ::go, onForget = null) }
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.nav_favorites_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.nav_rich_needs_valhalla), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
}

/** Domicile / Travail : tap = Itinéraire ; appui long = ma position, ou retirer. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(title: String, icon: ImageVector, place: Place?, position: LatLon?, modifier: Modifier, onGo: (Place) -> Unit, onSet: (Place?) -> Unit, setLabel: Int) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .background(
                    if (place != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .combinedClickable(onClick = { place?.let(onGo) }, onLongClick = { menu = true })
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                place?.label ?: stringResource(R.string.nav_favorite_unset),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (position != null) DropdownMenuItem(text = { Text(stringResource(setLabel)) }, onClick = { menu = false; onSet(Place(title, position)) })
            if (place != null) DropdownMenuItem(text = { Text(stringResource(R.string.nav_clear_favorite)) }, onClick = { menu = false; onSet(null) })
        }
    }
}

/** Résultat : libellé sur 2 lignes, puis Itinéraire / Piste / Mixte ; appui long = Domicile/Travail. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(place: Place, places: NavPlaces, onGo: (Place, GoToProfile) -> Unit, onForget: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = { onGo(place, GoToProfile.ROUTE) }, onLongClick = { menu = true }).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(place.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(start = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoToProfile.entries.forEach { profile ->
                    AssistChip(onClick = { onGo(place, profile) }, label = { Text(profileLabel(profile)) })
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.nav_set_home)) }, onClick = { menu = false; places.updateHome(place) })
            DropdownMenuItem(text = { Text(stringResource(R.string.nav_set_work)) }, onClick = { menu = false; places.updateWork(place) })
            onForget?.let { DropdownMenuItem(text = { Text(stringResource(R.string.library_delete)) }, onClick = { menu = false; it() }) }
        }
    }
    HorizontalDivider()
}
