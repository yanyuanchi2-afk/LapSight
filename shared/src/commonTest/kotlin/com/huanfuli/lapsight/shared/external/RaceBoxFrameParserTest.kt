package com.huanfuli.lapsight.shared.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaceBoxFrameParserTest {

    @Test
    fun decodesValidSyntheticLiveFixFrame() {
        val result = RaceBoxFrameParser()
            .accept(
                RaceBoxFrameBuilder.liveFix(
                    iTowMillis = 1_250L,
                    latitudeDegrees = 39.8123456,
                    longitudeDegrees = -86.1064567,
                    altitudeMeters = 220.25,
                    horizontalAccuracyMeters = 0.6,
                    verticalAccuracyMeters = 1.1,
                    speedMetersPerSecond = 21.4,
                    headingDegrees = 91.5,
                    speedAccuracyMetersPerSecond = 0.08,
                    headingAccuracyDegrees = 0.2,
                    satellitesInUse = 20,
                ),
            )
            .single()

        val snapshotResult = assertIs<RaceBoxFrameParseResult.Snapshot>(result)
        val snapshot = snapshotResult.snapshot
        assertTrue(snapshot.quality.isValid)
        assertTrue(snapshot.hasUsableLocation)
        assertEquals(1_250L, snapshot.elapsedMillis)
        assertEquals(39.8123456, snapshot.latitude ?: 0.0, 0.0000001)
        assertEquals(-86.1064567, snapshot.longitude ?: 0.0, 0.0000001)
        assertEquals(220.25, snapshot.altitudeMeters ?: 0.0, 0.000001)
        assertEquals(0.6, snapshot.quality.horizontalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(1.1, snapshot.quality.verticalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(21.4, snapshot.speedMetersPerSecond ?: 0.0, 0.000001)
        assertEquals(91.5, snapshot.headingDegrees ?: 0.0, 0.000001)
        assertEquals(0.08, snapshot.quality.speedAccuracyMetersPerSecond ?: 0.0, 0.000001)
        assertEquals(0.2, snapshot.quality.headingAccuracyDegrees ?: 0.0, 0.000001)
        assertEquals(20, snapshot.quality.satellitesInUse)
        assertEquals(true, snapshot.quality.usesDualFrequency)
        assertEquals(ExternalGnssProtocol.RaceBox, snapshot.source.protocol)
        assertEquals(ExternalGnssTransport.Replay, snapshot.source.transport)
        assertEquals(ExternalGnssHardwareValidationStatus.Unverified, snapshot.source.hardwareValidationStatus)
        assertEquals(ExternalGnssHardwareValidationStatus.Unverified, snapshot.source.receiver?.hardwareValidationStatus)
        assertEquals(2026, snapshotResult.metadata.calendarTime.year)
        assertTrue(snapshotResult.metadata.calendarTime.isValid)
    }

    @Test
    fun noFixFrameIsTypedDataNotThrown() {
        val result = RaceBoxFrameParser()
            .accept(RaceBoxFrameBuilder.noFix(iTowMillis = 2_000L, satellitesInUse = 4))
            .single()

        val snapshot = assertIs<RaceBoxFrameParseResult.Snapshot>(result).snapshot
        assertFalse(snapshot.quality.isValid)
        assertFalse(snapshot.hasUsableLocation)
        assertEquals(ExternalGnssFixType.NoFix, snapshot.quality.fixType)
        assertEquals(4, snapshot.quality.satellitesInUse)
        assertNull(snapshot.latitude)
        assertNull(snapshot.longitude)
    }

    @Test
    fun fragmentedInputMatchesWholeFrameInput() {
        val first = RaceBoxFrameBuilder.liveFix(iTowMillis = 1_000L, latitudeDegrees = 39.0)
        val second = RaceBoxFrameBuilder.liveFix(iTowMillis = 1_040L, latitudeDegrees = 39.0001)
        val burst = first + second

        val whole = RaceBoxFrameParser().accept(burst).snapshots()
        val parser = RaceBoxFrameParser()
        val fragmented = mutableListOf<RaceBoxFrameParseResult>()
        listOf(
            burst.copyOfRange(0, 3),
            burst.copyOfRange(3, 17),
            burst.copyOfRange(17, first.size + 5),
            burst.copyOfRange(first.size + 5, burst.size),
        ).forEach { fragment ->
            fragmented += parser.accept(fragment)
        }

        assertEquals(whole, fragmented.snapshots())
    }

    @Test
    fun burstFramesDecodeInOrderWithRateMetadata() {
        val burst = (0 until 8)
            .map { index ->
                RaceBoxFrameBuilder.liveFix(
                    iTowMillis = 10_000L + index * 40L,
                    latitudeDegrees = 39.0 + index * 0.0001,
                    longitudeDegrees = -86.0 - index * 0.0001,
                )
            }
            .reduce(ByteArray::plus)

        val snapshots = RaceBoxFrameParser().accept(burst).snapshots()

        assertEquals(8, snapshots.size)
        assertEquals((0 until 8).map { 10_000L + it * 40L }, snapshots.map { it.elapsedMillis })
        assertEquals(null, snapshots.first().source.updateRateHz)
        snapshots.drop(1).forEach { snapshot ->
            assertEquals(25.0, snapshot.source.updateRateHz ?: 0.0, 0.000001)
            assertEquals(ExternalGnssProtocol.RaceBox, snapshot.source.protocol)
        }
    }

    @Test
    fun checksumFailureIsRejectedWithoutThrowing() {
        val frame = RaceBoxFrameBuilder.liveFix(iTowMillis = 3_000L).copyOf()
        frame[frame.lastIndex] = (frame.last().toInt() xor 0x01).toByte()

        val result = RaceBoxFrameParser().accept(frame).single()

        val rejected = assertIs<RaceBoxFrameParseResult.Rejected>(result)
        assertEquals(RaceBoxFrameRejectReason.ChecksumMismatch, rejected.reason)
    }

    @Test
    fun unsupportedMessageIdsAreIgnored() {
        val result = RaceBoxFrameParser()
            .accept(RaceBoxFrameBuilder.unsupportedMessage())
            .single()

        val ignored = assertIs<RaceBoxFrameParseResult.Ignored>(result)
        assertEquals(RaceBoxFrameIgnoredReason.UnsupportedMessage, ignored.reason)
    }

    @Test
    fun telemetryFrameDecodesProtocolFieldsWithoutTimingDependence() {
        val result = RaceBoxFrameParser()
            .accept(
                RaceBoxFrameBuilder.basicTelemetry(
                    iTowMillis = 4_000L,
                    accelerationMetersPerSecondSquared = ExternalGnssVector3(0.1, -0.2, 9.81),
                    gyroDegreesPerSecond = ExternalGnssVector3(1.0, -2.0, 3.0),
                    vehicleSpeedMetersPerSecond = 19.5,
                ),
            )
            .single()

        val telemetry = assertIs<RaceBoxFrameParseResult.Telemetry>(result)
        assertEquals(4_000L, telemetry.elapsedMillis)
        assertEquals(0.1, telemetry.telemetry.accelerationMetersPerSecondSquared?.x ?: 0.0, 0.001)
        assertEquals(-0.2, telemetry.telemetry.accelerationMetersPerSecondSquared?.y ?: 0.0, 0.001)
        assertEquals(9.81, telemetry.telemetry.accelerationMetersPerSecondSquared?.z ?: 0.0, 0.001)
        assertEquals(0.017, telemetry.telemetry.gyroRadiansPerSecond?.x ?: 0.0, 0.001)
        assertEquals(-0.035, telemetry.telemetry.gyroRadiansPerSecond?.y ?: 0.0, 0.001)
        assertEquals(0.052, telemetry.telemetry.gyroRadiansPerSecond?.z ?: 0.0, 0.001)
        assertEquals(19.5, telemetry.telemetry.vehicleSpeedMetersPerSecond ?: 0.0, 0.000001)
        assertEquals(ExternalGnssProtocol.RaceBox, telemetry.source.protocol)

        assertEquals(emptyList(), listOf(result).snapshots(), "telemetry-only frames must not produce timing fixes")
    }

    @Test
    fun unsupportedTelemetryIsExplicit() {
        val result = RaceBoxFrameParser()
            .accept(RaceBoxFrameBuilder.unsupportedTelemetry(iTowMillis = 4_500L))
            .single()

        val unsupported = assertIs<RaceBoxFrameParseResult.UnsupportedTelemetry>(result)
        assertEquals(RaceBoxFrameUnsupportedTelemetryReason.UnsupportedMessage, unsupported.reason)
    }

}

private fun List<RaceBoxFrameParseResult>.snapshots(): List<ExternalGnssFixSnapshot> =
    filterIsInstance<RaceBoxFrameParseResult.Snapshot>().map { it.snapshot }
