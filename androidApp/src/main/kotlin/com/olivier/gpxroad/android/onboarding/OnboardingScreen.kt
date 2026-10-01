package com.olivier.gpxroad.android.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.TrackLibrary
import kotlinx.coroutines.launch

/**
 * Accueil au premier lancement (`OnboardingView` iOS), 3 pages : importer une trace, charger la
 * trace d'exemple, rappel de sécurité. Ne revient jamais une fois passé.
 */
@Composable
fun OnboardingScreen(library: TrackLibrary, onFinish: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { library.import(it) }
        onFinish()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.onb_skip)) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> Page(stringResource(R.string.onb_import_title), stringResource(R.string.onb_import_message), stringResource(R.string.onb_import_action)) {
                        picker.launch(arrayOf("*/*"))
                    }
                    1 -> Page(stringResource(R.string.onb_sample_title), stringResource(R.string.onb_sample_message), stringResource(R.string.onb_sample_action)) {
                        val text = runCatching { context.assets.open("sample-trail.gpx").bufferedReader().use { it.readText() } }.getOrNull()
                        val result = text?.let { library.importText(it, activateIfNone = true) }
                        if (result is TrackLibrary.ImportResult.Success) library.rename(result.entry.id, context.getString(R.string.onb_sample_name))
                        onFinish()
                    }
                    else -> Page(stringResource(R.string.onb_safety_title), stringResource(R.string.onb_safety_message), stringResource(R.string.onb_go), onFinish)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { index ->
                    Box(
                        Modifier.padding(4.dp).size(8.dp).background(
                            if (pager.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                        ),
                    )
                }
            }
            if (pager.currentPage < 2) {
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.onb_next))
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))
        Button(onClick = onAction) { Text(action) }
    }
}
