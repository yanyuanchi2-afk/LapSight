package com.huanfuli.lapsight.shared

import com.huanfuli.lapsight.shared.external.ExternalGnssFixType
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt

enum class LocationSource {
    Simulated,
    PhoneGps,
    ExternalGnss,
}

enum class GpsFixStatus {
    Idle,
    Acquiring,
    Simulated,
    Live,
    Degraded,
    Unavailable,
}

data class LocationSample(
    val elapsedMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double?,
    val speedMetersPerSecond: Double?,
    val headingDegrees: Double?,
    val altitudeMeters: Double?,
    val source: LocationSource,
    /**
     * Per-fix quality signals a GNSS receiver reports alongside the position.
     * All nullable so simulated feeds and legacy/persisted samples that never
     * carried them decode cleanly. Estimated 1-sigma speed accuracy in m/s. */
    val speedAccuracyMetersPerSecond: Double? = null,
    /** Estimated 1-sigma vertical accuracy in meters. */
    val verticalAccuracyMeters: Double? = null,
    /** Satellites used in the position solution at this fix, when known. */
    val satellitesInUse: Int? = null,
    /**
     * True when the fix was computed with a dual-frequency (e.g. L1+L5)
     * constellation, which materially improves positional accuracy. Null when
     * the capability was not observed (simulated/legacy).
     */
    val usesDualFrequency: Boolean? = null,
    /** Receiver solution mode for external GNSS fixes (for example RTK Float/Fixed). */
    val externalFixType: ExternalGnssFixType? = null,
)

data class GpsProbeState(
    val isRunning: Boolean,
    val fixStatus: GpsFixStatus,
    val latestSample: LocationSample?,
    val sampleCount: Int,
    val elapsedMillis: Long,
    val updateRateHz: Double,
) {
    val speedKmhLabel: String
        get() = latestSample?.speedMetersPerSecond
            ?.let { (it * 3.6).roundToInt().toString() }
            ?: "--"

    val accuracyLabel: String
        get() = latestSample?.horizontalAccuracyMeters
            ?.let { max(0.0, it).roundToInt().toString() }
            ?: "--"

    val updateRateLabel: String
        get() = if (updateRateHz > 0.0) updateRateHz.formatOneDecimal() else "--"

    val elapsedLabel: String
        get() = elapsedMillis.formatElapsed()

    fun stopped(): GpsProbeState = copy(isRunning = false)

    companion object {
        fun idle(): GpsProbeState = GpsProbeState(
            isRunning = false,
            fixStatus = GpsFixStatus.Idle,
            latestSample = null,
            sampleCount = 0,
            elapsedMillis = 0,
            updateRateHz = 0.0,
        )

        fun started(): GpsProbeState = idle().copy(
            isRunning = true,
            fixStatus = GpsFixStatus.Acquiring,
        )
    }
}

object GpsProbeSimulator {
    private const val BASE_LATITUDE = 39.8121
    private const val BASE_LONGITUDE = -86.1062

    fun next(previous: GpsProbeState, tick: Int): GpsProbeState {
        val elapsedMillis = tick.coerceAtLeast(0) * 1_000L
        val angle = tick * PI / 18.0
        val sample = LocationSample(
            elapsedMillis = elapsedMillis,
            latitude = BASE_LATITUDE + sin(angle) * 0.00045,
            longitude = BASE_LONGITUDE + kotlin.math.cos(angle) * 0.00045,
            horizontalAccuracyMeters = 5.0 + (tick % 5) * 1.7,
            speedMetersPerSecond = 8.0 + (tick % 8) * 0.9,
            headingDegrees = (tick * 12 % 360).toDouble(),
            altitudeMeters = 219.0,
            source = LocationSource.Simulated,
        )
        return previous.copy(
            isRunning = true,
            fixStatus = GpsFixStatus.Simulated,
            latestSample = sample,
            sampleCount = tick.coerceAtLeast(previous.sampleCount + 1),
            elapsedMillis = elapsedMillis,
            updateRateHz = 1.0,
        )
    }
}

private fun Double.formatOneDecimal(): String {
    val scaled = (this * 10.0).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}

private fun Long.formatElapsed(): String {
    val totalSeconds = this / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
