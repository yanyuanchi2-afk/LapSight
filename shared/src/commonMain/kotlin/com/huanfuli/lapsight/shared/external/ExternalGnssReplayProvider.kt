package com.huanfuli.lapsight.shared.external

import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.LocationSampleProvider
import com.huanfuli.lapsight.shared.LocationSource

class ExternalGnssReplayProvider(
    private val decodedFixes: List<ExternalGnssFixSnapshot>,
) : LocationSampleProvider {

    private val queuedSamples = mutableListOf<LocationSample>()
    private var nextFixIndex = 0
    private var running = false

    val fixCount: Int get() = decodedFixes.size

    override val isRunning: Boolean get() = running

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun reset() {
        running = false
        nextFixIndex = 0
        queuedSamples.clear()
    }

    override fun nextSample(): LocationSample? {
        if (!running) return null
        enqueueRemainingDecodedFixes()
        return if (queuedSamples.isEmpty()) {
            null
        } else {
            queuedSamples.removeAt(0)
        }
    }

    override fun drainPending(): List<LocationSample> {
        if (!running) return emptyList()
        enqueueRemainingDecodedFixes()
        val drained = queuedSamples.toList()
        queuedSamples.clear()
        return drained
    }

    private fun enqueueRemainingDecodedFixes() {
        while (nextFixIndex < decodedFixes.size) {
            decodedFixes[nextFixIndex].toLocationSample()?.let { queuedSamples += it }
            nextFixIndex++
        }
    }
}

/**
 * Maps a decoded external fix into the shared [LocationSample] contract, or
 * `null` when the snapshot has no usable location yet (no-fix/acquiring).
 *
 * Public so any [com.huanfuli.lapsight.shared.LocationSampleProvider]
 * implementation that decodes external protocol bytes — replay-backed here,
 * platform BLE clients elsewhere — shares one conversion instead of
 * duplicating field mapping per platform.
 */
fun ExternalGnssFixSnapshot.toLocationSample(): LocationSample? {
    val elapsedMillis = elapsedMillis ?: return null
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    if (!quality.isValid) return null

    return LocationSample(
        elapsedMillis = elapsedMillis,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = quality.horizontalAccuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        headingDegrees = headingDegrees,
        altitudeMeters = altitudeMeters,
        source = LocationSource.ExternalGnss,
        speedAccuracyMetersPerSecond = quality.speedAccuracyMetersPerSecond,
        verticalAccuracyMeters = quality.verticalAccuracyMeters,
        satellitesInUse = quality.satellitesInUse,
        usesDualFrequency = quality.usesDualFrequency,
        externalFixType = quality.fixType,
    )
}
