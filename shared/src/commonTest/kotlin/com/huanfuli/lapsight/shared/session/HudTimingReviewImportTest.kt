package com.huanfuli.lapsight.shared.session

import com.huanfuli.lapsight.shared.HudTimingLog
import com.huanfuli.lapsight.shared.HudTimingLogLap
import com.huanfuli.lapsight.shared.HudTimingLogSector
import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.CourseSetup
import com.huanfuli.lapsight.shared.track.SectorBoundary
import com.huanfuli.lapsight.shared.track.StartFinishLineDto
import com.huanfuli.lapsight.shared.track.TrackProfile
import com.huanfuli.lapsight.shared.track.TrackReferenceLine
import com.huanfuli.lapsight.shared.track.TrackRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HudTimingReviewImportTest {
    @Test
    fun bindsExactRevisionAndPreservesLapAndSectorTimes() {
        val result = assertIs<HudTimingReviewImportResult.Ready>(
            buildHudTimingReviewPayload(
                log = log(),
                profile = profile(),
                app = AppMetadata("test"),
                importedAtEpochMillis = 1_234_567L,
            ),
        )
        val payload = result.payload

        assertEquals("hud-7-3610A686", payload.session.id)
        assertEquals("profile-1", payload.session.trackId)
        assertEquals(CourseDirection.Reverse, payload.session.direction)
        assertEquals("geometry-1", payload.session.courseCompatibilityKey.geometryCompatibilityId)
        assertEquals(LocationSource.ExternalGnss, payload.session.source.source)
        assertEquals(false, payload.session.source.isSimulated)
        assertEquals(LapDto(1, 1_000, 61_000), payload.laps.single())
        assertEquals(
            SectorResultDto(
                lapNumber = 1,
                sectorId = "sector-1",
                sectorOrder = 1,
                startedAtMillis = 1_000,
                endedAtMillis = 31_000,
                durationMillis = 30_000,
                cumulativeSplitMillis = 30_000,
            ),
            payload.sectorResults.single(),
        )
        assertEquals(2, payload.samples.size)
        assertEquals(61_500L, payload.totalDurationMillis)
    }

    @Test
    fun rejectsGeometryIdentityMismatchInsteadOfGuessing() {
        val result = buildHudTimingReviewPayload(
            log = log().copy(geometryCompatibilityId = "other-geometry"),
            profile = profile(),
            app = AppMetadata("test"),
            importedAtEpochMillis = 1L,
        )

        assertIs<HudTimingReviewImportResult.Rejected>(result)
    }

    private fun profile(): TrackProfile {
        val a = GeoPointDto(39.0, -86.0)
        val b = GeoPointDto(39.001, -86.001)
        return TrackProfile(
            profileId = "profile-1",
            name = "Test Track",
            createdAtEpochMillis = 10L,
            source = SourceMetadata(LocationSource.ExternalGnss, false),
            revisions = listOf(
                TrackRevision(
                    revisionId = "revision-2",
                    ordinal = 2,
                    createdAtEpochMillis = 20L,
                    sourceMarkingSessionId = null,
                    referenceLine = TrackReferenceLine(listOf(a, b), isClosed = true),
                    courseSetup = CourseSetup(
                        startFinish = StartFinishLineDto(a, b),
                        sectorsEnabled = true,
                        sectorCount = 2,
                        boundaries = listOf(SectorBoundary("sb-1", 1, a, b, 0.5)),
                    ),
                    geometryCompatibilityId = "geometry-1",
                ),
            ),
        )
    }

    private fun log() = HudTimingLog(
        sessionId = 7L,
        sizeBytes = 1_000L,
        crc32 = 0x3610A686u,
        path = "/TR0007.CSV",
        complete = true,
        durationMillis = 61_500L,
        lapCount = 1,
        bestLapMillis = 60_000L,
        samples = listOf(sample(1_000L), sample(61_000L)),
        laps = listOf(HudTimingLogLap(1, 60_000L, 60_000L, 61_000L)),
        sectors = listOf(HudTimingLogSector(1, 1, 30_000L, 30_000L, null, 31_000L, 30_000L)),
        profileId = "profile-1",
        revisionId = "revision-2",
        geometryCompatibilityId = "geometry-1",
        direction = CourseDirection.Reverse,
    )

    private fun sample(elapsed: Long) = LocationSample(
        elapsedMillis = elapsed,
        latitude = 39.0,
        longitude = -86.0,
        horizontalAccuracyMeters = null,
        speedMetersPerSecond = 10.0,
        headingDegrees = null,
        altitudeMeters = null,
        source = LocationSource.ExternalGnss,
        satellitesInUse = 20,
    )
}
