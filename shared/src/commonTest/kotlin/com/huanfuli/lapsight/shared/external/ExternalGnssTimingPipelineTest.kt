package com.huanfuli.lapsight.shared.external

import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.ghost.DeltaUnavailableReason
import com.huanfuli.lapsight.shared.ghost.LiveDeltaSnapshot
import com.huanfuli.lapsight.shared.lap.ReplayFixtures
import com.huanfuli.lapsight.shared.session.AppMetadata
import com.huanfuli.lapsight.shared.session.SaveDraftResult
import com.huanfuli.lapsight.shared.session.SessionController
import com.huanfuli.lapsight.shared.session.SessionControllerTest.TestTrackFactory
import com.huanfuli.lapsight.shared.session.SourceMetadata
import com.huanfuli.lapsight.shared.session.StartTimingResult
import com.huanfuli.lapsight.shared.session.TimingSessionPayloadV1
import com.huanfuli.lapsight.shared.storage.InMemorySessionStore
import com.huanfuli.lapsight.shared.storage.LoadResult
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 6 Plan 04 closeout gate: proves decoded NMEA and synthetic RaceBox
 * external samples flow through the SAME `SessionController` timing pipeline
 * used by phone/simulated GPS, with no lap-engine hardware-specific branch
 * (D-04), honest ExternalGnss provenance (D-05), full replay as the
 * acceptance backbone (D-06), and telemetry treated only as optional
 * provenance/capture, never as timing input (D-09).
 *
 * Fixture geometry is [ReplayFixtures.DEMO_COURSE] / [ReplayFixtures.multiLapLoop]
 * — the same rectangular-loop fixture already used by the lap engine, session,
 * and ghost integration suites — re-encoded byte-for-byte as NMEA 0183 RMC
 * sentences and synthetic RaceBox live-fix/telemetry frames, then decoded back
 * through [Nmea0183Parser] / [RaceBoxFrameParser] exactly as a real receiver
 * byte stream would be. This is still protocol-complete/hardware-unvalidated:
 * no physical receiver or BLE transport is involved (D-02).
 */
class ExternalGnssTimingPipelineTest {

    private val app = AppMetadata(appVersion = "test", platform = "test")
    private val fixedClock: () -> Long = { 1_700_000_000_000L }

    // --- Pipeline wiring shared by every scenario ------------------------------

    private fun controllerFor(store: InMemorySessionStore, source: LocationSource): SessionController =
        SessionController(
            store = store,
            appMetadata = app,
            engineConfig = ReplayFixtures.DEMO_CONFIG,
            now = fixedClock,
            sourceForTrack = { _ ->
                SourceMetadata(
                    source = source,
                    isSimulated = source == LocationSource.Simulated,
                    label = if (source == LocationSource.Simulated) "Demo" else null,
                )
            },
        )

    /** A fresh store + started controller over the shared demo-course Track for [source]. */
    private fun startedController(source: LocationSource): Pair<SessionController, InMemorySessionStore> {
        val store = InMemorySessionStore()
        val track = TestTrackFactory.savedTrackWithStartFinish(source)
        store.saveTrackBundle(track, TestTrackFactory.markingFor(track, source), app)
        val controller = controllerFor(store, source)
        assertIs<StartTimingResult.Started>(controller.startTiming(track.id))
        return controller to store
    }

    /** Decode a full NMEA RMC byte stream for [samples] through one shared parser instance. */
    private fun decodeNmeaSamples(samples: List<LocationSample>): List<LocationSample> {
        val parser = Nmea0183Parser()
        return samples.flatMap { sample ->
            parser.accept(sample.toNmeaRmcSentence())
                .filterIsInstance<Nmea0183ParseResult.Snapshot>()
                .mapNotNull { it.snapshot.toLocationSample() }
        }
    }

    /** Decode a full RaceBox live-fix byte stream for [samples] through one shared parser instance. */
    private fun decodeRaceBoxSamples(samples: List<LocationSample>): List<LocationSample> {
        val parser = RaceBoxFrameParser()
        return samples.flatMap { sample ->
            parser.accept(sample.toRaceBoxLiveFixFrame())
                .filterIsInstance<RaceBoxFrameParseResult.Snapshot>()
                .mapNotNull { it.snapshot.toLocationSample() }
        }
    }

    // --- D-04/D-06: full pipeline replay completes the same fixture laps -------

    @Test
    fun nmeaDecodedFeedCompletesSameLapsAsPhoneSimulatedFeed() {
        val lapDurations = listOf(40_000L, 32_000L, 36_000L)
        val rawSamples = ReplayFixtures.multiLapLoop(lapDurations)

        val (baselineController, _) = startedController(LocationSource.Simulated)
        val baselineRecorder = assertNotNull(baselineController.recorderForTest())
        rawSamples.forEach { baselineRecorder.onSample(it) }
        val baselineLaps = baselineRecorder.timingState.completedLaps

        val nmeaSamples = decodeNmeaSamples(rawSamples)
        assertEquals(
            rawSamples.size,
            nmeaSamples.size,
            "every fixture sample must round-trip through an NMEA RMC sentence into a usable fix",
        )
        assertTrue(nmeaSamples.all { it.source == LocationSource.ExternalGnss })

        val (externalController, _) = startedController(LocationSource.ExternalGnss)
        val externalRecorder = assertNotNull(externalController.recorderForTest())
        nmeaSamples.forEach { externalRecorder.onSample(it) }
        val externalLaps = externalRecorder.timingState.completedLaps

        assertEquals(
            lapDurations.size,
            baselineLaps.size,
            "sanity: the raw fixture itself must complete every requested lap",
        )
        assertEquals(
            baselineLaps.size,
            externalLaps.size,
            "an NMEA-decoded external feed must complete the same lap count as the phone/simulated feed (D-04)",
        )
        baselineLaps.zip(externalLaps).forEach { (expected, actual) ->
            assertEquals(expected.lapNumber, actual.lapNumber)
            assertTrue(
                abs(expected.durationMillis - actual.durationMillis) <= NMEA_LAP_TIMING_TOLERANCE_MILLIS,
                "lap ${expected.lapNumber} duration drifted after NMEA round-trip: " +
                    "expected ~${expected.durationMillis}ms, got ${actual.durationMillis}ms",
            )
        }
    }

    @Test
    fun raceBoxDecodedFeedCompletesSameLapsAsPhoneSimulatedFeed() {
        val lapDurations = listOf(40_000L, 32_000L, 36_000L)
        val rawSamples = ReplayFixtures.multiLapLoop(lapDurations)

        val (baselineController, _) = startedController(LocationSource.Simulated)
        val baselineRecorder = assertNotNull(baselineController.recorderForTest())
        rawSamples.forEach { baselineRecorder.onSample(it) }
        val baselineLaps = baselineRecorder.timingState.completedLaps

        val raceBoxSamples = decodeRaceBoxSamples(rawSamples)
        assertEquals(
            rawSamples.size,
            raceBoxSamples.size,
            "every fixture sample must round-trip through a RaceBox live-fix frame into a usable fix",
        )
        assertTrue(raceBoxSamples.all { it.source == LocationSource.ExternalGnss })

        val (externalController, _) = startedController(LocationSource.ExternalGnss)
        val externalRecorder = assertNotNull(externalController.recorderForTest())
        raceBoxSamples.forEach { externalRecorder.onSample(it) }
        val externalLaps = externalRecorder.timingState.completedLaps

        assertEquals(baselineLaps.size, externalLaps.size, "sanity: raw fixture must complete every requested lap")
        assertEquals(
            baselineLaps.size,
            externalLaps.size,
            "a RaceBox-decoded external feed must complete the same lap count as the phone/simulated feed (D-04)",
        )
        baselineLaps.zip(externalLaps).forEach { (expected, actual) ->
            assertEquals(expected.lapNumber, actual.lapNumber)
            assertTrue(
                abs(expected.durationMillis - actual.durationMillis) <= RACEBOX_LAP_TIMING_TOLERANCE_MILLIS,
                "lap ${expected.lapNumber} duration drifted after RaceBox round-trip: " +
                    "expected ~${expected.durationMillis}ms, got ${actual.durationMillis}ms",
            )
        }
    }

    // --- D-09: absent telemetry (NMEA has none) is handled cleanly -------------

    @Test
    fun nmeaSnapshotsNeverCarryTelemetryAndPipelineStillCompletes() {
        val rawSamples = ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L))
        val parser = Nmea0183Parser()
        val snapshots = rawSamples.flatMap { sample ->
            parser.accept(sample.toNmeaRmcSentence()).filterIsInstance<Nmea0183ParseResult.Snapshot>()
        }.map { it.snapshot }

        assertTrue(snapshots.isNotEmpty())
        assertTrue(
            snapshots.all { it.telemetry == null },
            "NMEA 0183 has no telemetry channel; absent telemetry must decode as null, not a crash or placeholder",
        )

        val (controller, _) = startedController(LocationSource.ExternalGnss)
        val recorder = assertNotNull(controller.recorderForTest())
        snapshots.mapNotNull { it.toLocationSample() }.forEach { recorder.onSample(it) }
        assertEquals(2, recorder.timingState.completedLaps.size, "absent telemetry must not block normal timing")
    }

    // --- D-09: RaceBox basic telemetry is captured but never becomes timing input --

    @Test
    fun raceBoxBasicTelemetryIsPreservedAsCaptureButNeverEntersTimingSamples() {
        val lapDurations = listOf(40_000L, 32_000L)
        val rawSamples = ReplayFixtures.multiLapLoop(lapDurations)
        val parser = RaceBoxFrameParser()
        val telemetrySnapshots = mutableListOf<ExternalGnssTelemetryMetadata>()
        val fixSamples = mutableListOf<LocationSample>()

        rawSamples.forEachIndexed { index, sample ->
            parser.accept(sample.toRaceBoxLiveFixFrame()).forEach { result ->
                when (result) {
                    is RaceBoxFrameParseResult.Snapshot -> result.snapshot.toLocationSample()?.let(fixSamples::add)
                    is RaceBoxFrameParseResult.Telemetry -> telemetrySnapshots += result.telemetry
                    else -> Unit
                }
            }
            // A receiver that exposes basic telemetry interleaves it with GNSS fixes;
            // model that on every other fix rather than assuming every receiver sends it.
            if (index % 2 == 0) {
                val telemetryFrame = RaceBoxFrameBuilder.basicTelemetry(
                    iTowMillis = sample.elapsedMillis,
                    accelerationMetersPerSecondSquared = ExternalGnssVector3(x = 0.4, y = -0.1, z = 9.8),
                    gyroDegreesPerSecond = ExternalGnssVector3(x = 1.0, y = 0.5, z = -0.3),
                    vehicleSpeedMetersPerSecond = sample.speedMetersPerSecond ?: 0.0,
                )
                parser.accept(telemetryFrame).forEach { result ->
                    when (result) {
                        is RaceBoxFrameParseResult.Snapshot -> result.snapshot.toLocationSample()?.let(fixSamples::add)
                        is RaceBoxFrameParseResult.Telemetry -> telemetrySnapshots += result.telemetry
                        else -> Unit
                    }
                }
            }
        }

        assertTrue(
            telemetrySnapshots.isNotEmpty(),
            "basic telemetry frames must be captured/preserved as provenance data when a receiver exposes them (D-09)",
        )
        assertEquals(
            rawSamples.size,
            fixSamples.size,
            "telemetry frames must never be converted into timing LocationSamples (D-09)",
        )

        val (controller, _) = startedController(LocationSource.ExternalGnss)
        val recorder = assertNotNull(controller.recorderForTest())
        fixSamples.forEach { recorder.onSample(it) }

        assertEquals(
            lapDurations.size,
            recorder.timingState.completedLaps.size,
            "telemetry-interleaved fixes must still complete the same laps as the fix-only feed",
        )
    }

    // --- Ghost delta behavior where applicable ----------------------------------

    @Test
    fun externalFeedProducesTheSameLiveDeltaAvailabilityTransitionsAsPhoneSimulatedFeed() {
        val rawSamples = ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L))
        val nmeaSamples = decodeNmeaSamples(rawSamples)

        val (controller, _) = startedController(LocationSource.ExternalGnss)
        val recorder = assertNotNull(controller.recorderForTest())

        var sawNoReferenceBeforeFirstLap = false
        var sawAvailableAfterFirstLap = false
        nmeaSamples.forEach { sample ->
            recorder.onSample(sample)
            val delta = controller.liveDelta()
            if (recorder.lapCount == 0) {
                if (delta is LiveDeltaSnapshot.Unavailable && delta.reason == DeltaUnavailableReason.NoReference) {
                    sawNoReferenceBeforeFirstLap = true
                }
            } else if (delta is LiveDeltaSnapshot.Available) {
                sawAvailableAfterFirstLap = true
            }
        }

        assertTrue(
            sawNoReferenceBeforeFirstLap,
            "external feed must report NoReference before any lap completes, exactly like phone/simulated (D-04)",
        )
        assertTrue(
            sawAvailableAfterFirstLap,
            "external feed must produce an available ghost delta once a reference lap exists, exactly like phone/simulated (D-04)",
        )
    }

    // --- D-05: session/review/export source metadata distinguishes provenance --

    @Test
    fun savedSessionSourceDistinguishesExternalGnssFromPhoneAndSimulated() {
        val rawSamples = ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L))
        val nmeaSamples = decodeNmeaSamples(rawSamples)

        val externalSource = saveAndLoadSessionSource(LocationSource.ExternalGnss, nmeaSamples)
        val phoneSource = saveAndLoadSessionSource(LocationSource.PhoneGps, rawSamples)
        val simulatedSource = saveAndLoadSessionSource(LocationSource.Simulated, rawSamples)

        assertEquals(LocationSource.ExternalGnss, externalSource.source)
        assertFalse(externalSource.isSimulated)
        assertEquals(LocationSource.PhoneGps, phoneSource.source)
        assertEquals(LocationSource.Simulated, simulatedSource.source)
        assertTrue(simulatedSource.isSimulated)
        assertEquals(
            3,
            setOf(externalSource.source, phoneSource.source, simulatedSource.source).size,
            "ExternalGnss, PhoneGps, and Simulated sessions must each record a distinct source (D-05)",
        )
    }

    private fun saveAndLoadSessionSource(source: LocationSource, samples: List<LocationSample>): SourceMetadata {
        val (controller, store) = startedController(source)
        val recorder = assertNotNull(controller.recorderForTest())
        samples.forEach { recorder.onSample(it) }
        controller.stop()
        val saved = assertIs<SaveDraftResult.Saved>(controller.saveStoppedDraft())
        val loaded = assertIs<LoadResult.Loaded<TimingSessionPayloadV1>>(store.loadTimingSession(saved.sessionId))
        return loaded.value.session.source
    }

    private companion object {
        // Sub-centimeter position rounding from NMEA/RaceBox re-encoding can shift an
        // interpolated line-crossing timestamp by at most a couple of milliseconds;
        // this is a replay round-trip tolerance, not algorithmic drift (D-27 applies
        // to exact-replay determinism of the SAME encoded input, not to encoding loss
        // between two different wire formats of the same physical fixture).
        const val NMEA_LAP_TIMING_TOLERANCE_MILLIS = 25L
        const val RACEBOX_LAP_TIMING_TOLERANCE_MILLIS = 10L
    }
}

// --- Fixture -> wire-format encoders (test-only, mirrors a real receiver stream) --

private fun LocationSample.toNmeaRmcSentence(): String {
    val time = nmeaTimeField(elapsedMillis)
    val (latText, latHemisphere) = nmeaLatitudeField(latitude)
    val (lonText, lonHemisphere) = nmeaLongitudeField(longitude)
    val speedKnots = (speedMetersPerSecond ?: 0.0) / KNOTS_TO_METERS_PER_SECOND
    val heading = headingDegrees ?: 0.0
    val payload = "GPRMC,$time,A,$latText,$latHemisphere,$lonText,$lonHemisphere," +
        "${decimalText(speedKnots, 3)},${decimalText(heading, 1)},070726,,,A"
    return nmeaSentenceWithChecksum(payload)
}

private fun LocationSample.toRaceBoxLiveFixFrame(): ByteArray = RaceBoxFrameBuilder.liveFix(
    iTowMillis = elapsedMillis,
    latitudeDegrees = latitude,
    longitudeDegrees = longitude,
    altitudeMeters = altitudeMeters ?: 0.0,
    horizontalAccuracyMeters = horizontalAccuracyMeters ?: 1.0,
    verticalAccuracyMeters = 1.0,
    speedMetersPerSecond = speedMetersPerSecond ?: 0.0,
    headingDegrees = headingDegrees ?: 0.0,
)

private const val KNOTS_TO_METERS_PER_SECOND = 0.514444

private fun nmeaSentenceWithChecksum(payload: String): String {
    val checksum = payload.fold(0) { acc, char -> acc xor char.code }
    return "\$$payload*${checksum.toString(16).uppercase().padStart(2, '0')}\r\n"
}

private fun nmeaTimeField(elapsedMillis: Long): String {
    val totalMillis = elapsedMillis % 86_400_000L
    val hours = totalMillis / 3_600_000L
    val minutes = (totalMillis / 60_000L) % 60L
    val seconds = (totalMillis / 1_000L) % 60L
    val fractionMillis = totalMillis % 1_000L
    return "${hours.toString().padStart(2, '0')}${minutes.toString().padStart(2, '0')}" +
        "${seconds.toString().padStart(2, '0')}.${fractionMillis.toString().padStart(3, '0')}"
}

private fun nmeaLatitudeField(latitude: Double): Pair<String, String> {
    val hemisphere = if (latitude >= 0.0) "N" else "S"
    val (degrees, minutesText) = nmeaDegreesMinutesText(abs(latitude))
    return "${degrees.toString().padStart(2, '0')}$minutesText" to hemisphere
}

private fun nmeaLongitudeField(longitude: Double): Pair<String, String> {
    val hemisphere = if (longitude >= 0.0) "E" else "W"
    val (degrees, minutesText) = nmeaDegreesMinutesText(abs(longitude))
    return "${degrees.toString().padStart(3, '0')}$minutesText" to hemisphere
}

/** ddmm.mmmmm-style degrees/minutes text at ~1.8cm minute-fraction resolution. */
private fun nmeaDegreesMinutesText(absoluteDegrees: Double): Pair<Int, String> {
    val degrees = absoluteDegrees.toInt()
    val minutes = (absoluteDegrees - degrees) * 60.0
    val minutesWhole = minutes.toInt()
    val minutesFraction = ((minutes - minutesWhole) * 100_000.0).roundToLong()
    val minutesText = "${minutesWhole.toString().padStart(2, '0')}.${minutesFraction.toString().padStart(5, '0')}"
    return degrees to minutesText
}

/** Non-negative decimal text with a fixed fraction-digit count, built without JVM-only formatting. */
private fun decimalText(value: Double, fractionDigits: Int): String {
    var multiplier = 1L
    repeat(fractionDigits) { multiplier *= 10L }
    val scaled = (value * multiplier).roundToLong()
    val whole = scaled / multiplier
    val fraction = scaled % multiplier
    return if (fractionDigits == 0) whole.toString() else "$whole.${fraction.toString().padStart(fractionDigits, '0')}"
}
