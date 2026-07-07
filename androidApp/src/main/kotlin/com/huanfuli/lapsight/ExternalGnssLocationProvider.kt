package com.huanfuli.lapsight

import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.LocationSampleProvider
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
import com.huanfuli.lapsight.shared.external.ExternalGnssProtocol
import com.huanfuli.lapsight.shared.external.ExternalGnssTelemetryMetadata
import com.huanfuli.lapsight.shared.external.ExternalGnssTransport
import com.huanfuli.lapsight.shared.external.Nmea0183ParseResult
import com.huanfuli.lapsight.shared.external.Nmea0183Parser
import com.huanfuli.lapsight.shared.external.RaceBoxFrameParseResult
import com.huanfuli.lapsight.shared.external.RaceBoxFrameParser
import com.huanfuli.lapsight.shared.external.toLocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [LocationSampleProvider] that composes an injectable
 * [ExternalGnssByteStreamClient] (real BLE hardware, or a fake byte-stream
 * test double) with the shared NMEA/RaceBox parsers.
 *
 * This is the Android side of the external-GNSS provider seam (D-04): it
 * starts/stops independently of phone GPS, emits [LocationSample]s through
 * the exact same interface the Fused/Direct-GNSS phone providers use, and
 * carries optional telemetry as a side channel that never feeds
 * [nextSample]/[drainPending] (D-09) — lap timing only ever sees normalized
 * location samples.
 *
 * Fully testable without hardware: any [ExternalGnssByteStreamClient],
 * including a fake one that hands the provider canned byte chunks, drives the
 * exact same parsing/queueing/connection-state path a real receiver would.
 */
class ExternalGnssLocationProvider(
    private val client: ExternalGnssByteStreamClient,
    private val protocol: ExternalGnssProtocol,
) : LocationSampleProvider {

    private var nmeaParser: Nmea0183Parser? = null
    private var raceBoxParser: RaceBoxFrameParser? = null

    private val queue = ArrayDeque<LocationSample>()
    private var running = false

    private val _connectionState = MutableStateFlow(
        ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected, protocol = protocol),
    )

    /** Connection lifecycle for Settings display (D-05); never drives timing. */
    val connectionState: StateFlow<ExternalGnssConnectionState> = _connectionState.asStateFlow()

    private val _latestTelemetry = MutableStateFlow<ExternalGnssTelemetryMetadata?>(null)

    /**
     * Most recent optional protocol telemetry (e.g. RaceBox basic IMU/vehicle
     * fields), for review/export plumbing only (D-09). Never consumed by
     * [nextSample]/[drainPending].
     */
    val latestTelemetry: StateFlow<ExternalGnssTelemetryMetadata?> = _latestTelemetry.asStateFlow()

    override val isRunning: Boolean get() = running

    override fun start() {
        if (running) return
        running = true
        allocateParser()
        synchronized(queue) { queue.clear() }
        client.start(onBytes = ::handleBytes, onConnectionPhase = ::handlePhase)
    }

    override fun stop() {
        if (!running) return
        running = false
        client.stop()
        _connectionState.value = _connectionState.value.copy(phase = ExternalGnssConnectionPhase.Disconnected)
    }

    override fun reset() {
        stop()
        allocateParser()
        _latestTelemetry.value = null
        synchronized(queue) { queue.clear() }
        _connectionState.value = ExternalGnssConnectionState(
            phase = ExternalGnssConnectionPhase.Disconnected,
            protocol = protocol,
        )
    }

    override fun nextSample(): LocationSample? =
        synchronized(queue) { queue.removeFirstOrNull() }

    override fun drainPending(): List<LocationSample> =
        synchronized(queue) {
            if (queue.isEmpty()) {
                emptyList()
            } else {
                val drained = queue.toList()
                queue.clear()
                drained
            }
        }

    private fun allocateParser() {
        when (protocol) {
            ExternalGnssProtocol.Nmea0183 -> {
                nmeaParser = Nmea0183Parser()
                raceBoxParser = null
            }
            ExternalGnssProtocol.RaceBox -> {
                raceBoxParser = RaceBoxFrameParser(transport = ExternalGnssTransport.Ble)
                nmeaParser = null
            }
            ExternalGnssProtocol.Unknown -> {
                nmeaParser = null
                raceBoxParser = null
            }
        }
    }

    private fun handlePhase(phase: ExternalGnssConnectionPhase) {
        _connectionState.value = _connectionState.value.copy(phase = phase)
    }

    private fun handleBytes(bytes: ByteArray) {
        if (!running) return
        when (protocol) {
            ExternalGnssProtocol.Nmea0183 -> nmeaParser?.accept(bytes)?.forEach(::handleNmeaResult)
            ExternalGnssProtocol.RaceBox -> raceBoxParser?.accept(bytes)?.forEach(::handleRaceBoxResult)
            ExternalGnssProtocol.Unknown -> Unit
        }
    }

    private fun handleNmeaResult(result: Nmea0183ParseResult) {
        val snapshot = (result as? Nmea0183ParseResult.Snapshot)?.snapshot ?: return
        enqueue(snapshot.toLocationSample())
    }

    private fun handleRaceBoxResult(result: RaceBoxFrameParseResult) {
        when (result) {
            is RaceBoxFrameParseResult.Snapshot -> enqueue(result.snapshot.toLocationSample())
            is RaceBoxFrameParseResult.Telemetry -> _latestTelemetry.value = result.telemetry
            is RaceBoxFrameParseResult.Rejected,
            is RaceBoxFrameParseResult.Ignored,
            is RaceBoxFrameParseResult.UnsupportedTelemetry,
            -> Unit
        }
    }

    private fun enqueue(sample: LocationSample?) {
        if (sample == null) return
        synchronized(queue) {
            // Bounded so a 25 Hz burst (RaceBox) or a reconnect backlog can never
            // grow the queue unboundedly between poll ticks (D-06 high-rate coverage).
            while (queue.size >= MAX_QUEUE_SIZE) {
                queue.removeFirst()
            }
            queue.addLast(sample)
        }
    }

    private companion object {
        const val MAX_QUEUE_SIZE = 1_000
    }
}
