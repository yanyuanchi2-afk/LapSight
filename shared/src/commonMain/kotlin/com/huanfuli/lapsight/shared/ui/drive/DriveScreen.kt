package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.DashOrientation
import com.huanfuli.lapsight.shared.HudRemoteCommand
import com.huanfuli.lapsight.shared.HudDisplayPage
import com.huanfuli.lapsight.shared.HudMarkingLog
import com.huanfuli.lapsight.shared.HudRuntimeState
import com.huanfuli.lapsight.shared.HudTimingTelemetry
import com.huanfuli.lapsight.shared.HudTimingLog
import com.huanfuli.lapsight.shared.HudSessionPhase
import com.huanfuli.lapsight.shared.DriveDisplaySettings
import com.huanfuli.lapsight.shared.GpsFixStatus
import com.huanfuli.lapsight.shared.LocationFeedMode
import com.huanfuli.lapsight.shared.LocationSampleProvider
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.PhoneGpsPermissionState
import com.huanfuli.lapsight.shared.nowEpochMillis
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
import com.huanfuli.lapsight.shared.ghost.DeltaDisplayState
import com.huanfuli.lapsight.shared.session.RawRecordingController
import com.huanfuli.lapsight.shared.session.AppMetadata
import com.huanfuli.lapsight.shared.session.HudTimingReviewImportResult
import com.huanfuli.lapsight.shared.session.SaveDraftResult
import com.huanfuli.lapsight.shared.session.SessionController
import com.huanfuli.lapsight.shared.session.SourceMetadata
import com.huanfuli.lapsight.shared.session.StartTimingResult
import com.huanfuli.lapsight.shared.session.TimingRunSnapshot
import com.huanfuli.lapsight.shared.session.buildHudTimingReviewPayload
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.storage.LoadResult
import com.huanfuli.lapsight.shared.storage.SaveResult
import com.huanfuli.lapsight.shared.ui.CheckActionIcon
import com.huanfuli.lapsight.shared.ui.CloseActionIcon
import com.huanfuli.lapsight.shared.ui.DeleteActionIcon
import com.huanfuli.lapsight.shared.ui.DriveMarkingController
import com.huanfuli.lapsight.shared.ui.DriveMarkingPhase
import com.huanfuli.lapsight.shared.ui.DriveMarkingSnapshot
import com.huanfuli.lapsight.shared.ui.LapSightTheme
import com.huanfuli.lapsight.shared.ui.SaveSessionIcon
import com.huanfuli.lapsight.shared.ui.START_TIMING_BLOCKED_COPY
import com.huanfuli.lapsight.shared.ui.components.LapDialog
import com.huanfuli.lapsight.shared.ui.components.LapDialogTextButton
import com.huanfuli.lapsight.shared.ui.strings
import com.huanfuli.lapsight.shared.glasses.GlassesGpsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val DISCARD_SESSION_ARMED_TIMEOUT_MILLIS = 4500L

/**
 * The Drive tab: selected GPS feed + Mark New Track capture + Track Review.
 *
 * Owns a [DriveMarkingController] over the injected [LocationSampleProvider] and
 * renders the mounted-phone dash. Phone GPS and simulated replay therefore feed
 * the same `LocationSample` stream into marking, timing, storage, and review.
 */
@Composable
fun DriveScreen(
    orientation: DashOrientation,
    onToggleOrientation: () -> Unit,
    orientationToggleEnabled: Boolean,
    onSavedTrack: () -> Unit,
    onSavedSession: () -> Unit,
    onTimingActiveChanged: (Boolean) -> Unit,
    requestedTimingActive: Boolean,
    displaySettings: DriveDisplaySettings,
    locationFeedMode: LocationFeedMode,
    locationProvider: LocationSampleProvider,
    phoneGpsPermission: PhoneGpsPermissionState,
    sessionStore: LocalSessionStore,
    sessionController: SessionController,
    onGlassesIdleGpsStateChanged: (GlassesGpsState) -> Unit = {},
    externalGnssConnectionState: StateFlow<ExternalGnssConnectionState> =
        MutableStateFlow(ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected)),
    hudRuntimeState: StateFlow<HudRuntimeState?> = MutableStateFlow(null),
    hudTimingTelemetry: StateFlow<HudTimingTelemetry> = MutableStateFlow(HudTimingTelemetry()),
    hudMarkingLog: StateFlow<HudMarkingLog?> = MutableStateFlow(null),
    hudTimingLog: StateFlow<HudTimingLog?> = MutableStateFlow(null),
    onHudCommandRequested: (HudRemoteCommand) -> Unit = {},
    onHudTimingResultHandled: () -> Unit = {},
) {
    val s = strings
    val controller = remember(locationProvider, sessionStore) {
        DriveMarkingController(provider = locationProvider, store = sessionStore)
    }
    val uiScope = rememberCoroutineScope()
    val recorderMutex = remember { Mutex() }
    var snapshot by remember { mutableStateOf(controller.snapshot()) }
    val restoredTimingRun = remember { sessionController.timingRunSnapshot() }
    var timingActive by remember { mutableStateOf(restoredTimingRun.isActive) }
    var timingSnapshot by remember {
        mutableStateOf(
            sessionController.snapshot().takeIf { restoredTimingRun.isActive },
        )
    }
    var timingRun by remember { mutableStateOf(restoredTimingRun) }
    var localTimingPaused by remember { mutableStateOf(false) }
    var showStopSummary by remember { mutableStateOf(false) }
    var confirmDiscardSession by remember { mutableStateOf(false) }
    var saveToast by remember { mutableStateOf<String?>(null) }
    var saveInProgress by remember { mutableStateOf(false) }
    var startTimingBlockedMessage by remember { mutableStateOf<String?>(null) }
    var wrongCourseBlock by remember {
        mutableStateOf<StartTimingResult.WrongCourseBlocked?>(null)
    }
    // Diagnostic raw-recording seam used when the app is not Ready (D-16, D-17). It
    // shares the live feed but constructs no lap/ghost timing state.
    val rawController = remember(locationProvider) {
        RawRecordingController(provider = locationProvider)
    }
    var rawRecordingActive by remember { mutableStateOf(false) }
    var rawSnapshot by remember { mutableStateOf(rawController.snapshot()) }
    val externalConnection by externalGnssConnectionState.collectAsState()
    val remoteHudState by hudRuntimeState.collectAsState()
    val remoteTiming by hudTimingTelemetry.collectAsState()
    val downloadedHudMarking by hudMarkingLog.collectAsState()
    val downloadedHudTiming by hudTimingLog.collectAsState()
    val remoteTimingPaused = locationFeedMode == LocationFeedMode.ExternalGnss &&
        remoteTiming.phase == HudSessionPhase.Paused
    val timingPaused = if (locationFeedMode == LocationFeedMode.ExternalGnss) {
        remoteTimingPaused
    } else {
        localTimingPaused
    }
    // Conservative Ready preview for the dash (D-13/D-14/D-32). The authoritative
    // gate runs in SessionController.startTiming; this mirrors its thresholds over
    // the inputs the stationary dash can see so the user knows before tapping Start.
    val dashReady = dashReadyState(snapshot)
    val displayedTimingRun = if (
        locationFeedMode == LocationFeedMode.ExternalGnss &&
        remoteTiming.phase in listOf(HudSessionPhase.Timing, HudSessionPhase.Paused)
    ) {
        timingRun.copy(
            isActive = true,
            lapCount = remoteTiming.lapCount,
            currentLapNumber = remoteTiming.currentLapNumber,
            currentLapMillis = remoteTiming.currentLapMillis.takeIf { it > 0L }
                ?: remoteTiming.sessionElapsedMillis,
            lastLapMillis = remoteTiming.lastLapMillis,
            bestLapMillis = remoteTiming.bestLapMillis,
            sessionElapsedMillis = remoteTiming.sessionElapsedMillis,
            currentSectorNumber = remoteTiming.currentSectorNumber,
            sectorCount = remoteTiming.sectorCount,
            latestSectorName = remoteTiming.latestSectorMillis?.let {
                "Sector ${maxOf(1, (remoteTiming.currentSectorNumber ?: 2) - 1)}"
            },
            latestSectorSplitMillis = remoteTiming.latestSectorMillis,
            source = SourceMetadata(LocationSource.ExternalGnss, isSimulated = false),
            deltaDisplay = remoteTiming.liveDeltaMillis?.let(DeltaDisplayState::fromDeltaMillis)
                ?: DeltaDisplayState.UNAVAILABLE,
        )
    } else timingRun
    val ensureSelectedLocationFeedReady: () -> Boolean = {
        if (locationFeedMode == LocationFeedMode.PhoneGps && !phoneGpsPermission.isGranted) {
            startTimingBlockedMessage = s.phoneGpsPermissionRequired
            phoneGpsPermission.requestPermission()
            snapshot = controller.snapshot()
            false
        } else {
            true
        }
    }

    LaunchedEffect(timingActive) {
        onTimingActiveChanged(timingActive)
    }

    LaunchedEffect(downloadedHudTiming?.sessionId, downloadedHudTiming?.crc32) {
        downloadedHudTiming?.let { result ->
            // The live phone recorder is only a dashboard shadow in HUD mode.
            // Replace it with the complete, CRC-verified SD result before the
            // normal explicit Save/Discard decision is shown.
            sessionController.discardDraft()
            timingActive = false
            timingSnapshot = null
            timingRun = TimingRunSnapshot.inactive()
            localTimingPaused = false
            confirmDiscardSession = false
            showStopSummary = true
        }
    }

    LaunchedEffect(requestedTimingActive) {
        val restored = sessionController.timingRunSnapshot()
        if (requestedTimingActive && restored.isActive) {
            if (!locationProvider.isRunning) locationProvider.start()
            timingActive = true
            timingSnapshot = sessionController.snapshot()
            timingRun = restored
            snapshot = controller.snapshot()
        }
    }

    // Drive may be re-entered after Review navigation or cold start. Hydrate the
    // persisted saved Track list so Start Timing does not depend on in-memory
    // state left over from the Track Review save action.
    LaunchedEffect(Unit) {
        controller.refreshSavedTracks()
        if (timingActive && !locationProvider.isRunning) locationProvider.start()
        snapshot = controller.snapshot()
    }

    // Physical HUD controls and a reconnecting APP are alternate inputs to the
    // same session. Adopt the authoritative HUD phase without inventing a
    // separate companion-only user flow.
    LaunchedEffect(
        locationFeedMode,
        externalConnection.phase,
        remoteHudState?.phase,
        snapshot.phase,
    ) {
        if (
            locationFeedMode == LocationFeedMode.ExternalGnss &&
            externalConnection.phase == ExternalGnssConnectionPhase.Connected
        ) {
            when (remoteHudState?.phase) {
                HudSessionPhase.Marking -> if (snapshot.phase == DriveMarkingPhase.Idle) {
                    controller.beginMarking()
                    snapshot = controller.snapshot()
                }
                HudSessionPhase.Review -> if (snapshot.phase == DriveMarkingPhase.Capturing) {
                    controller.stopMarking()
                    snapshot = controller.snapshot()
                }
                HudSessionPhase.Timing,
                HudSessionPhase.Paused,
                -> {
                    if (!locationProvider.isRunning) locationProvider.start()
                    timingActive = true
                }
                HudSessionPhase.Idle -> if (timingActive) {
                    timingActive = false
                    localTimingPaused = false
                }
                null,
                -> Unit
            }
        }
    }

    // The live BLE/NMEA stream keeps the dashboard responsive, but after stop
    // the complete SD log is the source of truth. Import it once its end-to-end
    // CRC has passed, including after APP process death and reconnection.
    LaunchedEffect(
        locationFeedMode,
        remoteHudState?.phase,
        downloadedHudMarking?.path,
        downloadedHudMarking?.crc32,
    ) {
        val log = downloadedHudMarking
        if (
            locationFeedMode == LocationFeedMode.ExternalGnss &&
            remoteHudState?.phase == HudSessionPhase.Review &&
            log != null
        ) {
            controller.reviewImportedMarking(log.samples)
            snapshot = controller.snapshot()
        }
    }

    // Keep the selected live backend warm while Drive is open. This is required
    // for the HUD-as-dashboard flow: reopening a killed APP must reconnect and
    // subscribe before the user starts another marking/timing action.
    val passiveFeedPrewarmActive =
        !timingActive &&
            !rawRecordingActive &&
            when (locationFeedMode) {
                LocationFeedMode.PhoneGps -> phoneGpsPermission.isGranted
                LocationFeedMode.ExternalGnss -> true
                LocationFeedMode.Simulated -> false
            }

    LaunchedEffect(passiveFeedPrewarmActive, locationProvider) {
        if (passiveFeedPrewarmActive) {
            if (!locationProvider.isRunning) {
                locationProvider.start()
            }
            snapshot = controller.snapshot()
        }
    }

    LaunchedEffect(showStopSummary, confirmDiscardSession) {
        if (showStopSummary && confirmDiscardSession) {
            delay(DISCARD_SESSION_ARMED_TIMEOUT_MILLIS)
            confirmDiscardSession = false
        }
    }

    LaunchedEffect(startTimingBlockedMessage) {
        if (startTimingBlockedMessage != null) {
            delay(2400L)
            startTimingBlockedMessage = null
        }
    }

    LaunchedEffect(
        snapshot.latestSample?.elapsedMillis,
        snapshot.feedQuality?.averageUpdateRateHz,
        snapshot.isDemoFeedRunning,
        locationFeedMode,
        phoneGpsPermission.isGranted,
    ) {
        onGlassesIdleGpsStateChanged(snapshot.toGlassesGpsState(locationFeedMode, phoneGpsPermission))
    }

    // Poll the provider on a timer while the demo feed runs (D-05). The feed
    // flows continuously as if the phone were physically moving around the
    // track, even before/after a marking capture or timing run.
    LaunchedEffect(snapshot.isDemoFeedRunning, timingActive, rawRecordingActive, timingPaused) {
        while (snapshot.isDemoFeedRunning || timingActive || rawRecordingActive) {
            delay(100L)
            if (rawRecordingActive) {
                // Raw recording owns the feed exclusively: pull through the raw seam
                // (no lap/ghost state) and read its diagnostic snapshot back.
                rawController.tick()
                rawSnapshot = rawController.snapshot()
                continue
            }
            val samples = controller.tick()
            snapshot = controller.snapshot()
            if (timingActive) {
                // Production sample pump: feed the active recorder through the
                // controller (never recorderForTest) and read the timing/delta
                // view back for the UI. Ingest every sample drained this tick so a
                // buffered backlog is never dropped.
                if (samples.isNotEmpty() && !timingPaused) {
                    withContext(Dispatchers.Default) {
                        recorderMutex.withLock {
                            samples.forEach { sessionController.ingestSample(it) }
                        }
                    }
                }
                timingSnapshot = sessionController.snapshot()
                timingRun = sessionController.timingRunSnapshot()
            }
        }
    }

    // Clearly-far preflight is a stationary, pre-Timing decision. The override
    // never appears on the passive moving fullscreen timing surface.
    wrongCourseBlock?.let { blocked ->
        LapDialog(
            title = s.checkSelectedTrack,
            text = blocked.message,
            onDismissRequest = { wrongCourseBlock = null },
            confirmText = s.stillUseThisTrack,
            confirmIcon = CheckActionIcon,
            onConfirm = {
                when (val result = sessionController.overrideWrongCourseAndStart()) {
                    is StartTimingResult.Started -> {
                        controller.restartFeedForTiming()
                        if (locationFeedMode == LocationFeedMode.ExternalGnss) {
                            onHudCommandRequested(HudRemoteCommand.TimingStart)
                        }
                        wrongCourseBlock = null
                        startTimingBlockedMessage = null
                        timingActive = true
                        localTimingPaused = false
                        timingSnapshot = sessionController.snapshot()
                        timingRun = sessionController.timingRunSnapshot()
                        snapshot = controller.snapshot()
                    }
                    is StartTimingResult.Blocked -> {
                        wrongCourseBlock = null
                        startTimingBlockedMessage = result.message
                    }
                    is StartTimingResult.WrongCourseBlocked -> {
                        wrongCourseBlock = result
                    }
                    is StartTimingResult.NotReady -> {
                        // The override path intentionally bypasses the Ready
                        // gate (D-18 evidence), so this is unreachable; close
                        // the dialog and surface the reason defensively.
                        wrongCourseBlock = null
                        startTimingBlockedMessage = result.message
                    }
                }
            },
            dismissText = s.chooseAnotherTrack,
            dismissIcon = CloseActionIcon,
        )
    }

    // Stop summary sheet (D-14): one explicit Save/Discard choice, with the
    // destructive branch armed in place instead of opening a second dialog.
    if (showStopSummary) {
        val hudResult = downloadedHudTiming
        val laps = hudResult?.lapCount ?: timingSnapshot?.activeDraft?.checkpointedLapCount ?: 0
        LapDialog(
            title = s.sessionEnded,
            text = if (confirmDiscardSession) {
                s.lapsRecordedTapDiscard(laps)
            } else {
                s.lapsRecordedSaveOrDiscard(laps)
            },
            onDismissRequest = {
                if (confirmDiscardSession) confirmDiscardSession = false
            },
            buttons = {
                Spacer(Modifier.weight(1f))
                LapDialogTextButton(
                    text = if (confirmDiscardSession) s.tapAgainDiscardSession else s.discard,
                    destructive = confirmDiscardSession,
                    enabled = !saveInProgress,
                    icon = DeleteActionIcon,
                    iconOnly = true,
                    contentDescription = if (confirmDiscardSession) {
                        s.tapAgainDiscardSession
                    } else {
                        s.discardSession
                    },
                    onClick = {
                        if (confirmDiscardSession) {
                            confirmDiscardSession = false
                            showStopSummary = false
                            sessionController.discardDraft()
                            if (hudResult != null) onHudTimingResultHandled()
                            timingActive = false
                            timingSnapshot = null
                            timingRun = TimingRunSnapshot.inactive()
                        } else {
                            confirmDiscardSession = true
                        }
                    },
                )
                Spacer(Modifier.width(LapSightTheme.spacing.sm))
                LapDialogTextButton(
                    text = if (saveInProgress) s.savingSession else s.saveSession,
                    enabled = !saveInProgress,
                    icon = SaveSessionIcon,
                    iconOnly = true,
                    contentDescription = if (saveInProgress) s.savingSession else s.saveSession,
                    onClick = {
                        saveInProgress = true
                        uiScope.launch {
                            if (hudResult != null) {
                                val importResult = withContext(Dispatchers.Default) {
                                    val profile = hudResult.profileId?.let { profileId ->
                                        (sessionStore.loadProfile(profileId) as? LoadResult.Loaded)?.value
                                    }
                                    if (profile == null) {
                                        HudTimingReviewImportResult.Rejected(
                                            "Matching course profile is unavailable",
                                        )
                                    } else {
                                        buildHudTimingReviewPayload(
                                            log = hudResult,
                                            profile = profile,
                                            app = AppMetadata(
                                                appVersion = "0.5.0",
                                                platform = "Android/HUD",
                                            ),
                                            importedAtEpochMillis = nowEpochMillis(),
                                        )
                                    }
                                }
                                when (importResult) {
                                    is HudTimingReviewImportResult.Ready -> {
                                        val saved = withContext(Dispatchers.Default) {
                                            sessionStore.saveTimingSession(
                                                importResult.payload,
                                                importResult.payload.app,
                                            )
                                        }
                                        if (saved is SaveResult.Saved) {
                                            confirmDiscardSession = false
                                            showStopSummary = false
                                            timingActive = false
                                            timingSnapshot = null
                                            timingRun = TimingRunSnapshot.inactive()
                                            onHudTimingResultHandled()
                                            saveToast = s.sessionSaved
                                            onSavedSession()
                                        }
                                    }
                                    is HudTimingReviewImportResult.Rejected -> {
                                        saveToast = importResult.reason
                                    }
                                }
                            } else {
                                val result = withContext(Dispatchers.Default) {
                                    recorderMutex.withLock {
                                        sessionController.saveStoppedDraft()
                                    }
                                }
                                if (result is SaveDraftResult.Saved) {
                                    confirmDiscardSession = false
                                    showStopSummary = false
                                    timingActive = false
                                    timingSnapshot = null
                                    timingRun = TimingRunSnapshot.inactive()
                                    saveToast = s.sessionSaved
                                    onSavedSession()
                                }
                            }
                            saveInProgress = false
                        }
                    },
                )
            },
        )
    }

    val renderedSnapshot = remoteHudState
        ?.takeIf {
            locationFeedMode == LocationFeedMode.ExternalGnss &&
                externalConnection.phase == ExternalGnssConnectionPhase.Connected &&
                it.phase == HudSessionPhase.Marking
        }
        ?.let {
            snapshot.copy(
                backendMarkingElapsedMillis = it.elapsedMillis,
                backendMarkingPointCount = it.pointCount,
            )
        }
        ?: snapshot

    DriveSurface(
        snapshot = renderedSnapshot,
        orientation = orientation,
        displaySettings = displaySettings,
        locationFeedMode = locationFeedMode,
        phoneGpsPermission = phoneGpsPermission,
        sessionStore = sessionStore,
        onToggleOrientation = onToggleOrientation,
        orientationToggleEnabled = orientationToggleEnabled,
        onSelectProfile = { profileId ->
            // Explicit user selection only (D-02/D-03); the controller never auto-derives.
            controller.selectTrack(profileId)
            startTimingBlockedMessage = null
            snapshot = controller.snapshot()
        },
        onSelectDirection = { direction ->
            // Pre-Timing Recorded/Reverse choice over the current Track (D-18).
            controller.selectDirection(direction)
            snapshot = controller.snapshot()
        },
        onSelectTopology = { topology ->
            controller.selectTopology(topology)
            snapshot = controller.snapshot()
        },
        onStartTiming = action@{
            if (!ensureSelectedLocationFeedReady()) return@action
            if (!snapshot.canStartTiming || timingActive) {
                startTimingBlockedMessage = snapshot.startTimingBlockedReason
                snapshot = controller.snapshot()
                return@action
            }
            // Start formal timing against the saved track (D-19).
            val trackId = snapshot.timingReadyTrackId
            if (trackId == null) {
                startTimingBlockedMessage = START_TIMING_BLOCKED_COPY
            } else {
                val latestGps = snapshot.latestSample
                when (
                    val result = sessionController.startTiming(
                        trackId = trackId,
                        latestGps = latestGps,
                        preflightNowElapsedMillis = latestGps?.elapsedMillis ?: 0L,
                        recentRateHz = snapshot.feedQuality?.averageUpdateRateHz,
                        // Hard Start gate only: low rate/accuracy remain visible warnings.
                        requireReady = true,
                    )
                ) {
                    is StartTimingResult.NotReady -> {
                        // Not Ready: do not start formal timing. The dash already
                        // offers the raw-recording path instead (D-16).
                        startTimingBlockedMessage = result.message
                        snapshot = controller.snapshot()
                    }
                    is StartTimingResult.Started -> {
                        // Timing starts from a clean feed session. For simulated
                        // data this rewinds the replay; for phone GPS it clears
                        // queued fixes and resets session-relative elapsed time.
                        controller.restartFeedForTiming()
                        if (locationFeedMode == LocationFeedMode.ExternalGnss) {
                            onHudCommandRequested(HudRemoteCommand.TimingStart)
                        }
                        startTimingBlockedMessage = null
                        timingActive = true
                        localTimingPaused = false
                        timingSnapshot = sessionController.snapshot()
                        timingRun = sessionController.timingRunSnapshot()
                        snapshot = controller.snapshot()
                    }
                    is StartTimingResult.Blocked -> {
                        startTimingBlockedMessage = result.message
                        snapshot = controller.snapshot()
                    }
                    is StartTimingResult.WrongCourseBlocked -> {
                        wrongCourseBlock = result
                        startTimingBlockedMessage = result.message
                        snapshot = controller.snapshot()
                    }
                }
            }
        },
        onBeginMarking = action@{
            if (!ensureSelectedLocationFeedReady()) return@action
            startTimingBlockedMessage = null
            if (locationFeedMode == LocationFeedMode.ExternalGnss) {
                onHudCommandRequested(HudRemoteCommand.MarkStart)
            }
            controller.beginMarking()
            snapshot = controller.snapshot()
        },
        onStopMarking = {
            when (snapshot.phase) {
                DriveMarkingPhase.Capturing -> {
                    if (locationFeedMode == LocationFeedMode.ExternalGnss) {
                        onHudCommandRequested(HudRemoteCommand.MarkStop)
                    }
                    controller.stopMarking()
                    snapshot = controller.snapshot()
                }
                DriveMarkingPhase.Idle,
                DriveMarkingPhase.Review -> {
                    // Track Review owns its own action buttons.
                }
            }
        },
        onToggleTimingPause = {
            if (timingActive) {
                if (locationFeedMode == LocationFeedMode.ExternalGnss) {
                    onHudCommandRequested(
                        if (remoteTimingPaused) HudRemoteCommand.TimingResume else HudRemoteCommand.TimingPause,
                    )
                } else if (localTimingPaused) {
                    sessionController.resume()
                    localTimingPaused = false
                } else {
                    sessionController.pause()
                    localTimingPaused = true
                }
            }
        },
        onStopTiming = {
            if (timingActive) {
                if (locationFeedMode == LocationFeedMode.ExternalGnss) {
                    onHudCommandRequested(HudRemoteCommand.TimingStop)
                }
                timingActive = false
                localTimingPaused = false
                confirmDiscardSession = false
                uiScope.launch {
                    withContext(Dispatchers.Default) {
                        recorderMutex.withLock {
                            sessionController.stop()
                        }
                    }
                    if (locationFeedMode != LocationFeedMode.ExternalGnss) {
                        showStopSummary = true
                    }
                }
            }
        },
        onStartRawRecording = action@{
            if (!ensureSelectedLocationFeedReady()) return@action
            if (timingActive || rawRecordingActive) return@action
            startTimingBlockedMessage = null
            rawController.start()
            rawRecordingActive = true
            rawSnapshot = rawController.snapshot()
            snapshot = controller.snapshot()
        },
        onStopRawRecording = {
            if (rawRecordingActive) {
                rawController.stop()
                rawRecordingActive = false
                rawSnapshot = rawController.snapshot()
                snapshot = controller.snapshot()
            }
        },
        timingActive = timingActive,
        timingPaused = timingPaused,
        timingSnapshot = timingSnapshot,
        timingRun = displayedTimingRun,
        dashReady = dashReady,
        rawRecordingActive = rawRecordingActive,
        rawSnapshot = rawSnapshot,
        hudDisplayPage = remoteHudState?.displayPage.takeIf {
            locationFeedMode == LocationFeedMode.ExternalGnss
        },
        onCycleHudDisplayPage = remoteHudState?.displayPage?.takeIf {
            locationFeedMode == LocationFeedMode.ExternalGnss
        }?.let { currentPage ->
            {
                val pages = HudDisplayPage.entries
                onHudCommandRequested(
                    HudRemoteCommand.DisplayPage(pages[(currentPage.ordinal + 1) % pages.size]),
                )
            }
        },
        reviewContent = {
            TrackReviewContent(
                snapshot = snapshot,
                controller = controller,
                onChanged = { snapshot = controller.snapshot() },
                onSavedTrack = onSavedTrack,
            )
        },
    )

    // Transient save success / blocked-start toast, drawn over the surface so
    // warning copy never reserves Drive layout space or shifts controls.
    val blockedToast = startTimingBlockedMessage.takeIf { wrongCourseBlock == null }
    val toast = blockedToast ?: saveToast
    toast?.let { message ->
        val isWarning = blockedToast != null
        LaunchedEffect(message, isWarning) {
            if (isWarning) return@LaunchedEffect
            delay(2000)
            saveToast = null
        }
        DriveToast(message = message, warning = isWarning)
    }
}

private fun DriveMarkingSnapshot.toGlassesGpsState(
    locationFeedMode: LocationFeedMode,
    phoneGpsPermission: PhoneGpsPermissionState,
): GlassesGpsState {
    latestSample?.let { sample ->
        val fixStatus = when (sample.source) {
            LocationSource.Simulated -> GpsFixStatus.Simulated
            LocationSource.PhoneGps,
            LocationSource.ExternalGnss -> GpsFixStatus.Live
        }
        return GlassesGpsState.from(
            fixStatus = fixStatus,
            accuracyMeters = sample.horizontalAccuracyMeters,
            sampleRateHz = feedQuality?.averageUpdateRateHz,
        )
    }
    val fixStatus = when {
        locationFeedMode == LocationFeedMode.PhoneGps && !phoneGpsPermission.isGranted ->
            GpsFixStatus.Unavailable
        isDemoFeedRunning -> GpsFixStatus.Acquiring
        else -> GpsFixStatus.Idle
    }
    return GlassesGpsState.from(
        fixStatus = fixStatus,
        accuracyMeters = null,
        sampleRateHz = feedQuality?.averageUpdateRateHz,
    )
}

@Composable
private fun DriveToast(
    message: String,
    warning: Boolean,
) {
    val spacing = LapSightTheme.spacing
    val color = if (warning) LapSightTheme.colors.statusCaution else LapSightTheme.colors.statusReady
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(spacing.md),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, color),
        ) {
            Text(
                text = message,
                color = color,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(
                    horizontal = spacing.md,
                    vertical = spacing.sm,
                ),
            )
        }
    }
}
