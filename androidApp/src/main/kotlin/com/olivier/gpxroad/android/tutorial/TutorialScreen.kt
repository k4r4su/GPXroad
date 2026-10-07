package com.olivier.gpxroad.android.tutorial

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.olivier.gpxroad.android.R

private class Topic(@StringRes val title: Int, val points: List<Int>)
private class Page(@StringRes val title: Int, @StringRes val summary: Int, val topics: List<Topic>)

/** Contenu du tutoriel (`TutorialContent` iOS) : une page par onglet, embarqué, hors ligne. */
private val pages = listOf(
    Page(
        R.string.tab_ride, R.string.tuto_ride_summary,
        listOf(
            Topic(R.string.tuto_follow, listOf(R.string.tuto_follow_1, R.string.tuto_follow_2, R.string.tuto_follow_3, R.string.tuto_follow_4, R.string.tuto_follow_5, R.string.tuto_follow_6)),
            Topic(R.string.tuto_record, listOf(R.string.tuto_record_1, R.string.tuto_record_2, R.string.tuto_record_3, R.string.tuto_record_4, R.string.tuto_record_5)),
            Topic(R.string.tuto_controls, listOf(R.string.tuto_controls_1, R.string.tuto_controls_2, R.string.tuto_controls_3)),
        ),
    ),
    Page(
        R.string.tab_goto, R.string.tuto_goto_summary,
        listOf(
            Topic(R.string.tuto_goto_search, listOf(R.string.tuto_goto_1, R.string.tuto_goto_2, R.string.tuto_goto_3)),
            Topic(R.string.tuto_goto_during, listOf(R.string.tuto_goto_4, R.string.tuto_goto_5, R.string.tuto_goto_6)),
        ),
    ),
    Page(
        R.string.tab_roadbook, R.string.tuto_rb_summary,
        listOf(
            Topic(R.string.tuto_rb_modes, listOf(R.string.tuto_rb_1, R.string.tuto_rb_2, R.string.tuto_rb_3, R.string.tuto_rb_4)),
            Topic(R.string.tuto_rb_roundabouts, listOf(R.string.tuto_rb_5, R.string.tuto_rb_6)),
            Topic(R.string.tuto_rb_other, listOf(R.string.tuto_rb_7, R.string.tuto_rb_8)),
        ),
    ),
    Page(
        R.string.tab_library, R.string.tuto_lib_summary,
        listOf(Topic(R.string.tuto_lib_tracks, listOf(R.string.tuto_lib_1, R.string.tuto_lib_2, R.string.tuto_lib_3, R.string.tuto_lib_4, R.string.tuto_lib_5, R.string.tuto_lib_6, R.string.tuto_lib_7, R.string.tuto_lib_8))),
    ),
    Page(
        R.string.tab_settings, R.string.tuto_set_summary,
        listOf(Topic(R.string.tuto_set_main, listOf(R.string.tuto_set_1, R.string.tuto_set_2, R.string.tuto_set_3, R.string.tuto_set_4, R.string.tuto_set_6, R.string.tuto_set_5))),
    ),
)

/** Tutoriel intégré (`TutorialView` iOS) : un onglet par écran de l'app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var selected by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_tutorial), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                pages.forEachIndexed { index, page ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(stringResource(page.title)) })
                }
            }
            val page = pages[selected]
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text(stringResource(page.summary), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(page.topics) { topic ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(topic.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        topic.points.forEach { point ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(point), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
