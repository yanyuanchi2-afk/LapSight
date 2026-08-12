package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.lap.GeoPoint
import com.huanfuli.lapsight.shared.lap.LocalProjection
import com.huanfuli.lapsight.shared.storage.LoadResult
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.track.TrackProfile
import com.huanfuli.lapsight.shared.ui.BackIcon
import com.huanfuli.lapsight.shared.ui.DriveMarkingSnapshot
import com.huanfuli.lapsight.shared.ui.LapSightTheme
import com.huanfuli.lapsight.shared.ui.MoreActionsIcon
import com.huanfuli.lapsight.shared.ui.components.ChipTone
import com.huanfuli.lapsight.shared.ui.components.LapButton
import com.huanfuli.lapsight.shared.ui.components.LapButtonStyle
import com.huanfuli.lapsight.shared.ui.components.SectionHeader
import com.huanfuli.lapsight.shared.ui.components.StatusChip
import com.huanfuli.lapsight.shared.ui.strings
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen secondary destination opened from Drive's existing Track row.
 *
 * This screen intentionally stays list-first. Public-library search and richer
 * source metadata can be added here later without increasing Drive complexity.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TrackCenterScreen(
    snapshot: DriveMarkingSnapshot,
    sessionStore: LocalSessionStore,
    onSelectProfile: (String) -> Unit,
    onNewTrack: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onBack)
    val spacing = LapSightTheme.spacing
    val s = strings
    var overflowOpen by remember { mutableStateOf(false) }
    val rows = remember(snapshot.selectableProfiles, snapshot.latestSample) {
        snapshot.selectableProfiles.map { row ->
            TrackCenterRow(
                profileId = row.profileId,
                name = row.name,
                isTimingReady = row.isTimingReady,
                isCurrent = row.profileId == snapshot.timingReadyTrackId,
                distanceMeters = profileDistanceMeters(
                    profileId = row.profileId,
                    location = snapshot.latestSample,
                    sessionStore = sessionStore,
                ),
            )
        }
    }
    val current = rows.firstOrNull { it.isCurrent }
    val nearby = rows
        .filterNot { it.isCurrent }
        .sortedWith(compareBy<TrackCenterRow> { it.distanceMeters == null }.thenBy { it.distanceMeters })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = BackIcon,
                    contentDescription = s.close,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = s.trackCenterTitle(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { overflowOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = MoreActionsIcon,
                        contentDescription = s.newTrack,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(s.newTrack) },
                        onClick = {
                            overflowOpen = false
                            onNewTrack()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = LapSightTheme.colors.cardBorder)

        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing.xl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = s.noSavedTracksYet,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(spacing.md))
                LapButton(
                    text = s.newTrack,
                    onClick = onNewTrack,
                    style = LapButtonStyle.Secondary,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            current?.let { row ->
                item { SectionHeader(s.current) }
                item {
                    TrackCenterListRow(
                        row = row,
                        onClick = onBack,
                    )
                }
            }
            if (nearby.isNotEmpty()) {
                item {
                    SectionHeader(
                        if (nearby.any { it.distanceMeters != null }) s.nearbyTracksTitle() else s.tracks,
                    )
                }
                items(nearby, key = { it.profileId }) { row ->
                    TrackCenterListRow(
                        row = row,
                        onClick = {
                            if (row.isTimingReady) onSelectProfile(row.profileId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackCenterListRow(
    row: TrackCenterRow,
    onClick: () -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    Surface(
        onClick = onClick,
        enabled = row.isTimingReady,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (row.isCurrent) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (row.isCurrent) MaterialTheme.colorScheme.primary else LapSightTheme.colors.cardBorder,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    color = if (row.isTimingReady) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        LapSightTheme.colors.disabledContent
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !row.isTimingReady -> s.needsStartFinishBeforeTiming
                        row.distanceMeters != null -> s.distanceAway(row.distanceMeters)
                        else -> s.track
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (row.isCurrent) {
                StatusChip(text = s.current, tone = ChipTone.Ready)
            }
        }
    }
}

private data class TrackCenterRow(
    val profileId: String,
    val name: String,
    val isTimingReady: Boolean,
    val isCurrent: Boolean,
    val distanceMeters: Double?,
)

private fun profileDistanceMeters(
    profileId: String,
    location: LocationSample?,
    sessionStore: LocalSessionStore,
): Double? {
    location ?: return null
    val profile = (sessionStore.loadProfile(profileId) as? LoadResult.Loaded<TrackProfile>)?.value
        ?: return null
    val point = profile.latestRevision?.referenceLine?.points?.firstOrNull() ?: return null
    val projection = LocalProjection(GeoPoint(location.latitude, location.longitude))
    val local = projection.toLocal(GeoPoint(point.latitude, point.longitude))
    return hypot(local.x, local.y)
}

private fun com.huanfuli.lapsight.shared.ui.LocalizedStrings.distanceAway(distanceMeters: Double): String {
    val rounded = distanceMeters.roundToInt()
    return when {
        rounded < 1_000 -> "$rounded m"
        else -> "${((rounded / 100.0).roundToInt() / 10.0)} km"
    }
}

private fun com.huanfuli.lapsight.shared.ui.LocalizedStrings.trackCenterTitle(): String = when (language) {
    com.huanfuli.lapsight.shared.ui.AppLanguage.English -> "Track Center"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Chinese -> "赛道中心"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Korean -> "트랙 센터"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Japanese -> "コースセンター"
    com.huanfuli.lapsight.shared.ui.AppLanguage.French -> "Centre des circuits"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Spanish -> "Centro de circuitos"
}

private fun com.huanfuli.lapsight.shared.ui.LocalizedStrings.nearbyTracksTitle(): String = when (language) {
    com.huanfuli.lapsight.shared.ui.AppLanguage.English -> "Nearby"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Chinese -> "附近"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Korean -> "주변"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Japanese -> "周辺"
    com.huanfuli.lapsight.shared.ui.AppLanguage.French -> "À proximité"
    com.huanfuli.lapsight.shared.ui.AppLanguage.Spanish -> "Cerca"
}
