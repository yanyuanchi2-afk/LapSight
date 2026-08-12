package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.DriveDisplaySettings
import com.huanfuli.lapsight.shared.LocationFeedMode
import com.huanfuli.lapsight.shared.PhoneGpsPermissionState
import com.huanfuli.lapsight.shared.SpeedUnit
import com.huanfuli.lapsight.shared.external.ExternalGnssFixType
import com.huanfuli.lapsight.shared.session.RawRecordingSnapshot
import com.huanfuli.lapsight.shared.session.ReadyBlocker
import com.huanfuli.lapsight.shared.session.ReadyState
import com.huanfuli.lapsight.shared.session.ReadyThresholds
import com.huanfuli.lapsight.shared.ui.DriveMarkingPhase
import com.huanfuli.lapsight.shared.ui.DriveMarkingSnapshot
import com.huanfuli.lapsight.shared.ui.LapSightTheme
import com.huanfuli.lapsight.shared.ui.components.ChipTone
import com.huanfuli.lapsight.shared.ui.components.MetricCell
import com.huanfuli.lapsight.shared.ui.components.MetricCellSize
import com.huanfuli.lapsight.shared.ui.components.StatusChip

/**
 * Stationary GPS/readiness status bar (D-13/D-14/D-32): source + Ready state
 * chips over speed/accuracy/samples/rate metric cells — one row of four in
 * portrait, a 2×2 [grid] inside the narrow landscape control rail.
 */
@Composable
internal fun DriveStatusBar(
    snapshot: DriveMarkingSnapshot,
    displaySettings: DriveDisplaySettings,
    locationFeedMode: LocationFeedMode,
    phoneGpsPermission: PhoneGpsPermissionState,
    dashReady: ReadyState,
    rawRecordingActive: Boolean,
    rawSnapshot: RawRecordingSnapshot,
    modifier: Modifier = Modifier,
    grid: Boolean = false,
    dense: Boolean = false,
) {
    val speedMultiplier = when (displaySettings.speedUnit) {
        SpeedUnit.KilometersPerHour -> 3.6
        SpeedUnit.MilesPerHour -> 2.2369362921
    }
    val speedUnit = when (displaySettings.speedUnit) {
        SpeedUnit.KilometersPerHour -> "km/h"
        SpeedUnit.MilesPerHour -> "mph"
    }
    val speedLabel = snapshot.latestSample?.speedMetersPerSecond
        ?.let { speed ->
            when {
                !speed.isFinite() -> "--"
                speed < StationarySpeedDeadbandMetersPerSecond -> "0"
                else -> (speed * speedMultiplier).toInt().toString()
            }
        }
        ?: "--"
    val sourceLabel: String
    val sourceTone: ChipTone
    when (locationFeedMode) {
        LocationFeedMode.PhoneGps -> if (phoneGpsPermission.isGranted) {
            sourceLabel = "GPS OK"
            sourceTone = ChipTone.Ready
        } else {
            sourceLabel = "GPS NEEDED"
            sourceTone = ChipTone.Caution
        }
        LocationFeedMode.ExternalGnss -> if (snapshot.latestSample != null) {
            sourceLabel = when (snapshot.latestSample.externalFixType) {
                ExternalGnssFixType.RtkFixed -> "RTK FIXED"
                ExternalGnssFixType.RtkFloat -> "RTK FLOAT"
                ExternalGnssFixType.DifferentialGps -> "DGNSS"
                else -> "EXT GPS"
            }
            sourceTone = if (snapshot.latestSample.externalFixType == ExternalGnssFixType.RtkFixed) {
                ChipTone.Ready
            } else {
                ChipTone.Caution
            }
        } else {
            sourceLabel = "EXT WAIT"
            sourceTone = ChipTone.Caution
        }
        LocationFeedMode.Simulated -> {
            sourceLabel = "SIM"
            sourceTone = ChipTone.Demo
        }
    }
    val rateLabel = snapshot.feedQuality?.averageUpdateRateHz?.let { formatOneDecimal(it) } ?: "--"
    // GNSS quality glance: satellites used and dual-frequency (L5) capability, only
    // when a direct-GNSS fix actually reported them (null on simulated/Fused fixes).
    val gnssLabel = snapshot.latestSample?.let { fix ->
        val sats = fix.satellitesInUse
        val dualFrequency = fix.usesDualFrequency == true
        when {
            sats != null && dualFrequency -> "$sats SAT · L5"
            sats != null -> "$sats SAT"
            dualFrequency -> "L5"
            else -> null
        }
    }
    // Ready / not-Ready glance state (D-13/D-14/D-32). Same green/amber semantic
    // branches as the source chip so the dash reads consistently.
    val readyLabel: String
    val readyColor: Color
    when {
        // Marking capture in progress: the header must describe THIS activity,
        // not scare with "NOT READY - no track" while the user records a course.
        snapshot.phase == DriveMarkingPhase.Capturing -> {
            readyLabel = "MARKING - recording course"
            readyColor = LapSightTheme.colors.recording
        }
        rawRecordingActive -> {
            readyLabel = "RAW REC - ${rawSnapshot.sampleCount} pts"
            readyColor = LapSightTheme.colors.statusCaution
        }
        dashReady is ReadyState.Ready -> {
            val warning = gpsQualityWarning(snapshot)
            if (warning != null) {
                readyLabel = "READY - ${warning.dashLabel()}"
                readyColor = LapSightTheme.colors.statusCaution
            } else {
                readyLabel = "READY"
                readyColor = LapSightTheme.colors.statusReady
            }
        }
        else -> {
            val primary = (dashReady as ReadyState.NotReady).reasons.firstOrNull()
            readyLabel = "NOT READY - ${primary?.dashLabel() ?: "checking"}"
            readyColor = LapSightTheme.colors.statusCaution
        }
    }
    val spacing = LapSightTheme.spacing
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LapSightTheme.colors.cardBorder),
    ) {
        Column(
            modifier = Modifier.padding(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                StatusChip(text = sourceLabel, tone = sourceTone)
                gnssLabel?.let { StatusChip(text = it, tone = ChipTone.Neutral) }
                Text(
                    text = readyLabel,
                    color = readyColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            val cells = listOf(
                "SPEED" to "$speedLabel $speedUnit",
                "ACCURACY" to "${snapshot.accuracyLabel} m",
                "SAMPLES" to snapshot.feedSampleCount.toString(),
                "RATE" to "$rateLabel Hz",
            )
            if (dense) {
                Text(
                    text = "SPD $speedLabel $speedUnit | ACC ${snapshot.accuracyLabel} m | RATE $rateLabel Hz",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                cells.chunked(if (grid) 2 else cells.size).forEach { rowCells ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        rowCells.forEach { (label, value) ->
                            MetricCell(
                                label = label,
                                value = value,
                                modifier = Modifier.weight(1f),
                                size = MetricCellSize.Compact,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Short glance label for a not-Ready primary reason shown on the dash (D-32). */
internal fun ReadyBlocker.dashLabel(): String = when (this) {
    ReadyBlocker.MissingFix -> "no GPS fix"
    ReadyBlocker.NonFiniteFix -> "bad GPS fix"
    ReadyBlocker.PoorAccuracy -> "GPS accuracy"
    ReadyBlocker.StaleFix -> "stale GPS"
    ReadyBlocker.LowSampleRate -> "low GPS rate"
    ReadyBlocker.NoCourseSelected -> "no track"
    ReadyBlocker.StartFinishUnconfirmed -> "no start/finish"
    ReadyBlocker.DirectionIncompatible -> "direction"
    ReadyBlocker.WrongCourseBlocked -> "wrong course"
    ReadyBlocker.PreflightUnavailable -> "course check"
}

private const val StationarySpeedDeadbandMetersPerSecond = 2.0

private fun gpsQualityWarning(snapshot: DriveMarkingSnapshot): ReadyBlocker? {
    val thresholds = ReadyThresholds.Default
    val fix = snapshot.latestSample ?: return null
    val accuracy = fix.horizontalAccuracyMeters
    if (accuracy == null || !accuracy.isFinite() || accuracy < 0.0 ||
        accuracy > thresholds.maxHorizontalAccuracyMeters
    ) {
        return ReadyBlocker.PoorAccuracy
    }
    val rate = snapshot.feedQuality?.averageUpdateRateHz
    if (rate == null || !rate.isFinite() || rate < thresholds.minSampleRateHz) {
        return ReadyBlocker.LowSampleRate
    }
    return null
}

/**
 * Conservative Ready preview computed from the stationary dash inputs
 * (D-13/D-14/D-32). It mirrors the authoritative [com.huanfuli.lapsight.shared.session.aggregateReady]
 * thresholds over the hard inputs the dash can see — GPS fix presence/validity
 * and current-track selection. GPS accuracy and update cadence are warnings in
 * [DriveStatusBar], not Start Timing blockers.
 */
internal fun dashReadyState(snapshot: DriveMarkingSnapshot): ReadyState {
    val reasons = mutableListOf<ReadyBlocker>()
    val fix = snapshot.latestSample
    if (fix == null) {
        reasons += ReadyBlocker.MissingFix
    } else if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
        fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0
    ) {
        reasons += ReadyBlocker.NonFiniteFix
    }
    if (!snapshot.canStartTiming) {
        reasons += ReadyBlocker.NoCourseSelected
    }
    return if (reasons.isEmpty()) ReadyState.Ready else ReadyState.NotReady(reasons)
}

internal fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10.0).toInt()
    return "${scaled / 10}.${scaled % 10}"
}
