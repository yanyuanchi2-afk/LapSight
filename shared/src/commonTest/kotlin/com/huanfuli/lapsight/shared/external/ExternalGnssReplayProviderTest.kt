package com.huanfuli.lapsight.shared.external

import com.huanfuli.lapsight.shared.LocationSampleProvider
import com.huanfuli.lapsight.shared.LocationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalGnssReplayProviderTest {

    @Test
    fun doesNotEmitUntilStarted() {
        val provider = ExternalGnssReplayProvider(listOf(fix(elapsedMillis = 1_000L)))

        assertFalse(provider.isRunning)
        assertNull(provider.nextSample())
        assertEquals(emptyList(), provider.drainPending())
    }

    @Test
    fun emitsNormalizedExternalGnssSamples() {
        val provider: LocationSampleProvider = ExternalGnssReplayProvider(
            listOf(
                fix(
                    elapsedMillis = 1_250L,
                    latitude = 39.8123,
                    longitude = -86.1064,
                    speedMetersPerSecond = 12.4,
                    headingDegrees = 91.0,
                    altitudeMeters = 221.5,
                    horizontalAccuracyMeters = 0.9,
                    satellitesInUse = 18,
                ),
            ),
        )

        provider.start()
        val sample = assertNotNull(provider.nextSample())

        assertEquals(1_250L, sample.elapsedMillis)
        assertEquals(39.8123, sample.latitude, 0.000001)
        assertEquals(-86.1064, sample.longitude, 0.000001)
        assertEquals(12.4, sample.speedMetersPerSecond ?: 0.0, 0.000001)
        assertEquals(91.0, sample.headingDegrees ?: 0.0, 0.000001)
        assertEquals(221.5, sample.altitudeMeters ?: 0.0, 0.000001)
        assertEquals(0.9, sample.horizontalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(18, sample.satellitesInUse)
        assertEquals(LocationSource.ExternalGnss, sample.source)
    }

    @Test
    fun highRateFixesDrainInOrderWithoutDroppingBacklog() {
        val provider = ExternalGnssReplayProvider(
            listOf(
                fix(elapsedMillis = 1_000L, latitude = 39.0),
                fix(elapsedMillis = 1_040L, latitude = 39.0001),
                fix(elapsedMillis = 1_080L, latitude = 39.0002),
                fix(elapsedMillis = 1_120L, latitude = 39.0003),
            ),
        )

        provider.start()
        val drained = provider.drainPending()

        assertEquals(listOf(1_000L, 1_040L, 1_080L, 1_120L), drained.map { it.elapsedMillis })
        assertEquals(listOf(39.0, 39.0001, 39.0002, 39.0003), drained.map { it.latitude })
        assertTrue(drained.all { it.source == LocationSource.ExternalGnss })
        assertNull(provider.nextSample(), "drained backlog must not be emitted twice")
    }

    @Test
    fun invalidOrIncompleteFixesDoNotBlockValidSamples() {
        val provider = ExternalGnssReplayProvider(
            listOf(
                fix(elapsedMillis = 1_000L, valid = false),
                fix(elapsedMillis = null),
                fix(elapsedMillis = 1_080L, latitude = 39.2),
            ),
        )

        provider.start()
        val drained = provider.drainPending()

        assertEquals(1, drained.size)
        assertEquals(1_080L, drained.single().elapsedMillis)
        assertEquals(39.2, drained.single().latitude, 0.000001)
    }

    @Test
    fun resetClearsQueuedFixesAndRewindsReplay() {
        val provider = ExternalGnssReplayProvider(
            listOf(
                fix(elapsedMillis = 1_000L, latitude = 39.0),
                fix(elapsedMillis = 1_040L, latitude = 39.1),
                fix(elapsedMillis = 1_080L, latitude = 39.2),
            ),
        )

        provider.start()
        assertEquals(1_000L, assertNotNull(provider.nextSample()).elapsedMillis)
        provider.reset()
        assertFalse(provider.isRunning)
        assertNull(provider.nextSample(), "reset stops the feed and clears queued stale fixes")

        provider.start()
        val afterReset = provider.drainPending()
        assertEquals(listOf(1_000L, 1_040L, 1_080L), afterReset.map { it.elapsedMillis })
    }
}

private fun fix(
    elapsedMillis: Long?,
    latitude: Double = 39.8121,
    longitude: Double = -86.1062,
    speedMetersPerSecond: Double? = 10.0,
    headingDegrees: Double? = 90.0,
    altitudeMeters: Double? = 220.0,
    horizontalAccuracyMeters: Double? = 1.2,
    satellitesInUse: Int? = 16,
    valid: Boolean = true,
): ExternalGnssFixSnapshot =
    ExternalGnssFixSnapshot(
        elapsedMillis = elapsedMillis,
        latitude = if (valid) latitude else null,
        longitude = if (valid) longitude else null,
        speedMetersPerSecond = speedMetersPerSecond,
        headingDegrees = headingDegrees,
        altitudeMeters = altitudeMeters,
        quality = ExternalGnssFixQuality(
            isValid = valid,
            satellitesInUse = satellitesInUse,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
        ),
        source = ExternalGnssSourceMetadata(
            protocol = ExternalGnssProtocol.Nmea0183,
            transport = ExternalGnssTransport.Replay,
            hardwareValidationStatus = ExternalGnssHardwareValidationStatus.Unverified,
        ),
    )
