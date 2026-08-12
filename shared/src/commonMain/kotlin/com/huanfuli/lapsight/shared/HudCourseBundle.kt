package com.huanfuli.lapsight.shared

import com.huanfuli.lapsight.shared.track.CourseDirection
import com.huanfuli.lapsight.shared.track.CourseTopology
import com.huanfuli.lapsight.shared.track.TrackProfile

const val HUD_COURSE_CHUNK_BYTES: Int = 96
const val HUD_COURSE_MAX_POINTS: Int = 512

/** Deterministic, CRC-protected course payload sent to the authoritative HUD. */
data class HudCourseBundle(
    val profileId: String,
    val revisionId: String,
    val geometryCompatibilityId: String,
    val bytes: ByteArray,
    val crc32: UInt = crc32(bytes),
)

/**
 * Encodes the selected immutable profile revision into the compact LSCB v1
 * interchange format. The HUD stores the exact bytes before parsing geometry.
 */
fun buildHudCourseBundle(
    profile: TrackProfile,
    direction: CourseDirection = profile.preferredDirection,
): HudCourseBundle? {
    val revision = profile.latestRevision ?: return null
    val points = revision.referenceLine.points
    val setup = revision.courseSetup
    val startFinish = setup.startFinish ?: return null
    if (points.size !in 2..HUD_COURSE_MAX_POINTS) return null
    if (points.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) return null
    if (setup.boundaries.size > 5) return null
    val timingPoints = buildList {
        add(startFinish.pointA)
        add(startFinish.pointB)
        setup.finishLine?.let { add(it.pointA); add(it.pointB) }
        setup.boundaries.forEach { add(it.pointA); add(it.pointB) }
    }
    if (timingPoints.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) return null
    if (setup.topology == CourseTopology.PointToPoint && setup.finishLine == null) return null

    val directionCode = if (direction == CourseDirection.Recorded) "R" else "V"
    val topologyCode = if (setup.topology == CourseTopology.Circuit) "C" else "P"
    val payload = buildString {
        append("LSCB,1,")
        append(encodeBase64(profile.profileId.encodeToByteArray())).append(',')
        append(encodeBase64(revision.revisionId.encodeToByteArray())).append(',')
        append(encodeBase64(revision.geometryCompatibilityId.encodeToByteArray())).append(',')
        append(encodeBase64(profile.name.encodeToByteArray())).append(',')
        append(directionCode).append(',')
        append(topologyCode).append(',')
        append(if (revision.referenceLine.isClosed) 1 else 0).append(',')
        append(points.size).append(',')
        append(setup.boundaries.size).append('\n')
        points.forEach { point ->
            append("P,").append(point.latitude).append(',').append(point.longitude).append('\n')
        }
        append("S,")
        append(startFinish.pointA.latitude).append(',').append(startFinish.pointA.longitude).append(',')
        append(startFinish.pointB.latitude).append(',').append(startFinish.pointB.longitude).append('\n')
        setup.finishLine?.let { finish ->
            append("F,")
            append(finish.pointA.latitude).append(',').append(finish.pointA.longitude).append(',')
            append(finish.pointB.latitude).append(',').append(finish.pointB.longitude).append('\n')
        }
        setup.boundaries.sortedBy { it.order }.forEach { boundary ->
            append("B,").append(boundary.order).append(',')
            append(boundary.pointA.latitude).append(',').append(boundary.pointA.longitude).append(',')
            append(boundary.pointB.latitude).append(',').append(boundary.pointB.longitude).append('\n')
        }
    }.encodeToByteArray()
    return HudCourseBundle(
        profileId = profile.profileId,
        revisionId = revision.revisionId,
        geometryCompatibilityId = revision.geometryCompatibilityId,
        bytes = payload,
    )
}
