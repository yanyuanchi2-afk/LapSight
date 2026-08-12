package com.huanfuli.lapsight.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.huanfuli.lapsight.shared.DriveDisplaySettings
import com.huanfuli.lapsight.shared.LanguageMode
import com.huanfuli.lapsight.shared.LocationFeedMode
import com.huanfuli.lapsight.shared.NoOpNtripActions
import com.huanfuli.lapsight.shared.NtripActions
import com.huanfuli.lapsight.shared.NtripConnectionPhase
import com.huanfuli.lapsight.shared.NtripConnectionState
import com.huanfuli.lapsight.shared.NtripSettings
import com.huanfuli.lapsight.shared.SpeedUnit
import com.huanfuli.lapsight.shared.ThemeMode
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesActions
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
import com.huanfuli.lapsight.shared.glasses.HudPage
import com.huanfuli.lapsight.shared.glasses.NoOpGlassesActions
import com.huanfuli.lapsight.shared.ui.components.LapCard
import com.huanfuli.lapsight.shared.ui.components.LapButton
import com.huanfuli.lapsight.shared.ui.components.LapButtonStyle
import com.huanfuli.lapsight.shared.ui.components.LapSwitchRow
import com.huanfuli.lapsight.shared.ui.components.SafetyNote
import com.huanfuli.lapsight.shared.ui.components.SegmentedControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One selectable entry in the location-source [SegmentedControl].
 *
 * Pure data so the availability/enabled/lock logic below is testable without
 * Compose (D-01/D-08: External GNSS only ever appears as a real option when
 * the platform wires a provider for it, and every option locks together while
 * timing is active, matching existing Phone GPS/Simulated behavior).
 */
internal data class LocationSourceOption(
    val mode: LocationFeedMode,
    val enabled: Boolean,
)

/**
 * Builds the ordered list of selectable location sources for Settings.
 *
 * Phone GPS and Simulated are always present (Simulated is always usable);
 * External GNSS only appears when [externalGnssAvailable] — i.e. when the
 * platform (Android) has wired a real provider for it. All options disable
 * together while [locationFeedLocked] (timing active), matching the existing
 * Phone GPS/Simulated lock behavior.
 */
internal fun locationSourceOptions(
    phoneGpsAvailable: Boolean,
    externalGnssAvailable: Boolean,
    locationFeedLocked: Boolean,
): List<LocationSourceOption> = buildList {
    add(LocationSourceOption(LocationFeedMode.PhoneGps, phoneGpsAvailable && !locationFeedLocked))
    add(LocationSourceOption(LocationFeedMode.Simulated, !locationFeedLocked))
    if (externalGnssAvailable) {
        add(LocationSourceOption(LocationFeedMode.ExternalGnss, !locationFeedLocked))
    }
}

/**
 * Resolves the [LocationFeedMode] actually in effect given platform
 * availability: a requested mode whose provider is unavailable on this
 * platform (e.g. External GNSS on iOS, or before Android wires BLE support)
 * falls back to Simulated so the feed is never silently dead.
 */
internal fun resolveEffectiveLocationFeedMode(
    requested: LocationFeedMode,
    phoneGpsAvailable: Boolean,
    externalGnssAvailable: Boolean,
): LocationFeedMode = when {
    requested == LocationFeedMode.PhoneGps && phoneGpsAvailable -> LocationFeedMode.PhoneGps
    requested == LocationFeedMode.ExternalGnss && externalGnssAvailable -> LocationFeedMode.ExternalGnss
    else -> LocationFeedMode.Simulated
}

/**
 * Resolves the source-note caption shown below the location-source selector.
 *
 * Priority order (IN-01 fix, 06-REVIEW.md): the External-GNSS-specific note
 * is checked BEFORE the generic Phone-GPS-unavailable note, so a user who has
 * selected External GNSS while Phone GPS happens to be unavailable sees the
 * External-GNSS-specific protocol-preview note, not the generic message.
 */
internal fun resolveSourceNote(
    locationFeedLocked: Boolean,
    effectiveLocationFeedMode: LocationFeedMode,
    phoneGpsAvailable: Boolean,
    requestedLocationFeedMode: LocationFeedMode,
    phoneGpsPermissionGranted: Boolean,
    strings: LocalizedStrings,
): String? = when {
    locationFeedLocked -> strings.locationLockedWhileTiming
    effectiveLocationFeedMode == LocationFeedMode.ExternalGnss -> strings.externalGnssNote
    !phoneGpsAvailable -> strings.phoneGpsUnavailable
    requestedLocationFeedMode == LocationFeedMode.PhoneGps && !phoneGpsPermissionGranted ->
        strings.phoneGpsPermissionRequired
    else -> null
}

/**
 * Display and mounted-phone behavior controls. Safety copy belongs here instead
 * of competing with live telemetry on the Drive surface.
 *
 * Grouped into instrument-panel cards; every selector is a [SegmentedControl]
 * and every toggle row is a full-width ≥48dp target.
 */
@Composable
internal fun SettingsScreen(
    settings: DriveDisplaySettings,
    phoneGpsAvailable: Boolean,
    phoneGpsPermissionGranted: Boolean,
    externalGnssAvailable: Boolean = false,
    externalGnssConnectionState: StateFlow<ExternalGnssConnectionState> =
        MutableStateFlow(ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected)),
    ntripSettings: StateFlow<NtripSettings> = MutableStateFlow(NtripSettings()),
    ntripConnectionState: StateFlow<NtripConnectionState> = MutableStateFlow(NtripConnectionState()),
    ntripActions: NtripActions = NoOpNtripActions,
    locationFeedLocked: Boolean,
    glassesConnectionState: StateFlow<GlassesConnectionState> =
        MutableStateFlow(GlassesConnectionState.Idle),
    glassesDevices: StateFlow<List<GlassesDeviceSummary>> =
        MutableStateFlow(emptyList()),
    glassesSelectedDeviceId: StateFlow<String?> = MutableStateFlow(null),
    glassesCastingEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    glassesPage: StateFlow<HudPage> = MutableStateFlow(HudPage.FOCUSED),
    glassesActions: GlassesActions = NoOpGlassesActions,
    onExternalGnssRescan: () -> Unit = {},
    onExternalGnssDisconnect: () -> Unit = {},
    onRequestPhoneGps: () -> Unit,
    onSettingsChanged: (DriveDisplaySettings) -> Unit,
) {
    val effectiveLocationFeedMode = resolveEffectiveLocationFeedMode(
        requested = settings.locationFeedMode,
        phoneGpsAvailable = phoneGpsAvailable,
        externalGnssAvailable = externalGnssAvailable,
    )
    val spacing = LapSightTheme.spacing
    val s = strings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = s.settings,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = s.devices,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
        )

        GlassesSettingsCard(
            connectionState = glassesConnectionState,
            devices = glassesDevices,
            selectedDeviceId = glassesSelectedDeviceId,
            castingEnabled = glassesCastingEnabled,
            page = glassesPage,
            actions = glassesActions,
        )

        if (externalGnssAvailable) {
            ExternalGnssSettingsCard(
                connectionState = externalGnssConnectionState,
                onRescan = onExternalGnssRescan,
                onDisconnect = onExternalGnssDisconnect,
            )
        }

        LapCard(title = s.units) {
            SegmentedControl(
                options = listOf("km/h", "mph"),
                selectedIndex = when (settings.speedUnit) {
                    SpeedUnit.KilometersPerHour -> 0
                    SpeedUnit.MilesPerHour -> 1
                },
                onSelect = { index ->
                    onSettingsChanged(
                        settings.copy(
                            speedUnit = if (index == 0) {
                                SpeedUnit.KilometersPerHour
                            } else {
                                SpeedUnit.MilesPerHour
                            },
                        ),
                    )
                },
            )
        }

        LapCard(title = s.locationSource) {
            val locationOptions = locationSourceOptions(
                phoneGpsAvailable = phoneGpsAvailable,
                externalGnssAvailable = externalGnssAvailable,
                locationFeedLocked = locationFeedLocked,
            )
            val optionLabels = locationOptions.map {
                when (it.mode) {
                    LocationFeedMode.PhoneGps -> s.phoneGps
                    LocationFeedMode.Simulated -> s.simulated
                    LocationFeedMode.ExternalGnss -> s.externalGnss
                }
            }
            val selectedIndex = locationOptions.indexOfFirst { it.mode == effectiveLocationFeedMode }
                .takeIf { it >= 0 } ?: 0
            SegmentedControl(
                options = optionLabels,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    when (val mode = locationOptions.getOrNull(index)?.mode) {
                        LocationFeedMode.PhoneGps -> {
                            if (phoneGpsPermissionGranted) {
                                onSettingsChanged(settings.copy(locationFeedMode = LocationFeedMode.PhoneGps))
                            } else {
                                onRequestPhoneGps()
                            }
                        }
                        LocationFeedMode.Simulated -> onSettingsChanged(
                            settings.copy(locationFeedMode = LocationFeedMode.Simulated),
                        )
                        LocationFeedMode.ExternalGnss -> onSettingsChanged(
                            settings.copy(locationFeedMode = LocationFeedMode.ExternalGnss),
                        )
                        null -> Unit
                    }
                },
                optionEnabled = { index -> locationOptions.getOrNull(index)?.enabled == true },
            )
            val sourceNote = resolveSourceNote(
                locationFeedLocked = locationFeedLocked,
                effectiveLocationFeedMode = effectiveLocationFeedMode,
                phoneGpsAvailable = phoneGpsAvailable,
                requestedLocationFeedMode = settings.locationFeedMode,
                phoneGpsPermissionGranted = phoneGpsPermissionGranted,
                strings = s,
            )
            sourceNote?.let {
                Text(
                    text = it,
                    color = LapSightTheme.colors.statusCaution,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (phoneGpsAvailable) {
                LapSwitchRow(
                    label = s.highRateGnss,
                    supporting = s.highRateGnssSupporting,
                    checked = settings.useDirectGnss,
                    enabled = !locationFeedLocked,
                    onCheckedChange = { onSettingsChanged(settings.copy(useDirectGnss = it)) },
                )
            }
        }

        Text(
            text = s.displayAndDash,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
        )

        LapCard(title = s.display) {
            SegmentedControl(
                options = listOf(s.themeSystem, s.themeDark, s.themeLight),
                selectedIndex = when (settings.themeMode) {
                    ThemeMode.System -> 0
                    ThemeMode.Dark -> 1
                    ThemeMode.Light -> 2
                },
                onSelect = { index ->
                    onSettingsChanged(
                        settings.copy(
                            themeMode = when (index) {
                                0 -> ThemeMode.System
                                1 -> ThemeMode.Dark
                                else -> ThemeMode.Light
                            },
                        ),
                    )
                },
            )
            LanguageSelector(
                selected = settings.languageMode,
                onSelect = { mode -> onSettingsChanged(settings.copy(languageMode = mode)) },
            )
            LapSwitchRow(
                label = s.fullscreenWhileTiming,
                checked = settings.fullscreenWhileTiming,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(fullscreenWhileTiming = it))
                },
            )
            LapSwitchRow(
                label = s.keepScreenAwakeWhileTiming,
                checked = settings.keepScreenAwakeWhileTiming,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(keepScreenAwakeWhileTiming = it))
                },
            )
        }

        LapCard(title = s.dashReadouts) {
            LapSwitchRow(
                label = s.speedTrace,
                checked = settings.showSpeedTrace,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(showSpeedTrace = it))
                },
            )
            LapSwitchRow(
                label = s.gpsDiagnostics,
                checked = settings.showGpsDiagnostics,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(showGpsDiagnostics = it))
                },
            )
        }

        Text(
            text = s.experimental,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
        )

        RtkExperimentalCard(
            ntripSettings = ntripSettings,
            ntripConnectionState = ntripConnectionState,
            ntripActions = ntripActions,
        )

        SafetyNote(
            text = s.safetySettings,
        )
        Spacer(Modifier.height(spacing.md))
    }
}

@Composable
private fun GlassesSettingsCard(
    connectionState: StateFlow<GlassesConnectionState>,
    devices: StateFlow<List<GlassesDeviceSummary>>,
    selectedDeviceId: StateFlow<String?>,
    castingEnabled: StateFlow<Boolean>,
    page: StateFlow<HudPage>,
    actions: GlassesActions,
) {
    val state by connectionState.collectAsState()
    val deviceList by devices.collectAsState()
    val selectedId by selectedDeviceId.collectAsState()
    val casting by castingEnabled.collectAsState()
    val selectedPage by page.collectAsState()
    val spacing = LapSightTheme.spacing
    val s = strings
    val firmwareUpdateRequired = deviceList.any { it.requiresFirmwareUpdate }
    val appUpdateRequired = (state as? GlassesConnectionState.Error)?.datAppUpdateRequired == true
    val hasSelectedDevice = selectedId != null

    LapCard(title = s.glasses) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.glassesConnectionLabel(state),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                (state as? GlassesConnectionState.Error)?.message?.let { message ->
                    Text(
                        text = message,
                        color = LapSightTheme.colors.statusCaution,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LapButton(
                text = s.registerPair,
                onClick = actions::register,
                style = LapButtonStyle.Secondary,
            )
        }

        if (firmwareUpdateRequired) {
            LapButton(
                text = s.openFirmwareUpdate,
                onClick = actions::openFirmwareUpdate,
                style = LapButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (appUpdateRequired) {
            LapButton(
                text = s.openGlassesAppUpdate,
                onClick = actions::openDatAppUpdate,
                style = LapButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (deviceList.isEmpty()) {
            Text(
                text = s.noGlassesDevices,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                deviceList.forEach { device ->
                    GlassesDeviceRow(
                        device = device,
                        selected = device.id == selectedId,
                        onSelect = { actions.pickDevice(device.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.sm))
        Text(
            text = s.glassesHudMode,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        SegmentedControl(
            options = listOf(s.glassesOff, s.hudDeltaOnly, s.hudFocused, s.hudTelemetry),
            selectedIndex = if (casting && hasSelectedDevice) selectedPage.ordinal + 1 else 0,
            onSelect = { index ->
                if (index == 0) {
                    actions.stopCasting()
                } else {
                    actions.setPage(HudPage.values()[index - 1])
                    if (!(casting && hasSelectedDevice)) actions.startCasting()
                }
            },
            optionEnabled = { index -> index == 0 || hasSelectedDevice },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExternalGnssSettingsCard(
    connectionState: StateFlow<ExternalGnssConnectionState>,
    onRescan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val state by connectionState.collectAsState()
    val s = strings
    val connected = state.phase == ExternalGnssConnectionPhase.Connected ||
        state.phase == ExternalGnssConnectionPhase.Reconnecting

    LapCard(title = s.externalGnss) {
        Text(
            text = s.externalGnssConnectionLabel(state.phase),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        state.receiver?.let { receiver ->
            Text(
                text = "${s.currentDevice}: ${receiver.displayName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.message.takeIf { state.phase == ExternalGnssConnectionPhase.Failed }?.let { message ->
            Text(
                text = message,
                color = LapSightTheme.colors.statusCaution,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LapSightTheme.spacing.sm),
        ) {
            LapButton(
                text = s.rescan,
                onClick = onRescan,
                style = LapButtonStyle.Secondary,
                modifier = Modifier.weight(1f),
            )
            if (connected) {
                LapButton(
                    text = s.disconnect,
                    onClick = onDisconnect,
                    style = LapButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * RTK/NTRIP corrections live behind a collapsed card: it is an engineering
 * experiment (LC29H test chain), not part of the default product flow, so
 * ordinary users should never have to look at it.
 */
@Composable
private fun RtkExperimentalCard(
    ntripSettings: StateFlow<NtripSettings>,
    ntripConnectionState: StateFlow<NtripConnectionState>,
    ntripActions: NtripActions,
) {
    val savedNtripSettings by ntripSettings.collectAsState()
    val correctionState by ntripConnectionState.collectAsState()
    var draft by remember(savedNtripSettings) { mutableStateOf(savedNtripSettings) }
    var expanded by remember { mutableStateOf(false) }
    val s = strings
    val chinese = s.language == AppLanguage.Chinese

    LapCard(
        title = s.rtkCorrections,
        trailing = {
            Text(
                text = if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = { expanded = !expanded },
    ) {
        Text(
            text = s.rtkExperimentalNote,
            color = LapSightTheme.colors.statusCaution,
            style = MaterialTheme.typography.bodySmall,
        )
        if (expanded) {
            Text(
                text = ntripStatusLabel(correctionState, chinese),
                color = if (correctionState.phase == NtripConnectionPhase.Streaming) {
                    LapSightTheme.colors.statusReady
                } else {
                    LapSightTheme.colors.statusCaution
                },
                style = MaterialTheme.typography.bodySmall,
            )
            LapSwitchRow(
                label = if (chinese) "启用网络 RTK 修正" else "Enable network RTK corrections",
                supporting = if (chinese) {
                    "手机只转发 RTCM；LC29H/HUD 仍是权威定位源"
                } else {
                    "The phone only relays RTCM; LC29H/HUD remains authoritative"
                },
                checked = draft.enabled,
                onCheckedChange = { draft = draft.copy(enabled = it) },
            )
            OutlinedTextField(
                value = draft.host,
                onValueChange = { draft = draft.copy(host = it) },
                label = { Text(if (chinese) "服务器" else "Caster host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.port.toString(),
                onValueChange = { value ->
                    value.filter(Char::isDigit).toIntOrNull()?.let { port ->
                        draft = draft.copy(port = port.coerceIn(1, 65535))
                    }
                },
                label = { Text(if (chinese) "端口" else "Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.mountpoint,
                onValueChange = { draft = draft.copy(mountpoint = it) },
                label = { Text(if (chinese) "挂载点" else "Mountpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                label = { Text(if (chinese) "用户名" else "Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text(if (chinese) "密码" else "Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LapButton(
                text = if (chinese) "保存并应用" else "Save and apply",
                onClick = { ntripActions.apply(draft) },
                style = LapButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun ntripStatusLabel(state: NtripConnectionState, chinese: Boolean): String {
    val base = when (state.phase) {
        NtripConnectionPhase.Disabled -> if (chinese) "未启用" else "Disabled"
        NtripConnectionPhase.IncompleteConfiguration -> if (chinese) "配置不完整" else "Configuration incomplete"
        NtripConnectionPhase.WaitingForHud -> if (chinese) "等待 HUD 连接" else "Waiting for HUD"
        NtripConnectionPhase.Connecting -> if (chinese) "正在连接修正服务" else "Connecting to correction service"
        NtripConnectionPhase.Streaming -> if (chinese) "正在向 LC29H 输入 RTCM" else "Streaming RTCM to LC29H"
        NtripConnectionPhase.Failed -> if (chinese) "修正服务连接失败" else "Correction connection failed"
    }
    val detail = state.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    val bytes = if (state.receivedBytes > 0) " · ${state.receivedBytes} B" else ""
    return base + bytes + detail
}

@Composable
private fun GlassesDeviceRow(
    device: GlassesDeviceSummary,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    val selectable = device.isDisplayCapable && !device.requiresFirmwareUpdate
    Surface(
        onClick = { if (selectable) onSelect() },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else LapSightTheme.colors.cardBorder,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    color = if (selectable) MaterialTheme.colorScheme.onSurface else LapSightTheme.colors.disabledContent,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.type,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = when {
                    device.requiresFirmwareUpdate -> s.firmwareUpdateRequired
                    selected -> s.current
                    device.isDisplayCapable -> s.displayCapable
                    else -> s.displayUnsupported
                },
                color = when {
                    device.requiresFirmwareUpdate -> LapSightTheme.colors.statusCaution
                    selected -> MaterialTheme.colorScheme.primary
                    device.isDisplayCapable -> LapSightTheme.colors.statusReady
                    else -> LapSightTheme.colors.disabledContent
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun LocalizedStrings.glassesConnectionLabel(state: GlassesConnectionState): String = when (state) {
    GlassesConnectionState.Idle -> glassesIdle
    GlassesConnectionState.Connecting -> glassesConnecting
    GlassesConnectionState.Connected -> glassesConnected
    is GlassesConnectionState.Reconnecting -> glassesReconnecting
    is GlassesConnectionState.Error -> state.message
}

internal fun LocalizedStrings.externalGnssConnectionLabel(phase: ExternalGnssConnectionPhase): String = when (phase) {
    ExternalGnssConnectionPhase.Disconnected -> externalGnssDisconnected
    ExternalGnssConnectionPhase.Scanning -> externalGnssScanning
    ExternalGnssConnectionPhase.Connecting -> externalGnssConnecting
    ExternalGnssConnectionPhase.Connected -> externalGnssConnected
    ExternalGnssConnectionPhase.Reconnecting -> externalGnssReconnecting
    ExternalGnssConnectionPhase.Failed -> externalGnssConnectionFailed
}

@Composable
private fun LanguageSelector(
    selected: LanguageMode,
    onSelect: (LanguageMode) -> Unit,
) {
    val spacing = LapSightTheme.spacing
    val s = strings
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, LapSightTheme.colors.cardBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = s.languageModeLabel(selected),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Icon(
                    imageVector = DropdownActionIcon,
                    contentDescription = s.languageTitle,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            LanguageMode.values().forEach { mode ->
                val isSelected = mode == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            text = s.languageModeLabel(mode),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = CheckActionIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}
