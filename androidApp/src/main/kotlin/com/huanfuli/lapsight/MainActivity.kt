package com.huanfuli.lapsight

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
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
import com.huanfuli.lapsight.shared.OrientationController
import com.huanfuli.lapsight.shared.PhoneGpsPermissionState
import com.huanfuli.lapsight.shared.SpeedUnit
import com.huanfuli.lapsight.shared.ThemeMode
import com.huanfuli.lapsight.shared.export.AndroidExportShareTarget
import com.huanfuli.lapsight.shared.external.ExternalGnssProtocol
import com.huanfuli.lapsight.shared.glasses.GlassesActions
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
import com.huanfuli.lapsight.shared.glasses.GlassesGpsState
import com.huanfuli.lapsight.shared.glasses.HudPage
import com.huanfuli.lapsight.shared.session.SessionController
import com.huanfuli.lapsight.shared.storage.StoragePaths
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val fineLocationPermissionGranted = mutableStateOf(false)
    private var phoneGpsProvider: AndroidPhoneLocationProvider? = null
    private var externalGnssProvider: ExternalGnssLocationProvider? = null
    private lateinit var displaySettingsStore: AndroidDisplaySettingsStore

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

        val shareTarget = AndroidExportShareTarget(this)
        displaySettingsStore = AndroidDisplaySettingsStore(this)
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
        externalGnssProvider = ExternalGnssLocationProvider(
            client = AndroidExternalGnssBleClient(
                context = this,
                hasBlePermission = { AndroidExternalGnssBleClient.hasBlePermission(this) },
            ),
            protocol = ExternalGnssProtocol.Nmea0183,
        )

        setContent {
            App(
                orientationController = orientationController,
                driveDisplayController = driveDisplayController,
                displaySettingsStore = displaySettingsStore,
                phoneGpsProvider = phoneGpsProvider,
                externalGnssProvider = externalGnssProvider,
                externalGnssConnectionState = externalGnssProvider!!.connectionState,
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
                sessionStore = StoragePaths.fileSessionStore(),
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
        glassesBridgeCollectionJobs.forEach { it.cancel() }
        glassesBridge?.stop()
        glassesScope.cancel()
        super.onDestroy()
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
    }
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
