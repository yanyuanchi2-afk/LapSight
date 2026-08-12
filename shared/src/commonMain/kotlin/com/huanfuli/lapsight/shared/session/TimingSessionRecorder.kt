package com.huanfuli.lapsight.shared.session

import com.huanfuli.lapsight.shared.GpsQualitySummary
import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.ghost.DeltaDisplayState
import com.huanfuli.lapsight.shared.ghost.CourseProgressMatcher
import com.huanfuli.lapsight.shared.ghost.CourseProgressMatcherThresholds
import com.huanfuli.lapsight.shared.ghost.LiveDeltaEngine
import com.huanfuli.lapsight.shared.ghost.LiveDeltaSnapshot
import com.huanfuli.lapsight.shared.ghost.ReferenceLap
import com.huanfuli.lapsight.shared.ghost.ReferenceLapSelector
import com.huanfuli.lapsight.shared.lap.CourseDefinition
import com.huanfuli.lapsight.shared.lap.LapEngine
import com.huanfuli.lapsight.shared.lap.LapEngineConfig
import com.huanfuli.lapsight.shared.lap.LapEvent
import com.huanfuli.lapsight.shared.lap.SectorEvent
import com.huanfuli.lapsight.shared.lap.SectorLine
import com.huanfuli.lapsight.shared.lap.SectorResult
import com.huanfuli.lapsight.shared.lap.StartFinishLine
import com.huanfuli.lapsight.shared.lap.GeoPoint
import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.ClosedReferencePath
import com.huanfuli.lapsight.shared.track.ClosedReferencePathResult
import com.huanfuli.lapsight.shared.track.CourseGeometryBuilder
import com.huanfuli.lapsight.shared.track.SectorLineDto
import com.huanfuli.lapsight.shared.track.StartFinishLineDto
import com.huanfuli.lapsight.shared.track.TrackReferenceLine

/**
 * Maps a serializable [StartFinishLineDto] to the lap-domain [StartFinishLine].
 *
 * The lap engine consumes the clean-room [CourseDefinition] types from the
 * `lap` package (domain), while a saved [com.huanfuli.lapsight.shared.track.Track]
 * stores [StartFinishLineDto] / [SectorLineDto] from the `track` package
 * (serializable). These mappers bridge the boundary without modifying the lap
 * package types.
 */
fun StartFinishLineDto.toDomain(): StartFinishLine = StartFinishLine(
    pointA = GeoPoint(latitude = pointA.latitude, longitude = pointA.longitude),
    pointB = GeoPoint(latitude = pointB.latitude, longitude = pointB.longitude),
)

/** Maps a serializable [SectorLineDto] to the lap-domain [SectorLine]. */
fun SectorLineDto.toDomain(): SectorLine = SectorLine(
    id = id,
    name = name,
    order = order,
    pointA = GeoPoint(latitude = pointA.latitude, longitude = pointA.longitude),
    pointB = GeoPoint(latitude = pointB.latitude, longitude = pointB.longitude),
)

/**
 * Derives a lap-domain [CourseDefinition] from a saved
 * [com.huanfuli.lapsight.shared.track.Track]'s start/finish + sectors.
 *
 * Requires a confirmed start/finish line (D-19); returns null otherwise.
 */
fun courseFromTrack(
    startFinish: StartFinishLineDto?,
    sectors: List<SectorLineDto>,
): CourseDefinition? {
    if (startFinish == null) return null
    return CourseDefinition(
        startFinish = startFinish.toDomain(),
        sectors = sectors.map { it.toDomain() },
    )
}

/** Derives a course for either a closed circuit or an open point-to-point run. */
fun courseFromTrack(
    topology: CourseTopology,
    startFinish: StartFinishLineDto?,
    finishLine: StartFinishLineDto?,
    sectors: List<SectorLineDto>,
): CourseDefinition? {
    if (startFinish == null) return null
    if (topology == CourseTopology.PointToPoint && finishLine == null) return null
    return CourseDefinition(
        startFinish = startFinish.toDomain(),
        finishLine = if (topology == CourseTopology.PointToPoint) finishLine?.toDomain() else null,
        sectors = sectors.map { it.toDomain() },
    )
}

/**
 * Derives a DIRECTION-SPECIFIC lap-domain [CourseDefinition] from a saved Track's
 * start/finish + sectors (D-18, D-21).
 *
 * The recorded-oriented base is projected into [direction] by
 * [CourseGeometryBuilder.directionalCourse]: Recorded keeps the recorded order and
 * an explicit accepted approach side; Reverse swaps every endpoint and reverses the
 * Sector order so the lap engine accepts only the opposite physical crossing and
 * rejects the recorded one from the first crossing. Returns null when start/finish
 * is unconfirmed (D-19).
 */
fun courseFromTrack(
    startFinish: StartFinishLineDto?,
    sectors: List<SectorLineDto>,
    direction: CourseDirection,
): CourseDefinition? {
    val base = courseFromTrack(startFinish, sectors) ?: return null
    return CourseGeometryBuilder.directionalCourse(base, direction)
}

/** Direction-specific course derivation that preserves open point-to-point finish lines. */
fun courseFromTrack(
    topology: CourseTopology,
    startFinish: StartFinishLineDto?,
    finishLine: StartFinishLineDto?,
    sectors: List<SectorLineDto>,
    direction: CourseDirection,
): CourseDefinition? {
    val base = courseFromTrack(topology, startFinish, finishLine, sectors) ?: return null
    if (topology == CourseTopology.PointToPoint) return base
    return CourseGeometryBuilder.directionalCourse(base, direction)
}

/**
 * LapEngine-backed formal timing recorder linked to a saved Track (D-13, D-17,
 * D-19).
 *
 * Owns a [LapEngine] constructed from the saved Track's [CourseDefinition].
 * Each incoming [LocationSample] is fed to the engine; raw samples, completed
 * [LapEvent]s, and [SectorEvent]s are checkpointed continuously into the draft
 * store so the draft survives crash/restart before formal Save (D-13, D-15).
 *
 * The recorder does NOT create a second timing algorithm: it reuses the
 * clean-room [LapEngine] and persists its outputs and raw inputs.
 *
 * @param session the formal [TimingSession] this recorder belongs to.
 * @param course the [CourseDefinition] derived from the saved Track.
 * @param config lap-engine tuning; tests pass [LapEngineConfig.lenientForTests].
 * @param store local-first persistence that checkpoints the active draft.
 * @param app app/build metadata captured on every checkpoint.
 * @param initialReference the saved Track's persisted fastest reference loaded at
 *   timing start (D-01), or null when none exists yet.
 * @param onCheckpoint optional callback invoked after each checkpoint (for the
 *   controller snapshot / UI).
 */
class TimingSessionRecorder(
    val session: TimingSession,
    private val course: CourseDefinition,
    private val config: LapEngineConfig,
    private val store: com.huanfuli.lapsight.shared.storage.LocalSessionStore,
    private val app: AppMetadata,
    initialReference: ReferenceLap? = null,
    referenceLine: TrackReferenceLine? = null,
    private val onCheckpoint: () -> Unit = {},
) {
    private val engine = LapEngine(course, config)
    private val samples: MutableList<LocationSample> = mutableListOf()
    private val completedLaps: MutableList<LapEvent> = mutableListOf()
    private val sectorEvents: MutableList<SectorEvent> = mutableListOf()

    /**
     * Complete-interval V2 Sector results accumulated from the engine (D-06,
     * D-11). Fanned out into every draft checkpoint UNCONDITIONALLY, exactly like
     * raw samples and laps; incomplete Sector coverage never suppresses recording.
     */
    private val sectorResults: MutableList<SectorResult> = mutableListOf()
    private var lastTimingState = engine.state
    private var totalDurationMillis: Long = 0L
    private var paused = false
    private var resumePending = false
    private var elapsedOffsetMillis = 0L
    private var lastAcceptedSourceElapsedMillis: Long? = null
    var checkpointCount: Int = 0
        private set

    private val courseMatcher = referenceLine?.takeIf { it.isClosed }?.let { line ->
        val path = when (val result = ClosedReferencePath.fromReferenceLine(line)) {
            is ClosedReferencePathResult.Loaded -> result.path
            is ClosedReferencePathResult.Rejected -> null
        }
        path?.let {
            val midpoint = GeoPointDto(
                latitude = (session.startFinish.pointA.latitude + session.startFinish.pointB.latitude) / 2.0,
                longitude = (session.startFinish.pointA.longitude + session.startFinish.pointB.longitude) / 2.0,
            )
            CourseProgressMatcher(
                path = it,
                startFinishProgressMeters = it.projectGeo(midpoint).progressMeters,
                compatibilityKey = session.courseCompatibilityKey,
                thresholds = CourseProgressMatcherThresholds(
                    maxHorizontalAccuracyMeters = config.maxHorizontalAccuracyMeters
                        ?: com.huanfuli.lapsight.shared.ghost.ProgressCurveBuilder.DEFAULT_MAX_HORIZONTAL_ACCURACY_METERS,
                ),
            )
        }
    }

    private val deltaEngine = LiveDeltaEngine(
        maxHorizontalAccuracyMeters = config.maxHorizontalAccuracyMeters
            ?: com.huanfuli.lapsight.shared.ghost.ProgressCurveBuilder.DEFAULT_MAX_HORIZONTAL_ACCURACY_METERS,
        courseMatcher = courseMatcher,
    )

    /**
     * The reference lap the live delta currently chases (D-01, D-12). Starts at
     * [initialReference] and is replaced in-session whenever a faster valid lap
     * completes. Read-only to consumers.
     */
    var activeReference: ReferenceLap? = initialReference
        private set

    /** Last current-lap number seen, used to reset live delta at each lap boundary. */
    private var lastLapNumberSeen: Int? = null

    init {
        deltaEngine.setReference(initialReference)
    }

    /**
     * Latest live-delta snapshot from the realtime engine (D-08..D-11). Read this
     * through [SessionController] rather than [SessionController.recorderForTest].
     */
    val liveDelta: LiveDeltaSnapshot
        get() = deltaEngine.snapshot

    /** Current [LapTimingState][com.huanfuli.lapsight.shared.lap.LapTimingState] snapshot. */
    val timingState get() = engine.state

    /** Number of raw samples checkpointed so far. */
    val sampleCount: Int get() = samples.size

    /** Number of completed laps checkpointed so far. */
    val lapCount: Int get() = completedLaps.size

    /** Number of sector events checkpointed so far. */
    val sectorEventCount: Int get() = sectorEvents.size

    /** Number of complete-interval V2 Sector results checkpointed so far (D-06, D-11). */
    val sectorResultCount: Int get() = sectorResults.size

    /** Latest sample observed, or null. */
    val latestSample: LocationSample? get() = samples.lastOrNull()

    /** True iff the session's source is simulated (D-42, D-43). */
    val isSimulated: Boolean get() = session.source.isSimulated

    /**
     * Build the read-only production timing/delta view for the Drive UI
     * (GHOST-03). Reads the live lap engine state, latest sample, and realtime
     * live-delta in one shot so the UI renders without touching recorder
     * internals or [SessionController.recorderForTest].
     */
    fun timingRunSnapshot(): TimingRunSnapshot {
        val timing = timingState
        return TimingRunSnapshot(
            isActive = true,
            lapCount = timing.lapCount,
            currentLapNumber = timing.currentLapNumber,
            currentLapMillis = timing.currentLapElapsedMillis,
            lastLapMillis = timing.lastLapMillis,
            bestLapMillis = timing.bestLapMillis,
            sessionElapsedMillis = totalDurationMillis,
            checkpointedSampleCount = sampleCount,
            speedMetersPerSecond = latestSample?.speedMetersPerSecond,
            accuracyMeters = latestSample?.horizontalAccuracyMeters,
            headingDegrees = latestSample?.headingDegrees,
            altitudeMeters = latestSample?.altitudeMeters,
            sampleRateHz = recentSampleRateHz(),
            currentSectorNumber = timing.sectors
                .indexOfFirst { it.status == com.huanfuli.lapsight.shared.lap.SectorStatus.Pending }
                .takeIf { it >= 0 }
                ?.plus(1),
            sectorCount = timing.sectors.size,
            latestSectorName = timing.latestSector?.let { event ->
                timing.sectors.firstOrNull { it.sectorId == event.sectorId }?.sectorName
                    ?: event.sectorId
            },
            latestSectorSplitMillis = timing.latestSector?.splitMillis,
            source = session.source,
            deltaDisplay = DeltaDisplayState.from(liveDelta),
            referenceLapMillis = activeReference?.durationMillis,
        )
    }

    private fun recentSampleRateHz(): Double? {
        val recent = samples.takeLast(10)
        if (recent.size < 2) return null
        val durationMillis = recent.last().elapsedMillis - recent.first().elapsedMillis
        if (durationMillis <= 0L) return null
        return (recent.size - 1) * 1_000.0 / durationMillis
    }

    /**
     * Feed one sample. Persists raw samples, newly completed laps, and sector
     * events into the draft checkpoint (D-13). Pure with respect to inputs.
     */
    fun onSample(sample: LocationSample) {
        if (paused) return
        if (resumePending) {
            lastAcceptedSourceElapsedMillis?.let { previous ->
                elapsedOffsetMillis += (sample.elapsedMillis - previous).coerceAtLeast(0L)
            }
            resumePending = false
        }
        lastAcceptedSourceElapsedMillis = sample.elapsedMillis
        processSample(
            sample.copy(elapsedMillis = (sample.elapsedMillis - elapsedOffsetMillis).coerceAtLeast(0L)),
            allowCheckpoint = true,
        )
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        if (paused) {
            paused = false
            resumePending = true
        }
    }

    /**
     * Rebuild in-memory timing state from an already-persisted draft. Replaying
     * must never rewrite the growing JSON payload once per historical sample.
     */
    fun restoreSamples(restoredSamples: List<LocationSample>) {
        restoredSamples.forEach { processSample(it, allowCheckpoint = false) }
        lastAcceptedSourceElapsedMillis = restoredSamples.lastOrNull()?.elapsedMillis
    }

    private fun processSample(
        sample: LocationSample,
        allowCheckpoint: Boolean,
    ) {
        val state = engine.onSample(sample)
        samples.add(sample)
        lastTimingState = state

        // Capture any newly completed laps since the previous checkpoint.
        val previousLapCount = completedLaps.size
        if (state.completedLaps.size > previousLapCount) {
            for (i in previousLapCount until state.completedLaps.size) {
                val lap = state.completedLaps[i]
                completedLaps.add(lap)
                // D-02/D-12: a newly completed faster valid lap immediately
                // becomes the active reference for the following lap. Build it
                // from the session's raw samples within the lap window.
                val candidate = ReferenceLapSelector.referenceFromLap(
                    trackId = session.trackId,
                    sessionId = session.id,
                    lap = lap,
                    allSamples = samples,
                    isSimulated = session.source.isSimulated,
                    maxHorizontalAccuracyMeters = config.maxHorizontalAccuracyMeters
                        ?: com.huanfuli.lapsight.shared.ghost.ProgressCurveBuilder.DEFAULT_MAX_HORIZONTAL_ACCURACY_METERS,
                    compatibilityKey = session.courseCompatibilityKey,
                )
                if (candidate != null) {
                    val updated = ReferenceLapSelector.fasterOf(activeReference, candidate)
                    if (updated !== activeReference) {
                        activeReference = updated
                        deltaEngine.setReference(updated)
                    }
                }
            }
        }
        // Capture any newly emitted sector event.
        val previousSectorEventCount = sectorEvents.size
        state.latestSector?.let { latest ->
            if (sectorEvents.none { it == latest }) {
                sectorEvents.add(latest)
            }
        }
        // Capture any newly closed complete-Sector intervals (D-06, D-11). The
        // engine appends to completedSectorResults as each interval closes, so we
        // mirror new entries without re-walking the whole list.
        val previousSectorResultCount = sectorResults.size
        if (state.completedSectorResults.size > previousSectorResultCount) {
            for (i in sectorResults.size until state.completedSectorResults.size) {
                sectorResults.add(state.completedSectorResults[i])
            }
        }

        totalDurationMillis = if (samples.size >= 2) {
            samples.last().elapsedMillis - samples.first().elapsedMillis
        } else {
            0L
        }

        // Drive the realtime live-delta engine. Reset it at each lap boundary so
        // the new current lap chases the reference from scratch (D-08).
        val currentLapNumber = state.currentLapNumber
        if (currentLapNumber != null && currentLapNumber != lastLapNumberSeen) {
            deltaEngine.startLap()
            lastLapNumberSeen = currentLapNumber
        }
        if (currentLapNumber != null) {
            deltaEngine.update(sample)
        }

        val significantTimingEvent =
            completedLaps.size > previousLapCount ||
                sectorEvents.size > previousSectorEventCount ||
                sectorResults.size > previousSectorResultCount
        if (
            allowCheckpoint &&
            (
                samples.size == 1 ||
                    samples.size % CHECKPOINT_SAMPLE_INTERVAL == 0 ||
                    significantTimingEvent
                )
        ) {
            checkpoint()
        }
    }

    /**
     * Stop the timing run. Transitions the draft to stoppedPendingSave; the
     * user must explicitly Save or Discard before formal history is created
     * (D-14). Performs a final checkpoint so the stopped state persists.
     */
    fun stop() {
        checkpoint()
    }

    private fun checkpoint() {
        val quality = GpsQualitySummary.from(samples)
        val qualitySummary = GpsQualitySummary(
            sampleCount = quality.sampleCount,
            averageAccuracyMeters = quality.bestAccuracyMeters,
            source = session.source.source,
        )
        store.saveTimingDraft(
            session = session,
            samples = samples.map { it.toDto() },
            laps = completedLaps.map { LapDto(it.lapNumber, it.startMillis, it.endMillis) },
            sectorEvents = sectorEvents.map {
                SectorEventDto(
                    lapNumber = it.lapNumber,
                    sectorId = it.sectorId,
                    sectorOrder = it.sectorOrder,
                    crossingMillis = it.crossingMillis,
                    splitMillis = it.splitMillis,
                )
            },
            gpsQuality = qualitySummary,
            totalDurationMillis = totalDurationMillis,
            app = app,
            sectorResults = sectorResults.map { it.toDto() },
        )
        checkpointCount++
        onCheckpoint()
    }

    private companion object {
        /** At 10 Hz, bounds crash-loss exposure to roughly five seconds. */
        const val CHECKPOINT_SAMPLE_INTERVAL = 50
    }
}
