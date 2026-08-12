package com.huanfuli.lapsight.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.huanfuli.lapsight.shared.track.CourseDirection

class HudRemoteProtocolTest {
    @Test
    fun commandsExpressUserIntentInsteadOfPhoneComputedState() {
        assertEquals("LS1,C,STATE_REQUEST\n", HudRemoteCommand.RequestState.toWireCommand())
        assertEquals("LS1,C,MARK_START\n", HudRemoteCommand.MarkStart.toWireCommand())
        assertEquals("LS1,C,MARK_STOP\n", HudRemoteCommand.MarkStop.toWireCommand())
        assertEquals("LS1,C,MARK_LOG_META\n", HudRemoteCommand.MarkLogMetadata.toWireCommand())
        assertEquals(
            "LS1,C,MARK_LOG_READ,192,96\n",
            HudRemoteCommand.MarkLogRead(offset = 192).toWireCommand(),
        )
        assertEquals(
            "LS1,C,COURSE_BEGIN,5,3610A686\n",
            HudRemoteCommand.CourseBegin(5, 0x3610A686u).toWireCommand(),
        )
        assertEquals(
            "LS1,C,COURSE_DATA,0,5,3610A686,aGVsbG8=\n",
            HudRemoteCommand.CourseWrite(0, "hello".encodeToByteArray()).toWireCommand(),
        )
        assertEquals("LS1,C,COURSE_COMMIT\n", HudRemoteCommand.CourseCommit.toWireCommand())
        assertEquals("LS1,C,COURSE_CLEAR\n", HudRemoteCommand.CourseClear.toWireCommand())
        assertEquals("LS1,C,TIMING_START\n", HudRemoteCommand.TimingStart.toWireCommand())
        assertEquals("LS1,C,TIMING_PAUSE\n", HudRemoteCommand.TimingPause.toWireCommand())
        assertEquals("LS1,C,TIMING_RESUME\n", HudRemoteCommand.TimingResume.toWireCommand())
        assertEquals("LS1,C,TIMING_STOP\n", HudRemoteCommand.TimingStop.toWireCommand())
        assertEquals(
            "LS1,C,DISPLAY_PAGE,COURSE\n",
            HudRemoteCommand.DisplayPage(HudDisplayPage.Course).toWireCommand(),
        )
        assertEquals("LS1,C,TIMING_LOG_META\n", HudRemoteCommand.TimingLogMetadata.toWireCommand())
        assertEquals(
            "LS1,C,TIMING_LOG_READ,7,192,96\n",
            HudRemoteCommand.TimingLogRead(sessionId = 7, offset = 192).toWireCommand(),
        )
        assertEquals(
            "LS1,C,TIMING_LOG_ACK,7,3610A686\n",
            HudRemoteCommand.TimingLogAcknowledge(7, 0x3610A686u).toWireCommand(),
        )
    }

    @Test
    fun parsesAuthoritativeHudMarkingSnapshot() {
        assertEquals(
            HudRemoteMessage.State(
                HudRuntimeState(
                    phase = HudSessionPhase.Marking,
                    elapsedMillis = 12_345L,
                    pointCount = 87,
                    completedLoops = 1,
                    referenceReady = true,
                ),
            ),
            parseHudRemoteMessage("LS1,S,MARKING,12345,87,1,1,0"),
        )
    }

    @Test
    fun parsesHudDisplayPageAndKeepsLegacyStateCompatible() {
        assertEquals(
            HudRemoteMessage.State(
                HudRuntimeState(
                    phase = HudSessionPhase.Timing,
                    elapsedMillis = 12_345L,
                    pointCount = 2,
                    completedLoops = 1,
                    referenceReady = true,
                    displayPage = HudDisplayPage.History,
                ),
            ),
            parseHudRemoteMessage("LS1,S,TIMING,12345,2,1,1,0,HISTORY"),
        )
        assertEquals(
            null,
            (parseHudRemoteMessage("LS1,S,IDLE,0,0,0,0,0") as HudRemoteMessage.State).value.displayPage,
        )
        assertNull(parseHudRemoteMessage("LS1,S,IDLE,0,0,0,0,0,UNKNOWN"))
    }

    @Test
    fun parsesAcknowledgementAndRejectsMalformedState() {
        assertEquals(
            HudRemoteMessage.Acknowledgement("MARK_START", "OK"),
            parseHudRemoteMessage("LS1,A,MARK_START,OK\n"),
        )
        assertNull(parseHudRemoteMessage("LS1,S,MARKING,-1,87,1,1,0"))
        assertNull(parseHudRemoteMessage("\$GNRMC,not-a-control-frame"))
    }

    @Test
    fun parsesAndValidatesMarkLogFrames() {
        assertEquals(
            HudRemoteMessage.MarkLogMetadata(5L, 0x3610A686u, "/LS0007.CSV"),
            parseHudRemoteMessage("LS1,L,META,5,3610A686,/LS0007.CSV"),
        )
        assertEquals(
            HudRemoteMessage.MarkLogData(0L, "hello".encodeToByteArray(), 0x3610A686u),
            parseHudRemoteMessage("LS1,L,DATA,0,5,3610A686,aGVsbG8="),
        )
        assertNull(parseHudRemoteMessage("LS1,L,DATA,0,5,00000000,aGVsbG8="))
    }

    @Test
    fun parsesCourseUploadProgress() {
        assertEquals(
            HudRemoteMessage.CourseUploadProgress(192),
            parseHudRemoteMessage("LS1,U,COURSE,192"),
        )
        assertNull(parseHudRemoteMessage("LS1,U,COURSE,-1"))
    }

    @Test
    fun parsesAuthoritativeHudLapEvent() {
        assertEquals(
            HudRemoteMessage.LapCompleted(3, 92_340L, 91_800L),
            parseHudRemoteMessage("LS1,T,LAP,3,92340,91800"),
        )
        assertEquals(
            HudRemoteMessage.TimingData(120_000, 3, 28_200, 91_800, 90_500, false),
            parseHudRemoteMessage("LS1,T,DATA,120000,3,28200,91800,90500,0"),
        )
        assertEquals(
            HudRemoteMessage.TimingData(120_000, 3, 28_200, 91_800, 90_500, false, 2, 3, -218),
            parseHudRemoteMessage("LS1,T,DATA,120000,3,28200,91800,90500,0,2,3,-218"),
        )
        assertEquals(
            HudRemoteMessage.SectorCompleted(3, 2, 31_200, 30_900, 300),
            parseHudRemoteMessage("LS1,T,SECTOR,3,2,31200,30900,+300"),
        )
        assertEquals(
            HudRemoteMessage.SectorCompleted(1, 1, 30_000, 30_000, null),
            parseHudRemoteMessage("LS1,T,SECTOR,1,1,30000,30000,N"),
        )
    }

    @Test
    fun parsesAndValidatesRecoverableTimingLogFrames() {
        assertEquals(
            HudRemoteMessage.TimingLogMetadata(
                sessionId = 7,
                sizeBytes = 5,
                crc32 = 0x3610A686u,
                complete = true,
                durationMillis = 120_000,
                lapCount = 2,
                bestLapMillis = 59_500,
                path = "/TR0007.CSV",
            ),
            parseHudRemoteMessage("LS1,R,META,7,5,3610A686,1,120000,2,59500,/TR0007.CSV"),
        )
        assertEquals(
            HudRemoteMessage.TimingLogData(7, 0, "hello".encodeToByteArray(), 0x3610A686u),
            parseHudRemoteMessage("LS1,R,DATA,7,0,5,3610A686,aGVsbG8="),
        )
        assertNull(parseHudRemoteMessage("LS1,R,DATA,7,0,5,00000000,aGVsbG8="))
        assertEquals(
            HudRemoteMessage.TimingLogMetadata(
                sessionId = 8,
                sizeBytes = 5,
                crc32 = 0x3610A686u,
                complete = false,
                durationMillis = 90_000,
                lapCount = 1,
                bestLapMillis = 60_000,
                path = "/TR0008.CSV",
                profileId = "profile-1",
                revisionId = "revision-2",
                geometryCompatibilityId = "geometry-1",
                direction = CourseDirection.Reverse,
            ),
            parseHudRemoteMessage(
                "LS1,R,META,8,5,3610A686,0,90000,1,60000,/TR0008.CSV," +
                    "${encodeBase64("profile-1".encodeToByteArray())}," +
                    "${encodeBase64("revision-2".encodeToByteArray())}," +
                    "${encodeBase64("geometry-1".encodeToByteArray())},V",
            ),
        )
    }

    @Test
    fun parsesTimingCsvFixesLapsAndCompleteSectors() {
        val csv = """record_type,session_elapsed_ms,lap_number,lap_elapsed_ms,sector_number,duration_ms,best_ms,delta_ms,latitude,longitude,speed_kmh,satellites,hdop
START,0,1,0,0,0,0,N,N,N,N,N,N
FIX,1000,1,1000,1,0,0,N,39.8121000,-86.1062000,36.000,18,0.70
SECTOR,30000,1,30000,1,30000,30000,N,N,N,N,N,N
LAP,60000,1,60000,0,60000,60000,N,N,N,N,N,N
"""

        val result = parseHudTimingCsv(csv)

        assertEquals(1, result.samples.size)
        assertEquals(10.0, result.samples.single().speedMetersPerSecond)
        assertEquals(HudTimingLogLap(1, 60_000, 60_000), result.laps.single())
        assertEquals(HudTimingLogSector(1, 1, 30_000, 30_000, null), result.sectors.single())
    }

    @Test
    fun convertsHudCsvToExternalGnssSamplesAndDropsNoFixAndDuplicateRows() {
        val csv = """elapsed_ms,utc_date,utc_time,latitude,longitude,speed_kmh,satellites,hdop,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,detected_loops,marking_elapsed_ms,event
0,0,0,0.0000000,0.0000000,0.000,0,0.00,0,0,0,0,0,0,0,0,TRACK_MARK_START
100,20260801,120000000,39.8121000,-86.1062000,36.000,18,0.70,0,0,1,0,0,0,0,100,
100,20260801,120000000,39.8121000,-86.1062000,36.000,18,0.70,0,0,1,0,0,0,0,100,LAP_MARK
200,20260801,120000100,39.8122000,-86.1061000,18.000,17,0.80,0,0,1,0,0,0,0,200,
"""

        val samples = parseHudMarkingCsv(csv)

        assertEquals(2, samples.size)
        assertEquals(LocationSource.ExternalGnss, samples.first().source)
        assertEquals(10.0, samples.first().speedMetersPerSecond)
        assertEquals(18, samples.first().satellitesInUse)
        assertEquals(200L, samples.last().elapsedMillis)
        assertTrue(samples.all { it.horizontalAccuracyMeters == null })
    }
}
