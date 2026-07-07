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
