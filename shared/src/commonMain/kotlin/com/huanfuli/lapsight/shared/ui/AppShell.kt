package com.huanfuli.lapsight.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.DashOrientation
import com.huanfuli.lapsight.shared.DriveDisplayController
import com.huanfuli.lapsight.shared.DriveDisplaySettings
import com.huanfuli.lapsight.shared.LocationFeedMode
import com.huanfuli.lapsight.shared.LocationSampleProvider
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.OrientationController
import com.huanfuli.lapsight.shared.PhoneGpsPermissionState
import com.huanfuli.lapsight.shared.export.ExportShareTarget
import com.huanfuli.lapsight.shared.export.NoOpExportShareTarget
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesActions
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
import com.huanfuli.lapsight.shared.glasses.GlassesGpsState
import com.huanfuli.lapsight.shared.glasses.HudPage
import com.huanfuli.lapsight.shared.glasses.NoOpGlassesActions
import com.huanfuli.lapsight.shared.session.DraftRecoveryAction
import com.huanfuli.lapsight.shared.session.DraftRecoveryPrompt
import com.huanfuli.lapsight.shared.session.SessionController
import com.huanfuli.lapsight.shared.session.SourceMetadata
import com.huanfuli.lapsight.shared.storage.InMemorySessionStore
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.ui.components.LapDialog
import com.huanfuli.lapsight.shared.ui.components.LapDialogTextButton
import com.huanfuli.lapsight.shared.ui.drive.DriveScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which bottom-navigation tab is active.
 */
enum class AppTab { Drive, Review, Settings }

/**
 * The persistent three-tab shell: Drive / Review / Settings bottom navigation
 * (D-26, D-27).
 *
 * The shell owns the app-wide window lock via [orientationController] (so the
 * lock applies regardless of tab) and the [sessionStore] used by Review. The
 * Drive tab owns the orientation toggle itself (D-29). In landscape on the
 * Drive tab the bottom navigation is hidden for a fullscreen mounted-dash mode
 * (D-29); the user returns to portrait to bring the navigation back.
 *
 * @param orientationController platform window lock; never sensor-driven.
 * @param sessionStore local-first store; defaults to an in-memory store so
 *   previews/tests need no platform storage root.
 * @param onSessionControllerReady invoked exactly once (per hoisted instance)
 *   with the [SessionController] constructed below (Phase 7 MR-01 seam), so
 *   `MainActivity` can hand the SAME instance to a Meta glasses bridge without
 *   constructing a second controller.
 */
@Composable
fun AppShell(
    orientationController: OrientationController,
    driveDisplayController: DriveDisplayController,
    displaySettings: DriveDisplaySettings,
    onDisplaySettingsChanged: (DriveDisplaySettings) -> Unit,
    simulatedGpsProvider: LocationSampleProvider,
    phoneGpsProvider: LocationSampleProvider? = null,
    externalGnssProvider: LocationSampleProvider? = null,
    externalGnssConnectionState: StateFlow<ExternalGnssConnectionState> =
        MutableStateFlow(ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected)),
    phoneGpsPermission: PhoneGpsPermissionState = PhoneGpsPermissionState(),
    sessionStore: LocalSessionStore = InMemorySessionStore(),
    exportShareTarget: ExportShareTarget = NoOpExportShareTarget,
    onSessionControllerReady: (SessionController) -> Unit = {},
    glassesConnectionState: StateFlow<GlassesConnectionState> =
        MutableStateFlow(GlassesConnectionState.Idle),
    glassesDevices: StateFlow<List<GlassesDeviceSummary>> =
        MutableStateFlow(emptyList()),
    glassesSelectedDeviceId: StateFlow<String?> = MutableStateFlow(null),
    glassesCastingEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    glassesPage: StateFlow<HudPage> = MutableStateFlow(HudPage.FOCUSED),
    glassesActions: GlassesActions = NoOpGlassesActions,
    onGlassesIdleGpsStateChanged: (GlassesGpsState) -> Unit = {},
    onTimingForegroundChanged: (Boolean, LocationFeedMode) -> Unit = { _, _ -> },
) {
    val s = strings
    var tab by remember { mutableStateOf(AppTab.Drive) }
    var orientation by remember { mutableStateOf(DashOrientation.Portrait) }
    var savedVersion by remember { mutableStateOf(0L) }
    val effectiveLocationFeedMode = when {
        displaySettings.locationFeedMode == LocationFeedMode.PhoneGps && phoneGpsProvider != null ->
            LocationFeedMode.PhoneGps
        displaySettings.locationFeedMode == LocationFeedMode.ExternalGnss && externalGnssProvider != null ->
            LocationFeedMode.ExternalGnss
        else -> LocationFeedMode.Simulated
    }
    // The session source must follow the LIVE feed, not the Track's marking source
    // (D-04/D-42). Capture the current feed mode in a State the controller's
    // sourceForTrack lambda reads at startTiming time, so switching feeds before a
    // run is reflected in the saved session provenance.
    val liveFeedModeState = rememberUpdatedState(effectiveLocationFeedMode)
    val sessionController = remember {
        SessionController(
            store = sessionStore,
            sourceForTrack = { _ ->
                when (liveFeedModeState.value) {
                    LocationFeedMode.PhoneGps -> SourceMetadata(
                        source = LocationSource.PhoneGps,
                        isSimulated = false,
                    )
                    LocationFeedMode.ExternalGnss -> SourceMetadata(
                        source = LocationSource.ExternalGnss,
                        isSimulated = false,
                    )
                    LocationFeedMode.Simulated -> SourceMetadata(
                        source = LocationSource.Simulated,
                        isSimulated = true,
                        label = "Demo",
                    )
                }
            },
        )
    }
    var recoveryPrompt by remember { mutableStateOf<DraftRecoveryPrompt?>(null) }
    var confirmDiscardDraft by remember { mutableStateOf(false) }
    var driveTimingActive by remember { mutableStateOf(false) }
    var recoveryBusy by remember { mutableStateOf(false) }
    var pendingPhoneGpsSelection by remember { mutableStateOf(false) }
    var orientationChangePending by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    // On launch, surface an unfinished draft recovery prompt (D-15).
    LaunchedEffect(Unit) {
        recoveryPrompt = sessionController.loadUnfinishedDraft()
    }

    // Phase 7 MR-01 seam: hand the hoisted controller instance to MainActivity
    // exactly once so a Meta glasses bridge can poll the SAME controller the
    // phone dash drives. Keyed on the instance (stable for the composition's
    // lifetime), never re-invoked on recomposition.
    LaunchedEffect(sessionController) {
        onSessionControllerReady(sessionController)
    }

    val windowSize = LocalWindowInfo.current.containerSize
    val windowOrientation = if (windowSize.width > windowSize.height) {
        DashOrientation.Landscape
    } else {
        DashOrientation.Portrait
    }
    LaunchedEffect(orientation, windowOrientation) {
        if (orientation == windowOrientation) {
            orientationChangePending = false
        }
    }
    LaunchedEffect(orientationChangePending, orientation) {
        if (orientationChangePending) {
            delay(OrientationChangePendingTimeoutMillis)
            orientationChangePending = false
        }
    }
    val driveFullscreen = tab == AppTab.Drive && (
        windowOrientation == DashOrientation.Landscape ||
            (driveTimingActive && displaySettings.fullscreenWhileTiming)
        )
    val showBottomNav = !driveFullscreen
    val activeLocationProvider = when (effectiveLocationFeedMode) {
        LocationFeedMode.PhoneGps -> phoneGpsProvider!!
        LocationFeedMode.ExternalGnss -> externalGnssProvider!!
        LocationFeedMode.Simulated -> simulatedGpsProvider
    }

    LaunchedEffect(pendingPhoneGpsSelection, phoneGpsPermission.isGranted) {
        if (pendingPhoneGpsSelection && phoneGpsPermission.isGranted) {
            pendingPhoneGpsSelection = false
            onDisplaySettingsChanged(displaySettings.copy(locationFeedMode = LocationFeedMode.PhoneGps))
        }
    }

    LaunchedEffect(driveFullscreen, driveTimingActive, displaySettings.keepScreenAwakeWhileTiming) {
        driveDisplayController.apply(
            fullscreen = driveFullscreen,
            keepScreenAwake =
                driveTimingActive && displaySettings.keepScreenAwakeWhileTiming,
        )
    }

    LaunchedEffect(driveTimingActive, effectiveLocationFeedMode) {
        onTimingForegroundChanged(driveTimingActive, effectiveLocationFeedMode)
    }

    // Recovery prompt: Resume / Save / Discard; never auto-promotes to history (D-16).
    recoveryPrompt?.let { prompt ->
        if (confirmDiscardDraft) {
            LapDialog(
                title = s.discardUnfinishedTitle,
                text = s.discardUnfinishedText,
                onDismissRequest = { confirmDiscardDraft = false },
                confirmText = s.discard,
                destructiveConfirm = true,
                confirmIcon = DeleteActionIcon,
                onConfirm = {
                    confirmDiscardDraft = false
                    sessionController.handleRecoveryAction(prompt, DraftRecoveryAction.Discard)
                    recoveryPrompt = null
                },
                dismissText = s.cancel,
                dismissIcon = CloseActionIcon,
            )
        } else {
            LapDialog(
                title = s.unfinishedSessionFound,
                text = s.unfinishedSessionText,
                onDismissRequest = { /* require an explicit choice */ },
                buttons = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LapSightTheme.spacing.sm, Alignment.End),
                    ) {
                        LapDialogTextButton(
                            text = s.resume,
                            enabled = !recoveryBusy,
                            icon = ResumeActionIcon,
                            iconOnly = true,
                            contentDescription = s.resume,
                            onClick = {
                                recoveryBusy = true
                                uiScope.launch {
                                    withContext(Dispatchers.Default) {
                                        sessionController.handleRecoveryAction(
                                            prompt,
                                            DraftRecoveryAction.Resume,
                                        )
                                    }
                                    recoveryPrompt = null
                                    recoveryBusy = false
                                    driveTimingActive = sessionController.timingRunSnapshot().isActive
                                    tab = AppTab.Drive
                                }
                            },
                        )
                        if (DraftRecoveryAction.Save in prompt.availableActions) {
                            LapDialogTextButton(
                                text = s.save,
                                enabled = !recoveryBusy,
                                icon = SaveSessionIcon,
                                iconOnly = true,
                                contentDescription = s.saveSession,
                                onClick = {
                                    recoveryBusy = true
                                    uiScope.launch {
                                        withContext(Dispatchers.Default) {
                                            sessionController.handleRecoveryAction(
                                                prompt,
                                                DraftRecoveryAction.Save,
                                            )
                                        }
                                        recoveryPrompt = null
                                        recoveryBusy = false
                                        savedVersion++
                                    }
                                },
                            )
                        }
                        LapDialogTextButton(
                            text = s.discard,
                            enabled = !recoveryBusy,
                            destructive = true,
                            icon = DeleteActionIcon,
                            iconOnly = true,
                            contentDescription = s.discardSession,
                            onClick = { confirmDiscardDraft = true },
                        )
                    }
                },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomNav) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = tab == AppTab.Drive,
                        onClick = { tab = AppTab.Drive },
                        icon = { Icon(DriveTabIcon, contentDescription = s.drive) },
                        label = { Text(s.drive) },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = tab == AppTab.Review,
                        onClick = { tab = AppTab.Review },
                        icon = { Icon(ReviewTabIcon, contentDescription = s.review) },
                        label = { Text(s.review) },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = tab == AppTab.Settings,
                        onClick = { tab = AppTab.Settings },
                        icon = { Icon(SettingsTabIcon, contentDescription = s.settings) },
                        label = { Text(s.settings) },
                        colors = navItemColors(),
                    )
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = if (driveFullscreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        }
        Box(
            modifier = contentModifier,
        ) {
            when (tab) {
                AppTab.Drive -> DriveScreen(
                    orientation = orientation,
                    onToggleOrientation = {
                        if (!orientationChangePending) {
                            val nextOrientation = if (orientation == DashOrientation.Portrait) {
                                DashOrientation.Landscape
                            } else {
                                DashOrientation.Portrait
                            }
                            orientationChangePending = true
                            orientationController.apply(nextOrientation)
                            orientation = nextOrientation
                        }
                    },
                    orientationToggleEnabled = !orientationChangePending,
                    onSavedTrack = { savedVersion++ },
                    onSavedSession = { savedVersion++ },
                    onTimingActiveChanged = { driveTimingActive = it },
                    requestedTimingActive = driveTimingActive,
                    displaySettings = displaySettings,
                    locationFeedMode = effectiveLocationFeedMode,
                    locationProvider = activeLocationProvider,
                    phoneGpsPermission = phoneGpsPermission,
                    sessionStore = sessionStore,
                    sessionController = sessionController,
                    glassesConnectionState = glassesConnectionState,
                    glassesSelectedDeviceId = glassesSelectedDeviceId,
                    glassesCastingEnabled = glassesCastingEnabled,
                    glassesPage = glassesPage,
                    glassesActions = glassesActions,
                    onGlassesIdleGpsStateChanged = onGlassesIdleGpsStateChanged,
                )
                AppTab.Review -> ReviewScreen(
                    sessionStore = sessionStore,
                    savedVersion = savedVersion,
                    displaySettings = displaySettings,
                    exportShareTarget = exportShareTarget,
                )
                AppTab.Settings -> SettingsScreen(
                    settings = displaySettings,
                    phoneGpsAvailable = phoneGpsProvider != null && phoneGpsPermission.isSupported,
                    phoneGpsPermissionGranted = phoneGpsPermission.isGranted,
                    externalGnssAvailable = externalGnssProvider != null,
                    externalGnssConnectionState = externalGnssConnectionState,
                    locationFeedLocked = driveTimingActive,
                    glassesConnectionState = glassesConnectionState,
                    glassesDevices = glassesDevices,
                    glassesSelectedDeviceId = glassesSelectedDeviceId,
                    glassesActions = glassesActions,
                    onRequestPhoneGps = {
                        pendingPhoneGpsSelection = true
                        phoneGpsPermission.requestPermission()
                    },
                    onSettingsChanged = onDisplaySettingsChanged,
                )
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

private const val OrientationChangePendingTimeoutMillis = 900L
