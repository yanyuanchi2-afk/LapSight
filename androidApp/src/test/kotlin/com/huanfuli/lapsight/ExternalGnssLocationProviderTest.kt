package com.huanfuli.lapsight

import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [ExternalGnssLocationProvider] can be exercised end to end with a
 * fake byte-stream client — no BLE hardware required (D-02). This is a plain
 * JVM unit test: the provider only depends on the injected
 * [ExternalGnssByteStreamClient] seam and the shared parsers, never on
 * Android Bluetooth APIs directly.
 */
class ExternalGnssLocationProviderTest {

    @Test
    fun fakeNmeaByteStreamProducesExternalGnssSample() {
        val client = FakeExternalGnssByteStreamClient()
        val provider = ExternalGnssLocationProvider(client, ExternalGnssProtocol.Nmea0183)

        provider.start()
        assertTrue(provider.isRunning)
        client.emitPhase(ExternalGnssConnectionPhase.Connected)
        client.emitBytes(RMC_SENTENCE.encodeToByteArray())

        val sample = provider.nextSample()
        assertEquals(LocationSource.ExternalGnss, sample?.source)
        assertEquals(48.1173, sample?.latitude ?: 0.0, 0.001)
        assertNull(provider.nextSample())
        assertEquals(ExternalGnssConnectionPhase.Connected, provider.connectionState.value.phase)
    }

    @Test
    fun nmeaQualityUsesTheSameSampleFieldsAsPhoneGps() {
        val client = FakeExternalGnssByteStreamClient()
        val provider = ExternalGnssLocationProvider(client, ExternalGnssProtocol.Nmea0183)

        provider.start()
        client.emitBytes((
            sentence("GPGGA,123519,4807.038,N,01131.000,E,4,18,0.7,545.4,M,46.9,M,,") +
                sentence("GNGST,123519,0.42,0.36,0.21,74.0,0.25,0.31,0.52")
            ).encodeToByteArray())

        val sample = provider.drainPending().last()
        assertEquals(LocationSource.ExternalGnss, sample.source)
        assertEquals(18, sample.satellitesInUse)
        assertEquals(0.36, sample.horizontalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(0.52, sample.verticalAccuracyMeters ?: 0.0, 0.000001)
    }

    @Test
    fun lc29hEpePopulatesExistingAccuracyWithoutHudSpecificUiModel() {
        val client = FakeExternalGnssByteStreamClient()
        val provider = ExternalGnssLocationProvider(client, ExternalGnssProtocol.Nmea0183)

        provider.start()
        client.emitBytes((
            sentence("GNGGA,123519,4807.038,N,01131.000,E,4,38,0.7,545.4,M,46.9,M,,") +
                sentence("PQTMEPE,2,0.031,0.028,0.052,0.042,0.067")
            ).encodeToByteArray())

        val sample = provider.drainPending().last()
        assertEquals(38, sample.satellitesInUse)
        assertEquals(0.042, sample.horizontalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(0.052, sample.verticalAccuracyMeters ?: 0.0, 0.000001)
    }

    @Test
    fun sameEpochNmeaSentencesProduceOneReceiverFix() {
        val client = FakeExternalGnssByteStreamClient()
        val provider = ExternalGnssLocationProvider(client, ExternalGnssProtocol.Nmea0183)

        provider.start()
        client.emitBytes((
            sentence("GNGGA,123519,4807.038,N,01131.000,E,4,38,0.7,545.4,M,46.9,M,,") +
                sentence("GNGST,123519,0.42,0.36,0.21,74.0,0.25,0.31,0.52") +
                sentence("PQTMEPE,2,0.031,0.028,0.052,0.042,0.067")
            ).encodeToByteArray())

        val samples = provider.drainPending()
        assertEquals(1, samples.size)
        assertEquals(38, samples.single().satellitesInUse)
        assertEquals(0.042, samples.single().horizontalAccuracyMeters ?: 0.0, 0.000001)
    }

    @Test
    fun drainPendingReturnsWholeBacklogInArrivalOrder() {
        val client = FakeExternalGnssByteStreamClient()
        val provider = ExternalGnssLocationProvider(client, ExternalGnssProtocol.Nmea0183)

        provider.start()
        // Two fixes one second apart, delivered in a single high-rate burst.
        client.emitBytes((RMC_SENTENCE + RMC_SENTENCE_LATER).encodeToByteArray())

        val drained = provider.drainPending()
        assertEquals(2, drained.size)
        assertTrue(drained[1].elapsedMillis > drained[0].elapsedMillis)
        assertTrue(provider.drainPending().isEmpty())
    }

    @Test
    fun stopPreventsFurtherSamplesUntilRestarted() {
        val client = FakeExternalGnssByteStreamClient()
        val provider = ExternalGnssLocationProvider(client, ExternalGnssProtocol.Nmea0183)

        provider.start()
        provider.stop()
        assertFalse(provider.isRunning)
        assertTrue(client.stopped)

        // Bytes delivered after stop (a straggling notification) must not enqueue.
        client.emitBytes(RMC_SENTENCE.encodeToByteArray())
        assertNull(provider.nextSample())
    }

    @Test
    fun resetClearsParserQueueAndTelemetry() {
        val client = FakeExternalGnssByteStreamClient()
        val provider = ExternalGnssLocationProvider(client, ExternalGnssProtocol.Nmea0183)

        provider.start()
        client.emitBytes(RMC_SENTENCE.encodeToByteArray())
        assertTrue(provider.drainPending().isNotEmpty())

        provider.reset()
        assertFalse(provider.isRunning)
        assertEquals(ExternalGnssConnectionPhase.Disconnected, provider.connectionState.value.phase)
        assertNull(provider.latestTelemetry.value)
        assertTrue(provider.drainPending().isEmpty())
    }

    private companion object {
        const val RMC_SENTENCE = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A\r\n"
        const val RMC_SENTENCE_LATER = "\$GPRMC,123520,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*60\r\n"
    }
}

private fun sentence(payload: String): String {
    val checksum = payload.fold(0) { current, char -> current xor char.code }
    return "\$$payload*${checksum.toString(16).uppercase().padStart(2, '0')}\r\n"
}

/**
 * Fake [ExternalGnssByteStreamClient] test double: hands the provider
 * whatever bytes/phases the test injects, with no BLE hardware involved.
 */
private class FakeExternalGnssByteStreamClient : ExternalGnssByteStreamClient {
    private var onBytes: ((ByteArray) -> Unit)? = null
    private var onPhase: ((ExternalGnssConnectionPhase) -> Unit)? = null
    var stopped: Boolean = false
        private set

    override fun start(
        onBytes: (ByteArray) -> Unit,
        onConnectionPhase: (ExternalGnssConnectionPhase) -> Unit,
    ) {
        stopped = false
        this.onBytes = onBytes
        this.onPhase = onConnectionPhase
    }

    override fun stop() {
        stopped = true
        onBytes = null
        onPhase = null
    }

    fun emitBytes(bytes: ByteArray) {
        onBytes?.invoke(bytes)
    }

    fun emitPhase(phase: ExternalGnssConnectionPhase) {
        onPhase?.invoke(phase)
    }
}
