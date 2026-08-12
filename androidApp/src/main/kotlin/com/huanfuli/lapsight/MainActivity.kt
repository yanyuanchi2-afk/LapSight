package com.huanfuli.lapsight

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.tooling.preview.Preview
import com.huanfuli.lapsight.glasses.GlassesBridge
import com.huanfuli.lapsight.shared.App
import com.huanfuli.lapsight.shared.DashOrientation
import com.huanfuli.lapsight.shared.DisplaySettingsStore
import com.huanfuli.lapsight.shared.DriveDisplayController
import com.huanfuli.lapsight.shared.DriveDisplaySettings
import com.huanfuli.lapsight.shared.LanguageMode
import com.huanfuli.lapsight.shared.LocationFeedMode
import com.huanfuli.lapsight.shared.NtripActions
import com.huanfuli.lapsight.shared.NtripConnectionState
import com.huanfuli.lapsight.shared.NtripSettings
import com.huanfuli.lapsight.shared.HudMarkingLog
import com.huanfuli.lapsight.shared.HudCourseBundle
import com.huanfuli.lapsight.shared.HUD_COURSE_CHUNK_BYTES
import com.huanfuli.lapsight.shared.HudRemoteCommand
import com.huanfuli.lapsight.shared.HudRemoteMessage
import com.huanfuli.lapsight.shared.HudRuntimeState
import com.huanfuli.lapsight.shared.HudTimingLog
import com.huanfuli.lapsight.shared.HudTimingTelemetry
import com.huanfuli.lapsight.shared.OrientationController
import com.huanfuli.lapsight.shared.PhoneGpsPermissionState
import com.huanfuli.lapsight.shared.SpeedUnit
import com.huanfuli.lapsight.shared.ThemeMode
import com.huanfuli.lapsight.shared.export.AndroidExportShareTarget
import com.huanfuli.lapsight.shared.external.ExternalGnssProtocol
import com.huanfuli.lapsight.shared.parseHudRemoteMessage
import com.huanfuli.lapsight.shared.parseHudMarkingCsv
import com.huanfuli.lapsight.shared.parseHudTimingCsv
import com.huanfuli.lapsight.shared.crc32
import com.huanfuli.lapsight.shared.glasses.GlassesActions
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
import com.huanfuli.lapsight.shared.glasses.GlassesGpsState
import com.huanfuli.lapsight.shared.glasses.HudPage
import com.huanfuli.lapsight.shared.session.SessionController
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.storage.StoragePaths
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val fineLocationPermissionGranted = mutableStateOf(false)
    private var phoneGpsProvider: AndroidPhoneLocationProvider? = null
    private var externalGnssProvider: ExternalGnssLocationProvider? = null
    private var externalGnssBleClient: AndroidExternalGnssBleClient? = null
    private var ntripClient: AndroidNtripClient? = null
    private lateinit var ntripSettingsStore: AndroidNtripSettingsStore
    private val ntripSettings = MutableStateFlow(NtripSettings())
    private val ntripConnectionState = MutableStateFlow(NtripConnectionState())
    @Volatile private var latestHudGga: String? = null
    private val hudRuntimeState = MutableStateFlow<HudRuntimeState?>(null)
    private val hudTimingTelemetry = MutableStateFlow(HudTimingTelemetry())
    private val hudMarkingLog = MutableStateFlow<HudMarkingLog?>(null)
    private val hudTimingLog = MutableStateFlow<HudTimingLog?>(null)
    private var hudLogTransfer: HudLogTransfer? = null
    private var hudLogDownloadComplete = false
    private var lastHudLogRequestElapsedMillis = 0L
    private var hudCourseUpload: HudCourseUpload? = null
    private var completedHudCourseCrc: UInt? = null
    private var pendingHudCourseBundle: HudCourseBundle? = null
    private var pendingHudCourseClear = false
    private var hudTimingLogTransfer: HudTimingLogTransfer? = null
    private var hudTimingLogMetadataRequested = false
    private var lastHudTimingLogRequestElapsedMillis = 0L
    private lateinit var displaySettingsStore: AndroidDisplaySettingsStore
    private lateinit var localSessionStore: LocalSessionStore

    /**
     * The single [SessionController] the phone dash drives, captured via
     * [com.huanfuli.lapsight.shared.App]'s `onSessionControllerReady` seam
     * (Phase 7 MR-01). A future Meta glasses bridge polls this SAME instance —
     * no second controller is ever constructed in `androidApp`.
     */
    private var sessionController: SessionController? = null

    /**
     * The Meta glasses bridge (Phase 7 MR-01/MR-03), constructed once
     * [sessionController] is captured. Owns its own [glassesScope] so its DAT
     * session/render-loop coroutines outlive individual recompositions and are
     * cancelled together in [onDestroy].
     */
    private var glassesBridge: GlassesBridge? = null
    private val glassesScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var glassesBridgeCollectionJobs: List<Job> = emptyList()
    private val glassesConnectionState = MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Idle)
    private val glassesDevices = MutableStateFlow<List<GlassesDeviceSummary>>(emptyList())
    private val glassesIdleGpsState = MutableStateFlow(GlassesGpsState.idle())
    private val selectedGlassesDeviceId = MutableStateFlow<String?>(null)
    private val glassesCastingEnabled = MutableStateFlow(false)
    private val glassesPage = MutableStateFlow(HudPage.FOCUSED)
    private var timingForegroundServiceActive = false
    private val glassesPreferences by lazy {
        getSharedPreferences("glasses_settings", Context.MODE_PRIVATE)
    }

    private val glassesActions = object : GlassesActions {
        override fun register() {
            runCatching { Wearables.startRegistration(this@MainActivity) }
                .onFailure { Log.e("MainActivity", "Wearables.startRegistration failed", it) }
        }

        override fun pickDevice(id: String) {
            selectedGlassesDeviceId.value = id
            glassesPreferences.edit().putString(KEY_SELECTED_GLASSES_DEVICE_ID, id).apply()
            if (glassesCastingEnabled.value) {
                glassesBridge?.connect(id)
            }
        }

        override fun startCasting() {
            val deviceId = selectedGlassesDeviceId.value
            if (deviceId == null) {
                glassesConnectionState.value = GlassesConnectionState.Error("Select glasses in Settings first")
                glassesCastingEnabled.value = false
                return
            }
            glassesCastingEnabled.value = true
            glassesBridge?.connect(deviceId)
        }

        override fun stopCasting() {
            glassesCastingEnabled.value = false
            glassesBridge?.stop()
        }

        override fun setPage(page: HudPage) {
            glassesPage.value = page
            glassesBridge?.page = page
        }

        override fun openFirmwareUpdate() {
            Wearables.openFirmwareUpdate(this@MainActivity)
                .onFailure { error, _ ->
                    Log.e("MainActivity", "openFirmwareUpdate failed: ${error.description}")
                }
        }

        override fun openDatAppUpdate() {
            Wearables.openDATGlassesAppUpdate(this@MainActivity)
                .onFailure { error, _ ->
                    Log.e("MainActivity", "openDATGlassesAppUpdate failed: ${error.description}")
                }
        }
    }

    private val ntripActions = object : NtripActions {
        override fun apply(settings: NtripSettings) {
            val normalized = settings.copy(
                host = settings.host.trim(),
                mountpoint = settings.mountpoint.trim().trimStart('/'),
            )
            ntripSettingsStore.save(normalized)
            ntripSettings.value = normalized
            ntripClient?.apply(normalized)
        }
    }

    private val requestLocationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            fineLocationPermissionGranted.value = hasFineLocationPermission()
        }

    // BLUETOOTH_CONNECT/BLUETOOTH_SCAN are runtime (dangerous) permissions only
    // from API 31; the manifest grant covers API 29-30 (Phase 7 minSdk floor,
    // see 07-01). BLUETOOTH_SCAN additionally serves the Phase 6 external GNSS
    // BLE client (D-04).
    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // Locks the window to the user's chosen orientation using fixed
    // (sensor-independent) values. Never SENSOR_*/USER_* — a mounted phone must
    // not rotate from accelerometer input under racing G-forces.
    private val orientationController = object : OrientationController {
        override fun apply(orientation: DashOrientation) {
            window.attributes = window.attributes.apply {
                rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            }
            requestedOrientation = when (orientation) {
                DashOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                DashOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }

    private val driveDisplayController = object : DriveDisplayController {
        override fun apply(fullscreen: Boolean, keepScreenAwake: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { insets ->
                    if (fullscreen) {
                        insets.systemBarsBehavior =
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        insets.hide(WindowInsets.Type.systemBars())
                    } else {
                        insets.show(WindowInsets.Type.systemBars())
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (fullscreen) {
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                } else {
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
            }
            if (keepScreenAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        fineLocationPermissionGranted.value = hasFineLocationPermission()

        // Wire the app-private storage root before any save/load access (D-21).
        StoragePaths.initialize(this)
        localSessionStore = StoragePaths.fileSessionStore()
        restoreLatestCachedHudTimingLog()?.let {
            hudTimingLog.value = it
            Log.i("LapSightHud", "Restored persisted HUD timing result session=${it.sessionId} without HUD transfer")
        }

        val shareTarget = AndroidExportShareTarget(this)
        displaySettingsStore = AndroidDisplaySettingsStore(this)
        ntripSettingsStore = AndroidNtripSettingsStore(this)
        ntripSettings.value = ntripSettingsStore.load()
        selectedGlassesDeviceId.value = glassesPreferences.getString(KEY_SELECTED_GLASSES_DEVICE_ID, null)
        phoneGpsProvider = AndroidPhoneLocationProvider(
            context = this,
            hasFineLocationPermission = { hasFineLocationPermission() },
            // Read fresh each feed start so a settings toggle applies on next start.
            useDirectGnss = { displaySettingsStore.load().useDirectGnss },
        )

        // Meta DAT SDK bootstrap (Phase 7 MR-01). Non-fatal on failure — a
        // glasses-less phone session must still work.
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e("MainActivity", "Wearables.initialize failed: ${error.description}") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermission.launch(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
            )
        }

        // External GNSS (Phase 6, protocol preview, D-01/D-02): NMEA 0183 over a
        // BLE UART-style notify characteristic. Injectable byte-stream client
        // seam so this is testable without hardware; the BLE transport itself
        // remains explicitly unvalidated until a real receiver confirms it.
        externalGnssBleClient = AndroidExternalGnssBleClient(
                context = this,
                hasBlePermission = { AndroidExternalGnssBleClient.hasBlePermission(this) },
            )
        externalGnssBleClient?.setHudMessageListener { line ->
            val message = parseHudRemoteMessage(line)
            handleHudMessage(message)
            Log.d("LapSightHud", "HUD ${message ?: line}")
        }
        externalGnssBleClient?.setGgaSentenceListener { sentence -> latestHudGga = sentence }
        externalGnssBleClient?.setReceiverListener { identity ->
            externalGnssProvider?.updateReceiver(identity)
        }
        externalGnssProvider = ExternalGnssLocationProvider(
            client = externalGnssBleClient!!,
            protocol = ExternalGnssProtocol.Nmea0183,
        )
        val externalGnssRescan: () -> Unit = {
            externalGnssProvider?.stop()
            externalGnssProvider?.start()
        }
        val externalGnssDisconnect: () -> Unit = {
            externalGnssProvider?.stop()
        }
        ntripClient = AndroidNtripClient(
            latestGga = { latestHudGga },
            onCorrections = { bytes -> externalGnssBleClient?.sendRtcmCorrections(bytes) },
            onState = { state -> ntripConnectionState.value = state },
        ).also { it.apply(ntripSettings.value) }
        glassesScope.launch {
            externalGnssProvider!!.connectionState.collect { state ->
                ntripClient?.setHudConnected(
                    state.phase == com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase.Connected,
                )
            }
        }

        setContent {
            App(
                orientationController = orientationController,
                driveDisplayController = driveDisplayController,
                displaySettingsStore = displaySettingsStore,
                phoneGpsProvider = phoneGpsProvider,
                externalGnssProvider = externalGnssProvider,
                externalGnssConnectionState = externalGnssProvider!!.connectionState,
                onExternalGnssRescan = externalGnssRescan,
                onExternalGnssDisconnect = externalGnssDisconnect,
                ntripSettings = ntripSettings,
                ntripConnectionState = ntripConnectionState,
                ntripActions = ntripActions,
                phoneGpsPermission = PhoneGpsPermissionState(
                    isSupported = true,
                    isGranted = fineLocationPermissionGranted.value,
                    requestPermission = {
                        requestLocationPermissions.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                ),
                sessionStore = localSessionStore,
                exportShareTarget = shareTarget,
                onSessionControllerReady = { controller ->
                    installGlassesBridge(controller)
                },
                glassesConnectionState = glassesConnectionState,
                glassesDevices = glassesDevices,
                glassesSelectedDeviceId = selectedGlassesDeviceId,
                glassesCastingEnabled = glassesCastingEnabled,
                glassesPage = glassesPage,
                glassesActions = glassesActions,
                onGlassesIdleGpsStateChanged = { state ->
                    glassesIdleGpsState.value = state
                },
                onTimingForegroundChanged = { active, feedMode ->
                    setTimingForegroundService(
                        active &&
                            (feedMode == LocationFeedMode.PhoneGps || feedMode == LocationFeedMode.ExternalGnss),
                    )
                },
                hudRuntimeState = hudRuntimeState,
                hudTimingTelemetry = hudTimingTelemetry,
                hudMarkingLog = hudMarkingLog,
                hudTimingLog = hudTimingLog,
                onHudCommandRequested = { command ->
                    sendHudCommand(command)
                },
                onHudCourseBundleRequested = ::beginHudCourseUpload,
                onHudTimingResultHandled = ::clearHandledHudTimingResult,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        fineLocationPermissionGranted.value = hasFineLocationPermission()
    }

    override fun onDestroy() {
        phoneGpsProvider?.stop()
        externalGnssProvider?.stop()
        ntripClient?.close()
        glassesBridgeCollectionJobs.forEach { it.cancel() }
        glassesBridge?.stop()
        glassesScope.cancel()
        super.onDestroy()
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun handleHudMessage(message: HudRemoteMessage?) {
        when (message) {
            is HudRemoteMessage.State -> {
                hudRuntimeState.value = message.value
                hudTimingTelemetry.value = hudTimingTelemetry.value.copy(
                    phase = message.value.phase,
                    sessionElapsedMillis = message.value.elapsedMillis,
                    lapCount = if (message.value.phase == com.huanfuli.lapsight.shared.HudSessionPhase.Timing ||
                        message.value.phase == com.huanfuli.lapsight.shared.HudSessionPhase.Paused
                    ) message.value.completedLoops else hudTimingTelemetry.value.lapCount,
                    currentLapNumber = if (message.value.phase == com.huanfuli.lapsight.shared.HudSessionPhase.Timing ||
                        message.value.phase == com.huanfuli.lapsight.shared.HudSessionPhase.Paused
                    ) message.value.pointCount.coerceAtLeast(1) else hudTimingTelemetry.value.currentLapNumber,
                )
                when (message.value.phase) {
                    com.huanfuli.lapsight.shared.HudSessionPhase.Marking -> {
                        hudMarkingLog.value = null
                        hudLogTransfer = null
                        hudLogDownloadComplete = false
                        lastHudLogRequestElapsedMillis = 0L
                    }
                    com.huanfuli.lapsight.shared.HudSessionPhase.Review -> requestHudLogProgress()
                    com.huanfuli.lapsight.shared.HudSessionPhase.Idle -> {
                        requestHudTimingLogMetadata()
                        if (pendingHudCourseClear) {
                            pendingHudCourseClear = false
                            externalGnssBleClient?.sendHudCommand(HudRemoteCommand.CourseClear)
                        } else {
                            pendingHudCourseBundle?.let(::beginHudCourseUpload)
                        }
                    }
                    com.huanfuli.lapsight.shared.HudSessionPhase.Timing,
                    com.huanfuli.lapsight.shared.HudSessionPhase.Paused,
                    -> hudTimingLogMetadataRequested = false
                }
            }
            is HudRemoteMessage.MarkLogMetadata -> handleHudLogMetadata(message)
            is HudRemoteMessage.MarkLogData -> handleHudLogData(message)
            is HudRemoteMessage.CourseUploadProgress -> handleHudCourseProgress(message.nextOffset)
            is HudRemoteMessage.LapCompleted -> {
                hudTimingTelemetry.value = hudTimingTelemetry.value.copy(
                    lapCount = message.lapNumber,
                    currentLapNumber = message.lapNumber + 1,
                    lastLapMillis = message.lapMillis,
                    bestLapMillis = message.bestLapMillis,
                )
            }
            is HudRemoteMessage.TimingData -> {
                hudTimingTelemetry.value = hudTimingTelemetry.value.copy(
                    phase = if (message.paused) com.huanfuli.lapsight.shared.HudSessionPhase.Paused
                        else com.huanfuli.lapsight.shared.HudSessionPhase.Timing,
                    sessionElapsedMillis = message.sessionElapsedMillis,
                    currentLapNumber = message.currentLapNumber,
                    currentLapMillis = message.currentLapMillis,
                    lastLapMillis = message.lastLapMillis,
                    bestLapMillis = message.bestLapMillis,
                    currentSectorNumber = message.currentSectorNumber,
                    sectorCount = message.sectorCount,
                    liveDeltaMillis = message.liveDeltaMillis,
                )
            }
            is HudRemoteMessage.SectorCompleted -> {
                hudTimingTelemetry.value = hudTimingTelemetry.value.copy(
                    currentSectorNumber = message.sectorNumber + 1,
                    latestSectorMillis = message.sectorMillis,
                    bestSectorMillis = message.bestSectorMillis,
                    liveDeltaMillis = message.deltaMillis,
                )
            }
            is HudRemoteMessage.TimingLogMetadata -> handleHudTimingLogMetadata(message)
            is HudRemoteMessage.TimingLogData -> handleHudTimingLogData(message)
            is HudRemoteMessage.Acknowledgement -> {
                if (message.command == "TIMING_START" &&
                    (message.result == "OK" || message.result == "ALREADY_ACTIVE")
                ) {
                    hudTimingLog.value = null
                    hudTimingLogTransfer = null
                    hudTimingLogMetadataRequested = false
                    hudTimingTelemetry.value = HudTimingTelemetry(
                        phase = com.huanfuli.lapsight.shared.HudSessionPhase.Timing,
                    )
                }
                if (message.command == "COURSE_COMMIT" && message.result == "OK") {
                    hudCourseUpload?.let { completedHudCourseCrc = it.bundle.crc32 }
                    Log.i("LapSightHud", "HUD course upload committed crc=${hudCourseUpload?.bundle?.crc32}")
                    hudCourseUpload = null
                }
            }
            is HudRemoteMessage.Error -> {
                if (message.command.startsWith("MARK_LOG")) {
                    lastHudLogRequestElapsedMillis = 0L
                    Log.e("LapSightHud", "HUD log request failed: ${message.reason}")
                }
                if (message.command.startsWith("COURSE")) {
                    Log.e("LapSightHud", "HUD course upload failed: ${message.reason}")
                }
                if (message.command.startsWith("TIMING_LOG")) {
                    lastHudTimingLogRequestElapsedMillis = 0L
                    Log.i("LapSightHud", "HUD timing result unavailable: ${message.reason}")
                }
            }
            else -> Unit
        }
    }

    private fun beginHudCourseUpload(bundle: HudCourseBundle) {
        pendingHudCourseBundle = bundle
        pendingHudCourseClear = false
        if (completedHudCourseCrc == bundle.crc32) {
            pendingHudCourseBundle = null
            return
        }
        if (hudRuntimeState.value?.phase != com.huanfuli.lapsight.shared.HudSessionPhase.Idle) return
        pendingHudCourseBundle = null
        val active = hudCourseUpload
        hudCourseUpload = if (active?.bundle?.crc32 == bundle.crc32) active else HudCourseUpload(bundle)
        externalGnssBleClient?.sendHudCommand(
            HudRemoteCommand.CourseBegin(bundle.bytes.size, bundle.crc32),
        )
    }

    private fun sendHudCommand(command: HudRemoteCommand) {
        if (command == HudRemoteCommand.CourseClear) {
            pendingHudCourseBundle = null
            val phase = hudRuntimeState.value?.phase
            if (phase != com.huanfuli.lapsight.shared.HudSessionPhase.Idle) {
                pendingHudCourseClear = true
                return
            }
        }
        externalGnssBleClient?.sendHudCommand(command)
    }

    private fun handleHudCourseProgress(nextOffset: Int) {
        val upload = hudCourseUpload ?: return
        if (nextOffset !in 0..upload.bundle.bytes.size) {
            Log.e("LapSightHud", "HUD course offset $nextOffset outside payload")
            return
        }
        if (nextOffset == upload.bundle.bytes.size) {
            externalGnssBleClient?.sendHudCommand(HudRemoteCommand.CourseCommit)
            return
        }
        val end = minOf(nextOffset + HUD_COURSE_CHUNK_BYTES, upload.bundle.bytes.size)
        externalGnssBleClient?.sendHudCommand(
            HudRemoteCommand.CourseWrite(nextOffset, upload.bundle.bytes.copyOfRange(nextOffset, end)),
        )
    }

    private fun requestHudLogProgress(force: Boolean = false) {
        if (hudRuntimeState.value?.phase != com.huanfuli.lapsight.shared.HudSessionPhase.Review) return
        if (hudLogDownloadComplete) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastHudLogRequestElapsedMillis < HUD_LOG_RETRY_MILLIS) return
        val command = hudLogTransfer?.let {
            HudRemoteCommand.MarkLogRead(offset = it.bytes.size().toLong())
        } ?: HudRemoteCommand.MarkLogMetadata
        lastHudLogRequestElapsedMillis = now
        externalGnssBleClient?.sendHudCommand(command)
    }

    private fun handleHudLogMetadata(message: HudRemoteMessage.MarkLogMetadata) {
        if (hudRuntimeState.value?.phase != com.huanfuli.lapsight.shared.HudSessionPhase.Review) return
        if (message.sizeBytes !in 1..MAX_HUD_LOG_BYTES) {
            Log.e("LapSightHud", "Refusing HUD log size ${message.sizeBytes}")
            return
        }
        val existing = hudLogTransfer
        hudLogTransfer = if (
            existing != null &&
            existing.sizeBytes == message.sizeBytes &&
            existing.crc32 == message.crc32 &&
            existing.path == message.path
        ) {
            existing
        } else {
            HudLogTransfer(message.sizeBytes, message.crc32, message.path)
        }
        requestHudLogProgress(force = true)
    }

    private fun handleHudLogData(message: HudRemoteMessage.MarkLogData) {
        if (hudRuntimeState.value?.phase != com.huanfuli.lapsight.shared.HudSessionPhase.Review) return
        val transfer = hudLogTransfer ?: return
        val expectedOffset = transfer.bytes.size().toLong()
        if (message.offset != expectedOffset) {
            Log.w("LapSightHud", "HUD log offset ${message.offset}, expected $expectedOffset")
            requestHudLogProgress(force = true)
            return
        }
        if (expectedOffset + message.bytes.size > transfer.sizeBytes) {
            Log.e("LapSightHud", "HUD log chunk exceeds declared size")
            hudLogTransfer = null
            lastHudLogRequestElapsedMillis = 0L
            return
        }
        transfer.bytes.write(message.bytes)
        if (transfer.bytes.size().toLong() < transfer.sizeBytes) {
            requestHudLogProgress(force = true)
            return
        }

        val bytes = transfer.bytes.toByteArray()
        val actualCrc = crc32(bytes)
        if (actualCrc != transfer.crc32) {
            Log.e("LapSightHud", "HUD log CRC mismatch expected=${transfer.crc32} actual=$actualCrc")
            hudLogTransfer = null
            lastHudLogRequestElapsedMillis = 0L
            return
        }
        val samples = parseHudMarkingCsv(bytes.toString(Charsets.UTF_8))
        if (samples.isEmpty()) {
            Log.e("LapSightHud", "HUD log contained no valid GNSS fixes")
            hudLogTransfer = null
            lastHudLogRequestElapsedMillis = 0L
            return
        }
        hudMarkingLog.value = HudMarkingLog(
            sizeBytes = transfer.sizeBytes,
            crc32 = transfer.crc32,
            path = transfer.path,
            samples = samples,
        )
        hudLogDownloadComplete = true
        Log.i(
            "LapSightHud",
            "HUD log complete path=${transfer.path} bytes=${transfer.sizeBytes} samples=${samples.size}",
        )
        hudLogTransfer = null
    }

    private fun requestHudTimingLogMetadata() {
        if (hudTimingLogMetadataRequested) return
        hudTimingLogMetadataRequested = true
        lastHudTimingLogRequestElapsedMillis = SystemClock.elapsedRealtime()
        externalGnssBleClient?.sendHudCommand(HudRemoteCommand.TimingLogMetadata)
    }

    private fun requestHudTimingLogChunk(force: Boolean = false) {
        val transfer = hudTimingLogTransfer ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastHudTimingLogRequestElapsedMillis < HUD_LOG_RETRY_MILLIS) return
        lastHudTimingLogRequestElapsedMillis = now
        externalGnssBleClient?.sendHudCommand(
            HudRemoteCommand.TimingLogRead(
                sessionId = transfer.metadata.sessionId,
                offset = transfer.bytes.size().toLong(),
            ),
        )
    }

    private fun handleHudTimingLogMetadata(message: HudRemoteMessage.TimingLogMetadata) {
        hudTimingLogMetadataRequested = true
        if (message.sizeBytes !in 1..MAX_HUD_LOG_BYTES) {
            Log.e("LapSightHud", "Refusing HUD timing log size ${message.sizeBytes}")
            return
        }
        loadPersistedHudTimingLog(message)?.let { recovered ->
            hudTimingLog.value = recovered
            hudTimingLogTransfer = null
            Log.i("LapSightHud", "HUD timing result restored from phone cache session=${message.sessionId}")
            if (persistHudTimingMetadata(message)) acknowledgeHudTimingLog(message)
            return
        }
        val existing = hudTimingLogTransfer
        hudTimingLogTransfer = if (existing?.metadata == message) existing else HudTimingLogTransfer(message)
        requestHudTimingLogChunk(force = true)
    }

    private fun handleHudTimingLogData(message: HudRemoteMessage.TimingLogData) {
        val transfer = hudTimingLogTransfer ?: return
        if (message.sessionId != transfer.metadata.sessionId) {
            Log.w("LapSightHud", "HUD timing result changed during transfer")
            hudTimingLogTransfer = null
            hudTimingLogMetadataRequested = false
            return
        }
        val expectedOffset = transfer.bytes.size().toLong()
        if (message.offset != expectedOffset) {
            Log.w("LapSightHud", "HUD timing log offset ${message.offset}, expected $expectedOffset")
            requestHudTimingLogChunk(force = true)
            return
        }
        if (expectedOffset + message.bytes.size > transfer.metadata.sizeBytes) {
            Log.e("LapSightHud", "HUD timing log chunk exceeds declared size")
            hudTimingLogTransfer = null
            return
        }
        transfer.bytes.write(message.bytes)
        if (transfer.bytes.size().toLong() < transfer.metadata.sizeBytes) {
            requestHudTimingLogChunk(force = true)
            return
        }
        val bytes = transfer.bytes.toByteArray()
        if (crc32(bytes) != transfer.metadata.crc32) {
            Log.e("LapSightHud", "HUD timing log whole-file CRC mismatch")
            hudTimingLogTransfer = null
            hudTimingLogMetadataRequested = false
            return
        }
        val result = buildHudTimingLog(transfer.metadata, bytes)
        val persisted = persistHudTimingLog(transfer.metadata, bytes)
        hudTimingLog.value = result
        hudTimingTelemetry.value = hudTimingTelemetry.value.copy(
            lapCount = result.lapCount,
            currentLapNumber = result.lapCount + 1,
            lastLapMillis = result.laps.lastOrNull()?.durationMillis,
            bestLapMillis = result.bestLapMillis,
            latestSectorMillis = result.sectors.lastOrNull()?.durationMillis,
            bestSectorMillis = result.sectors.lastOrNull()?.bestSectorMillis,
        )
        Log.i(
            "LapSightHud",
            "HUD timing log complete session=${result.sessionId} bytes=${result.sizeBytes} " +
                "laps=${result.laps.size} samples=${result.samples.size} complete=${result.complete}",
        )
        hudTimingLogTransfer = null
        if (persisted) acknowledgeHudTimingLog(transfer.metadata)
    }

    private fun buildHudTimingLog(
        metadata: HudRemoteMessage.TimingLogMetadata,
        bytes: ByteArray,
    ): HudTimingLog {
        val contents = parseHudTimingCsv(bytes.toString(Charsets.UTF_8))
        return HudTimingLog(
            sessionId = metadata.sessionId,
            sizeBytes = metadata.sizeBytes,
            crc32 = metadata.crc32,
            path = metadata.path,
            complete = metadata.complete,
            durationMillis = metadata.durationMillis,
            lapCount = metadata.lapCount,
            bestLapMillis = metadata.bestLapMillis,
            samples = contents.samples,
            laps = contents.laps,
            sectors = contents.sectors,
            profileId = metadata.profileId,
            revisionId = metadata.revisionId,
            geometryCompatibilityId = metadata.geometryCompatibilityId,
            direction = metadata.direction,
        )
    }

    private fun hudTimingLogFile(metadata: HudRemoteMessage.TimingLogMetadata) =
        filesDir.resolve("lapsight/hud-timing-results/${metadata.sessionId}-${metadata.crc32}.csv")

    private fun persistHudTimingLog(metadata: HudRemoteMessage.TimingLogMetadata, bytes: ByteArray): Boolean =
        runCatching {
            val target = hudTimingLogFile(metadata)
            target.parentFile?.mkdirs()
            val temporary = target.resolveSibling("${target.name}.tmp")
            temporary.writeBytes(bytes)
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not atomically cache HUD timing log" }
            check(persistHudTimingMetadata(metadata)) { "Could not persist HUD timing metadata" }
            true
        }.onFailure { Log.e("LapSightHud", "Could not cache HUD timing log", it) }.getOrDefault(false)

    private fun persistHudTimingMetadata(metadata: HudRemoteMessage.TimingLogMetadata): Boolean =
        getSharedPreferences(HUD_TIMING_RESULT_PREFERENCES, Context.MODE_PRIVATE).edit()
                .putLong("session_id", metadata.sessionId)
                .putLong("size_bytes", metadata.sizeBytes)
                .putLong("crc32", metadata.crc32.toLong())
                .putBoolean("complete", metadata.complete)
                .putLong("duration_ms", metadata.durationMillis)
                .putInt("lap_count", metadata.lapCount)
                .putLong("best_lap_ms", metadata.bestLapMillis ?: 0L)
                .putString("hud_path", metadata.path)
                .putString("profile_id", metadata.profileId)
                .putString("revision_id", metadata.revisionId)
                .putString("geometry_id", metadata.geometryCompatibilityId)
                .putString("direction", metadata.direction?.name)
                .commit()

    private fun loadPersistedHudTimingLog(metadata: HudRemoteMessage.TimingLogMetadata): HudTimingLog? =
        runCatching {
            val bytes = hudTimingLogFile(metadata).takeIf { it.isFile }?.readBytes() ?: return@runCatching null
            if (bytes.size.toLong() != metadata.sizeBytes || crc32(bytes) != metadata.crc32) return@runCatching null
            buildHudTimingLog(metadata, bytes)
        }.getOrNull()

    private fun restoreLatestCachedHudTimingLog(): HudTimingLog? {
        val preferences = getSharedPreferences(HUD_TIMING_RESULT_PREFERENCES, Context.MODE_PRIVATE)
        val sessionId = preferences.getLong("session_id", 0L).takeIf { it > 0L } ?: return null
        val sizeBytes = preferences.getLong("size_bytes", 0L).takeIf { it > 0L } ?: return null
        val crcValue = preferences.getLong("crc32", -1L).takeIf { it in 0..0xFFFF_FFFFL } ?: return null
        val metadata = HudRemoteMessage.TimingLogMetadata(
            sessionId = sessionId,
            sizeBytes = sizeBytes,
            crc32 = crcValue.toUInt(),
            complete = preferences.getBoolean("complete", false),
            durationMillis = preferences.getLong("duration_ms", 0L).coerceAtLeast(0L),
            lapCount = preferences.getInt("lap_count", 0).coerceAtLeast(0),
            bestLapMillis = preferences.getLong("best_lap_ms", 0L).takeIf { it > 0L },
            path = preferences.getString("hud_path", null) ?: return null,
            profileId = preferences.getString("profile_id", null),
            revisionId = preferences.getString("revision_id", null),
            geometryCompatibilityId = preferences.getString("geometry_id", null),
            direction = preferences.getString("direction", null)?.let {
                runCatching { com.huanfuli.lapsight.shared.track.CourseDirection.valueOf(it) }.getOrNull()
            },
        )
        return loadPersistedHudTimingLog(metadata)
    }

    private fun acknowledgeHudTimingLog(metadata: HudRemoteMessage.TimingLogMetadata) {
        externalGnssBleClient?.sendHudCommand(
            HudRemoteCommand.TimingLogAcknowledge(metadata.sessionId, metadata.crc32),
        )
    }

    private fun clearHandledHudTimingResult() {
        val result = hudTimingLog.value
        if (result != null) {
            filesDir.resolve("lapsight/hud-timing-results/${result.sessionId}-${result.crc32}.csv").delete()
        }
        getSharedPreferences(HUD_TIMING_RESULT_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        hudTimingLog.value = null
        hudTimingLogTransfer = null
        hudTimingLogMetadataRequested = false
    }

    private fun setTimingForegroundService(active: Boolean) {
        if (timingForegroundServiceActive == active) return
        timingForegroundServiceActive = active
        if (active) {
            TimingForegroundService.start(this)
        } else {
            TimingForegroundService.stop(this)
        }
    }

    private fun installGlassesBridge(controller: SessionController) {
        if (sessionController === controller && glassesBridge != null) return
        glassesBridgeCollectionJobs.forEach { it.cancel() }
        glassesBridge?.stop()
        sessionController = controller
        val bridge = GlassesBridge(
            sessionController = controller,
            scope = glassesScope,
            idleGpsState = { glassesIdleGpsState.value },
            speedUnit = { displaySettingsStore.load().speedUnit },
        )
        bridge.page = glassesPage.value
        glassesBridge = bridge
        glassesBridgeCollectionJobs = listOf(
            glassesScope.launch {
                bridge.connectionState.collect { state ->
                    glassesConnectionState.value = state
                    if (state is GlassesConnectionState.Error) {
                        glassesCastingEnabled.value = false
                    }
                }
            },
            glassesScope.launch {
                bridge.devices.collect { devices -> glassesDevices.value = devices }
            },
        )
        if (glassesCastingEnabled.value) {
            selectedGlassesDeviceId.value?.let { bridge.connect(it) }
        }
    }

    private companion object {
        private const val KEY_SELECTED_GLASSES_DEVICE_ID = "selected_glasses_device_id"
        private const val HUD_LOG_RETRY_MILLIS = 2_000L
        private const val MAX_HUD_LOG_BYTES = 32L * 1024L * 1024L
        private const val HUD_TIMING_RESULT_PREFERENCES = "hud_timing_result"
    }

    private data class HudLogTransfer(
        val sizeBytes: Long,
        val crc32: UInt,
        val path: String,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    private data class HudCourseUpload(val bundle: HudCourseBundle)

    private data class HudTimingLogTransfer(
        val metadata: HudRemoteMessage.TimingLogMetadata,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
    )
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}

private class AndroidDisplaySettingsStore(
    activity: ComponentActivity,
) : DisplaySettingsStore {
    private val preferences = activity.getSharedPreferences("display_settings", Context.MODE_PRIVATE)

    override fun load(): DriveDisplaySettings = DriveDisplaySettings(
        speedUnit = runCatching {
            SpeedUnit.valueOf(
                preferences.getString("speed_unit", SpeedUnit.KilometersPerHour.name)
                    ?: SpeedUnit.KilometersPerHour.name,
            )
        }.getOrDefault(SpeedUnit.KilometersPerHour),
        fullscreenWhileTiming = preferences.getBoolean("fullscreen_while_timing", true),
        keepScreenAwakeWhileTiming = preferences.getBoolean("keep_screen_awake", true),
        showSpeedTrace = preferences.getBoolean("show_speed_trace", true),
        showGpsDiagnostics = preferences.getBoolean("show_gps_diagnostics", true),
        themeMode = runCatching {
            ThemeMode.valueOf(
                preferences.getString("theme_mode", ThemeMode.System.name)
                    ?: ThemeMode.System.name,
            )
        }.getOrDefault(ThemeMode.System),
        languageMode = runCatching {
            LanguageMode.valueOf(
                preferences.getString("language_mode", LanguageMode.System.name)
                    ?: LanguageMode.System.name,
            )
        }.getOrDefault(LanguageMode.System),
        locationFeedMode = runCatching {
            LocationFeedMode.valueOf(
                preferences.getString("location_feed_mode", LocationFeedMode.PhoneGps.name)
                    ?: LocationFeedMode.PhoneGps.name,
            )
        }.getOrDefault(LocationFeedMode.PhoneGps),
        useDirectGnss = preferences.getBoolean("use_direct_gnss", false),
    )

    override fun save(settings: DriveDisplaySettings) {
        preferences.edit()
            .putString("speed_unit", settings.speedUnit.name)
            .putBoolean("fullscreen_while_timing", settings.fullscreenWhileTiming)
            .putBoolean("keep_screen_awake", settings.keepScreenAwakeWhileTiming)
            .putBoolean("show_speed_trace", settings.showSpeedTrace)
            .putBoolean("show_gps_diagnostics", settings.showGpsDiagnostics)
            .putString("theme_mode", settings.themeMode.name)
            .putString("language_mode", settings.languageMode.name)
            .putString("location_feed_mode", settings.locationFeedMode.name)
            .putBoolean("use_direct_gnss", settings.useDirectGnss)
            .apply()
    }
}
