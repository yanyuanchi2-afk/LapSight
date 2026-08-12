package com.huanfuli.lapsight.shared.ui

import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.LocationSampleProvider
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.SimulatedGpsProvider
import com.huanfuli.lapsight.shared.fixtures.GpsFixtureLibrary
import com.huanfuli.lapsight.shared.session.AppMetadata
import com.huanfuli.lapsight.shared.session.SessionControllerTest.TestTrackFactory
import com.huanfuli.lapsight.shared.storage.InMemorySessionStore
import com.huanfuli.lapsight.shared.storage.SchemaMigrations
import com.huanfuli.lapsight.shared.track.ClosedReferencePath
import com.huanfuli.lapsight.shared.track.ClosedReferencePathResult
import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.CourseGeometryBuilder
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.CurrentTrackSelection
import com.huanfuli.lapsight.shared.track.ReferenceLineExtractor
import com.huanfuli.lapsight.shared.track.StartFinishLineDto
import com.huanfuli.lapsight.shared.track.Track
import com.huanfuli.lapsight.shared.track.TrackPayloadV1
import com.huanfuli.lapsight.shared.track.TrackReviewDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 0 (Plan 03-05 Task 1) coverage for [DriveMarkingController].
 *
 * Exercises the marking-capture state machine in plain Kotlin (no Compose):
 * the controller collects samples from a [SimulatedGpsProvider], stops into a
 * [com.huanfuli.lapsight.shared.track.TrackReviewState] produced by
 * [ReferenceLineExtractor], and keeps Start Timing blocked until a saved Track
 * has a confirmed start/finish line (D-19).
 */
class DriveMarkingControllerTest {

    private val store = InMemorySessionStore()
    private val app = AppMetadata(appVersion = "0.3.0", platform = "test")

    private fun controller(scenarioId: String = GpsFixtureLibrary.CLEAN_10_LOOP): DriveMarkingController =
        DriveMarkingController(
            provider = SimulatedGpsProvider(scenarioId),
            store = store,
            appMetadata = app,
            now = { 1_700_000_000_000L },
        )

    private fun DriveMarkingController.captureSamples(count: Int) {
        repeat(count) { tick() }
    }

    /**
     * Persists [track] as its V2 profile (profileId == track.id) and optionally makes
     * it the explicit current selection. Reuses the canonical migration mapping so the
     * seeded profile is a real, validatable aggregate.
     */
    private fun seedProfile(track: Track, select: Boolean) {
        val profile = SchemaMigrations.migrateTrack(TrackPayloadV1(track = track, app = app))
        store.saveProfile(profile, app)
        if (select) {
            store.setCurrentSelection(CurrentTrackSelection(profileId = profile.profileId))
        }
    }

    @Test
    fun beginMarkingTransitionsToCapturingAndDoesNotRequireStartAtFinish() {
        val controller = controller()
        // The clean fixture starts mid-oval; marking must not require starting at
        // the (yet-unknown) start/finish line (D-07).
        controller.beginMarking()
        assertEquals(DriveMarkingPhase.Capturing, controller.snapshot().phase)
        controller.captureSamples(2400) // ~10 loops of the clean fixture
        controller.stopMarking()
        val snap = controller.snapshot()
        assertEquals(DriveMarkingPhase.Review, snap.phase)
        val review = snap.reviewState
        assertNotNull(review)
        // Extraction produced a reference-ready capture via ReferenceLineExtractor.
        assertTrue(review.isReferenceReady, "clean capture should be reference-ready")
        assertNotNull(review.extraction.referenceLine)
        // Marking never carries lap times (D-08): the extraction has no laps.
        // (ReferenceLineExtraction has no lap field by construction; asserted via type.)
        assertEquals(LocationSource.Simulated, review.extraction.markingSession.source.source)
    }

    @Test
    fun importedHudMarkingReplacesPartialPhoneCaptureBeforeReview() {
        val controller = controller()
        controller.beginMarking()
        controller.captureSamples(3)
        val imported = listOf(
            LocationSample(0, 39.0, -86.0, null, 10.0, null, null, LocationSource.ExternalGnss),
            LocationSample(100, 39.0001, -86.0001, null, 11.0, null, null, LocationSource.ExternalGnss),
            LocationSample(200, 39.0002, -86.0002, null, 12.0, null, null, LocationSource.ExternalGnss),
            LocationSample(300, 39.0003, -86.0003, null, 13.0, null, null, LocationSource.ExternalGnss),
        )

        controller.reviewImportedMarking(imported)

        val review = assertNotNull(controller.snapshot().reviewState)
        assertEquals(DriveMarkingPhase.Review, controller.snapshot().phase)
        assertEquals(imported.size, review.extraction.markingSession.samples.size)
        assertEquals(LocationSource.ExternalGnss, review.extraction.markingSession.source.source)
    }

    @Test
    fun markingATrackSelectsItAsCurrentAndUnblocksTiming() {
        val controller = controller()
        // No saved track yet: Start Timing is blocked with the exact UI-SPEC copy and
        // the snapshot routes to the explicit selector (D-03).
        var snap = controller.snapshot()
        assertFalse(snap.canStartTiming)
        assertTrue(snap.needsTrackSelection, "blocked Timing must route to the selector")
        assertNull(snap.currentTrackName)
        assertEquals(
            "Mark a track first. Timing needs a saved start/finish line.",
            snap.startTimingBlockedReason,
        )

        // Capture + stop into review (ready), but do NOT confirm start/finish yet.
        controller.beginMarking()
        controller.captureSamples(2400)
        controller.stopMarking()
        snap = controller.snapshot()
        assertTrue(snap.reviewState?.canSave == true)
        // Even a ready review is not yet a saved + selected Track.
        assertFalse(snap.canStartTiming)

        // Confirm start/finish from the reference line, then save.
        controller.confirmStartFinish()
        controller.saveTrack()
        snap = controller.snapshot()
        // Saving makes the new Track the EXPLICIT current selection (D-01, D-02), so
        // Timing unblocks against the exact persisted profile — not a newest-ready
        // heuristic.
        assertTrue(snap.canStartTiming, "timing should unblock after the saved track is selected")
        assertFalse(snap.needsTrackSelection)
        assertNull(snap.reviewState, "review cleared after save")
        assertEquals(DriveMarkingPhase.Idle, snap.phase)
        assertEquals(1, snap.savedTrackCount)
        assertEquals("track-1700000000000", snap.timingReadyTrackId)
        assertEquals("Demo Track", snap.timingReadyTrackName)
        assertEquals("Demo Track", snap.currentTrackName)
    }

    @Test
    fun confirmStartFinishUsesPerpendicularClosedPathBoundary() {
        val controller = controller()

        controller.beginMarking()
        controller.captureSamples(2400)
        controller.stopMarking()

        val reviewBefore = controller.snapshot().reviewState
        assertNotNull(reviewBefore)
        val referenceLine = reviewBefore.extraction.referenceLine
        assertNotNull(referenceLine)
        val oldPathSegmentLine = StartFinishLineDto(
            pointA = referenceLine.points[0],
            pointB = referenceLine.points[1],
        )

        controller.confirmStartFinish()

        val startFinish = controller.snapshot().reviewState?.startFinish
        assertNotNull(startFinish)
        val path = ClosedReferencePath.fromReferenceLine(referenceLine)
        assertTrue(path is ClosedReferencePathResult.Loaded)
        val recommendedProgress = path.path.projectGeo(
            com.huanfuli.lapsight.shared.session.GeoPointDto(
                latitude = (startFinish.pointA.latitude + startFinish.pointB.latitude) / 2.0,
                longitude = (startFinish.pointA.longitude + startFinish.pointB.longitude) / 2.0,
            ),
        ).progressMeters
        assertNotEquals(oldPathSegmentLine, startFinish, "timing line must not be the first path segment")
        assertEquals(1, CourseGeometryBuilder.pathCrossingCount(path.path, progress = recommendedProgress))
    }

    @Test
    fun restartFeedForTimingRewindsTheProviderAndClearsProbeSamples() {
        val controller = controller()

        controller.beginMarking()
        controller.captureSamples(32)
        assertTrue(controller.snapshot().feedSampleCount > 1)

        controller.restartFeedForTiming()
        val firstTimingSample = controller.tick().single()

        assertEquals(GpsFixtureLibrary.cleanTenLoop().first(), firstTimingSample)
        assertEquals(1, controller.snapshot().feedSampleCount)
        assertTrue(controller.snapshot().isDemoFeedRunning)
    }

    @Test
    fun phoneGpsProviderPersistsPhoneGpsSourceThroughTheSameInterface() {
        val phoneSamples = GpsFixtureLibrary.cleanTenLoop()
            .map { it.copy(source = LocationSource.PhoneGps) }
        val controller = DriveMarkingController(
            provider = ListLocationSampleProvider(phoneSamples),
            store = InMemorySessionStore(),
            appMetadata = app,
            now = { 1_700_000_000_100L },
        )

        controller.beginMarking()
        controller.captureSamples(2400)
        controller.stopMarking()

        val review = controller.snapshot().reviewState
        assertNotNull(review)
        assertEquals(LocationSource.PhoneGps, review.extraction.markingSession.source.source)
        assertFalse(review.extraction.markingSession.source.isSimulated)

        controller.confirmStartFinish()
        val track = controller.saveTrack()

        assertNotNull(track)
        assertEquals(LocationSource.PhoneGps, track.source.source)
        assertFalse(track.source.isSimulated)
    }

    @Test
    fun externalGnssProviderPersistsExternalSourceThroughTheSameInterface() {
        val externalSamples = GpsFixtureLibrary.cleanTenLoop()
            .map { it.copy(source = LocationSource.ExternalGnss) }
        val controller = DriveMarkingController(
            provider = ListLocationSampleProvider(externalSamples),
            store = InMemorySessionStore(),
            appMetadata = app,
            now = { 1_700_000_000_150L },
        )

        controller.beginMarking()
        controller.captureSamples(2400)
        controller.stopMarking()

        val review = controller.snapshot().reviewState
        assertNotNull(review)
        assertEquals(LocationSource.ExternalGnss, review.extraction.markingSession.source.source)
        assertFalse(review.extraction.markingSession.source.isSimulated)

        controller.confirmStartFinish()
        val track = controller.saveTrack()

        assertNotNull(track)
        assertEquals(LocationSource.ExternalGnss, track.source.source)
        assertFalse(track.source.isSimulated)
    }

    @Test
    fun pointToPointMarkingSavesOpenTrackWithFinishLine() {
        val samples = GpsFixtureLibrary.pointToPointRun()
        val controller = DriveMarkingController(
            provider = ListLocationSampleProvider(samples),
            store = InMemorySessionStore(),
            appMetadata = app,
            now = { 1_700_000_000_200L },
        )

        controller.selectTopology(CourseTopology.PointToPoint)
        controller.beginMarking()
        controller.captureSamples(samples.size)
        controller.stopMarking()

        val review = controller.snapshot().reviewState
        assertNotNull(review)
        assertEquals(CourseTopology.PointToPoint, review.extraction.topology)
        assertTrue(review.canSave)
        assertNotNull(review.startFinish)
        assertNotNull(review.finishLine)

        val track = controller.saveTrack()

        assertNotNull(track)
        assertEquals(CourseTopology.PointToPoint, track.topology)
        assertTrue(track.referenceLine?.isClosed == false)
        assertNotNull(track.finishLine)
    }

    // D-01 / D-03: a persisted explicit selection resolves on a fresh controller, and
    // a newer unselected profile NEVER becomes current (no newest-Track fallback).
    @Test
    fun newControllerResolvesPersistedSelectionAndIgnoresNewerProfile() {
        val selected = TestTrackFactory.savedTrackWithStartFinish()
            .copy(id = "track-selected", name = "Selected Track", createdAtEpochMillis = 1_000L)
        seedProfile(selected, select = true)
        // A newer, timing-ready, but UNSELECTED profile that a newest-ready heuristic
        // would wrongly pick.
        val newer = TestTrackFactory.savedTrackWithStartFinish()
            .copy(id = "track-newer", name = "Newer Track", createdAtEpochMillis = 9_000L)
        seedProfile(newer, select = false)

        val controller = controller()
        val snap = controller.snapshot()

        assertTrue(snap.canStartTiming, "persisted explicit selection should unblock timing")
        assertEquals(selected.id, snap.timingReadyTrackId)
        assertEquals(selected.name, snap.timingReadyTrackName)
        assertEquals(selected.name, snap.currentTrackName)
        // Both active profiles are offered by the selector, latest revision only (D-14).
        assertEquals(
            setOf("track-selected", "track-newer"),
            snap.selectableProfiles.map { it.profileId }.toSet(),
        )
        assertTrue(snap.selectableProfiles.all { it.isTimingReady })
    }

    // D-03 / D-04: with NO selection, Timing stays blocked and routes to the selector
    // even though timing-ready profiles exist. This is the direct replacement for the
    // removed newest-ready regression test — no `maxByOrNull(createdAtEpochMillis)`
    // derivation remains.
    @Test
    fun noCurrentSelectionBlocksTimingEvenWhenTimingReadyProfilesExist() {
        val readyOld = TestTrackFactory.savedTrackWithStartFinish()
            .copy(id = "track-ready-old", name = "Old Ready", createdAtEpochMillis = 1_000L)
        val readyNew = TestTrackFactory.savedTrackWithStartFinish()
            .copy(id = "track-ready-new", name = "New Ready", createdAtEpochMillis = 9_000L)
        seedProfile(readyOld, select = false)
        seedProfile(readyNew, select = false)

        val controller = controller()
        val snap = controller.snapshot()

        // No newest-ready fallback: Timing is blocked and the selector is offered.
        assertFalse(snap.canStartTiming, "no explicit selection must NOT fall back to the newest track")
        assertTrue(snap.needsTrackSelection)
        assertNull(snap.timingReadyTrackId)
        assertNull(snap.currentTrackName)
        assertEquals(
            "Mark a track first. Timing needs a saved start/finish line.",
            snap.startTimingBlockedReason,
        )
        // The selector still lists both active profiles for the user to choose from.
        assertEquals(2, snap.selectableProfiles.size)
    }

    // D-16: the selector offers only ACTIVE profiles; an archived current selection is
    // unavailable and never silently replaced by another profile.
    @Test
    fun archivedCurrentSelectionIsUnavailableAndExcludedFromSelector() {
        val active = TestTrackFactory.savedTrackWithStartFinish()
            .copy(id = "track-active", name = "Active Track", createdAtEpochMillis = 1_000L)
        seedProfile(active, select = false)

        val archivedTrack = TestTrackFactory.savedTrackWithStartFinish()
            .copy(id = "track-archived", name = "Archived Track", createdAtEpochMillis = 2_000L)
        val archivedProfile = SchemaMigrations
            .migrateTrack(TrackPayloadV1(track = archivedTrack, app = app))
            .copy(archivedAtEpochMillis = 3_000L)
        store.saveProfile(archivedProfile, app)
        store.setCurrentSelection(CurrentTrackSelection(profileId = archivedProfile.profileId))

        val controller = controller()
        val snap = controller.snapshot()

        // The archived selection blocks Timing (no fallback to the active profile).
        assertFalse(snap.canStartTiming)
        assertTrue(snap.needsTrackSelection)
        assertNull(snap.timingReadyTrackId)
        // Only the active profile is selectable.
        assertEquals(listOf("track-active"), snap.selectableProfiles.map { it.profileId })
    }

    // SC-03 / D-18: the pre-Timing selector exposes the selected Course Direction and
    // persists an explicit Recorded/Reverse choice against the current Track.
    @Test
    fun selectDirectionPersistsRecordedReverseChoiceForCurrentTrack() {
        val track = TestTrackFactory.savedTrackWithStartFinish()
            .copy(id = "track-dir", name = "Direction Track", createdAtEpochMillis = 1_000L)
        seedProfile(track, select = true)

        val controller = controller()
        // A freshly selected Track defaults to its Recorded direction (D-18).
        assertEquals(CourseDirection.Recorded, controller.snapshot().selectedDirection)

        // Choosing Reverse updates ONLY the direction, keeping the same Track selected.
        controller.selectDirection(CourseDirection.Reverse)
        val snap = controller.snapshot()
        assertEquals(CourseDirection.Reverse, snap.selectedDirection)
        assertEquals(track.id, snap.timingReadyTrackId, "direction change must not drop the Track")

        // The choice survives a relaunch-equivalent fresh controller over the same store.
        assertEquals(CourseDirection.Reverse, controller().snapshot().selectedDirection)
    }

    @Test
    fun stopMarkingOnTooShortCaptureDegradesToNotSaveReadyReview() {
        val controller = controller(scenarioId = GpsFixtureLibrary.NOISE_DRIFT)
        controller.beginMarking()
        controller.captureSamples(5)
        controller.stopMarking()
        val review = controller.snapshot().reviewState
        assertNotNull(review)
        assertFalse(review.canSave, "too-short capture must not be save-ready")
        assertTrue(review.notReadyReasons.isNotEmpty())
        // Save is refused; only Re-record/Discard offered.
        assertFalse(TrackReviewDecision.Save in review.availableDecisions)
        assertTrue(TrackReviewDecision.ReRecord in review.availableDecisions)
        assertTrue(TrackReviewDecision.Discard in review.availableDecisions)
    }

    private class ListLocationSampleProvider(
        private val samples: List<LocationSample>,
    ) : LocationSampleProvider {
        private var index = 0
        private var running = false
        private val cycleMillis: Long = if (samples.size < 2) {
            0L
        } else {
            val span = samples.last().elapsedMillis - samples.first().elapsedMillis
            span + span / (samples.size - 1)
        }

        override val isRunning: Boolean
            get() = running

        override fun start() {
            running = true
        }

        override fun stop() {
            running = false
        }

        override fun reset() {
            running = false
            index = 0
        }

        override fun nextSample(): LocationSample? {
            if (!running || samples.isEmpty()) return null
            val base = samples[index % samples.size]
            val completedCycles = index / samples.size
            index++
            return if (completedCycles == 0) {
                base
            } else {
                base.copy(elapsedMillis = base.elapsedMillis + completedCycles * cycleMillis)
            }
        }
    }
}
