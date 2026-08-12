package com.huanfuli.lapsight.shared.ui

import com.huanfuli.lapsight.shared.GpsQualitySummary
import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.LocationSampleProvider
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.nowEpochMillis
import com.huanfuli.lapsight.shared.session.AppMetadata
import com.huanfuli.lapsight.shared.session.SourceMetadata
import com.huanfuli.lapsight.shared.session.toDto
import com.huanfuli.lapsight.shared.storage.LoadResult
import com.huanfuli.lapsight.shared.storage.LocalSessionStore
import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.ClosedReferencePath
import com.huanfuli.lapsight.shared.track.ClosedReferencePathResult
import com.huanfuli.lapsight.shared.track.CourseGeometryBuilder
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.CreateProfileResult
import com.huanfuli.lapsight.shared.track.CurrentProfileResolution
import com.huanfuli.lapsight.shared.track.CurrentTrackSelection
import com.huanfuli.lapsight.shared.track.ReferenceLineExtraction
import com.huanfuli.lapsight.shared.track.ReferenceLineExtractor
import com.huanfuli.lapsight.shared.track.ReviewEntryType
import com.huanfuli.lapsight.shared.track.StartFinishLineDto
import com.huanfuli.lapsight.shared.track.StartFinishRecommender
import com.huanfuli.lapsight.shared.track.Track
import com.huanfuli.lapsight.shared.track.TrackMarkingSession
import com.huanfuli.lapsight.shared.track.TrackProfileController
import com.huanfuli.lapsight.shared.track.TrackReviewState

/**
 * Phases of the Mark New Track capture flow (D-06..D-12).
 *
 * This is continuous capture, NOT lap timing: [Review] never carries laps
 * (D-08). Timing is a separate flow (Plan 03-06) and stays blocked until a
 * saved [Track] has a confirmed start/finish line (D-19).
 */
enum class DriveMarkingPhase { Idle, Capturing, Review }

/** Exact UI-SPEC copy shown while Start Timing is blocked (D-19). */
const val START_TIMING_BLOCKED_COPY: String =
    "Mark a track first. Timing needs a saved start/finish line."

/**
 * Immutable snapshot of the Drive surface state rendered by [DriveScreen].
 *
 * The controller is plain Kotlin (no Compose); it hands this snapshot to the
 * composable, which re-reads it after every event. This keeps the marking state
 * machine testable without a Compose runtime.
 */
data class DriveMarkingSnapshot(
    val phase: DriveMarkingPhase,
    val isDemoFeedRunning: Boolean,
    val feedSampleCount: Int,
    val latestSample: LocationSample?,
    val feedQuality: GpsQualitySummary?,
    val reviewState: TrackReviewState?,
    val savedTrackCount: Int,
    val timingReadyTrackId: String?,
    val timingReadyTrackName: String?,
    val canStartTiming: Boolean,
    val startTimingBlockedReason: String,
    /** Exact persisted current-Track name (D-01); null when nothing is selected. */
    val currentTrackName: String? = null,
    /** True when Timing is blocked and the UI must route to the Track selector (D-03). */
    val needsTrackSelection: Boolean = true,
    /** Active profiles (latest revision only) offered by the Track selector (D-14). */
    val selectableProfiles: List<TrackProfileRow> = emptyList(),
    /** The persisted Course Direction of the current selection (D-18); Recorded when none. */
    val selectedDirection: CourseDirection = CourseDirection.Recorded,
    /** Topology of the currently selected saved Track, or null when none is selected. */
    val currentTrackTopology: CourseTopology? = null,
    /** The marking trace captured so far; non-empty only while Capturing (live feedback). */
    val capturedSamples: List<LocationSample> = emptyList(),
    /** Course shape selected for the next marking capture. */
    val selectedTopology: CourseTopology = CourseTopology.Circuit,
    /** Authoritative HUD marking clock when the active backend is LapSight-HUD. */
    val backendMarkingElapsedMillis: Long? = null,
    /** Authoritative HUD geometry-point count; distinct from the raw NMEA sample count. */
    val backendMarkingPointCount: Int? = null,
) {
    val speedKmhLabel: String
        get() = latestSample?.speedMetersPerSecond
            ?.let { ((it * 3.6).toInt()).toString() } ?: "--"

    val accuracyLabel: String
        get() = latestSample?.horizontalAccuracyMeters
            ?.let { (if (it < 0) 0.0 else it).toInt().toString() } ?: "--"

    companion object {
        fun empty(): DriveMarkingSnapshot = DriveMarkingSnapshot(
            phase = DriveMarkingPhase.Idle,
            isDemoFeedRunning = false,
            feedSampleCount = 0,
            latestSample = null,
            feedQuality = null,
            reviewState = null,
            savedTrackCount = 0,
            timingReadyTrackId = null,
            timingReadyTrackName = null,
            canStartTiming = false,
            startTimingBlockedReason = START_TIMING_BLOCKED_COPY,
            currentTrackName = null,
            needsTrackSelection = true,
            selectableProfiles = emptyList(),
            selectedDirection = CourseDirection.Recorded,
            currentTrackTopology = null,
            selectedTopology = CourseTopology.Circuit,
        )
    }
}

/**
 * A selectable Track row for the Drive selector (D-02, D-14).
 *
 * Only active profiles are surfaced and only their latest revision is described, so
 * the selector never offers an archived Track or an older geometry revision.
 */
data class TrackProfileRow(
    val profileId: String,
    val name: String,
    val isTimingReady: Boolean,
)

/**
 * Plain-Kotlin controller for the Mark New Track capture state machine.
 *
 * Owns no Compose dependency: it drives a [LocationSampleProvider], collects a
 * continuous marking trace, runs [ReferenceLineExtractor] on stop, and exposes a
 * [TrackReviewState] for the Track Review UI. Save writes the Track + source
 * marking through a [LocalSessionStore]; Discard keeps the marking out of Review
 * history; Re-record returns to capture (D-12, D-31).
 *
 * @param provider the normal GPS feed boundary (simulated now, real later).
 * @param store local-first persistence for saved tracks/markings.
 * @param appMetadata captured on every save (D-25).
 * @param now clock used for ids/timestamps; injectable for deterministic tests.
 * @param extractor reference-line extractor; injectable for tests.
 * @param defaultTrackName name applied to a saved track when none is set.
 */
class DriveMarkingController(
    private val provider: LocationSampleProvider,
    private val store: LocalSessionStore,
    private val appMetadata: AppMetadata = AppMetadata(appVersion = "0.3.0"),
    private val now: () -> Long = ::nowEpochMillisSafe,
    private val extractor: (TrackMarkingSession, CourseTopology) -> ReferenceLineExtraction =
        { marking, topology -> ReferenceLineExtractor.extract(marking, topology = topology) },
    private val defaultTrackName: String = "Demo Track",
) {
    private var phase: DriveMarkingPhase = DriveMarkingPhase.Idle
    private val feedSamples: MutableList<LocationSample> = mutableListOf()
    private val captured: MutableList<LocationSample> = mutableListOf()
    private var reviewState: TrackReviewState? = null
    private val savedTracks: MutableList<Track> = mutableListOf()
    private var trackNameOverride: String? = null
    private var selectedTopology: CourseTopology = CourseTopology.Circuit

    /** Resolves the explicit current Track selection without any newest-Track fallback. */
    private val profileController = TrackProfileController(store)

    init {
        refreshSavedTracks()
    }

    /** Current immutable view of the Drive surface. */
    fun snapshot(): DriveMarkingSnapshot {
        // Resolve ONLY the explicit persisted current selection (D-01..D-04). There is
        // no `maxByOrNull(createdAtEpochMillis)` newest-ready fallback any more: an
        // unavailable selection blocks Timing and routes to the selector instead of
        // silently switching to another Track (D-03).
        val selected = profileController.resolveCurrent() as? CurrentProfileResolution.Selected
        val canStart = selected != null
        val selectableProfiles = store.listActiveProfiles().map { profile ->
            val setup = profile.latestRevision?.courseSetup
            TrackProfileRow(
                profileId = profile.profileId,
                name = profile.name,
                // Describe the latest revision only (D-14).
                isTimingReady = setup?.startFinish != null &&
                    (setup.topology != CourseTopology.PointToPoint || setup.finishLine != null),
            )
        }
        return DriveMarkingSnapshot(
            phase = phase,
            isDemoFeedRunning = provider.isRunning,
            feedSampleCount = feedSamples.size,
            latestSample = feedSamples.lastOrNull(),
            feedQuality = if (feedSamples.isEmpty()) null else GpsQualitySummary.from(feedSamples),
            reviewState = reviewState,
            savedTrackCount = savedTracks.size,
            timingReadyTrackId = selected?.profile?.profileId,
            timingReadyTrackName = selected?.profile?.name,
            canStartTiming = canStart,
            startTimingBlockedReason = if (canStart) "" else START_TIMING_BLOCKED_COPY,
            currentTrackName = selected?.profile?.name,
            needsTrackSelection = !canStart,
            selectableProfiles = selectableProfiles,
            selectedDirection = selected?.direction ?: CourseDirection.Recorded,
            currentTrackTopology = selected?.revision?.courseSetup?.topology,
            capturedSamples = if (phase == DriveMarkingPhase.Capturing) captured.toList() else emptyList(),
            selectedTopology = selectedTopology,
        )
    }

    /** Select the topology used by the next marking capture. */
    fun selectTopology(topology: CourseTopology) {
        if (phase == DriveMarkingPhase.Idle) {
            selectedTopology = topology
        }
    }

    /**
     * Persist an explicit Recorded/Reverse [direction] for the current Track (D-18).
     *
     * This is a pre-Timing configuration over the SAME selected revision: it rewrites
     * only the direction of the existing current selection and never changes the
     * selected Track. It is a no-op when no Track is currently selected.
     */
    fun selectDirection(direction: CourseDirection) {
        val selection = (store.loadCurrentSelection() as? LoadResult.Loaded)?.value ?: return
        val profileId = selection.profileId ?: return
        store.setCurrentSelection(CurrentTrackSelection(profileId = profileId, direction = direction))
    }

    /**
     * Make [profileId] the EXPLICIT current Track selection (D-02). This is the only
     * way Drive changes the current Track: it loads the exactly-named active profile
     * and persists it as the selection with the profile's preferred direction. It is a
     * no-op for a missing/unloadable profile and NEVER derives a different Track
     * (no newest/nearby fallback, D-03/D-04).
     */
    fun selectTrack(profileId: String) {
        val profile = (store.loadProfile(profileId) as? LoadResult.Loaded)?.value ?: return
        store.setCurrentSelection(
            CurrentTrackSelection(profileId = profile.profileId, direction = profile.preferredDirection),
        )
    }

    /**
     * Re-hydrate saved Tracks from the canonical local store. Review reads the
     * same index directly, so Drive must do this on construction/tab entry and
     * after save to keep Start Timing stable across navigation and cold start.
     */
    fun refreshSavedTracks() {
        savedTracks.clear()
        savedTracks.addAll(loadSavedTracksFromStore())
    }

    /** Start or stop the background demo feed (D-05, D-44). */
    fun toggleDemoFeed() {
        if (provider.isRunning) {
            provider.stop()
        } else {
            provider.reset()
            feedSamples.clear()
            provider.start()
        }
    }

    /**
     * Begin a continuous marking capture. Does NOT require starting at
     * start/finish — the user may leave pit/paddock first (D-07). Starts the
     * feed if it is not already running so marking is self-contained.
     */
    fun beginMarking() {
        if (phase != DriveMarkingPhase.Idle) return
        if (!provider.isRunning) {
            provider.reset()
            feedSamples.clear()
            provider.start()
        }
        captured.clear()
        reviewState = null
        phase = DriveMarkingPhase.Capturing
    }

    /**
     * Advance the feed by every sample ready this tick. Called on a UI timer.
     * Draining the full backlog (rather than one sample per tick) means a real
     * receiver that buffered several fixes between ticks never has them dropped or
     * delayed at the poll rate. While capturing, each sample is also appended to
     * the marking trace. Returns the drained batch in arrival order (possibly
     * empty) so the timing path can ingest every sample.
     */
    fun tick(): List<LocationSample> {
        if (!provider.isRunning) return emptyList()
        val batch = provider.drainPending()
        if (batch.isEmpty()) return emptyList()
        feedSamples.addAll(batch)
        if (phase == DriveMarkingPhase.Capturing) {
            captured.addAll(batch)
        }
        return batch
    }

    /**
     * Stop the capture and run [ReferenceLineExtractor] over the continuous
     * trace, producing a [TrackReviewState] for Track Review (D-09..D-11).
     */
    fun stopMarking() {
        if (phase != DriveMarkingPhase.Capturing) return
        buildReviewFromCaptured()
    }

    /**
     * Replace any reconnect-only phone trace with the complete, CRC-verified
     * marking captured by the authoritative HUD backend.
     */
    fun reviewImportedMarking(samples: List<LocationSample>) {
        if (samples.isEmpty()) return
        captured.clear()
        captured.addAll(samples)
        buildReviewFromCaptured()
    }

    private fun buildReviewFromCaptured() {
        val source = sourceMetadataFor(captured)
        val marking = TrackMarkingSession(
            id = "mark-${now()}",
            createdAtEpochMillis = now(),
            source = source,
            samples = captured.map { it.toDto() },
        )
        val extraction = extractor(marking, selectedTopology)
        reviewState = TrackReviewState.from(trackNameOverride ?: defaultTrackName, extraction)
            .withRecommendedTimingLines()
        phase = DriveMarkingPhase.Review
    }

    /**
     * Place a perpendicular start/finish line at the start of the extracted closed
     * reference path, confirming it for timing (D-11, D-19). Only meaningful while
     * reviewing a save-ready capture.
     */
    fun confirmStartFinish() {
        val review = reviewState ?: return
        if (!review.canSave) return
        val ref = review.extraction.referenceLine ?: return
        if (review.startFinish != null) return
        val recommended = recommendedTimingLines(ref) ?: return
        reviewState = review.copy(
            startFinish = recommended.first,
            finishLine = recommended.second,
        )
    }

    /**
     * Save the reviewed track + source marking through the store (D-10, D-25).
     * Returns the saved [Track] on success, or null if the capture is not
     * save-ready.
     */
    fun saveTrack(): Track? {
        val currentReview = reviewState
        if (
            currentReview?.startFinish == null ||
            (currentReview.extraction.topology == CourseTopology.PointToPoint && currentReview.finishLine == null)
        ) {
            confirmStartFinish()
        }
        val review = reviewState ?: return null
        if (review.extraction.topology == CourseTopology.PointToPoint && review.finishLine == null) return null
        if (!review.canSave) return null
        val createdAt = now()
        val track = review.toTrack(
            id = "track-$createdAt",
            createdAtEpochMillis = createdAt,
        )
        store.saveTrackBundle(track, review.extraction.markingSession, appMetadata)
        // Route the completed-marking save through the create-first-profile path so the
        // named V2 profile is built and validated in ONE place (SC-01, T-05-07): it
        // validates the name, generates opaque IDs (profileId == track.id), and writes
        // an immutable first revision without auto-selecting.
        val created = profileController.saveProfile(track = track, name = track.name, app = appMetadata)
        if (created is CreateProfileResult.Created) {
            // The mark -> time flow then EXPLICITLY makes the just-created profile the
            // current selection so Timing resolves through TrackProfileController with no
            // newest-Track fallback (D-01, D-02). Selection is a separate, deliberate step
            // here; the create API itself never selects.
            store.setCurrentSelection(
                CurrentTrackSelection(
                    profileId = created.profile.profileId,
                    direction = created.profile.preferredDirection,
                ),
            )
        }
        refreshSavedTracks()
        reviewState = null
        phase = DriveMarkingPhase.Idle
        return track
    }

    /** Re-record: discard the current review and resume capturing (D-31). */
    fun reRecord() {
        if (phase != DriveMarkingPhase.Review) return
        reviewState = null
        captured.clear()
        if (!provider.isRunning) {
            provider.reset()
            feedSamples.clear()
            provider.start()
        }
        phase = DriveMarkingPhase.Capturing
    }

    /**
     * Discard: drop the marking trace and reference line so it never enters
     * Review history (D-16, D-31). Returns to Idle.
     */
    fun discard() {
        if (phase != DriveMarkingPhase.Review) return
        reviewState = null
        captured.clear()
        phase = DriveMarkingPhase.Idle
    }

    /**
     * Restart the demo feed from a clean phase for a formal timing run. The current
     * app wiring uses a replay provider, so timing should not inherit the arbitrary
     * sample index left by marking/review UAT.
     */
    fun restartFeedForTiming() {
        provider.reset()
        feedSamples.clear()
        provider.start()
    }

    /** Set the user-entered track name for the next save. */
    fun setTrackName(name: String) {
        trackNameOverride = name.ifBlank { null }
        reviewState?.let { reviewState = it.copy(trackName = name.ifBlank { defaultTrackName }) }
    }

    private fun sourceMetadataFor(samples: List<LocationSample>): SourceMetadata {
        val source = samples.firstOrNull()?.source ?: LocationSource.Simulated
        val simulated = source == LocationSource.Simulated
        return SourceMetadata(
            source = source,
            isSimulated = simulated,
            label = if (simulated) "Demo" else null,
        )
    }

    private fun loadSavedTracksFromStore(): List<Track> {
        val seen = mutableSetOf<String>()
        return store.readIndex().rows
            .filter { it.type == ReviewEntryType.Track }
            .sortedBy { it.createdAtEpochMillis }
            .mapNotNull { row ->
                if (!seen.add(row.id)) {
                    null
                } else {
                    when (val result = store.loadTrack(row.id)) {
                        is LoadResult.Loaded -> result.value.track
                        LoadResult.NotFound -> null
                        is LoadResult.Corrupt -> null
                    }
                }
            }
    }

    private fun TrackReviewState.withRecommendedTimingLines(): TrackReviewState {
        val ref = extraction.referenceLine ?: return this
        val recommended = recommendedTimingLines(ref) ?: return this
        return copy(startFinish = recommended.first, finishLine = recommended.second)
    }

    private fun recommendedTimingLines(
        referenceLine: com.huanfuli.lapsight.shared.track.TrackReferenceLine,
    ): Pair<StartFinishLineDto, StartFinishLineDto?>? =
        if (referenceLine.isClosed) {
            val line = StartFinishRecommender.recommendCircuit(referenceLine)?.line
                ?: defaultStartFinishLine(referenceLine)
                ?: return null
            line to null
        } else {
            val lines = StartFinishRecommender.recommendPointToPoint(referenceLine) ?: return null
            lines.start.line to lines.finish.line
        }

    private fun defaultStartFinishLine(referenceLine: com.huanfuli.lapsight.shared.track.TrackReferenceLine): StartFinishLineDto? =
        when (val path = ClosedReferencePath.fromReferenceLine(referenceLine)) {
            is ClosedReferencePathResult.Loaded ->
                CourseGeometryBuilder.buildStartFinishLine(path.path, progress = 0.0)
            is ClosedReferencePathResult.Rejected -> null
        }
}

/**
 * Default clock for the controller. Indirection so tests can inject a fixed
 * `now` without depending on the platform actual at construction time.
 */
private fun nowEpochMillisSafe(): Long = try {
    nowEpochMillis()
} catch (e: Throwable) {
    0L
}
