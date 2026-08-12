package com.huanfuli.lapsight.shared.session

import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.SimulatedGpsProvider
import com.huanfuli.lapsight.shared.fixtures.GpsFixtureLibrary
import com.huanfuli.lapsight.shared.lap.LapEngineConfig
import com.huanfuli.lapsight.shared.lap.ReplayFixtures
import com.huanfuli.lapsight.shared.session.SessionControllerTest.TestTrackFactory.savedTrackWithStartFinish
import com.huanfuli.lapsight.shared.storage.InMemorySessionStore
import com.huanfuli.lapsight.shared.storage.LoadResult
import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.CurrentTrackSelection
import com.huanfuli.lapsight.shared.track.Track
import com.huanfuli.lapsight.shared.track.TrackMarkingSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 0 (Plan 03-06 Task 1) coverage for the formal timing-session lifecycle.
 *
 * Asserts the Phase 3 product invariants D-13..D-20, SESS-01, and the
 * ghost-candidate boundary D-43:
 *  - Start Timing is blocked without a saved Track with confirmed start/finish (D-19).
 *  - A saved Track creates an active draft; samples feed the existing LapEngine; raw
 *    samples/laps/sector events checkpoint into the draft (D-13); Stop transitions to
 *    stoppedPendingSave (D-14).
 *  - Save promotes a stopped draft into Review history; Discard removes the draft
 *    payload/index entries and never enters history (D-14, D-16).
 *  - GhostCandidate derivation ignores simulated sessions for the real per-track best
 *    (D-20, D-43).
 */
class SessionControllerTest {

    private val store = InMemorySessionStore()
    private val app = AppMetadata(appVersion = "0.3.0", platform = "test")

    private fun controller(
        source: LocationSource = LocationSource.Simulated,
    ): SessionController = SessionController(
        store = store,
        appMetadata = app,
        engineConfig = LapEngineConfig.lenientForTests(),
        now = { 1_700_000_000_000L },
        sourceForTrack = { _ ->
            SourceMetadata(
                source = source,
                isSimulated = source == LocationSource.Simulated,
                label = if (source == LocationSource.Simulated) "Demo" else null,
            )
        },
    )

    private fun saveTrackWithStartFinish(
        source: LocationSource = LocationSource.Simulated,
    ): Track {
        val track = savedTrackWithStartFinish(source)
        val marking = TestTrackFactory.markingFor(track)
        store.saveTrackBundle(track, marking, app)
        return track
    }

    // --- Test 1 (D-19): Start Timing blocked without a saved Track -------------

    @Test
    fun startTimingWithoutSavedTrackReturnsBlockedStateWithExactCopy() {
        val controller = controller()
        val result = controller.startTiming(trackId = "does-not-exist")
        assertTrue(result is StartTimingResult.Blocked)
        assertEquals(
            "Mark a track first. Timing needs a saved start/finish line.",
            result.message,
        )
        // No draft was created.
        assertNull(controller.snapshot().activeDraft)
    }

    @Test
    fun startTimingWithSavedTrackButNoStartFinishReturnsBlockedState() {
        val controller = controller()
        val track = TestTrackFactory.savedTrackWithoutStartFinish()
        store.saveTrackBundle(track, TestTrackFactory.markingFor(track), app)

        val result = controller.startTiming(track.id)

        assertTrue(result is StartTimingResult.Blocked)
        assertEquals(
            "Mark a track first. Timing needs a saved start/finish line.",
            result.message,
        )
        assertNull(controller.snapshot().activeDraft)
    }

    // --- Test 2 (D-13, D-14): Start → active draft → checkpoint → stoppedPendingSave

    @Test
    fun startTimingWithSavedTrackCreatesActiveDraftFeedsLapEngineAndCheckpoints() {
        val track = saveTrackWithStartFinish()
        val controller = controller()

        val result = controller.startTiming(track.id)

        assertTrue(result is StartTimingResult.Started, "expected Started but was $result")
        val snap = controller.snapshot()
        val draft = snap.activeDraft
        assertNotNull(draft, "an active draft must exist after start")
        assertEquals(TimingDraftState.ActiveDraft, draft.state)
        assertEquals(track.id, draft.trackId)

        // Feed the canonical multi-lap fixture through the recorder so laps +
        // sectors are produced against the saved Track's start/finish + sectors
        // (which derive from ReplayFixtures.DEMO_COURSE). Raw samples/laps/
        // sector events must checkpoint continuously (D-13).
        val recorder = controller.recorderForTest() ?: error("recorder must be available after start")
        val samples = ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L, 36_000L))
        samples.forEach { recorder.onSample(it) }

        val afterFeed = controller.snapshot().activeDraft
        assertNotNull(afterFeed)
        assertTrue(afterFeed.checkpointedSampleCount > 0, "raw samples must checkpoint continuously")
        assertTrue(afterFeed.checkpointedLapCount >= 1, "completed laps must checkpoint")
        assertTrue(afterFeed.checkpointedSectorEventCount >= 1, "sector events must checkpoint")

        // Stop transitions to stoppedPendingSave (D-14). Save/Discard is explicit.
        controller.stop()
        val stopped = controller.snapshot().activeDraft
        assertNotNull(stopped)
        assertEquals(TimingDraftState.StoppedPendingSave, stopped.state)
    }

    @Test
    fun pauseDropsSamplesAndResumeExcludesPausedDuration() {
        val track = saveTrackWithStartFinish()
        val controller = controller()
        assertIs<StartTimingResult.Started>(controller.startTiming(track.id))
        val source = ReplayFixtures.multiLapLoop(listOf(40_000L)).first()

        controller.ingestSample(source.copy(elapsedMillis = 0L))
        controller.pause()
        controller.ingestSample(source.copy(elapsedMillis = 60_000L))
        controller.resume()
        controller.ingestSample(source.copy(elapsedMillis = 61_000L))
        controller.ingestSample(source.copy(elapsedMillis = 62_000L))

        val run = controller.timingRunSnapshot()
        assertEquals(3, run.checkpointedSampleCount)
        assertEquals(1_000L, run.sessionElapsedMillis)
    }

    // --- Test 3 (D-14, D-16): Save → Review history; Discard → no history ------

    @Test
    fun savePromotesStoppedDraftToReviewHistory() {
        val track = saveTrackWithStartFinish()
        val controller = controller()
        val provider = SimulatedGpsProvider(scenarioId = GpsFixtureLibrary.CLEAN_10_LOOP)
        controller.startTiming(track.id)
        val recorder = controller.recorderForTest()!!
        provider.start(); repeat(480) { provider.nextSample()?.let { recorder.onSample(it) } }
        controller.stop()

        val saved = controller.saveStoppedDraft()

        assertTrue(saved is SaveDraftResult.Saved)
        val index = store.readIndex()
        assertTrue(
            index.rows.any { it.id == saved.sessionId && it.type == com.huanfuli.lapsight.shared.track.ReviewEntryType.TimingSession },
            "saved session must appear in Review history",
        )
        // After save there is no active/unfinished draft.
        assertNull(controller.snapshot().activeDraft)
    }

    @Test
    fun discardRemovesDraftPayloadAndIndexEntriesAndNeverEntersHistory() {
        val track = saveTrackWithStartFinish()
        val controller = controller()
        val provider = SimulatedGpsProvider(scenarioId = GpsFixtureLibrary.CLEAN_10_LOOP)
        controller.startTiming(track.id)
        val recorder = controller.recorderForTest()!!
        provider.start(); repeat(480) { provider.nextSample()?.let { recorder.onSample(it) } }
        controller.stop()

        controller.discardDraft()

        val index = store.readIndex()
        assertFalse(
            index.rows.any { it.type == com.huanfuli.lapsight.shared.track.ReviewEntryType.TimingSession },
            "discarded drafts must NOT appear in Review history (D-16)",
        )
        assertNull(controller.snapshot().activeDraft, "draft cleared after discard")
    }

    // --- Test 6 (D-20, D-43): ghost-candidate boundary --------------------------

    @Test
    fun ghostCandidateExcludesSimulatedSessionsFromRealPerTrackBest() {
        val track = saveTrackWithStartFinish(source = LocationSource.Simulated)
        val controller = controller(source = LocationSource.Simulated)
        val samples = ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L, 36_000L))
        controller.startTiming(track.id)
        val recorder = controller.recorderForTest()!!
        samples.forEach { recorder.onSample(it) }
        controller.stop()
        val saved = controller.saveStoppedDraft() as SaveDraftResult.Saved

        // Simulated sessions can be saved for UAT (D-43)...
        val index = store.readIndex()
        assertTrue(index.rows.any { it.id == saved.sessionId })

        // ...but the real per-track ghost candidate MUST exclude them (D-20, D-43).
        val ghost = store.ghostCandidateForTrack(track.id)
        assertNull(ghost, "simulated sessions must not become real ghost candidates")
    }

    @Test
    fun ghostCandidateReturnsFastestValidLapFromRealSessionsOnly() {
        val realTrack = savedTrackWithStartFinish(source = LocationSource.PhoneGps)
        store.saveTrackBundle(realTrack, TestTrackFactory.markingFor(realTrack, source = LocationSource.PhoneGps), app)
        val controller = controller(source = LocationSource.PhoneGps)

        // Use the canonical multi-lap loop fixture to produce real completed laps.
        val course = ReplayFixtures.DEMO_COURSE
        val samples = ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L, 36_000L))
        // Map fixture samples onto the saved real track's start/finish line so the
        // LapEngine's derived CourseDefinition lines up. We do this by feeding the
        // samples directly through the recorder (the recorder derives CourseDefinition
        // from the saved Track's start/finish + sectors).
        controller.startTiming(realTrack.id)
        val recorder = controller.recorderForTest()!!
        samples.forEach { recorder.onSample(it) }
        controller.stop()
        val saved = controller.saveStoppedDraft() as SaveDraftResult.Saved

        val ghost = store.ghostCandidateForTrack(realTrack.id)
        assertNotNull(ghost, "a real (non-simulated) saved session must yield a ghost candidate")
        assertEquals(realTrack.id, ghost.trackId)
        assertTrue(ghost.lapDurationMillis > 0L)
        // Best lap should match the fastest completed lap from the saved session.
        val savedPayload = store.loadTimingSession(saved.sessionId)
        assertTrue(savedPayload is com.huanfuli.lapsight.shared.storage.LoadResult.Loaded<*>)
        @Suppress("UNCHECKED_CAST")
        val payload = (savedPayload as com.huanfuli.lapsight.shared.storage.LoadResult.Loaded<TimingSessionPayloadV1>).value
        assertEquals(payload.laps.minOf { it.durationMillis }, ghost.lapDurationMillis)
    }

    // --- SC-03 / SC-04 (D-18, D-21): selected Course Direction drives Timing ------

    @Test
    fun recordedSelectionCompletesTheForwardReplay() {
        val track = saveTrackWithStartFinish()
        // Recorded is the default, but make the selection explicit for clarity.
        store.setCurrentSelection(
            CurrentTrackSelection(profileId = track.id, direction = CourseDirection.Recorded),
        )
        val controller = controller()
        assertIs<StartTimingResult.Started>(controller.startTiming(track.id))
        val recorder = controller.recorderForTest()!!

        // The canonical multi-lap loop drives the recorded (eastbound) direction.
        ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L)).forEach { recorder.onSample(it) }
        controller.stop()

        assertTrue(recorder.lapCount >= 1, "the recorded direction accepts the forward replay")
        val saved = controller.saveStoppedDraft() as SaveDraftResult.Saved
        val payload = (store.loadTimingSession(saved.sessionId) as LoadResult.Loaded<TimingSessionPayloadV1>).value
        assertEquals(CourseDirection.Recorded, payload.session.direction)
    }

    @Test
    fun reverseSelectionRejectsTheForwardReplayAndSnapshotsDirection() {
        val track = saveTrackWithStartFinish()
        // Persist Reverse as the current selection for this Track.
        store.setCurrentSelection(
            CurrentTrackSelection(profileId = track.id, direction = CourseDirection.Reverse),
        )
        val controller = controller()
        assertIs<StartTimingResult.Started>(controller.startTiming(track.id))
        val recorder = controller.recorderForTest()!!

        // Feed the SAME forward (eastbound) replay. Under the Reverse configuration the
        // accepted approach side is flipped, so every eastbound start/finish crossing is
        // the wrong way and no lap is ever completed (SC-04, D-21).
        ReplayFixtures.multiLapLoop(listOf(40_000L, 32_000L)).forEach { recorder.onSample(it) }
        controller.stop()

        val draft = controller.snapshot().activeDraft
        assertNotNull(draft)
        assertEquals(0, draft.checkpointedLapCount, "Reverse rejects the opposite-direction replay")

        // Direction is snapshotted into the persisted session/draft (D-14, D-18).
        val saved = controller.saveStoppedDraft() as SaveDraftResult.Saved
        val payload = (store.loadTimingSession(saved.sessionId) as LoadResult.Loaded<TimingSessionPayloadV1>).value
        assertEquals(CourseDirection.Reverse, payload.session.direction)
    }

    object TestTrackFactory {
        fun savedTrackWithStartFinish(source: LocationSource = LocationSource.Simulated): Track {
            val sf = ReplayFixtures.DEMO_COURSE.startFinish
            val sfDto = com.huanfuli.lapsight.shared.track.StartFinishLineDto(
                pointA = com.huanfuli.lapsight.shared.session.GeoPointDto(
                    latitude = sf.pointA.latitude,
                    longitude = sf.pointA.longitude,
                ),
                pointB = com.huanfuli.lapsight.shared.session.GeoPointDto(
                    latitude = sf.pointB.latitude,
                    longitude = sf.pointB.longitude,
                ),
            )
            val sectorDtos = ReplayFixtures.DEMO_COURSE.orderedSectors.map {
                com.huanfuli.lapsight.shared.track.SectorLineDto(
                    id = it.id,
                    name = it.name,
                    order = it.order,
                    pointA = com.huanfuli.lapsight.shared.session.GeoPointDto(
                        latitude = it.pointA.latitude,
                        longitude = it.pointA.longitude,
                    ),
                    pointB = com.huanfuli.lapsight.shared.session.GeoPointDto(
                        latitude = it.pointB.latitude,
                        longitude = it.pointB.longitude,
                    ),
                )
            }
            return Track(
                id = "track-${source.name.lowercase()}-1",
                name = "Test Oval ${source.name}",
                createdAtEpochMillis = 1_000L,
                sourceMarkingSessionId = "mark-${source.name.lowercase()}-1",
                source = SourceMetadata(
                    source = source,
                    isSimulated = source == LocationSource.Simulated,
                    label = if (source == LocationSource.Simulated) "Demo" else null,
                ),
                referenceLine = null,
                startFinish = sfDto,
                sectors = sectorDtos,
            )
        }

        fun savedTrackWithoutStartFinish(source: LocationSource = LocationSource.Simulated): Track =
            savedTrackWithStartFinish(source).copy(
                id = "track-nosf-${source.name.lowercase()}-1",
                startFinish = null,
            )

        fun markingFor(track: Track, source: LocationSource = track.source.source): TrackMarkingSession =
            TrackMarkingSession(
                id = track.sourceMarkingSessionId ?: "mark-${track.id}",
                createdAtEpochMillis = track.createdAtEpochMillis,
                source = SourceMetadata(
                    source = source,
                    isSimulated = source == LocationSource.Simulated,
                    label = if (source == LocationSource.Simulated) "Demo" else null,
                ),
                samples = emptyList(),
            )
    }
}
