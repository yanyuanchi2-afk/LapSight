package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.review.buildTrackTraceLayers
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.SectorLineDto
import com.huanfuli.lapsight.shared.track.TrackReviewState
import com.huanfuli.lapsight.shared.ui.CloseActionIcon
import com.huanfuli.lapsight.shared.ui.DeleteActionIcon
import com.huanfuli.lapsight.shared.ui.DriveMarkingController
import com.huanfuli.lapsight.shared.ui.DriveMarkingSnapshot
import com.huanfuli.lapsight.shared.ui.LapSightTheme
import com.huanfuli.lapsight.shared.ui.LocalizedStrings
import com.huanfuli.lapsight.shared.ui.ReplayActionIcon
import com.huanfuli.lapsight.shared.ui.SaveSessionIcon
import com.huanfuli.lapsight.shared.ui.TraceView
import com.huanfuli.lapsight.shared.ui.strings
import com.huanfuli.lapsight.shared.ui.components.ChipTone
import com.huanfuli.lapsight.shared.ui.components.DisclosureSection
import com.huanfuli.lapsight.shared.ui.components.LapButton
import com.huanfuli.lapsight.shared.ui.components.LapButtonStyle
import com.huanfuli.lapsight.shared.ui.components.LapCard
import com.huanfuli.lapsight.shared.ui.components.LapDialog
import com.huanfuli.lapsight.shared.ui.components.MetricCell
import com.huanfuli.lapsight.shared.ui.components.MetricCellSize
import com.huanfuli.lapsight.shared.ui.components.StatusChip

/**
 * Track Review surface rendered when a marking capture stops (D-31).
 *
 * Leads with the just-captured course map — the one artifact that shows the
 * capture worked. A clean capture offers exactly two actions: Save Track
 * (which places the start/finish at the recorded start when not already set —
 * same call the old explicit button made; adjustable later in the course
 * editor) and Discard. A failed capture leads with Re-record as the recovery
 * path instead of dominant disabled buttons. Track Review never shows lap
 * times for marking samples (D-08); Re-record and Discard require explicit
 * confirmation.
 *
 * Portrait renders a scrolling card with QA numbers behind a Capture-details
 * disclosure. Landscape renders a fixed two-pane spread — map left, actions
 * right, QA numbers as a caption under the map — with no scrolling.
 */
@Composable
internal fun TrackReviewContent(
    snapshot: DriveMarkingSnapshot,
    controller: DriveMarkingController,
    onChanged: () -> Unit,
    onSavedTrack: () -> Unit,
) {
    val review = snapshot.reviewState ?: return
    val s = strings
    var confirmReRecord by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Name-on-create affordance (D-02, SC-01): the user names the Track before saving.
    // The controller validates/falls back to a default if left blank.
    var trackName by remember { mutableStateOf("") }

    if (confirmReRecord) {
        LapDialog(
            title = s.reRecordTrackTitle,
            text = s.reRecordTrackText,
            onDismissRequest = { confirmReRecord = false },
            confirmText = s.reRecordTrack,
            confirmIcon = ReplayActionIcon,
            confirmIconOnly = true,
            confirmContentDescription = s.reRecordTrack,
            onConfirm = {
                confirmReRecord = false
                controller.reRecord()
                onChanged()
            },
            dismissText = s.cancel,
            dismissIcon = CloseActionIcon,
            dismissIconOnly = true,
            dismissContentDescription = s.cancel,
        )
    }
    if (confirmDiscard) {
        LapDialog(
            title = s.discardTrackTitle,
            text = s.discardTrackText,
            onDismissRequest = { confirmDiscard = false },
            confirmText = s.discard,
            destructiveConfirm = true,
            confirmIcon = DeleteActionIcon,
            confirmIconOnly = true,
            confirmContentDescription = s.discardTrack,
            onConfirm = {
                confirmDiscard = false
                controller.discard()
                onChanged()
            },
            dismissText = s.cancel,
            dismissIcon = CloseActionIcon,
            dismissIconOnly = true,
            dismissContentDescription = s.cancel,
        )
    }

    val onNameChange: (String) -> Unit = {
        trackName = it
        controller.setTrackName(it)
    }
    val onSave = {
        controller.setTrackName(trackName)
        controller.saveTrack()
        onChanged()
        onSavedTrack()
    }

    BoxWithConstraints {
        if (maxWidth > maxHeight) {
            TrackReviewLandscape(
                review = review,
                trackName = trackName,
                onNameChange = onNameChange,
                onSave = onSave,
                onReRecordRequest = { confirmReRecord = true },
                onDiscardRequest = { confirmDiscard = true },
            )
        } else {
            TrackReviewPortraitCard(
                review = review,
                trackName = trackName,
                onNameChange = onNameChange,
                onSave = onSave,
                onReRecordRequest = { confirmReRecord = true },
                onDiscardRequest = { confirmDiscard = true },
            )
        }
    }
}

/** One-line status for a clean capture, shared by both orientations. */
private fun cleanCaptureStatus(review: TrackReviewState, s: LocalizedStrings): String =
    when (review.extraction.topology) {
        CourseTopology.PointToPoint -> "${s.pointToPoint}. ${s.startFinishSet}"
        CourseTopology.Circuit -> {
            val capture = if (review.extraction.acceptedLoopCount <= 1) {
                "1 ${s.accepted}"
            } else {
                "${review.extraction.acceptedLoopCount} ${s.cleanLoopsCaptured}"
            }
            "$capture. " + if (review.startFinish != null) {
                s.startFinishSet
            } else {
                s.savingPlacesStartFinish
            }
        }
    }

@Composable
private fun TrackReviewPortraitCard(
    review: TrackReviewState,
    trackName: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onReRecordRequest: () -> Unit,
    onDiscardRequest: () -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    LapCard {
        Text(
            text = s.trackReview,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        TrackReviewSourceChip(review)

        // The captured course, drawn from the same layers Review uses. Shown for
        // failed captures too — seeing the broken trace explains the failure.
        CapturedCourseMap(review = review)

        if (review.canSave) {
            Text(
                text = cleanCaptureStatus(review, s),
                color = LapSightTheme.colors.statusReady,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(spacing.xs))
            // Name this Track before saving (D-02). Blank falls back to the default
            // name in the controller; the name never forms a storage path (T-05-07).
            OutlinedTextField(
                value = trackName,
                onValueChange = onNameChange,
                label = { Text(s.trackName) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            // Save Track — the one primary action on a clean capture.
            LapButton(
                text = s.save,
                onClick = onSave,
                icon = SaveSessionIcon,
                modifier = Modifier.fillMaxWidth(),
            )
            LapButton(
                text = s.discard,
                onClick = onDiscardRequest,
                style = LapButtonStyle.GhostDestructive,
                icon = DeleteActionIcon,
                iconOnly = true,
                contentDescription = s.discardTrack,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = s.failedCaptureStatusFor(review.extraction.topology),
                color = LapSightTheme.colors.statusCaution,
                style = MaterialTheme.typography.bodyLarge,
            )
            // Failed capture: recovery leads (Re-record), no disabled Save in sight.
            LapButton(
                text = s.retry,
                onClick = onReRecordRequest,
                icon = ReplayActionIcon,
                modifier = Modifier.fillMaxWidth(),
            )
            LapButton(
                text = s.discard,
                onClick = onDiscardRequest,
                style = LapButtonStyle.GhostDestructive,
                icon = DeleteActionIcon,
                iconOnly = true,
                contentDescription = s.discardTrack,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = s.noLapTimesNote,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        DisclosureSection(title = s.captureDetails) {
            MetricCell(
                label = s.loops,
                value = "${review.extraction.detectedLoopCount} ${s.detected} · " +
                    "${review.extraction.acceptedLoopCount} ${s.accepted} · " +
                    "${review.extraction.rejectedLoopCount} ${s.rejected}",
                size = MetricCellSize.Row,
            )
            MetricCell(
                label = s.samples,
                value = "${review.rawSampleCount} · ${s.degraded}: ${review.quality.degradedSampleCount}",
                size = MetricCellSize.Row,
            )
        }
    }
}

/**
 * Landscape spread: captured course fills the left pane over a QA caption and
 * the D-08 note; the right rail holds identity, status, and the two actions
 * bottom-anchored. Everything is on screen at once — nothing scrolls.
 */
@Composable
private fun TrackReviewLandscape(
    review: TrackReviewState,
    trackName: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onReRecordRequest: () -> Unit,
    onDiscardRequest: () -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            CapturedCourseMap(
                review = review,
                modifier = Modifier.weight(1f),
                fillParent = true,
            )
            Text(
                text = "${review.extraction.detectedLoopCount} ${s.loops} ${s.detected} · " +
                    "${review.extraction.acceptedLoopCount} ${s.accepted} · " +
                    "${review.rawSampleCount} ${s.samples} · " +
                    "${review.quality.degradedSampleCount} ${s.degraded}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = s.noLapTimesNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(
            modifier = Modifier.width(320.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = s.trackReview,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            TrackReviewSourceChip(review)
            if (review.canSave) {
                Text(
                    text = cleanCaptureStatus(review, s),
                    color = LapSightTheme.colors.statusReady,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = trackName,
                    onValueChange = onNameChange,
                    label = { Text(s.trackName) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                LapButton(
                    text = s.save,
                    onClick = onSave,
                    icon = SaveSessionIcon,
                    modifier = Modifier.fillMaxWidth(),
                )
                LapButton(
                    text = s.discard,
                    onClick = onDiscardRequest,
                    style = LapButtonStyle.GhostDestructive,
                    icon = DeleteActionIcon,
                    iconOnly = true,
                    contentDescription = s.discardTrack,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = s.failedCaptureStatusFor(review.extraction.topology),
                    color = LapSightTheme.colors.statusCaution,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                LapButton(
                    text = s.retry,
                    onClick = onReRecordRequest,
                    icon = ReplayActionIcon,
                    modifier = Modifier.fillMaxWidth(),
                )
                LapButton(
                    text = s.discard,
                    onClick = onDiscardRequest,
                    style = LapButtonStyle.GhostDestructive,
                    icon = DeleteActionIcon,
                    iconOnly = true,
                    contentDescription = s.discardTrack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TrackReviewSourceChip(review: TrackReviewState) {
    val s = strings
    when (review.extraction.markingSession.source.source) {
        LocationSource.Simulated -> StatusChip(text = s.demoSimGps, tone = ChipTone.Demo)
        LocationSource.PhoneGps -> StatusChip(text = s.phoneGpsLive, tone = ChipTone.Ready)
        LocationSource.ExternalGnss -> StatusChip(text = s.externalGnss, tone = ChipTone.Ready)
    }
}

/** The captured marking trace + extracted reference line + start/finish. */
@Composable
private fun CapturedCourseMap(
    review: TrackReviewState,
    modifier: Modifier = Modifier,
    fillParent: Boolean = false,
) {
    val layers = remember(review) {
        val finishAsLine = review.finishLine?.let {
            listOf(SectorLineDto("finish", "Finish", 999, it.pointA, it.pointB))
        } ?: emptyList()
        buildTrackTraceLayers(
            markingSamples = review.extraction.markingSession.samples,
            referenceLine = review.extraction.referenceLine,
            startFinish = review.startFinish,
            sectors = finishAsLine,
            outlierSamples = emptyList(),
            viewWidth = 400.0,
            viewHeight = 300.0,
        )
    }
    if (layers.isNotEmpty()) {
        TraceView(
            layers = layers,
            modifier = modifier,
            minHeight = 200.dp,
            maxHeight = 260.dp,
            fillParent = fillParent,
        )
    }
}
