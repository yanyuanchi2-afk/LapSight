package com.huanfuli.lapsight.shared

import com.huanfuli.lapsight.shared.session.GeoPointDto
import com.huanfuli.lapsight.shared.session.SourceMetadata
import com.huanfuli.lapsight.shared.track.CourseSetup
import com.huanfuli.lapsight.shared.track.StartFinishLineDto
import com.huanfuli.lapsight.shared.track.TrackProfile
import com.huanfuli.lapsight.shared.track.TrackReferenceLine
import com.huanfuli.lapsight.shared.track.TrackRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HudCourseBundleTest {
    @Test
    fun buildsVersionedDeterministicCourseWithEndToEndCrc() {
        val points = listOf(
            GeoPointDto(40.0, -86.0),
            GeoPointDto(40.001, -85.999),
            GeoPointDto(40.0, -85.998),
        )
        val profile = TrackProfile(
            profileId = "profile-1",
            name = "Test Oval",
            createdAtEpochMillis = 1L,
            source = SourceMetadata(LocationSource.ExternalGnss, false),
            revisions = listOf(
                TrackRevision(
                    revisionId = "revision-1",
                    ordinal = 1,
                    createdAtEpochMillis = 1L,
                    sourceMarkingSessionId = null,
                    referenceLine = TrackReferenceLine(points, isClosed = true),
                    courseSetup = CourseSetup(
                        startFinish = StartFinishLineDto(points[0], points[1]),
                    ),
                    geometryCompatibilityId = "geometry-1",
                ),
            ),
        )

        val bundle = assertNotNull(buildHudCourseBundle(profile))
        val text = bundle.bytes.decodeToString()

        assertTrue(text.startsWith("LSCB,1,"))
        assertEquals(3, text.lineSequence().count { it.startsWith("P,") })
        assertTrue(text.lineSequence().any { it.startsWith("S,") })
        assertEquals(crc32(bundle.bytes), bundle.crc32)
        assertEquals(bundle.bytes.contentToString(), buildHudCourseBundle(profile)?.bytes?.contentToString())
    }
}
