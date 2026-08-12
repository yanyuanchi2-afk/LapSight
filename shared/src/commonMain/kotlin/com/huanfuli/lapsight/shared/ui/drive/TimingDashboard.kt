package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.ui.LapSightAutoSize
import com.huanfuli.lapsight.shared.DashOrientation
import com.huanfuli.lapsight.shared.DriveDisplaySettings
import com.huanfuli.lapsight.shared.HudDisplayPage
import com.huanfuli.lapsight.shared.SpeedUnit
import com.huanfuli.lapsight.shared.ghost.DeltaDisplayState
import com.huanfuli.lapsight.shared.ghost.DeltaTone
import com.huanfuli.lapsight.shared.lap.formatLapTime
import com.huanfuli.lapsight.shared.nowEpochMillis
import com.huanfuli.lapsight.shared.session.TimingRunSnapshot
import com.huanfuli.lapsight.shared.ui.LapSightTheme
import com.huanfuli.lapsight.shared.ui.PauseActionIcon
import com.huanfuli.lapsight.shared.ui.RotateScreenIcon
import com.huanfuli.lapsight.shared.ui.ResumeActionIcon
import com.huanfuli.lapsight.shared.ui.StopActionIcon
import com.huanfuli.lapsight.shared.ui.components.TimingText
import kotlinx.coroutines.delay

/**
 * Fullscreen timing surface shown while a formal timing session is running (D-29).
 *
 * Priority order while moving (UI-SPEC Timing Surface Layout): current lap time is
 * the primary display, the live delta is the second core readout and value-only
 * (`--` / `+0.421s` / `-0.218s`, D-13/D-14), and last/best/laps/speed/accuracy are
 * compact secondary metrics that keep working even when the delta is `--`
 * (D-19/T-04-11). All numerals render in the mono timing face — tabular digits
 * never horizontal-jitter as values tick (UI-SPEC Display role). The surface stays
 * passive while moving (no charts/maps beyond the optional speed trace, D-16).
 */
@Composable
internal fun TimingRunSurface(
    timingRun: TimingRunSnapshot,
    orientation: DashOrientation,
    isLandscapeWindow: Boolean,
    displaySettings: DriveDisplaySettings,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    timingPaused: Boolean,
    onToggleTimingPause: () -> Unit,
    onStopTiming: () -> Unit,
    hudDisplayPage: HudDisplayPage? = null,
    onCycleHudDisplayPage: (() -> Unit)? = null,
    isCompactLandscape: Boolean,
    padding: Dp,
) {
    var speedHistory by remember(timingRun.isActive) { mutableStateOf(emptyList<Float>()) }
    var bestLapBeforeLatestCompletion by remember(timingRun.isActive) { mutableStateOf<Long?>(null) }
    var lastCelebratedLapCount by remember(timingRun.isActive) { mutableStateOf(0) }
    var fastestLapFlashInitialized by remember(timingRun.isActive) { mutableStateOf(false) }
    var fastestLapFlash by remember(timingRun.isActive) { mutableStateOf<FastestLapFlash?>(null) }

    LaunchedEffect(
        timingRun.isActive,
        timingRun.lapCount,
        timingRun.lastLapMillis,
        timingRun.bestLapMillis,
    ) {
        if (!timingRun.isActive) {
            bestLapBeforeLatestCompletion = null
            lastCelebratedLapCount = 0
            fastestLapFlashInitialized = false
            fastestLapFlash = null
            return@LaunchedEffect
        }
        if (!fastestLapFlashInitialized) {
            bestLapBeforeLatestCompletion = timingRun.bestLapMillis
            lastCelebratedLapCount = timingRun.lapCount
            fastestLapFlashInitialized = true
            return@LaunchedEffect
        }

        val completedLapMillis = timingRun.lastLapMillis
        val completedNewLap =
            completedLapMillis != null && timingRun.lapCount > lastCelebratedLapCount
        val previousBest = bestLapBeforeLatestCompletion
        if (completedNewLap && (previousBest == null || completedLapMillis < previousBest)) {
            val improvementText = previousBest
                ?.let { DeltaDisplayState.fromDeltaMillis(completedLapMillis - it).text }
                ?: "NEW BEST"
            fastestLapFlash = FastestLapFlash(
                lapCount = timingRun.lapCount,
                lapMillis = completedLapMillis,
                improvementText = improvementText,
            )
            lastCelebratedLapCount = timingRun.lapCount
            bestLapBeforeLatestCompletion = timingRun.bestLapMillis
            delay(1_500)
            if (fastestLapFlash?.lapCount == timingRun.lapCount) {
                fastestLapFlash = null
            }
        } else {
            if (completedNewLap) {
                lastCelebratedLapCount = timingRun.lapCount
            }
            bestLapBeforeLatestCompletion = timingRun.bestLapMillis
        }
    }

    LaunchedEffect(timingRun.checkpointedSampleCount) {
        timingRun.speedMetersPerSecond?.let { speed ->
            speedHistory = (speedHistory + speed.toFloat()).takeLast(90)
        }
    }

    val speedMultiplier = when (displaySettings.speedUnit) {
        SpeedUnit.KilometersPerHour -> 3.6
        SpeedUnit.MilesPerHour -> 2.2369362921
    }
    val speedUnit = when (displaySettings.speedUnit) {
        SpeedUnit.KilometersPerHour -> "km/h"
        SpeedUnit.MilesPerHour -> "mph"
    }
    val speedLabel = timingRun.speedMetersPerSecond
        ?.let { (it * speedMultiplier).toInt().toString() } ?: "--"
    val accuracyLabel = timingRun.accuracyMeters
        ?.let { (if (it < 0) 0.0 else it).toInt().toString() } ?: "--"
    val sectorValue = if (timingRun.sectorCount > 0) {
        "${timingRun.currentSectorNumber ?: "--"}/${timingRun.sectorCount}"
    } else {
        "--"
    }
    val metrics = buildList {
        add(TelemetryMetric("LAST", timingRun.lastLapMillis.formatLapTime()))
        add(TelemetryMetric("BEST", timingRun.bestLapMillis.formatLapTime()))
        add(TelemetryMetric("REFERENCE", timingRun.referenceLapMillis.formatLapTime()))
        add(TelemetryMetric("LAP", timingRun.currentLapNumber?.toString() ?: "--"))
        add(TelemetryMetric("COMPLETED", timingRun.lapCount.toString()))
        add(TelemetryMetric("SECTOR", sectorValue))
        add(
            TelemetryMetric(
                timingRun.latestSectorName?.uppercase() ?: "LAST SPLIT",
                timingRun.latestSectorSplitMillis.formatLapTime(),
            ),
        )
        add(TelemetryMetric("SESSION", timingRun.sessionElapsedMillis.formatLapTime()))
        if (displaySettings.showGpsDiagnostics) {
            add(TelemetryMetric("GPS ACCURACY", accuracyLabel, "m"))
            add(
                TelemetryMetric(
                    "GPS RATE",
                    timingRun.sampleRateHz?.let(::formatOneDecimal) ?: "--",
                    "Hz",
                ),
            )
            add(
                TelemetryMetric(
                    "HEADING",
                    timingRun.headingDegrees?.toInt()?.toString() ?: "--",
                    "deg",
                ),
            )
            add(
                TelemetryMetric(
                    "ALTITUDE",
                    timingRun.altitudeMeters?.let(::formatOneDecimal) ?: "--",
                    "m",
                ),
            )
        }
    }

    // The dash surface drops to the deepest background: instrument mode.
    val dashModifier = Modifier
        .fillMaxSize()
        .background(LapSightTheme.colors.dashBackground)
        .safeContentPadding()
        .padding(padding)
    val pagerState = rememberPagerState(pageCount = { TimingPanelPageCount })
    val clockUpdateIntervalMillis =
        if (pagerState.isScrollInProgress) ClockUpdateIntervalWhilePagingMillis else ClockUpdateIntervalMillis

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            TimingPanelTelemetry -> TelemetryTimingPanel(
                isLandscapeWindow = isLandscapeWindow,
                dashModifier = dashModifier,
                timingRun = timingRun,
                speedLabel = speedLabel,
                speedUnit = speedUnit,
                displaySettings = displaySettings,
                speedHistory = speedHistory,
                metrics = metrics,
                clockUpdateIntervalMillis = clockUpdateIntervalMillis,
                orientation = orientation,
                onToggleOrientation = onToggleOrientation,
                orientationToggleEnabled = orientationToggleEnabled,
                timingPaused = timingPaused,
                onToggleTimingPause = onToggleTimingPause,
                onStopTiming = onStopTiming,
                hudDisplayPage = hudDisplayPage,
                onCycleHudDisplayPage = onCycleHudDisplayPage,
            )
            TimingPanelLapFocusDark -> LapFocusPanel(
                style = LapFocusStyle.Dark,
                timingRun = timingRun,
                fastestLapFlash = fastestLapFlash,
                clockUpdateIntervalMillis = clockUpdateIntervalMillis,
                orientation = orientation,
                onToggleOrientation = onToggleOrientation,
                orientationToggleEnabled = orientationToggleEnabled,
                timingPaused = timingPaused,
                onToggleTimingPause = onToggleTimingPause,
                onStopTiming = onStopTiming,
                hudDisplayPage = hudDisplayPage,
                onCycleHudDisplayPage = onCycleHudDisplayPage,
                isLandscapeWindow = isLandscapeWindow,
                isCompactLandscape = isCompactLandscape,
                padding = padding,
            )
            TimingPanelLapFocusColor -> LapFocusPanel(
                style = LapFocusStyle.ColorFlood,
                timingRun = timingRun,
                fastestLapFlash = fastestLapFlash,
                clockUpdateIntervalMillis = clockUpdateIntervalMillis,
                orientation = orientation,
                onToggleOrientation = onToggleOrientation,
                orientationToggleEnabled = orientationToggleEnabled,
                timingPaused = timingPaused,
                onToggleTimingPause = onToggleTimingPause,
                onStopTiming = onStopTiming,
                hudDisplayPage = hudDisplayPage,
                onCycleHudDisplayPage = onCycleHudDisplayPage,
                isLandscapeWindow = isLandscapeWindow,
                isCompactLandscape = isCompactLandscape,
                padding = padding,
            )
        }
    }
}

private const val TimingPanelTelemetry = 0
private const val TimingPanelLapFocusDark = 1
private const val TimingPanelLapFocusColor = 2
private const val TimingPanelPageCount = 3
private const val ClockUpdateIntervalMillis = 50L
private const val ClockUpdateIntervalWhilePagingMillis = 100L

private data class FastestLapFlash(
    val lapCount: Int,
    val lapMillis: Long,
    val improvementText: String,
)

private enum class LapFocusStyle {
    Dark,
    ColorFlood,
}

@Composable
private fun TelemetryTimingPanel(
    isLandscapeWindow: Boolean,
    dashModifier: Modifier,
    timingRun: TimingRunSnapshot,
    speedLabel: String,
    speedUnit: String,
    displaySettings: DriveDisplaySettings,
    speedHistory: List<Float>,
    metrics: List<TelemetryMetric>,
    clockUpdateIntervalMillis: Long,
    orientation: DashOrientation,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    timingPaused: Boolean,
    onToggleTimingPause: () -> Unit,
    onStopTiming: () -> Unit,
    hudDisplayPage: HudDisplayPage?,
    onCycleHudDisplayPage: (() -> Unit)?,
) {
    val spacing = LapSightTheme.spacing
    // Layout follows the ACTUAL window shape, not the requested lock: on
    // platforms where the lock is a no-op (iOS NoOpOrientationController) the
    // toggle state and the real window can disagree, and a portrait column
    // painted into a landscape window would clip the controls.
    if (isLandscapeWindow) {
        Row(
            modifier = dashModifier,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                PrimaryTimingReadouts(
                    timingRun = timingRun,
                    timingPaused = timingPaused,
                    speedLabel = speedLabel,
                    speedUnit = speedUnit,
                    compact = true,
                    clockUpdateIntervalMillis = clockUpdateIntervalMillis,
                )
                if (displaySettings.showSpeedTrace) {
                    SpeedTrace(
                        samples = speedHistory,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TimingControls(
                    orientation = orientation,
                    onToggleOrientation = onToggleOrientation,
                    orientationToggleEnabled = orientationToggleEnabled,
                    timingPaused = timingPaused,
                    onToggleTimingPause = onToggleTimingPause,
                    onStopTiming = onStopTiming,
                    hudDisplayPage = hudDisplayPage,
                    onCycleHudDisplayPage = onCycleHudDisplayPage,
                )
            }
            TelemetryGrid(
                metrics = metrics,
                compact = true,
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
            )
        }
    } else {
        Column(
            modifier = dashModifier,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            PrimaryTimingReadouts(
                timingRun = timingRun,
                timingPaused = timingPaused,
                speedLabel = speedLabel,
                speedUnit = speedUnit,
                compact = false,
                clockUpdateIntervalMillis = clockUpdateIntervalMillis,
            )
            if (displaySettings.showSpeedTrace) {
                SpeedTrace(
                    samples = speedHistory,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
            TelemetryGrid(
                metrics = metrics,
                compact = true,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            TimingControls(
                orientation = orientation,
                onToggleOrientation = onToggleOrientation,
                orientationToggleEnabled = orientationToggleEnabled,
                timingPaused = timingPaused,
                onToggleTimingPause = onToggleTimingPause,
                onStopTiming = onStopTiming,
                hudDisplayPage = hudDisplayPage,
                onCycleHudDisplayPage = onCycleHudDisplayPage,
            )
        }
    }
}

@Composable
private fun LapFocusPanel(
    style: LapFocusStyle,
    timingRun: TimingRunSnapshot,
    fastestLapFlash: FastestLapFlash?,
    clockUpdateIntervalMillis: Long,
    orientation: DashOrientation,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    timingPaused: Boolean,
    onToggleTimingPause: () -> Unit,
    onStopTiming: () -> Unit,
    hudDisplayPage: HudDisplayPage?,
    onCycleHudDisplayPage: (() -> Unit)?,
    isLandscapeWindow: Boolean,
    isCompactLandscape: Boolean,
    padding: Dp,
) {
    val colors = LapSightTheme.colors
    val targetBackground = when {
        fastestLapFlash != null -> colors.lapFocusFastestBackground
        style == LapFocusStyle.Dark -> colors.lapFocusNeutralBackground
        timingRun.deltaDisplay.tone == DeltaTone.Faster -> colors.lapFocusFasterBackground
        timingRun.deltaDisplay.tone == DeltaTone.Slower -> colors.lapFocusSlowerBackground
        else -> colors.lapFocusNeutralBackground
    }
    val background by animateColorAsState(targetValue = targetBackground)
    val flooded = style == LapFocusStyle.ColorFlood || fastestLapFlash != null
    val focusBackground = flooded || style == LapFocusStyle.Dark
    val primaryColor =
        if (focusBackground) colors.onLapFocusBackground else MaterialTheme.colorScheme.primary
    val secondaryColor =
        if (focusBackground) colors.onLapFocusBackground.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    val deltaColor =
        if (flooded) colors.onLapFocusBackground else timingRun.deltaDisplay.tone.toDeltaColor()
    val heroLabel = if (fastestLapFlash != null) {
        "FASTEST LAP"
    } else {
        timingRun.currentLapNumber?.let { "LAP $it" } ?: "LAP"
    }
    val deltaLabel = if (fastestLapFlash != null) "GAIN" else "DELTA"
    val deltaText = fastestLapFlash?.improvementText ?: timingRun.deltaDisplay.text
    val compact = isLandscapeWindow && isCompactLandscape
    val spacing = LapSightTheme.spacing

    val panelModifier = Modifier
        .fillMaxSize()
        .background(background)
        .safeContentPadding()
        .padding(padding)
        .padding(spacing.md)
    if (isLandscapeWindow) {
        Column(
            modifier = panelModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = heroLabel,
                    color = secondaryColor,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Text(
                    text = timingRun.bestLapMillis.formatLapTime(),
                    color = secondaryColor,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.35f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    LapFocusTimeReadout(
                        timingRun = timingRun,
                        timingPaused = timingPaused,
                        fastestLapFlash = fastestLapFlash,
                        clockUpdateIntervalMillis = clockUpdateIntervalMillis,
                        color = primaryColor,
                        compact = compact,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    LapFocusDeltaReadout(
                        label = deltaLabel,
                        value = deltaText,
                        labelColor = secondaryColor,
                        valueColor = deltaColor,
                        compact = compact,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                LapFocusPageIndicator(
                    pageIndex = if (style == LapFocusStyle.Dark) TimingPanelLapFocusDark else TimingPanelLapFocusColor,
                    color = secondaryColor,
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(if (isCompactLandscape) 0.44f else 0.34f),
                ) {
                    TimingControls(
                        orientation = orientation,
                        onToggleOrientation = onToggleOrientation,
                        orientationToggleEnabled = orientationToggleEnabled,
                        timingPaused = timingPaused,
                        onToggleTimingPause = onToggleTimingPause,
                        onStopTiming = onStopTiming,
                        hudDisplayPage = hudDisplayPage,
                        onCycleHudDisplayPage = onCycleHudDisplayPage,
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = panelModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = heroLabel,
                color = secondaryColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text(
                text = timingRun.bestLapMillis.formatLapTime(),
                color = secondaryColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                LapFocusTimeReadout(
                    timingRun = timingRun,
                    timingPaused = timingPaused,
                    fastestLapFlash = fastestLapFlash,
                    clockUpdateIntervalMillis = clockUpdateIntervalMillis,
                    color = primaryColor,
                    compact = compact,
                )
                LapFocusDeltaReadout(
                    label = deltaLabel,
                    value = deltaText,
                    labelColor = secondaryColor,
                    valueColor = deltaColor,
                    compact = compact,
                )
            }
        }
        LapFocusPageIndicator(
            pageIndex = if (style == LapFocusStyle.Dark) TimingPanelLapFocusDark else TimingPanelLapFocusColor,
            color = secondaryColor,
        )
        TimingControls(
            orientation = orientation,
            onToggleOrientation = onToggleOrientation,
            orientationToggleEnabled = orientationToggleEnabled,
            timingPaused = timingPaused,
            onToggleTimingPause = onToggleTimingPause,
            onStopTiming = onStopTiming,
            hudDisplayPage = hudDisplayPage,
            onCycleHudDisplayPage = onCycleHudDisplayPage,
        )
    }
}

@Composable
private fun LapFocusTimeReadout(
    timingRun: TimingRunSnapshot,
    timingPaused: Boolean,
    fastestLapFlash: FastestLapFlash?,
    clockUpdateIntervalMillis: Long,
    color: Color,
    compact: Boolean,
) {
    val heroMaxFontSize = if (compact) {
        LapSightAutoSize.lapFocusTimeMaxCompact
    } else {
        LapSightAutoSize.lapFocusTimeMax
    }
    if (fastestLapFlash != null) {
        StaticLapTimeText(
            millis = fastestLapFlash.lapMillis,
            color = color,
            textAlign = TextAlign.Center,
            minFontSize = LapSightAutoSize.lapFocusTimeMin,
            maxFontSize = heroMaxFontSize,
            style = MaterialTheme.typography.displayLarge.copy(fontFamily = LapSightTheme.monoFamily),
        )
    } else {
        RunningLapTimeText(
            currentLapMillis = timingRun.currentLapMillis,
            isActive = timingRun.isActive,
            isRunning = !timingPaused,
            updateIntervalMillis = clockUpdateIntervalMillis,
            color = color,
            textAlign = TextAlign.Center,
            minFontSize = LapSightAutoSize.lapFocusTimeMin,
            maxFontSize = heroMaxFontSize,
            style = MaterialTheme.typography.displayLarge.copy(fontFamily = LapSightTheme.monoFamily),
        )
    }
}

@Composable
private fun LapFocusDeltaReadout(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    compact: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            text = value,
            color = valueColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = LapSightAutoSize.lapFocusDeltaMin,
                maxFontSize = if (compact) {
                    LapSightAutoSize.lapFocusDeltaMaxCompact
                } else {
                    LapSightAutoSize.lapFocusDeltaMax
                },
                stepSize = LapSightAutoSize.step,
            ),
            style = MaterialTheme.typography.displayMedium.copy(fontFamily = LapSightTheme.monoFamily),
        )
    }
}

@Composable
private fun LapFocusPageIndicator(
    pageIndex: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LapSightTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(TimingPanelPageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == pageIndex) 8.dp else 6.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(color.copy(alpha = if (index == pageIndex) 0.92f else 0.34f)),
            )
        }
    }
}

private data class TelemetryMetric(
    val label: String,
    val value: String,
    val unit: String = "",
)

@Composable
private fun RunningLapTimeText(
    currentLapMillis: Long?,
    isActive: Boolean,
    isRunning: Boolean,
    updateIntervalMillis: Long,
    color: Color,
    textAlign: TextAlign,
    minFontSize: TextUnit,
    maxFontSize: TextUnit,
    style: TextStyle,
) {
    var displayMillis by remember(isActive) { mutableStateOf(0L) }
    var baseMillis by remember(isActive) { mutableStateOf<Long?>(null) }
    var baseEpochMillis by remember(isActive) { mutableStateOf(nowEpochMillis()) }

    LaunchedEffect(isActive, currentLapMillis) {
        if (isActive) {
            val base = currentLapMillis ?: 0L
            displayMillis = base
            baseMillis = base
            baseEpochMillis = nowEpochMillis()
        } else {
            displayMillis = 0L
            baseMillis = null
        }
    }

    LaunchedEffect(isActive, isRunning, updateIntervalMillis) {
        while (isActive && isRunning) {
            delay(updateIntervalMillis.coerceAtLeast(16L))
            val base = baseMillis ?: continue
            val delta = nowEpochMillis() - baseEpochMillis
            if (delta >= 0) {
                displayMillis = base + delta
            }
        }
    }

    StaticLapTimeText(
        millis = displayMillis,
        color = color,
        textAlign = textAlign,
        minFontSize = minFontSize,
        maxFontSize = maxFontSize,
        style = style,
    )
}

@Composable
private fun StaticLapTimeText(
    millis: Long,
    color: Color,
    textAlign: TextAlign,
    minFontSize: TextUnit,
    maxFontSize: TextUnit,
    style: TextStyle,
) {
    Text(
        text = millis.formatLapTime(),
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = maxFontSize,
            stepSize = LapSightAutoSize.step,
        ),
        style = style,
    )
}

@Composable
private fun PrimaryTimingReadouts(
    timingRun: TimingRunSnapshot,
    timingPaused: Boolean,
    speedLabel: String,
    speedUnit: String,
    compact: Boolean,
    clockUpdateIntervalMillis: Long,
) {
    val spacing = LapSightTheme.spacing
    val mono = LapSightTheme.monoFamily
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "CURRENT LAP",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        // Glance-safe hero readout (D-31): the display role anchors the type and
        // autoSize shrinks to fit the viewport so long lap times never clip.
        RunningLapTimeText(
            currentLapMillis = timingRun.currentLapMillis,
            isActive = timingRun.isActive,
            isRunning = !timingPaused,
            updateIntervalMillis = clockUpdateIntervalMillis,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Start,
            minFontSize = LapSightAutoSize.heroMin,
            maxFontSize = if (compact) LapSightAutoSize.heroMaxCompact else LapSightAutoSize.heroMax,
            style = MaterialTheme.typography.displayLarge.copy(fontFamily = mono),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                DeltaReadout(
                    display = timingRun.deltaDisplay,
                    compact = true,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SPEED",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = speedLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = LapSightAutoSize.speedMin,
                            maxFontSize = if (compact) LapSightAutoSize.speedMaxCompact else LapSightAutoSize.speedMax,
                            stepSize = LapSightAutoSize.step,
                        ),
                        style = MaterialTheme.typography.displaySmall.copy(fontFamily = mono),
                    )
                    Text(
                        text = speedUnit,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(start = spacing.xs, bottom = spacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryGrid(
    metrics: List<TelemetryMetric>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = LapSightTheme.spacing
    val rows = metrics.chunked(3)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                row.forEach { metric ->
                    TelemetryCell(
                        metric = metric,
                        compact = compact,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TelemetryCell(
    metric: TelemetryMetric,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = LapSightTheme.spacing
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .padding(spacing.sm),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = metric.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Spacer(Modifier.height(spacing.xs))
            Row(verticalAlignment = Alignment.Bottom) {
                TimingText(
                    text = metric.value,
                    style = if (compact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                )
                if (metric.unit.isNotEmpty()) {
                    Text(
                        text = metric.unit,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = spacing.xs, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedTrace(
    samples: List<Float>,
    modifier: Modifier = Modifier,
) {
    val axisColor = LapSightTheme.colors.chartGrid
    val lineColor = MaterialTheme.colorScheme.primary
    val spacing = LapSightTheme.spacing
    Canvas(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .padding(spacing.sm),
    ) {
        drawLine(
            color = axisColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
        if (samples.size < 2) return@Canvas
        val maxSpeed = samples.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val xStep = size.width / (samples.size - 1)
        samples.zipWithNext().forEachIndexed { index, pair ->
            drawLine(
                color = lineColor,
                start = Offset(
                    x = index * xStep,
                    y = size.height - (pair.first / maxSpeed * size.height),
                ),
                end = Offset(
                    x = (index + 1) * xStep,
                    y = size.height - (pair.second / maxSpeed * size.height),
                ),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun TimingControls(
    orientation: DashOrientation,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    timingPaused: Boolean,
    onToggleTimingPause: () -> Unit,
    onStopTiming: () -> Unit,
    hudDisplayPage: HudDisplayPage?,
    onCycleHudDisplayPage: (() -> Unit)?,
) {
    val spacing = LapSightTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Timing-active controls need a gloved-use target; Stop gets the larger
        // footprint because it is the critical moving-state action.
        Button(
            onClick = onToggleTimingPause,
            modifier = Modifier.weight(0.8f).height(64.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = if (timingPaused) ResumeActionIcon else PauseActionIcon,
                contentDescription = if (timingPaused) "Resume timing" else "Pause timing",
                modifier = Modifier.size(32.dp),
            )
        }
        Button(
            onClick = onStopTiming,
            modifier = Modifier.weight(1.2f).height(76.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                imageVector = StopActionIcon,
                contentDescription = "Stop timing",
                modifier = Modifier.size(34.dp),
            )
        }
        if (hudDisplayPage != null && onCycleHudDisplayPage != null) {
            Button(
                onClick = onCycleHudDisplayPage,
                modifier = Modifier.weight(0.8f).height(64.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(
                    text = "HUD\n${hudDisplayPage.wireName.take(4)}",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Button(
            onClick = onToggleOrientation,
            enabled = orientationToggleEnabled,
            modifier = Modifier.weight(0.6f).height(64.dp),
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
                    if (orientation == DashOrientation.Portrait) "Switch to landscape" else "Switch to portrait",
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * Value-only live-delta readout (D-13, D-14, D-15, D-18).
 *
 * Renders exactly the display text (`--`, `+0.421s`, `-0.218s`) in the semantic
 * tone color. No words, no explanatory copy, no stale value — the
 * [DeltaDisplayState] already collapsed unavailable states to `--`/neutral.
 */
@Composable
private fun DeltaReadout(
    display: DeltaDisplayState,
    compact: Boolean,
) {
    Text(
        text = "DELTA",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
    Text(
        text = display.text,
        color = display.tone.toDeltaColor(),
        textAlign = TextAlign.Start,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = LapSightAutoSize.deltaMin,
            maxFontSize = if (compact) LapSightAutoSize.deltaMaxCompact else LapSightAutoSize.deltaMax,
            stepSize = LapSightAutoSize.step,
        ),
        style = MaterialTheme.typography.displayMedium.copy(fontFamily = LapSightTheme.monoFamily),
    )
}

/**
 * Racing-convention delta colors (LapSightColors semantic layer): green =
 * faster than reference, red = slower, gray = neutral / unavailable.
 */
@Composable
internal fun DeltaTone.toDeltaColor(): Color = when (this) {
    DeltaTone.Faster -> LapSightTheme.colors.deltaFaster
    DeltaTone.Slower -> LapSightTheme.colors.deltaSlower
    DeltaTone.Neutral -> LapSightTheme.colors.deltaNeutral
}
