package com.huanfuli.lapsight.shared.session

import com.huanfuli.lapsight.shared.HudTimingLog
import com.huanfuli.lapsight.shared.LocationSource
import com.huanfuli.lapsight.shared.ghost.CourseCompatibilityKey
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.SectorLineDto
import com.huanfuli.lapsight.shared.track.TrackProfile

/** Result of binding an authoritative HUD log to an exact immutable course revision. */
sealed interface HudTimingReviewImportResult {
    data class Ready(val payload: TimingSessionPayloadV1) : HudTimingReviewImportResult
    data class Rejected(val reason: String) : HudTimingReviewImportResult
}

/**
 * Converts a CRC-verified HUD timing log into the same canonical payload used by
 * phone-only sessions. No geometry is guessed: profile, revision, geometry
 * compatibility id, and direction must all be present and agree exactly.
 */
fun buildHudTimingReviewPayload(
    log: HudTimingLog,
    profile: TrackProfile,
    app: AppMetadata,
    importedAtEpochMillis: Long,
): HudTimingReviewImportResult {
    val profileId = log.profileId
        ?: return HudTimingReviewImportResult.Rejected("HUD result has no course profile identity")
    val revisionId = log.revisionId
        ?: return HudTimingReviewImportResult.Rejected("HUD result has no course revision identity")
    val geometryId = log.geometryCompatibilityId
        ?: return HudTimingReviewImportResult.Rejected("HUD result has no course geometry identity")
    val direction = log.direction
        ?: return HudTimingReviewImportResult.Rejected("HUD result has no course direction")
    if (profile.profileId != profileId) {
        return HudTimingReviewImportResult.Rejected("HUD result belongs to a different course profile")
    }
    val revision = profile.revisions.firstOrNull { it.revisionId == revisionId }
        ?: return HudTimingReviewImportResult.Rejected("Matching course revision is unavailable")
    if (revision.geometryCompatibilityId != geometryId) {
        return HudTimingReviewImportResult.Rejected("Course geometry identity does not match the HUD result")
    }
    val setup = revision.courseSetup
    val startFinish = setup.startFinish
        ?: return HudTimingReviewImportResult.Rejected("Matching course revision has no start line")
    if (setup.topology == CourseTopology.PointToPoint && setup.finishLine == null) {
        return HudTimingReviewImportResult.Rejected("Matching point-to-point course has no finish line")
    }
    if (log.samples.isEmpty()) {
        return HudTimingReviewImportResult.Rejected("HUD result contains no valid GNSS samples")
    }

    val samples = log.samples.map { it.toDto() }
    val source = SourceMetadata(
        source = LocationSource.ExternalGnss,
        isSimulated = false,
        label = "LapSight HUD",
    )
    val session = TimingSession(
        id = "hud-${log.sessionId}-${log.crc32.toString(16).uppercase().padStart(8, '0')}",
        trackId = profile.profileId,
        trackName = profile.name,
        createdAtEpochMillis = importedAtEpochMillis,
        source = source,
        startFinish = startFinish,
        finishLine = setup.finishLine,
        topology = setup.topology,
        sectors = setup.boundaries.sortedBy { it.order }.map { boundary ->
            SectorLineDto(
                id = boundary.id,
                name = "Sector ${boundary.order}",
                order = boundary.order,
                pointA = boundary.pointA,
                pointB = boundary.pointB,
            )
        },
        direction = direction,
        courseCompatibilityKey = CourseCompatibilityKey(
            profileId = profile.profileId,
            geometryCompatibilityId = geometryId,
            direction = direction,
            isSimulated = false,
        ),
    )
    val laps = log.laps.sortedBy { it.lapNumber }.map { lap ->
        LapDto(
            lapNumber = lap.lapNumber,
            startMillis = lap.completedAtMillis - lap.durationMillis,
            endMillis = lap.completedAtMillis,
        )
    }
    val sectorResults = log.sectors
        .sortedWith(compareBy({ it.lapNumber }, { it.sectorNumber }))
        .map { sector ->
            SectorResultDto(
                lapNumber = sector.lapNumber,
                sectorId = "sector-${sector.sectorNumber}",
                sectorOrder = sector.sectorNumber,
                startedAtMillis = sector.crossingMillis - sector.durationMillis,
                endedAtMillis = sector.crossingMillis,
                durationMillis = sector.durationMillis,
                cumulativeSplitMillis = sector.cumulativeSplitMillis,
            )
        }
    return HudTimingReviewImportResult.Ready(
        TimingSessionPayloadV1(
            session = session,
            app = app,
            samples = samples,
            laps = laps,
            sectorEvents = emptyList(),
            gpsQuality = gpsQualitySummaryOf(samples, LocationSource.ExternalGnss),
            totalDurationMillis = log.durationMillis,
            sectorResults = sectorResults,
        ),
    )
}
