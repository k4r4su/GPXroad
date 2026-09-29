package com.olivier.gpxroad.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.AppSettings
import com.olivier.gpxroad.android.net.OverpassClient
import com.olivier.gpxroad.android.net.OverpassKind
import com.olivier.gpxroad.android.net.RoutingClient
import com.olivier.gpxroad.android.net.RoutingProvider
import com.olivier.gpxroad.android.net.ServerSettings
import com.olivier.gpxroad.android.net.ValhallaConfiguration
import com.olivier.gpxroad.android.roadbook.RoadbookTexts
import com.olivier.gpxroad.shared.roadbook.LandmarkCategory
import com.olivier.gpxroad.shared.roadbook.LandmarkConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Réglages > Repères du Road Book : un interrupteur par catégorie, par famille (comme l'iPhone). */
@Composable
fun LandmarkCategoriesSection(settings: AppSettings) {
    val resources = LocalContext.current.resources
    Text(stringResource(R.string.settings_landmarks_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LandmarkConstants.GROUP_PRIORITY.forEach { group ->
        Text(RoadbookTexts.groupLabel(resources, group), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LandmarkCategory.entries.filter { it.group == group }.forEach { category ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${RoadbookTexts.emoji(category)}  ${RoadbookTexts.categoryLabel(resources, category)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = category in settings.landmarkCategories, onCheckedChange = { settings.updateLandmarkCategory(category, it) })
            }
        }
    }
}

/** Réglages > Avancé > Routage Valhalla (mêmes champs que `ValhallaSettingsView` iOS). */
@Composable
fun ValhallaSection(servers: ServerSettings, routing: RoutingClient) {
    var enabled by remember { mutableStateOf(servers.valhallaEnabled) }
    var endpoint by remember { mutableStateOf(servers.valhallaEndpoint) }
    var username by remember { mutableStateOf(servers.valhallaUsername) }
    var password by remember { mutableStateOf(servers.valhallaPassword) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val testing = stringResource(R.string.server_testing)
    val invalid = stringResource(R.string.server_invalid)
    val httpsRequired = stringResource(R.string.server_https_required)
    val connected = stringResource(R.string.server_connected, "%s")

    Toggle(stringResource(R.string.valhalla_enable), enabled) { enabled = it }
    Field(stringResource(R.string.valhalla_url), endpoint, KeyboardType.Uri) { endpoint = it }
    Field(stringResource(R.string.server_username), username) { username = it }
    Field(stringResource(R.string.server_password), password, secret = true) { password = it }
    Text(stringResource(R.string.valhalla_usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { servers.updateValhalla(enabled, endpoint, username, password) }) { Text(stringResource(R.string.server_save)) }
        OutlinedButton(onClick = {
            val url = endpoint.trim()
            status = when {
                url.isEmpty() -> invalid
                !url.startsWith("https://") -> httpsRequired
                else -> testing
            }
            if (status != testing) return@OutlinedButton
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching { connected.format(routing.status(ValhallaConfiguration(url, username, password))) }.getOrElse { it.message ?: invalid }
                }
            }
        }) { Text(stringResource(R.string.server_test)) }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val last = routing.lastSuccess
    Text(
        stringResource(R.string.server_last_routing) + " : " + (last?.let {
            stringResource(if (it.first == RoutingProvider.VALHALLA) R.string.routing_valhalla else R.string.routing_osrm) + " · " + time(it.second)
        } ?: stringResource(R.string.server_none)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Réglages > Avancé > Serveur Overpass (mêmes champs que `OverpassSettingsView` iOS). */
@Composable
fun OverpassSection(servers: ServerSettings, overpass: OverpassClient) {
    var enabled by remember { mutableStateOf(servers.overpassEnabled) }
    var lan by remember { mutableStateOf(servers.overpassLanEndpoint) }
    var endpoint by remember { mutableStateOf(servers.overpassEndpoint) }
    var username by remember { mutableStateOf(servers.overpassUsername) }
    var password by remember { mutableStateOf(servers.overpassPassword) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val testing = stringResource(R.string.server_testing)
    val invalid = stringResource(R.string.server_invalid)
    val httpsRequired = stringResource(R.string.server_https_required)
    val connected = stringResource(R.string.server_connected, "%s")
    val lanLabel = stringResource(R.string.server_lan)
    val ownLabel = stringResource(R.string.server_own)

    Toggle(stringResource(R.string.overpass_enable), enabled) { enabled = it }
    Text(stringResource(R.string.overpass_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Field(stringResource(R.string.overpass_lan), lan, KeyboardType.Uri) { lan = it }
    Field(stringResource(R.string.overpass_public), endpoint, KeyboardType.Uri) { endpoint = it }
    Field(stringResource(R.string.server_username), username) { username = it }
    Field(stringResource(R.string.server_password), password, secret = true) { password = it }
    Text(stringResource(R.string.server_credentials_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { servers.updateOverpass(enabled, endpoint, lan, username, password) }) { Text(stringResource(R.string.server_save)) }
        OutlinedButton(onClick = {
            val own = endpoint.trim()
            val home = lan.trim()
            if (own.isNotEmpty() && !own.startsWith("https://")) {
                status = httpsRequired
                return@OutlinedButton
            }
            if (own.isEmpty() && home.isEmpty()) {
                status = invalid
                return@OutlinedButton
            }
            status = testing
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    listOfNotNull(
                        home.takeIf { it.isNotEmpty() }?.let { lanLabel + " : " + (runCatching { connected.format(overpass.status(it, "", "")) }.getOrElse { e -> e.message ?: invalid }) },
                        own.takeIf { it.isNotEmpty() }?.let { ownLabel + " : " + (runCatching { connected.format(overpass.status(it, username, password)) }.getOrElse { e -> e.message ?: invalid }) },
                    ).joinToString("\n")
                }
            }
        }) { Text(stringResource(R.string.server_test)) }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val last = overpass.lastSuccess
    Text(
        stringResource(R.string.server_last_overpass) + " : " + (last?.let {
            stringResource(
                when (it.first) {
                    OverpassKind.LAN -> R.string.server_lan
                    OverpassKind.OWN -> R.string.server_own
                    OverpassKind.PUBLIC_FALLBACK -> R.string.server_public
                },
            ) + " · " + time(it.second)
        } ?: stringResource(R.string.server_none)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun time(millis: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, onChange: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
