package com.huanfuli.lapsight.shared.session

import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.ghost.CourseCompatibilityKey
import com.huanfuli.lapsight.shared.ghost.DeltaUnavailableReason
import com.huanfuli.lapsight.shared.ghost.GhostCompatibility
import com.huanfuli.lapsight.shared.ghost.LiveDeltaSnapshot
import com.huanfuli.lapsight.shared.ghost.ReferenceLap
import com.huanfuli.lapsight.shared.ghost.ReferenceLapSelector
import com.huanfuli.lapsight.shared.lap.CourseDefinition
import com.huanfuli.lapsight.shared.lap.LapEngineConfig
import com.huanfuli.lapsight.shared.lap.LapEvent
import com.huanfuli.lapsight.shared.nowEpochMillis
import com.huanfuli.lapsight.shared.storage.LoadResult
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.CurrentProfileResolution
import com.huanfuli.lapsight.shared.track.Track
import com.huanfuli.lapsight.shared.track.TrackProfileController
import com.huanfuli.lapsight.shared.track.TrackReferenceLine

/**
 * Plain-Kotlin controller for the formal timing-session lifecycle (D-13..D-20,
 * SESS-01).
 *
 * Owns no Compose dependency. [startTiming] loads a saved [Track], derives a
 * [CourseDefinition][com.huanfuli.lapsight.shared.lap.CourseDefinition] from the
 * Track's start/finish + sectors, constructs a [TimingSessionRecorder] over the
 * existing [LapEngine][com.huanfuli.lapsight.shared.lap.LapEngine], and creates an
 * [active draft][TimingDraftState.ActiveDraft]. It rejects a missing Track or a
 * Track without a confirmed start/finish with the exact UI-SPEC copy (D-19).
 *
 * After [stop], the user must explicitly [saveStoppedDraft] or [discardDraft]
 * before formal Review history is created (D-14, D-16).
 * [loadUnfinishedDraft] surfaces a recovery prompt on app launch (D-15).
 *
 * @param store local-first persistence for draft checkpoints, saved sessions,
 *   and the ghost-candidate derivation.
 * @param appMetadata captured on every save (D-25).
 * @param engineConfig lap-engine tuning; tests pass [LapEngineConfig.lenientForTests].
 * @param now clock used for ids/timestamps; injectable for deterministic tests.
 * @param sourceForTrack derives the active run [SourceMetadata]. Tests and future
 *   platform providers inject the live provider source here so a real run on a
 *   Demo-created Track remains real; the default preserves legacy Demo behavior
 *   for current UI wiring until real providers are introduced (D-42, D-43).
 */
class SessionController(
    private val store: LocalSessionStore,
    private val appMetadata: AppMetadata = AppMetadata(appVersion = "0.3.0"),
    private val engineConfig: LapEngineConfig = LapEngineConfig(),
    private val now: () -> Long = ::nowEpochMillisSafe,
    private val sourceForTrack: (Track) -> SourceMetadata = { track ->
        SourceMetadata(
            source = track.source.source,
            isSimulated = track.source.isSimulated,
            label = track.source.label,
        )
    },
) {
    private var recorder: TimingSessionRecorder? = null
    private var session: TimingSession? = null
    private var pendingWrongCourseStart: ResolvedTimingStart? = null

    /**
     * Current immutable snapshot of the active draft, or null when no draft is
     * active/stopped.
     */
    fun snapshot(): SessionControllerSnapshot {
        val rec = recorder ?: return SessionControllerSnapshot(null)
        val activeSession = rec.session
        val state =
            if (stopped) TimingDraftState.StoppedPendingSave else TimingDraftState.ActiveDraft
        val draft = TimingDraftSnapshot(
            state = state,
            sessionId = activeSession.id,
            trackId = activeSession.trackId,
            trackName = activeSession.trackName,
            source = activeSession.source,
            checkpointedSampleCount = rec.sampleCount,
            checkpointedLapCount = rec.lapCount,
            checkpointedSectorEventCount = rec.sectorEventCount,
        )
        return SessionControllerSnapshot(draft)
    }

    private var stopped: Boolean = false

    /**
     * Start formal timing for [trackId]. Returns [StartTimingResult.Blocked]
     * with the exact UI-SPEC copy when the Track does not exist or has no
     * confirmed start/finish (D-19).
     */
    fun startTiming(
        trackId: String,
        latestGps: LocationSample? = null,
        preflightNowElapsedMillis: Long = latestGps?.elapsedMillis ?: 0L,
        recentRateHz: Double? = null,
        requireReady: Boolean = false,
    ): StartTimingResult {
        pendingWrongCourseStart = null
        val track = loadTrackForTiming(trackId)
            ?: return StartTimingResult.Blocked(START_TIMING_BLOCKED_COPY)
        val startFinish = track.startFinish
            ?: return StartTimingResult.Blocked(START_TIMING_BLOCKED_COPY)
        val finishLine = track.finishLine
        if (track.topology == CourseTopology.PointToPoint && finishLine == null) {
            return StartTimingResult.Blocked(START_TIMING_BLOCKED_COPY)
        }
        val runSource = sourceForTrack(track)
        // Resolve the persisted Course Direction / compatibility identity for this
        // Track and build the direction-specific course (D-18, D-21). Reverse flips
        // the accepted approach side and Sector order; Recorded keeps the recorded
        // orientation. Source is the active run provider, not the Track marking source.
        val courseIdentity = selectedCourseIdentityFor(track, runSource)
        val course = courseFromTrack(track.topology, startFinish, finishLine, track.sectors, courseIdentity.direction)
            ?: return StartTimingResult.Blocked(START_TIMING_BLOCKED_COPY)
        val preflightResult = courseIdentity.referenceLine?.let { referenceLine ->
            WrongCoursePreflight().evaluate(
                referenceLine = referenceLine,
                latestFix = latestGps,
                nowElapsedMillis = preflightNowElapsedMillis,
            )
        } ?: CoursePreflightResult.Unavailable(
            CoursePreflightUnavailableReason.MalformedGeometry,
        )
        val resolved = ResolvedTimingStart(
            track = track,
            source = runSource,
            identity = courseIdentity,
            course = course,
            startFinish = startFinish,
            finishLine = finishLine,
            preflightResult = preflightResult,
        )
        if (preflightResult is CoursePreflightResult.Blocked) {
            pendingWrongCourseStart = resolved
            return StartTimingResult.WrongCourseBlocked(
                distanceMeters = preflightResult.distanceMeters,
                thresholdMeters = preflightResult.thresholdMeters,
                message = WRONG_COURSE_BLOCKED_COPY,
            )
        }
        // Production Start uses the UX gate, not the stricter field-UAT quality
        // gate: low GPS rate, poor accuracy, and unavailable course-distance checks
        // are surfaced as warnings, but they must not trap the user before timing.
        if (requireReady) {
            val readyState = aggregateStartReady(
                latest = latestGps,
                selection = TrackProfileController(store).resolveCurrent(),
                startFinishConfirmed = true,
                directionCompatible = true,
                preflight = preflightResult,
            )
            if (readyState is ReadyState.NotReady) {
                return StartTimingResult.NotReady(
                    reasons = readyState.reasons,
                    message = READY_NOT_MET_COPY,
                )
            }
        }
        return startResolvedTiming(
            resolved = resolved,
            preflightSnapshot = CoursePreflightSnapshot.from(preflightResult),
        )
    }

    /**
     * Consume the exact previously blocked start without rerunning preflight.
     *
     * The selected revision, direction, provider source, course, and blocked
     * evidence are captured together, then passed through the normal recorder
     * construction path with an explicit override snapshot (D-23).
     */
    fun overrideWrongCourseAndStart(): StartTimingResult {
        val pending = pendingWrongCourseStart
            ?: return StartTimingResult.Blocked(START_TIMING_BLOCKED_COPY)
        pendingWrongCourseStart = null
        return startResolvedTiming(
            resolved = pending,
            preflightSnapshot = CoursePreflightSnapshot.from(
                pending.preflightResult,
                overrideUsed = true,
            ),
        )
    }

    private fun startResolvedTiming(
        resolved: ResolvedTimingStart,
        preflightSnapshot: CoursePreflightSnapshot,
    ): StartTimingResult {
        val track = resolved.track
        val runSource = resolved.source
        val courseIdentity = resolved.identity
        val createdAt = now()
        val session = TimingSession(
            id = "session-$createdAt",
            trackId = track.id,
            trackName = track.name,
            createdAtEpochMillis = createdAt,
            source = runSource,
            startFinish = resolved.startFinish,
            finishLine = resolved.finishLine,
            topology = track.topology,
            sectors = track.sectors,
            direction = courseIdentity.direction,
            courseCompatibilityKey = courseIdentity.compatibilityKey,
            coursePreflight = preflightSnapshot,
        )
        val rec = TimingSessionRecorder(
            session = session,
            course = resolved.course,
            config = engineConfig,
            store = store,
            app = appMetadata,
            initialReference = loadReferenceFor(session),
            referenceLine = courseIdentity.referenceLine,
        )
        this.session = session
        this.recorder = rec
        this.stopped = false
        // Persist the initial empty checkpoint so recovery sees the draft.
        store.saveTimingDraft(
            session = session,
            samples = emptyList(),
            laps = emptyList(),
            sectorEvents = emptyList(),
            gpsQuality = GpsQualitySummary(
                sampleCount = 0,
                averageAccuracyMeters = null,
                source = session.source.source,
            ),
            totalDurationMillis = 0L,
            app = appMetadata,
        )
        return StartTimingResult.Started(session.id)
    }

    /**
     * Stop the timing run and transition to stoppedPendingSave. The user must
     * explicitly save or discard afterwards (D-14).
     */
    fun stop() {
        val rec = recorder ?: return
        rec.stop()
        stopped = true
    }

    fun pause() {
        recorder?.pause()
    }

    fun resume() {
        recorder?.resume()
    }

    /**
     * Promote the stopped draft into formal Review history (D-14). Returns
     * [SaveDraftResult.NothingToSave] when no draft is stopped.
     */
    fun saveStoppedDraft(): SaveDraftResult {
        val payload = store.loadUnfinishedDraft()
            ?: return SaveDraftResult.NothingToSave
        store.saveTimingSession(payload, appMetadata)
        // D-01/D-12, T-04-06: commit the best eligible reference to global storage
        // ONLY on explicit Save. Discard never reaches here, so a discarded faster
        // lap never becomes the persisted reference.
        promoteReferenceFromPayload(payload)
        recorder = null
        session = null
        stopped = false
        return SaveDraftResult.Saved(payload.session.id)
    }

    /**
     * Discard the unfinished draft so it never enters Review history (D-16).
     */
    fun discardDraft() {
        store.discardTimingDraft()
        recorder = null
        session = null
        stopped = false
    }

    /**
     * On app launch, return a recovery prompt for an unfinished draft with
     * Resume / Save / Discard actions (D-15). Returns null when no draft exists.
     */
    fun loadUnfinishedDraft(): DraftRecoveryPrompt? {
        val payload = store.loadUnfinishedDraft() ?: return null
        val actions = if (payload.laps.isNotEmpty() || payload.samples.size > 1) {
            listOf(DraftRecoveryAction.Resume, DraftRecoveryAction.Save, DraftRecoveryAction.Discard)
        } else {
            listOf(DraftRecoveryAction.Resume, DraftRecoveryAction.Discard)
        }
        return DraftRecoveryPrompt(
            sessionId = payload.session.id,
            trackId = payload.session.trackId,
            trackName = payload.session.trackName,
            state = TimingDraftState.StoppedPendingSave,
            availableActions = actions,
        )
    }

    /**
     * Handle a recovery action from [loadUnfinishedDraft]. Resume restarts the
     * recorder over the saved draft state; Save/Discard behave like the live
     * controller methods (D-15).
     */
    fun handleRecoveryAction(
        prompt: DraftRecoveryPrompt,
        action: DraftRecoveryAction,
    ): SaveDraftResult {
        return when (action) {
            DraftRecoveryAction.Resume -> {
                val payload = store.loadUnfinishedDraft() ?: return SaveDraftResult.NothingToSave
                val track = loadTrackForTiming(payload.session.trackId)
                if (track?.startFinish != null) {
                    // Rebuild the SAME direction-specific course the draft was timed under,
                    // taken from the immutable session snapshot (D-18).
                    val course = courseFromTrack(
                        payload.session.topology,
                        track.startFinish,
                        payload.session.finishLine ?: track.finishLine,
                        track.sectors,
                        payload.session.direction,
                    )
                    if (course == null) return SaveDraftResult.NothingToSave
                    val rec = TimingSessionRecorder(
                        session = payload.session,
                        course = course,
                        config = engineConfig,
                        store = store,
                        app = appMetadata,
                        initialReference = loadReferenceFor(payload.session),
                        referenceLine = track.referenceLine,
                    )
                    // Replay the already-captured samples so the in-memory engine
                    // state catches up to the persisted draft. Replaying through
                    // onSample also rebuilds the session-local active reference
                    // from the draft's completed laps (D-12 on resume).
                    rec.restoreSamples(payload.samples.map { it.toModel() })
                    this.recorder = rec
                    this.session = payload.session
                    this.stopped = false
                }
                SaveDraftResult.Saved(prompt.sessionId)
            }
            DraftRecoveryAction.Save -> saveStoppedDraft()
            DraftRecoveryAction.Discard -> {
                discardDraft()
                SaveDraftResult.NothingToSave
            }
        }
    }

    /**
     * Read-only live-delta snapshot for the Drive UI (GHOST-02, GHOST-03). Reads
     * through the controller so production UI never touches [recorderForTest].
     * Returns a [DeltaUnavailableReason.NoCurrentLap] snapshot when no timing run
     * is active.
     */
    fun liveDelta(): LiveDeltaSnapshot =
        recorder?.liveDelta ?: LiveDeltaSnapshot.Unavailable(DeltaUnavailableReason.NoCurrentLap)

    /**
     * Read-only production timing/delta snapshot for the Drive UI (GHOST-03).
     * Returns an [TimingRunSnapshot.inactive] view when no run is active so the UI
     * renders a neutral `--` delta and empty metrics without special-casing null.
     * Production UI reads this instead of [recorderForTest].
     */
    fun timingRunSnapshot(): TimingRunSnapshot =
        recorder?.timingRunSnapshot() ?: TimingRunSnapshot.inactive()

    /**
     * Feed one live GPS sample into the active timing recorder (production sample
     * pump). No-op when no run is active. Lets the Drive UI drive timing without
     * reaching through [recorderForTest].
     */
    fun ingestSample(sample: com.huanfuli.lapsight.shared.LocationSample) {
        recorder?.onSample(sample)
    }

    /**
     * The reference lap the active timing run currently chases (D-01, D-12), or
     * null when no run is active / no reference is available.
     */
    fun activeReference(): ReferenceLap? = recorder?.activeReference

    /**
     * Test-only accessor for the live recorder so tests can feed samples after
     * [startTiming] returns. Not for production use.
     */
    fun recorderForTest(): TimingSessionRecorder? = recorder

    private fun loadTrackForTiming(trackId: String): Track? {
        val profileResult = store.loadProfile(trackId)
        val profile = (profileResult as? LoadResult.Loaded)?.value
        val revision = profile?.latestRevision
        if (profile != null && revision != null) {
            return Track(
                id = profile.profileId,
                name = profile.name,
                createdAtEpochMillis = profile.createdAtEpochMillis,
                sourceMarkingSessionId = revision.sourceMarkingSessionId,
                source = profile.source,
                referenceLine = revision.referenceLine,
                startFinish = revision.courseSetup.startFinish,
                finishLine = revision.courseSetup.finishLine,
                topology = revision.courseSetup.topology,
                sectors = revision.courseSetup.boundaries.map {
                    com.huanfuli.lapsight.shared.track.SectorLineDto(
                        id = it.id,
                        name = "Sector ${it.order}",
                        order = it.order,
                        pointA = it.pointA,
                        pointB = it.pointB,
                    )
                }
            )
        }

        val result = store.loadTrack(trackId)
        return (result as? LoadResult.Loaded)?.value?.track
    }

    /**
     * The persisted Course Direction for [trackId] (D-18). Reads the exact current
     * selection and uses its direction only when it names this Track; any other case
     * (no selection, a different Track, corrupt) falls back to
     * [CourseDirection.Recorded] so a normal start is never blocked by selection state.
     */
    private fun selectedCourseIdentityFor(track: Track, source: SourceMetadata): SelectedCourseIdentity {
        val selected = TrackProfileController(store).resolveCurrent() as? CurrentProfileResolution.Selected
        if (selected != null && selected.profile.profileId == track.id) {
            return SelectedCourseIdentity(
                direction = selected.direction,
                compatibilityKey = CourseCompatibilityKey(
                    profileId = selected.profile.profileId,
                    geometryCompatibilityId = selected.revision.geometryCompatibilityId,
                    direction = selected.direction,
                    isSimulated = source.isSimulated,
                ),
                referenceLine = selected.revision.referenceLine,
            )
        }
        val direction = selectedDirectionFor(track.id)
        return SelectedCourseIdentity(
            direction = direction,
            compatibilityKey = GhostCompatibility
                .migratedV1Key(trackId = track.id, isSimulated = source.isSimulated)
                .copy(direction = direction),
            referenceLine = track.referenceLine,
        )
    }

    private fun selectedDirectionFor(trackId: String): CourseDirection {
        val selection = (store.loadCurrentSelection() as? LoadResult.Loaded)?.value ?: return CourseDirection.Recorded
        return if (selection.profileId == trackId) selection.direction else CourseDirection.Recorded
    }

    /**
     * Loads the persisted fastest reference for a session's Track within its
     * source boundary (D-01, D-04). A real session loads only the real reference;
     * a simulated session loads only the simulated reference.
     *
     */
    private fun loadReferenceFor(session: TimingSession): ReferenceLap? {
        val result = store.loadReferenceLap(session.courseCompatibilityKey)
        return (result as? LoadResult.Loaded)?.value?.toReferenceLap()
    }

    /**
     * Promote the fastest valid lap of a just-saved session into the global
     * reference store when it is strictly faster than the existing reference
     * (D-01, D-12). The source boundary is taken from the session, so a simulated
     * session can only update the simulated slot and never the real reference
     * (D-04, D-24).
     */
    private fun promoteReferenceFromPayload(payload: TimingSessionPayloadV1) {
        val candidate = referenceFromPayload(payload) ?: return
        val existing = (
            store.loadReferenceLap(payload.session.courseCompatibilityKey)
                as? LoadResult.Loaded
            )?.value?.toReferenceLap()
        val best = ReferenceLapSelector.fasterOf(existing, candidate)
        // Only persist when the new lap is the strict winner (fasterOf prefers the
        // existing reference on ties), avoiding needless rewrites.
        if (best === candidate) {
            store.saveReferenceLap(candidate.toReferencePayloadV2(payload.session.source, appMetadata), appMetadata)
        }
    }

    /**
     * Rebuild the fastest [ReferenceLap] from a saved/draft payload's laps and
     * samples (used for Save promotion and resume). Returns null when no valid
     * reference can be built.
     */
    private fun referenceFromPayload(payload: TimingSessionPayloadV1): ReferenceLap? {
        val fastest = payload.laps.minByOrNull { it.durationMillis } ?: return null
        val samples = payload.samples.map { it.toModel() }
        return ReferenceLapSelector.referenceFromLap(
            trackId = payload.session.trackId,
            sessionId = payload.session.id,
            lap = LapEvent(
                lapNumber = fastest.lapNumber,
                startMillis = fastest.startMillis,
                endMillis = fastest.endMillis,
            ),
            allSamples = samples,
            isSimulated = payload.session.source.isSimulated,
            compatibilityKey = payload.session.courseCompatibilityKey,
        )
    }

    private data class SelectedCourseIdentity(
        val direction: CourseDirection,
        val compatibilityKey: CourseCompatibilityKey,
        val referenceLine: TrackReferenceLine?,
    )

    private data class ResolvedTimingStart(
        val track: Track,
        val source: SourceMetadata,
        val identity: SelectedCourseIdentity,
        val course: CourseDefinition,
        val startFinish: com.huanfuli.lapsight.shared.track.StartFinishLineDto,
        val finishLine: com.huanfuli.lapsight.shared.track.StartFinishLineDto?,
        val preflightResult: CoursePreflightResult,
    )
}

/** Snapshot of the controller's active draft, rendered by the Drive UI. */
data class SessionControllerSnapshot(val activeDraft: TimingDraftSnapshot?)

/** Exact UI-SPEC copy shown while Start Timing is blocked (D-19). */
const val START_TIMING_BLOCKED_COPY: String =
    "Mark a track first. Timing needs a saved start/finish line."

/** Exact D-23 action copy for a clearly-far preflight override. */
const val STILL_USE_THIS_TRACK_ACTION: String = "Still use this track"

/** Conservative pre-Timing warning; it never appears on the active timing dash. */
const val WRONG_COURSE_BLOCKED_COPY: String =
    "This GPS fix is clearly far from the selected course. Check the current track before timing."

/** Shown when the aggregate Ready gate blocks formal timing (D-13, D-16). */
const val READY_NOT_MET_COPY: String =
    "Not ready to time yet. Record raw GPS for diagnosis, or wait for a clean fix."

/**
 * Default clock for the controller. Indirection so tests can inject a fixed
 * `now` without depending on the platform actual at construction time.
 */
private fun nowEpochMillisSafe(): Long = try {
    nowEpochMillis()
} catch (e: Throwable) {
    0L
}
