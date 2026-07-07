package com.huanfuli.lapsight.shared

enum class SpeedUnit {
    KilometersPerHour,
    MilesPerHour,
}

enum class ThemeMode {
    System,
    Dark,
    Light,
}

enum class LocationFeedMode {
    PhoneGps,
    Simulated,
    /**
     * Optional Android external GNSS receiver (RaceBox/NMEA protocol preview,
     * D-01/D-02). Only selectable when the platform wires a real
     * [com.huanfuli.lapsight.shared.LocationSampleProvider] for it; falls back
     * to Simulated everywhere else (iOS, or Android without a provider).
     */
    ExternalGnss,
}

enum class LanguageMode {
    System,
    English,
    Chinese,
    Korean,
    Japanese,
    French,
    Spanish,
}

data class PhoneGpsPermissionState(
    val isSupported: Boolean = false,
    val isGranted: Boolean = false,
    val requestPermission: () -> Unit = {},
)

data class DriveDisplaySettings(
    val speedUnit: SpeedUnit = SpeedUnit.KilometersPerHour,
    val fullscreenWhileTiming: Boolean = true,
    val keepScreenAwakeWhileTiming: Boolean = true,
    val showSpeedTrace: Boolean = true,
    val showGpsDiagnostics: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val languageMode: LanguageMode = LanguageMode.System,
    val locationFeedMode: LocationFeedMode = LocationFeedMode.Simulated,
    /**
     * Opt-in high-rate phone GNSS engine (Android only). When true, Phone GPS is
     * fed by the raw `GPS_PROVIDER` for the fastest fixes the chipset offers plus
     * satellite/L5 quality signals, instead of the battery-throttled Fused engine.
     * Ignored on platforms without a direct-GNSS provider.
     */
    val useDirectGnss: Boolean = false,
)

interface DisplaySettingsStore {
    fun load(): DriveDisplaySettings
    fun save(settings: DriveDisplaySettings)
}

class InMemoryDisplaySettingsStore(
    initial: DriveDisplaySettings = DriveDisplaySettings(),
) : DisplaySettingsStore {
    private var settings = initial

    override fun load(): DriveDisplaySettings = settings

    override fun save(settings: DriveDisplaySettings) {
        this.settings = settings
    }
}

interface DriveDisplayController {
    fun apply(fullscreen: Boolean, keepScreenAwake: Boolean)
}

object NoOpDriveDisplayController : DriveDisplayController {
    override fun apply(fullscreen: Boolean, keepScreenAwake: Boolean) = Unit
}
