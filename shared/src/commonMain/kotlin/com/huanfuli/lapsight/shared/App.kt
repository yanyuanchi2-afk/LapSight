package com.huanfuli.lapsight.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.huanfuli.lapsight.shared.export.ExportShareTarget
import com.huanfuli.lapsight.shared.export.NoOpExportShareTarget
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
import com.huanfuli.lapsight.shared.fixtures.GpsFixtureLibrary
import com.huanfuli.lapsight.shared.glasses.GlassesActions
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
import com.huanfuli.lapsight.shared.glasses.GlassesGpsState
import com.huanfuli.lapsight.shared.glasses.HudPage
import com.huanfuli.lapsight.shared.glasses.NoOpGlassesActions
import com.huanfuli.lapsight.shared.session.SessionController
import com.huanfuli.lapsight.shared.storage.InMemorySessionStore
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.ui.AppShell
import com.huanfuli.lapsight.shared.ui.LapSightTheme
import com.huanfuli.lapsight.shared.ui.ProvideLocalizedStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * LapSight root composable (Plan 03-05 refactor).
 *
 * Shrinks to the dark racing theme (D-26) plus an [AppShell] handoff. The shell
 * owns the three-tab navigation and the app-wide window lock; the Drive tab
 * owns the marking capture and the orientation toggle; Review reads the
 * local-first store. The lap engine and storage consume the same
 * [LocationSampleProvider] interface regardless of whether the selected feed is
 * phone GPS or a deterministic simulator.
 *
 * @param orientationController platform window lock; never sensor-driven.
 * @param sessionStore local-first store; defaults to an in-memory store so
 *   previews/tests need no platform storage root. The Android entrypoint
 *   injects a `FileSessionStore` over the real app-private root.
 * @param onSessionControllerReady invoked exactly once with the hoisted
 *   [SessionController] instance (Phase 7 MR-01 seam) so `MainActivity` can
 *   poll the SAME controller the phone dash drives — e.g. for a Meta glasses
 *   bridge. No second controller is ever constructed.
 */
@Composable
@Preview
fun App(
    orientationController: OrientationController = NoOpOrientationController,
    driveDisplayController: DriveDisplayController = NoOpDriveDisplayController,
    displaySettingsStore: DisplaySettingsStore = InMemoryDisplaySettingsStore(),
    phoneGpsProvider: LocationSampleProvider? = null,
    externalGnssProvider: LocationSampleProvider? = null,
    externalGnssConnectionState: StateFlow<ExternalGnssConnectionState> =
        MutableStateFlow(ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected)),
    ntripSettings: StateFlow<NtripSettings> = MutableStateFlow(NtripSettings()),
    ntripConnectionState: StateFlow<NtripConnectionState> = MutableStateFlow(NtripConnectionState()),
    ntripActions: NtripActions = NoOpNtripActions,
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
    onExternalGnssRescan: () -> Unit = {},
    onExternalGnssDisconnect: () -> Unit = {},
    onTimingForegroundChanged: (Boolean, LocationFeedMode) -> Unit = { _, _ -> },
    hudRuntimeState: StateFlow<HudRuntimeState?> = MutableStateFlow(null),
    hudTimingTelemetry: StateFlow<HudTimingTelemetry> = MutableStateFlow(HudTimingTelemetry()),
    hudMarkingLog: StateFlow<HudMarkingLog?> = MutableStateFlow(null),
    hudTimingLog: StateFlow<HudTimingLog?> = MutableStateFlow(null),
    onHudCommandRequested: (HudRemoteCommand) -> Unit = {},
    onHudCourseBundleRequested: (HudCourseBundle) -> Unit = {},
    onHudTimingResultHandled: () -> Unit = {},
) {
    var displaySettings by remember { mutableStateOf(displaySettingsStore.load()) }
    val simulatedGpsProvider = remember {
        SimulatedGpsProvider(scenarioId = GpsFixtureLibrary.VARIABLE_PACE_GHOST_UAT)
    }
    val useDarkTheme = when (displaySettings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    ProvideLocalizedStrings(languageMode = displaySettings.languageMode) {
        LapSightTheme(useDarkTheme = useDarkTheme) {
            AppShell(
                orientationController = orientationController,
                driveDisplayController = driveDisplayController,
                displaySettings = displaySettings,
                onDisplaySettingsChanged = { updated ->
                    displaySettings = updated
                    displaySettingsStore.save(updated)
                },
                simulatedGpsProvider = simulatedGpsProvider,
                phoneGpsProvider = phoneGpsProvider,
                externalGnssProvider = externalGnssProvider,
                externalGnssConnectionState = externalGnssConnectionState,
                ntripSettings = ntripSettings,
                ntripConnectionState = ntripConnectionState,
                ntripActions = ntripActions,
                phoneGpsPermission = phoneGpsPermission,
                sessionStore = sessionStore,
                exportShareTarget = exportShareTarget,
                onSessionControllerReady = onSessionControllerReady,
                glassesConnectionState = glassesConnectionState,
                glassesDevices = glassesDevices,
                glassesSelectedDeviceId = glassesSelectedDeviceId,
                glassesCastingEnabled = glassesCastingEnabled,
                glassesPage = glassesPage,
                glassesActions = glassesActions,
                onGlassesIdleGpsStateChanged = onGlassesIdleGpsStateChanged,
                onExternalGnssRescan = onExternalGnssRescan,
                onExternalGnssDisconnect = onExternalGnssDisconnect,
                onTimingForegroundChanged = onTimingForegroundChanged,
                hudRuntimeState = hudRuntimeState,
                hudTimingTelemetry = hudTimingTelemetry,
                hudMarkingLog = hudMarkingLog,
                hudTimingLog = hudTimingLog,
                onHudCommandRequested = onHudCommandRequested,
                onHudCourseBundleRequested = onHudCourseBundleRequested,
                onHudTimingResultHandled = onHudTimingResultHandled,
            )
        }
    }
}
