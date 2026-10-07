package com.olivier.gpxroad.android.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olivier.gpxroad.android.R
import com.olivier.gpxroad.android.data.LoadedTrack
import com.olivier.gpxroad.shared.offline.ReadinessLevel
import com.olivier.gpxroad.shared.offline.ReadinessPart

/** Texte de la pastille de la liste : `null` = rien à montrer (trace active sans préparation en cours ni résultat). */
@Composable
fun readinessLabel(preparer: TrackPreparer, track: LoadedTrack): Pair<String, ReadinessLevel?> {
    if (preparer.preparingTrackId == track.entry.id) return stringResource(R.string.track_preparing) to null
    val state = preparer.readiness(track)
    return when (state.level) {
        ReadinessLevel.READY -> stringResource(R.string.track_ready) to ReadinessLevel.READY
        ReadinessLevel.PARTIAL -> stringResource(R.string.track_incomplete) to ReadinessLevel.PARTIAL
        else -> stringResource(R.string.track_not_prepared) to ReadinessLevel.NONE
    }
}

/** Fiche de la trace active : ce qui est en local, et « Préparer maintenant » (comme `TrackReadinessSection` iOS). */
@Composable
fun TrackReadinessRow(preparer: TrackPreparer, track: LoadedTrack) {
    val state = preparer.readiness(track)
    val preparing = preparer.preparingTrackId == track.entry.id
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.prep_title), style = MaterialTheme.typography.titleSmall)
        if (state.level == ReadinessLevel.READY) {
            Text(stringResource(R.string.prep_all_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val names = state.missing.map {
                stringResource(
                    when (it) {
                        ReadinessPart.MAP -> R.string.prep_part_map
                        ReadinessPart.LANDMARKS -> R.string.prep_part_landmarks
                        ReadinessPart.ROUTE_MATCH -> R.string.prep_part_route
                        ReadinessPart.ROUNDABOUTS -> R.string.prep_part_roundabouts
                    },
                )
            }.joinToString(", ")
            Text(stringResource(R.string.prep_missing, names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { preparer.prepare(track, force = true) }, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                Text(if (preparing) stringResource(R.string.track_preparing) else stringResource(R.string.prep_now))
            }
        }
    }
}
