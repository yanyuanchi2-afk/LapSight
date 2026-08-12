package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.DashOrientation
import com.huanfuli.lapsight.shared.DriveDisplaySettings
import com.huanfuli.lapsight.shared.HudDisplayPage
import com.huanfuli.lapsight.shared.LocationFeedMode
import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.PhoneGpsPermissionState
import com.huanfuli.lapsight.shared.VelocityAidedGpsFilter
import com.huanfuli.lapsight.shared.glasses.HudPage
import com.huanfuli.lapsight.shared.glasses.NoOpGlassesActions
import com.huanfuli.lapsight.shared.lap.formatLapTime
import com.huanfuli.lapsight.shared.review.TraceLayer
import com.huanfuli.lapsight.shared.review.TraceRole
import com.huanfuli.lapsight.shared.review.buildTrackTrace
import com.huanfuli.lapsight.shared.review.buildTrackTraceLayers
import com.huanfuli.lapsight.shared.session.GeoPointDto
import com.huanfuli.lapsight.shared.session.RawRecordingSnapshot
import com.huanfuli.lapsight.shared.session.ReadyState
import com.huanfuli.lapsight.shared.session.SessionControllerSnapshot
import com.huanfuli.lapsight.shared.session.TimingRunSnapshot
import com.huanfuli.lapsight.shared.session.toDto
import com.huanfuli.lapsight.shared.storage.LoadResult
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.SectorLineDto
import com.huanfuli.lapsight.shared.track.TrackProfile
import com.huanfuli.lapsight.shared.ui.AddActionIcon
import com.huanfuli.lapsight.shared.ui.CloseActionIcon
import com.huanfuli.lapsight.shared.ui.DriveMarkingPhase
import com.huanfuli.lapsight.shared.ui.DriveMarkingSnapshot
import com.huanfuli.lapsight.shared.ui.LapSightTheme
import com.huanfuli.lapsight.shared.ui.PlayActionIcon
import com.huanfuli.lapsight.shared.ui.PointToPointCourseIcon
import com.huanfuli.lapsight.shared.ui.ReviewTabIcon
import com.huanfuli.lapsight.shared.ui.RotateScreenIcon
import com.huanfuli.lapsight.shared.ui.StopActionIcon
import com.huanfuli.lapsight.shared.ui.TracePositionMarker
import com.huanfuli.lapsight.shared.ui.TraceView
import com.huanfuli.lapsight.shared.ui.courseMarker
import com.huanfuli.lapsight.shared.ui.strings
import com.huanfuli.lapsight.shared.ui.components.LapButton
import com.huanfuli.lapsight.shared.ui.components.LapButtonStyle
import com.huanfuli.lapsight.shared.ui.components.LapDialog
import com.huanfuli.lapsight.shared.ui.components.MetricCell
import com.huanfuli.lapsight.shared.ui.components.MetricCellSize
import com.huanfuli.lapsight.shared.ui.components.SegmentedControl
import com.huanfuli.lapsight.shared.ui.components.ChipTone
import com.huanfuli.lapsight.shared.ui.components.StatusChip
import lapsight.shared.generated.resources.Res
import lapsight.shared.generated.resources.material_symbols_laps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Stationary Drive surface host: status bar + config controls, or the
 * fullscreen dash / Track Review depending on phase. Layout follows the
 * deliberately locked window, not device tilt.
 */
@Composable
internal fun DriveSurface(
    snapshot: DriveMarkingSnapshot,
    orientation: DashOrientation,
    displaySettings: DriveDisplaySettings,
    locationFeedMode: LocationFeedMode,
    phoneGpsPermission: PhoneGpsPermissionState,
    sessionStore: LocalSessionStore,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onSelectDirection: (CourseDirection) -> Unit,
    onSelectTopology: (CourseTopology) -> Unit,
    onStartTiming: () -> Unit,
    onBeginMarking: () -> Unit,
    onStopMarking: () -> Unit,
    onToggleTimingPause: () -> Unit,
    onStopTiming: () -> Unit,
    onStartRawRecording: () -> Unit,
    onStopRawRecording: () -> Unit,
    timingActive: Boolean,
    timingPaused: Boolean,
    timingSnapshot: SessionControllerSnapshot?,
    timingRun: TimingRunSnapshot,
    dashReady: ReadyState,
    rawRecordingActive: Boolean,
    rawSnapshot: RawRecordingSnapshot,
    hudDisplayPage: HudDisplayPage? = null,
    onCycleHudDisplayPage: (() -> Unit)? = null,
    reviewContent: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val isLandscape = maxWidth > maxHeight
        val isCompactLandscape = isLandscape && maxHeight < 520.dp
        val spacing = LapSightTheme.spacing
        val padding = if (isCompactLandscape) spacing.sm else spacing.md

        if (snapshot.phase == DriveMarkingPhase.Review) {
            // Track Review replaces the dash controls (D-31). Portrait scrolls a
            // card; landscape gets bounded height so the review lays out as a
            // fixed two-pane spread with no scrolling.
            if (isLandscape) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                        .padding(padding),
                ) {
                    reviewContent()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    reviewContent()
                }
            }
            return@BoxWithConstraints
        }

        // While formal timing is active, show the fullscreen timing surface with
        // current/last/best/laps/speed/accuracy (D-29, D-42, SESS-01).
        if (timingActive) {
            TimingRunSurface(
                timingRun = timingRun,
                orientation = orientation,
                isLandscapeWindow = isLandscape,
                displaySettings = displaySettings,
                onToggleOrientation = onToggleOrientation,
                orientationToggleEnabled = orientationToggleEnabled,
                timingPaused = timingPaused,
                onToggleTimingPause = onToggleTimingPause,
                onStopTiming = onStopTiming,
                hudDisplayPage = hudDisplayPage,
                onCycleHudDisplayPage = onCycleHudDisplayPage,
                isCompactLandscape = isCompactLandscape,
                padding = padding,
            )
            return@BoxWithConstraints
        }

        if (isLandscape) {
            // Two-pane cockpit: the course visual owns the left pane, controls
            // live in a fixed-width right rail with the actions bottom-anchored.
            // Everything fits without scrolling, and the rotate toggle is
            // reachable in every state — in landscape it is the only way back
            // (the bottom navigation is hidden, D-29).
            LandscapeCockpit(
                snapshot = snapshot,
                orientation = orientation,
                sessionStore = sessionStore,
                displaySettings = displaySettings,
                locationFeedMode = locationFeedMode,
                phoneGpsPermission = phoneGpsPermission,
                onToggleOrientation = onToggleOrientation,
                orientationToggleEnabled = orientationToggleEnabled,
                onSelectProfile = onSelectProfile,
                onSelectDirection = onSelectDirection,
                onSelectTopology = onSelectTopology,
                onStartTiming = onStartTiming,
                onBeginMarking = onBeginMarking,
                onStopMarking = onStopMarking,
                onStartRawRecording = onStartRawRecording,
                onStopRawRecording = onStopRawRecording,
                dashReady = dashReady,
                rawRecordingActive = rawRecordingActive,
                rawSnapshot = rawSnapshot,
                compactControls = isCompactLandscape,
            )
        } else {
            // Portrait: the middle of the screen belongs to the course — a live
            // marking trace while capturing, the selected track's map otherwise —
            // and the primary action row anchors to the bottom, in thumb reach.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DriveStatusBar(
                    snapshot = snapshot,
                    displaySettings = displaySettings,
                    locationFeedMode = locationFeedMode,
                    phoneGpsPermission = phoneGpsPermission,
                    dashReady = dashReady,
                    rawRecordingActive = rawRecordingActive,
                    rawSnapshot = rawSnapshot,
                    modifier = Modifier.fillMaxWidth(),
                )
                ControlPanel(
                    snapshot = snapshot,
                    orientation = orientation,
                    sessionStore = sessionStore,
                    onToggleOrientation = onToggleOrientation,
                    orientationToggleEnabled = orientationToggleEnabled,
                    onSelectProfile = onSelectProfile,
                    onSelectDirection = onSelectDirection,
                    onSelectTopology = onSelectTopology,
                    onStartTiming = onStartTiming,
                    onBeginMarking = onBeginMarking,
                    onStopMarking = onStopMarking,
                    onStartRawRecording = onStartRawRecording,
                    onStopRawRecording = onStopRawRecording,
                    dashReady = dashReady,
                    rawRecordingActive = rawRecordingActive,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    fillHeight = true,
                )
            }
        }
    }
}

/**
 * Landscape pre-timing cockpit: course visual left, control rail right.
 *
 * The left pane shows the thing that matters for the current phase — the
 * selected course, the live marking trace, or the empty state. The right rail
 * stacks status (2×2 metrics), a scrollable phase control area, and a fixed
 * bottom action row so Start/Stop and the orientation toggle remain reachable
 * even when future controls are added.
 */
@Composable
private fun LandscapeCockpit(
    snapshot: DriveMarkingSnapshot,
    orientation: DashOrientation,
    sessionStore: LocalSessionStore,
    displaySettings: DriveDisplaySettings,
    locationFeedMode: LocationFeedMode,
    phoneGpsPermission: PhoneGpsPermissionState,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onSelectDirection: (CourseDirection) -> Unit,
    onSelectTopology: (CourseTopology) -> Unit,
    onStartTiming: () -> Unit,
    onBeginMarking: () -> Unit,
    onStopMarking: () -> Unit,
    onStartRawRecording: () -> Unit,
    onStopRawRecording: () -> Unit,
    dashReady: ReadyState,
    rawRecordingActive: Boolean,
    rawSnapshot: RawRecordingSnapshot,
    compactControls: Boolean,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    var showTrackPicker by remember { mutableStateOf(false) }
    var showNewTrackDialog by remember { mutableStateOf(false) }
    if (showTrackPicker) {
        TrackPickerDialog(
            snapshot = snapshot,
            onSelectProfile = onSelectProfile,
            onBeginMarking = { showNewTrackDialog = true },
            onClose = { showTrackPicker = false },
        )
    }
    if (showNewTrackDialog) {
        NewTrackCourseDialog(
            selectedTopology = snapshot.selectedTopology,
            onSelectTopology = onSelectTopology,
            onBeginMarking = onBeginMarking,
            onClose = { showNewTrackDialog = false },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Fullscreen Drive still needs content-safe insets for rounded
            // corners, front camera cutouts, system bars, and gesture edges.
            // The parent background fills edge-to-edge; only controls move in.
            .safeContentPadding()
            .padding(spacing.sm),
    ) {
        val railWidth = if (compactControls) {
            minOf(maxWidth * 0.44f, LandscapeCompactRailMaxWidth)
        } else {
            minOf(maxWidth * 0.40f, LandscapeRailMaxWidth)
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val previewHeight = minOf(maxHeight, maxWidth / CoursePreviewAspectRatio)
                val previewWidth = previewHeight * CoursePreviewAspectRatio
                val previewModifier = Modifier
                    .width(previewWidth)
                    .height(previewHeight)
                when {
                    snapshot.phase == DriveMarkingPhase.Capturing -> MarkingTracePane(
                        samples = snapshot.capturedSamples,
                        modifier = previewModifier,
                    )
                    snapshot.currentTrackName != null -> SelectedTrackPreview(
                        profileId = snapshot.timingReadyTrackId,
                        sessionStore = sessionStore,
                        compact = false,
                        livePosition = snapshot.latestSample,
                        fillParent = true,
                        modifier = previewModifier,
                    )
                    rawRecordingActive -> Text(
                        text = s.rawGpsRecordingShort,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> NoTrackState(
                        hasSavedTracks = snapshot.selectableProfiles.isNotEmpty(),
                        onChooseTrack = { showTrackPicker = true },
                        onBeginMarking = { showNewTrackDialog = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(
                modifier = Modifier.width(railWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                DriveStatusBar(
                    snapshot = snapshot,
                    displaySettings = displaySettings,
                    locationFeedMode = locationFeedMode,
                    phoneGpsPermission = phoneGpsPermission,
                    dashReady = dashReady,
                    rawRecordingActive = rawRecordingActive,
                    rawSnapshot = rawSnapshot,
                    modifier = Modifier.fillMaxWidth(),
                    grid = true,
                    dense = compactControls,
                )
                when {
                    snapshot.phase == DriveMarkingPhase.Capturing -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            MarkingMetricsRow(snapshot = snapshot)
                            Text(
                                text = s.markingGuidanceFor(snapshot.selectedTopology),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        DriveActionRow(
                            primaryIcon = StopActionIcon,
                            primaryLabel = s.stopMarking,
                            primaryDescription = s.stopMarking,
                            primaryContainerColor = MaterialTheme.colorScheme.errorContainer,
                            primaryContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            primaryEnabled = true,
                            onPrimary = onStopMarking,
                            orientation = orientation,
                            onToggleOrientation = onToggleOrientation,
                            orientationToggleEnabled = orientationToggleEnabled,
                            compact = compactControls,
                        )
                    }
                    rawRecordingActive -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            Text(
                                text = s.rawGpsRecordingLong,
                                color = LapSightTheme.colors.statusCaution,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        DriveActionRow(
                            primaryIcon = StopActionIcon,
                            primaryLabel = s.stopRawRecording,
                            primaryDescription = s.stopRawRecording,
                            primaryContainerColor = MaterialTheme.colorScheme.errorContainer,
                            primaryContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            primaryEnabled = true,
                            onPrimary = onStopRawRecording,
                            orientation = orientation,
                            onToggleOrientation = onToggleOrientation,
                            orientationToggleEnabled = orientationToggleEnabled,
                            compact = compactControls,
                        )
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            TrackSelectorSection(
                                snapshot = snapshot,
                                onClick = { showTrackPicker = true },
                            )
                            if (snapshot.canStartTiming && snapshot.currentTrackTopology != CourseTopology.PointToPoint) {
                                DirectionSelectorSection(
                                    selected = snapshot.selectedDirection,
                                    onSelectDirection = onSelectDirection,
                                )
                            }
                        }
                        DriveActionRow(
                            primaryIcon = PlayActionIcon,
                            primaryLabel = s.startTiming,
                            primaryDescription = s.startTiming,
                            primaryContainerColor = MaterialTheme.colorScheme.primary,
                            primaryContentColor = MaterialTheme.colorScheme.onPrimary,
                            primaryEnabled = snapshot.canStartTiming,
                            onPrimary = onStartTiming,
                            orientation = orientation,
                            onToggleOrientation = onToggleOrientation,
                            orientationToggleEnabled = orientationToggleEnabled,
                            compact = compactControls,
                        )
                    }
                }
            }
        }
    }
}

/** MARKING TIME + POINTS cells shared by the portrait stack and the rail. */
@Composable
private fun MarkingMetricsRow(
    snapshot: DriveMarkingSnapshot,
    modifier: Modifier = Modifier,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    val samples = snapshot.capturedSamples
    val localElapsedMillis = if (samples.size >= 2) {
        samples.last().elapsedMillis - samples.first().elapsedMillis
    } else {
        0L
    }
    val elapsedMillis = snapshot.backendMarkingElapsedMillis ?: localElapsedMillis
    val pointCount = snapshot.backendMarkingPointCount ?: samples.size
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        MetricCell(
            label = s.markingTime,
            value = elapsedMillis.formatLapTime(),
            modifier = Modifier.weight(1f),
            size = MetricCellSize.Compact,
        )
        MetricCell(
            label = s.points,
            value = pointCount.toString(),
            modifier = Modifier.weight(1f),
            size = MetricCellSize.Compact,
        )
    }
}

/** Distance a fix must travel before the heading arrow re-aims (anti-jitter). */
private const val HEADING_ANCHOR_METERS = 3.0

/**
 * Runs the live fix stream through [VelocityAidedGpsFilter] for display.
 *
 * Each new fix (identified by its elapsed timestamp) is folded into a filter
 * scoped to [key], so position jitter is damped before the heading anchor and
 * the between-fix smoother consume it. Display only: timing, recording, and
 * Ready gating all keep reading the raw fixes.
 */
@Composable
private fun rememberFilteredLivePosition(
    key: Any?,
    livePosition: LocationSample?,
): LocationSample? {
    val filter = remember(key) { VelocityAidedGpsFilter() }
    var filtered by remember(key) { mutableStateOf<LocationSample?>(null) }
    LaunchedEffect(key, livePosition?.elapsedMillis) {
        val fix = livePosition ?: return@LaunchedEffect
        filtered = filter.update(fix)
    }
    return filtered ?: livePosition
}

/** Live-dot smoothing ticker period (~30 fps) and its bounded forward lead. */
private const val LIVE_SMOOTHING_FRAME_MILLIS = 33L

/**
 * Forward lead must cover a whole ~1 Hz fix interval, or the dot glides for the
 * covered stretch and then freezes until the next fix. The predictor's own
 * 1500 ms cap remains the hard runaway bound for stale fixes.
 */
private const val LIVE_SMOOTHING_MAX_LEAD_MILLIS = 1_200L

/** Re-sync correction is eased over this window instead of snapping the dot. */
private const val LIVE_SMOOTHING_BLEND_MILLIS = 180L

/** Mutable scratch that survives recomposition without triggering it. */
private class LiveSmoothingScratch {
    var lastShown: GeoPointDto? = null
    var blendFrom: GeoPointDto? = null
    var blendStartMillis = 0L
}

/**
 * Smooths the live "you are here" dot between the receiver's ~1 Hz fixes.
 *
 * Phone GNSS solves a position about once a second, so the raw dot teleports.
 * This drives [LiveTrackPredictor] off a lightweight ticker: between fixes it
 * projects the dot forward along the last segment's velocity, re-syncing on every
 * real fix. The re-sync itself is blended from the last drawn point over
 * [LIVE_SMOOTHING_BLEND_MILLIS] so the correction never reads as a jump. Forward
 * lead is bounded (here and by the predictor's own cap) so a stale fix can never
 * let the dot run away. Returns the raw fix until a second fix establishes a
 * velocity, and null before the first fix.
 */
@Composable
private fun rememberSmoothedLivePosition(
    key: Any?,
    livePosition: LocationSample?,
): GeoPointDto? {
    var previous by remember(key) { mutableStateOf<LocationSample?>(null) }
    var latest by remember(key) { mutableStateOf<LocationSample?>(null) }
    var latestArrivalMillis by remember(key) { mutableStateOf(0L) }
    val scratch = remember(key) { LiveSmoothingScratch() }

    // Monotonic wall-clock ticker so the dot glides between fixes without waiting
    // for a new fix to trigger recomposition.
    val nowMillis by produceState(0L, key) {
        while (true) {
            delay(LIVE_SMOOTHING_FRAME_MILLIS)
            value += LIVE_SMOOTHING_FRAME_MILLIS
        }
    }

    LaunchedEffect(key, livePosition?.elapsedMillis) {
        val fix = livePosition ?: return@LaunchedEffect
        if (fix.elapsedMillis != latest?.elapsedMillis) {
            previous = latest
            latest = fix
            latestArrivalMillis = nowMillis
            // Ease from wherever the dot is currently drawn to the new track.
            scratch.blendFrom = scratch.lastShown
            scratch.blendStartMillis = nowMillis
        }
    }

    val currentLatest = latest
        ?: return livePosition?.let { GeoPointDto(it.latitude, it.longitude) }
    val sinceArrival = (nowMillis - latestArrivalMillis)
        .coerceIn(0L, LIVE_SMOOTHING_MAX_LEAD_MILLIS)
    val predicted = LiveTrackPredictor.predict(
        previous = previous,
        latest = currentLatest,
        atElapsedMillis = currentLatest.elapsedMillis + sinceArrival,
    )
    val target = GeoPointDto(predicted.latitude, predicted.longitude)
    val blendFrom = scratch.blendFrom
    val blendT = (nowMillis - scratch.blendStartMillis).toDouble() / LIVE_SMOOTHING_BLEND_MILLIS
    val shown = if (blendFrom != null && blendT < 1.0) {
        val t = blendT.coerceAtLeast(0.0)
        GeoPointDto(
            latitude = blendFrom.latitude + (target.latitude - blendFrom.latitude) * t,
            longitude = blendFrom.longitude + (target.longitude - blendFrom.longitude) * t,
        )
    } else {
        target
    }
    scratch.lastShown = shown
    return shown
}

/** Rough planar distance between two fixes; ample for a few-meter heading gate. */
private fun approxDistanceMeters(a: GeoPointDto, b: GeoPointDto): Double {
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = 111_320.0 * cos(a.latitude * PI / 180.0)
    val dLat = (b.latitude - a.latitude) * metersPerDegLat
    val dLon = (b.longitude - a.longitude) * metersPerDegLon
    return hypot(dLat, dLon)
}

/**
 * Live "you are here" marker from the tail of the marking trace, or null when
 * there are too few points to place it. Heading comes from a slightly older
 * point so momentary GPS jitter does not spin the arrow; both points are the
 * projected trace's own points, so the marker stays pinned to the drawn course.
 */
private fun liveHeadingMarker(layers: List<TraceLayer>): TracePositionMarker? {
    val points = layers.firstOrNull { it.role == TraceRole.Marking }?.points ?: return null
    val current = points.lastOrNull() ?: return null
    val previous = points.getOrNull(points.size - 5)?.takeIf { it != current }
    return TracePositionMarker(current = current, previous = previous)
}

/**
 * The accumulating marking trace, filling a landscape pane. Redraws every ~10
 * samples so canvas work stays off the per-sample hot path.
 */
@Composable
private fun MarkingTracePane(
    samples: List<LocationSample>,
    modifier: Modifier = Modifier,
) {
    val s = strings
    CourseMapSurface(modifier = modifier) {
        val layerStep = samples.size / 10
        val layers = remember(layerStep) {
            buildTrackTraceLayers(
                markingSamples = samples.map { it.toDto() },
                referenceLine = null,
                startFinish = null,
                sectors = emptyList(),
                outlierSamples = emptyList(),
                viewWidth = 400.0,
                viewHeight = 300.0,
            )
        }
        if (layers.isNotEmpty()) {
            TraceView(
                layers = layers,
                fillParent = true,
                positionMarker = liveHeadingMarker(layers),
            )
        } else {
            Text(
                text = s.waitingForFirstGpsFix,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Pre-timing configuration controls. With [fillHeight] the middle section
 * (course preview / empty state / live marking feedback) expands and the
 * action row is pushed to the bottom of the available space.
 */
@Composable
private fun ControlPanel(
    snapshot: DriveMarkingSnapshot,
    orientation: DashOrientation,
    sessionStore: LocalSessionStore,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onSelectDirection: (CourseDirection) -> Unit,
    onSelectTopology: (CourseTopology) -> Unit,
    onStartTiming: () -> Unit,
    onBeginMarking: () -> Unit,
    onStopMarking: () -> Unit,
    onStartRawRecording: () -> Unit,
    onStopRawRecording: () -> Unit,
    dashReady: ReadyState,
    rawRecordingActive: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillHeight: Boolean = false,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        when (snapshot.phase) {
            DriveMarkingPhase.Capturing -> {
                // Live capture feedback: the trace being drawn, marking time, and
                // what to do — the screen must confirm the app is recording.
                MarkingLiveSection(
                    snapshot = snapshot,
                    compact = compact,
                    modifier = if (fillHeight) Modifier.weight(1f) else Modifier,
                )
                DriveActionRow(
                    primaryIcon = StopActionIcon,
                    primaryLabel = s.stopMarking,
                    primaryDescription = s.stopMarking,
                    primaryContainerColor = MaterialTheme.colorScheme.errorContainer,
                    primaryContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    primaryEnabled = true,
                    onPrimary = onStopMarking,
                    orientation = orientation,
                    onToggleOrientation = onToggleOrientation,
                    orientationToggleEnabled = orientationToggleEnabled,
                    compact = compact,
                )
            }
            DriveMarkingPhase.Review -> {
                // Track Review owns its own actions; no primary CTA here.
            }
            DriveMarkingPhase.Idle -> if (rawRecordingActive) {
                // Diagnostic raw recording in progress (D-16): samples are captured
                // for replay/diagnosis but NO lap/ghost timing has started (D-17).
                Text(
                    text = s.rawGpsRecordingLong,
                    color = LapSightTheme.colors.statusCaution,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (fillHeight) Spacer(Modifier.weight(1f))
                // The rotate toggle stays reachable while raw recording — it
                // must never be possible to strand the user in an orientation.
                DriveActionRow(
                    primaryIcon = StopActionIcon,
                    primaryLabel = s.stopRawRecording,
                    primaryDescription = s.stopRawRecording,
                    primaryContainerColor = MaterialTheme.colorScheme.errorContainer,
                    primaryContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    primaryEnabled = true,
                    onPrimary = onStopRawRecording,
                    orientation = orientation,
                    onToggleOrientation = onToggleOrientation,
                    orientationToggleEnabled = orientationToggleEnabled,
                    compact = compact,
                )
            } else {
                var showTrackPicker by remember { mutableStateOf(false) }
                var showNewTrackDialog by remember { mutableStateOf(false) }
                if (showTrackPicker) {
                    TrackPickerDialog(
                        snapshot = snapshot,
                        onSelectProfile = onSelectProfile,
                        onBeginMarking = { showNewTrackDialog = true },
                        onClose = { showTrackPicker = false },
                    )
                }
                if (showNewTrackDialog) {
                    NewTrackCourseDialog(
                        selectedTopology = snapshot.selectedTopology,
                        onSelectTopology = onSelectTopology,
                        onBeginMarking = onBeginMarking,
                        onClose = { showNewTrackDialog = false },
                    )
                }

                TrackSelectorSection(
                    snapshot = snapshot,
                    onClick = { showTrackPicker = true },
                    compact = compact,
                )

                if (snapshot.currentTrackName != null) {
                    // The selected course fills the middle — the map is the reason
                    // to glance at this screen before starting.
                    SelectedTrackPreview(
                        profileId = snapshot.timingReadyTrackId,
                        sessionStore = sessionStore,
                        compact = compact,
                        livePosition = snapshot.latestSample,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    NoTrackState(
                        hasSavedTracks = snapshot.selectableProfiles.isNotEmpty(),
                        onChooseTrack = { showTrackPicker = true },
                        onBeginMarking = { showNewTrackDialog = true },
                        modifier = if (fillHeight) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
                    )
                }

                if (snapshot.canStartTiming && snapshot.currentTrackTopology != CourseTopology.PointToPoint) {
                    DirectionSelectorSection(
                        selected = snapshot.selectedDirection,
                        onSelectDirection = onSelectDirection,
                    )
                }
                if (fillHeight && snapshot.currentTrackName != null) {
                    Spacer(Modifier.weight(1f))
                }
                DriveActionRow(
                    primaryIcon = PlayActionIcon,
                    primaryLabel = s.startTiming,
                    primaryDescription = s.startTiming,
                    primaryContainerColor = MaterialTheme.colorScheme.primary,
                    primaryContentColor = MaterialTheme.colorScheme.onPrimary,
                    primaryEnabled = snapshot.canStartTiming,
                    onPrimary = onStartTiming,
                    orientation = orientation,
                    onToggleOrientation = onToggleOrientation,
                    orientationToggleEnabled = orientationToggleEnabled,
                    compact = compact,
                )
            }
        }
    }
}

/**
 * Live feedback while marking (D-06..D-08): marking clock, captured points,
 * the accumulating trace, and the loop guidance. The trace redraws every ~10
 * samples so the canvas work stays off the per-sample hot path.
 */
@Composable
private fun MarkingLiveSection(
    snapshot: DriveMarkingSnapshot,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    val samples = snapshot.capturedSamples
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        MarkingMetricsRow(snapshot = snapshot)
        // Rebuild the drawn layers in coarse steps, not on every GPS sample.
        val layerStep = samples.size / 10
        val layers = remember(layerStep) {
            buildTrackTraceLayers(
                markingSamples = samples.map { it.toDto() },
                referenceLine = null,
                startFinish = null,
                sectors = emptyList(),
                outlierSamples = emptyList(),
                viewWidth = 400.0,
                viewHeight = 300.0,
            )
        }
        if (layers.isNotEmpty()) {
            BoundedCourseMapSurface(compact = compact) {
                TraceView(
                    layers = layers,
                    fillParent = true,
                    positionMarker = liveHeadingMarker(layers),
                )
            }
        } else {
            Text(
                text = s.waitingForFirstGpsFix,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = s.markingGuidanceFor(snapshot.selectedTopology),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Course map of the currently selected Track (latest revision), with its
 * start/finish and sector lines — fills the pre-timing middle so the screen
 * shows what is about to be timed.
 */
@Composable
private fun SelectedTrackPreview(
    profileId: String?,
    sessionStore: LocalSessionStore,
    compact: Boolean,
    livePosition: LocationSample?,
    modifier: Modifier = Modifier,
    fillParent: Boolean = false,
) {
    if (profileId == null) return
    val profile = remember(profileId) {
        (sessionStore.loadProfile(profileId) as? LoadResult.Loaded<TrackProfile>)?.value
    }
    val latest = profile?.latestRevision
    val referenceLine = latest?.referenceLine
    if (referenceLine == null || referenceLine.points.isEmpty()) return

    val trace = remember(profileId, latest.ordinal) {
        val finishAsLine = latest.courseSetup.finishLine?.let {
            listOf(SectorLineDto("finish", "Finish", 999, it.pointA, it.pointB))
        } ?: emptyList()
        buildTrackTrace(
            markingSamples = emptyList(),
            referenceLine = referenceLine,
            startFinish = latest.courseSetup.startFinish,
            sectors = latest.courseSetup.boundaries.map {
                SectorLineDto(id = it.id, name = "Sector ${it.order}", order = it.order, pointA = it.pointA, pointB = it.pointB)
            } + finishAsLine,
            outlierSamples = emptyList(),
            viewWidth = 400.0,
            viewHeight = 300.0,
        )
    }
    if (trace.layers.isEmpty()) return

    // Live "you are here": project the current fix through the map's own
    // viewport so it lands on the drawn course. Heading comes from an earlier
    // fix, advanced only once the driver has moved a few meters, so a parked car
    // shows a steady arrow instead of chasing GPS jitter.
    // The fix stream is first run through the velocity-aided filter (display
    // only — timing/recording keep consuming raw fixes), then heading anchoring
    // works off the filtered ~1 Hz fixes and the drawn dot is additionally
    // smoothed between them via the predictor.
    val filteredPosition = rememberFilteredLivePosition(profileId, livePosition)
    val rawCurrent = filteredPosition?.let { GeoPointDto(it.latitude, it.longitude) }
    var headingAnchor by remember(profileId) { mutableStateOf<GeoPointDto?>(null) }
    var headingFrom by remember(profileId) { mutableStateOf<GeoPointDto?>(null) }
    LaunchedEffect(profileId, rawCurrent?.latitude, rawCurrent?.longitude) {
        val c = rawCurrent ?: return@LaunchedEffect
        val anchor = headingAnchor
        if (anchor == null) {
            headingAnchor = c
        } else if (approxDistanceMeters(anchor, c) >= HEADING_ANCHOR_METERS) {
            headingFrom = anchor
            headingAnchor = c
        }
    }
    val current = rememberSmoothedLivePosition(profileId, filteredPosition)
    val marker = courseMarker(trace.viewport, current, headingFrom)

    val frameModifier = if (fillParent) {
        modifier
    } else {
        modifier
    }
    if (fillParent) {
        CourseMapSurface(modifier = frameModifier) {
            TraceView(
                layers = trace.layers,
                fillParent = true,
                positionMarker = marker,
            )
        }
    } else {
        BoundedCourseMapSurface(compact = compact, modifier = frameModifier) {
            TraceView(
                layers = trace.layers,
                fillParent = true,
                positionMarker = marker,
            )
        }
    }
}

@Composable
private fun BoundedCourseMapSurface(
    compact: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CourseMapSurface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(if (compact) CompactCoursePreviewAspectRatio else CoursePreviewAspectRatio),
        content = content,
    )
}

@Composable
private fun CourseMapSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val spacing = LapSightTheme.spacing
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LapSightTheme.colors.cardBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.sm),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private const val CoursePreviewAspectRatio = 4f / 3f
private const val CompactCoursePreviewAspectRatio = 16f / 9f
private val LandscapeCompactRailMaxWidth = 400.dp
private val LandscapeRailMaxWidth = 420.dp

/**
 * Centered empty state when no Track is selected: names the situation once
 * and leads with the action that fits it — marking a first track, or picking
 * a saved one.
 */
@Composable
private fun NoTrackState(
    hasSavedTracks: Boolean,
    onChooseTrack: () -> Unit,
    onBeginMarking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = s.noTrackSelected,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = if (hasSavedTracks) {
                s.pickSavedTrack
            } else {
                s.markFirstTrack
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.md))
        // Cap the action width so buttons stay hand-sized inside a wide
        // landscape pane while still filling a portrait column.
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (hasSavedTracks) {
                LapButton(
                    text = s.chooseTrack,
                    onClick = onChooseTrack,
                    icon = ReviewTabIcon,
                    modifier = Modifier.fillMaxWidth(),
                )
                LapButton(
                    text = s.newTrack,
                    onClick = onBeginMarking,
                    style = LapButtonStyle.Secondary,
                    icon = AddActionIcon,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LapButton(
                    text = s.newTrack,
                    onClick = onBeginMarking,
                    icon = AddActionIcon,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NewTrackCourseDialog(
    selectedTopology: CourseTopology,
    onSelectTopology: (CourseTopology) -> Unit,
    onBeginMarking: () -> Unit,
    onClose: () -> Unit,
) {
    val s = strings
    val spacing = LapSightTheme.spacing
    LapDialog(
        title = s.newTrack,
        onDismissRequest = onClose,
        confirmText = s.newTrack,
        confirmIcon = AddActionIcon,
        onConfirm = {
            onClose()
            onBeginMarking()
        },
        dismissText = s.cancel,
        onDismiss = onClose,
        content = {
            Text(
                text = s.markFirstTrack,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(spacing.sm))
            CourseTopologySelector(
                selected = selectedTopology,
                onSelectTopology = onSelectTopology,
            )
        },
    )
}

@Composable
private fun DriveActionRow(
    primaryIcon: ImageVector,
    primaryLabel: String,
    primaryDescription: String,
    primaryContainerColor: Color,
    primaryContentColor: Color,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    orientation: DashOrientation,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    compact: Boolean = false,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    val actionHeight = if (compact) 56.dp else 72.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.weight(1f).height(actionHeight),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryContainerColor,
                contentColor = primaryContentColor,
                disabledContainerColor = LapSightTheme.colors.disabledContainer,
                disabledContentColor = LapSightTheme.colors.disabledContent,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    imageVector = primaryIcon,
                    contentDescription = primaryDescription,
                    modifier = Modifier.size(if (compact) 24.dp else 30.dp),
                )
                if (!compact) {
                    Text(
                        text = primaryLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
        Button(
            onClick = onToggleOrientation,
            enabled = orientationToggleEnabled,
            modifier = Modifier.weight(0.34f).height(actionHeight),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                disabledContentColor = LapSightTheme.colors.disabledContent,
            ),
        ) {
            Icon(
                imageVector = RotateScreenIcon,
                contentDescription =
                    if (orientation == DashOrientation.Portrait) s.switchToLandscape else s.switchToPortrait,
                modifier = Modifier.size(if (compact) 28.dp else 32.dp),
            )
        }
    }
}

/**
 * Track picker (D-01, D-02, D-03): active latest-revision profiles as explicit
 * selection actions. The current Track renders as the highlighted selection —
 * not disabled — and not-timing-ready tracks explain themselves.
 */
@Composable
private fun TrackPickerDialog(
    snapshot: DriveMarkingSnapshot,
    onSelectProfile: (String) -> Unit,
    onBeginMarking: () -> Unit,
    onClose: () -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    // Cap the list against the real window: the AlertDialog text slot has no
    // height weighting, so a fixed 420dp list would push the dialog buttons
    // off a ~360dp-tall landscape screen.
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val listMaxHeight = minOf(420.dp, windowHeight * 0.45f)
    LapDialog(
        title = s.chooseTrack,
        onDismissRequest = onClose,
        confirmText = s.close,
        confirmIcon = CloseActionIcon,
        confirmIconOnly = true,
        confirmContentDescription = s.closeTrackPicker,
        onConfirm = onClose,
        dismissText = s.newTrack,
        dismissIcon = AddActionIcon,
        onDismiss = {
            onClose()
            onBeginMarking()
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (snapshot.selectableProfiles.isEmpty()) {
                    Text(
                        text = s.noSavedTracksYet,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    snapshot.selectableProfiles.forEach { row ->
                        val isCurrent = row.profileId == snapshot.timingReadyTrackId
                        TrackPickerRow(
                            name = row.name,
                            isCurrent = isCurrent,
                            isTimingReady = row.isTimingReady,
                            onClick = {
                                if (row.isTimingReady && !isCurrent) {
                                    onSelectProfile(row.profileId)
                                }
                                onClose()
                            },
                        )
                    }
                }
            }
        },
    )
}

/** One selectable track row: selected = accent border, not faded-out disabled. */
@Composable
private fun TrackPickerRow(
    name: String,
    isCurrent: Boolean,
    isTimingReady: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (isCurrent) MaterialTheme.colorScheme.primary else LapSightTheme.colors.cardBorder,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = if (isTimingReady) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        LapSightTheme.colors.disabledContent
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isTimingReady) {
                    Text(
                        text = s.needsStartFinishBeforeTiming,
                        color = LapSightTheme.colors.statusCaution,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (isCurrent) {
                Text(
                    text = s.current,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Compact pre-Timing Track selector (D-01, D-02, D-03): always states the
 * current Track by name or clearly states none. Tapping opens the picker.
 * Rendered only on the stationary Drive surface, never the moving dash.
 */
@Composable
private fun TrackSelectorSection(
    snapshot: DriveMarkingSnapshot,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LapSightTheme.colors.cardBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.track,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = snapshot.currentTrackName ?: s.noTrackSelected,
                    color = if (snapshot.currentTrackName != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = if (compact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "›",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun CourseTopologySelector(
    selected: CourseTopology,
    onSelectTopology: (CourseTopology) -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    val circuitIcon = painterResource(Res.drawable.material_symbols_laps)
    val pointToPointIcon = rememberVectorPainter(PointToPointCourseIcon)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = s.courseType,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(0.6f),
        )
        SegmentedControl(
            options = listOf(s.circuit, s.pointToPoint),
            selectedIndex = if (selected == CourseTopology.Circuit) 0 else 1,
            onSelect = { index ->
                onSelectTopology(
                    if (index == 0) CourseTopology.Circuit else CourseTopology.PointToPoint,
                )
            },
            optionIcons = listOf(circuitIcon, pointToPointIcon),
            iconOnly = true,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/**
 * Pre-Timing Course Direction selector (D-18): run the SAME saved revision in
 * the Recorded direction or its Reverse without re-marking. Explicit choice,
 * no automatic recommendation; stationary surface only (safety).
 */
@Composable
private fun DirectionSelectorSection(
    selected: CourseDirection,
    onSelectDirection: (CourseDirection) -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = s.direction,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(0.6f),
        )
        SegmentedControl(
            options = listOf(s.recorded, s.reverse),
            selectedIndex = if (selected == CourseDirection.Recorded) 0 else 1,
            onSelect = { index ->
                onSelectDirection(
                    if (index == 0) CourseDirection.Recorded else CourseDirection.Reverse,
                )
            },
            modifier = Modifier.weight(1.4f),
        )
    }
}
