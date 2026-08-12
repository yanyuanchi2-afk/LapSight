package com.huanfuli.lapsight.shared

import com.huanfuli.lapsight.shared.track.CourseDirection

/** User intents sent from the normal APP workflow to a connected LapSight HUD. */
sealed interface HudRemoteCommand {
    data object RequestState : HudRemoteCommand
    data object MarkStart : HudRemoteCommand
    data object MarkStop : HudRemoteCommand
    data object MarkLogMetadata : HudRemoteCommand
    data class MarkLogRead(
        val offset: Long,
        val length: Int = HUD_LOG_CHUNK_BYTES,
    ) : HudRemoteCommand
    data class CourseBegin(val sizeBytes: Int, val crc32: UInt) : HudRemoteCommand
    data class CourseWrite(val offset: Int, val bytes: ByteArray) : HudRemoteCommand {
        override fun equals(other: Any?): Boolean =
            other is CourseWrite && offset == other.offset && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * offset + bytes.contentHashCode()
    }
    data object CourseCommit : HudRemoteCommand
    data object CourseClear : HudRemoteCommand
    data object TimingStart : HudRemoteCommand
    data object TimingPause : HudRemoteCommand
    data object TimingResume : HudRemoteCommand
    data object TimingStop : HudRemoteCommand
    data class DisplayPage(val page: HudDisplayPage) : HudRemoteCommand
    data object TimingLogMetadata : HudRemoteCommand
    data class TimingLogRead(
        val sessionId: Long,
        val offset: Long,
        val length: Int = HUD_LOG_CHUNK_BYTES,
    ) : HudRemoteCommand
    data class TimingLogAcknowledge(val sessionId: Long, val crc32: UInt) : HudRemoteCommand
}

const val HUD_LOG_CHUNK_BYTES: Int = 96

fun HudRemoteCommand.toWireCommand(): String = when (this) {
    HudRemoteCommand.RequestState -> "LS1,C,STATE_REQUEST\n"
    HudRemoteCommand.MarkStart -> "LS1,C,MARK_START\n"
    HudRemoteCommand.MarkStop -> "LS1,C,MARK_STOP\n"
    HudRemoteCommand.MarkLogMetadata -> "LS1,C,MARK_LOG_META\n"
    is HudRemoteCommand.MarkLogRead -> {
        require(offset >= 0L) { "HUD log offset must be non-negative" }
        require(length in 1..HUD_LOG_CHUNK_BYTES) { "HUD log chunk must be 1..$HUD_LOG_CHUNK_BYTES bytes" }
        "LS1,C,MARK_LOG_READ,$offset,$length\n"
    }
    is HudRemoteCommand.CourseBegin -> {
        require(sizeBytes > 0) { "HUD course size must be positive" }
        "LS1,C,COURSE_BEGIN,$sizeBytes,${crc32.toHex8()}\n"
    }
    is HudRemoteCommand.CourseWrite -> {
        require(offset >= 0) { "HUD course offset must be non-negative" }
        require(bytes.size in 1..HUD_COURSE_CHUNK_BYTES) {
            "HUD course chunk must be 1..$HUD_COURSE_CHUNK_BYTES bytes"
        }
        "LS1,C,COURSE_DATA,$offset,${bytes.size},${crc32(bytes).toHex8()},${encodeBase64(bytes)}\n"
    }
    HudRemoteCommand.CourseCommit -> "LS1,C,COURSE_COMMIT\n"
    HudRemoteCommand.CourseClear -> "LS1,C,COURSE_CLEAR\n"
    HudRemoteCommand.TimingStart -> "LS1,C,TIMING_START\n"
    HudRemoteCommand.TimingPause -> "LS1,C,TIMING_PAUSE\n"
    HudRemoteCommand.TimingResume -> "LS1,C,TIMING_RESUME\n"
    HudRemoteCommand.TimingStop -> "LS1,C,TIMING_STOP\n"
    is HudRemoteCommand.DisplayPage -> "LS1,C,DISPLAY_PAGE,${page.wireName}\n"
    HudRemoteCommand.TimingLogMetadata -> "LS1,C,TIMING_LOG_META\n"
    is HudRemoteCommand.TimingLogRead -> {
        require(sessionId > 0L) { "HUD timing session id must be positive" }
        require(offset >= 0L) { "HUD timing log offset must be non-negative" }
        require(length in 1..HUD_LOG_CHUNK_BYTES) { "HUD timing log chunk must be 1..$HUD_LOG_CHUNK_BYTES bytes" }
        "LS1,C,TIMING_LOG_READ,$sessionId,$offset,$length\n"
    }
    is HudRemoteCommand.TimingLogAcknowledge -> {
        require(sessionId > 0L) { "HUD timing session id must be positive" }
        "LS1,C,TIMING_LOG_ACK,$sessionId,${crc32.toHex8()}\n"
    }
}

enum class HudSessionPhase { Idle, Marking, Review, Timing, Paused }

enum class HudDisplayPage(val wireName: String) {
    Race("RACE"),
    Glance("GLANCE"),
    History("HISTORY"),
    Course("COURSE"),
    Diagnostics("DIAGNOSTICS"),
}

/** Authoritative runtime snapshot reported by the HUD. */
data class HudRuntimeState(
    val phase: HudSessionPhase = HudSessionPhase.Idle,
    val elapsedMillis: Long = 0L,
    val pointCount: Int = 0,
    val completedLoops: Int = 0,
    val referenceReady: Boolean = false,
    val displayPage: HudDisplayPage? = null,
)

data class HudTimingTelemetry(
    val phase: HudSessionPhase = HudSessionPhase.Idle,
    val sessionElapsedMillis: Long = 0L,
    val lapCount: Int = 0,
    val currentLapNumber: Int = 1,
    val currentLapMillis: Long = 0L,
    val lastLapMillis: Long? = null,
    val bestLapMillis: Long? = null,
    val currentSectorNumber: Int? = null,
    val sectorCount: Int = 0,
    val latestSectorMillis: Long? = null,
    val bestSectorMillis: Long? = null,
    val liveDeltaMillis: Long? = null,
)

/** A complete, CRC-verified marking log downloaded from the HUD's SD card. */
data class HudMarkingLog(
    val sizeBytes: Long,
    val crc32: UInt,
    val path: String,
    val samples: List<LocationSample>,
)

data class HudTimingLog(
    val sessionId: Long,
    val sizeBytes: Long,
    val crc32: UInt,
    val path: String,
    val complete: Boolean,
    val durationMillis: Long,
    val lapCount: Int,
    val bestLapMillis: Long?,
    val samples: List<LocationSample>,
    val laps: List<HudTimingLogLap>,
    val sectors: List<HudTimingLogSector>,
    val profileId: String? = null,
    val revisionId: String? = null,
    val geometryCompatibilityId: String? = null,
    val direction: CourseDirection? = null,
)

data class HudTimingLogLap(
    val lapNumber: Int,
    val durationMillis: Long,
    val bestLapMillis: Long,
    val completedAtMillis: Long = durationMillis,
)

data class HudTimingLogSector(
    val lapNumber: Int,
    val sectorNumber: Int,
    val durationMillis: Long,
    val bestSectorMillis: Long,
    val deltaMillis: Long?,
    val crossingMillis: Long = durationMillis,
    val cumulativeSplitMillis: Long = durationMillis,
)

data class HudTimingLogContents(
    val samples: List<LocationSample>,
    val laps: List<HudTimingLogLap>,
    val sectors: List<HudTimingLogSector>,
)

sealed interface HudRemoteMessage {
    data class State(val value: HudRuntimeState) : HudRemoteMessage
    data class Acknowledgement(
        val command: String,
        val result: String,
    ) : HudRemoteMessage
    data class Error(
        val command: String,
        val reason: String,
    ) : HudRemoteMessage
    data class MarkLogMetadata(
        val sizeBytes: Long,
        val crc32: UInt,
        val path: String,
    ) : HudRemoteMessage
    data class MarkLogData(
        val offset: Long,
        val bytes: ByteArray,
        val crc32: UInt,
    ) : HudRemoteMessage {
        override fun equals(other: Any?): Boolean =
            other is MarkLogData && offset == other.offset && crc32 == other.crc32 && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * (31 * offset.hashCode() + bytes.contentHashCode()) + crc32.hashCode()
    }
    data class CourseUploadProgress(val nextOffset: Int) : HudRemoteMessage
    data class LapCompleted(
        val lapNumber: Int,
        val lapMillis: Long,
        val bestLapMillis: Long,
    ) : HudRemoteMessage
    data class TimingData(
        val sessionElapsedMillis: Long,
        val currentLapNumber: Int,
        val currentLapMillis: Long,
        val lastLapMillis: Long?,
        val bestLapMillis: Long?,
        val paused: Boolean,
        val currentSectorNumber: Int? = null,
        val sectorCount: Int = 0,
        val liveDeltaMillis: Long? = null,
    ) : HudRemoteMessage
    data class SectorCompleted(
        val lapNumber: Int,
        val sectorNumber: Int,
        val sectorMillis: Long,
        val bestSectorMillis: Long,
        val deltaMillis: Long?,
    ) : HudRemoteMessage
    data class TimingLogMetadata(
        val sessionId: Long,
        val sizeBytes: Long,
        val crc32: UInt,
        val complete: Boolean,
        val durationMillis: Long,
        val lapCount: Int,
        val bestLapMillis: Long?,
        val path: String,
        val profileId: String? = null,
        val revisionId: String? = null,
        val geometryCompatibilityId: String? = null,
        val direction: CourseDirection? = null,
    ) : HudRemoteMessage
    data class TimingLogData(
        val sessionId: Long,
        val offset: Long,
        val bytes: ByteArray,
        val crc32: UInt,
    ) : HudRemoteMessage {
        override fun equals(other: Any?): Boolean = other is TimingLogData &&
            sessionId == other.sessionId && offset == other.offset && crc32 == other.crc32 &&
            bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * (31 * (31 * sessionId.hashCode() + offset.hashCode()) +
            bytes.contentHashCode()) + crc32.hashCode()
    }
}

/** Parses one complete newline-delimited `LS1` control/status frame. */
fun parseHudRemoteMessage(line: String): HudRemoteMessage? {
    val fields = line.trim().split(',')
    if (fields.size < 3 || fields[0] != "LS1") return null
    return when (fields[1]) {
        "S" -> parseHudState(fields)
        "A" -> if (fields.size == 4) {
            HudRemoteMessage.Acknowledgement(command = fields[2], result = fields[3])
        } else {
            null
        }
        "E" -> if (fields.size >= 4) {
            HudRemoteMessage.Error(command = fields[2], reason = fields.drop(3).joinToString(","))
        } else {
            null
        }
        "L" -> parseHudLog(fields)
        "R" -> parseHudTimingLog(fields)
        "U" -> if (fields.size == 4 && fields[2] == "COURSE") {
            fields[3].toIntOrNull()?.takeIf { it >= 0 }?.let(HudRemoteMessage::CourseUploadProgress)
        } else {
            null
        }
        "T" -> if (fields.size == 6 && fields[2] == "LAP") {
            val lap = fields[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val lapMillis = fields[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val best = fields[5].toLongOrNull()?.takeIf { it > 0 } ?: return null
            HudRemoteMessage.LapCompleted(lap, lapMillis, best)
        } else if (fields.size in setOf(9, 12) && fields[2] == "DATA") {
            val session = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val lap = fields[4].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val current = fields[5].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val last = fields[6].toLongOrNull()?.takeIf { it >= 0 }?.takeIf { it > 0 }
            val best = fields[7].toLongOrNull()?.takeIf { it >= 0 }?.takeIf { it > 0 }
            val paused = when (fields[8]) { "0" -> false; "1" -> true; else -> return null }
            val sector = fields.getOrNull(9)?.toIntOrNull()?.takeIf { it > 0 }
            val sectorCount = fields.getOrNull(10)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            val delta = fields.getOrNull(11)?.let { if (it == "N") null else it.toLongOrNull() }
            if (fields.size == 12 && (sector == null || sectorCount == 0 || sector > sectorCount ||
                    (fields[11] != "N" && delta == null))) return null
            HudRemoteMessage.TimingData(session, lap, current, last, best, paused, sector, sectorCount, delta)
        } else if (fields.size == 8 && fields[2] == "SECTOR") {
            val lap = fields[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val sector = fields[4].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val duration = fields[5].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val best = fields[6].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val delta = if (fields[7] == "N") null else fields[7].toLongOrNull() ?: return null
            HudRemoteMessage.SectorCompleted(lap, sector, duration, best, delta)
        } else null
        else -> null
    }
}

private fun parseHudTimingLog(fields: List<String>): HudRemoteMessage? = when (fields.getOrNull(2)) {
    "META" -> {
        if (fields.size !in setOf(11, 15)) return null
        val sessionId = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val size = fields[4].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val crc = fields[5].toUIntOrNull(16) ?: return null
        val complete = when (fields[6]) { "0" -> false; "1" -> true; else -> return null }
        val duration = fields[7].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val lapCount = fields[8].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val best = fields[9].toLongOrNull()?.takeIf { it >= 0L }?.takeIf { it > 0L }
        val path = fields[10].takeIf { it.startsWith('/') && !it.contains("..") } ?: return null
        val profileId = fields.getOrNull(11)?.let(::decodeBase64StringOrNull)
        val revisionId = fields.getOrNull(12)?.let(::decodeBase64StringOrNull)
        val geometryId = fields.getOrNull(13)?.let(::decodeBase64StringOrNull)
        val direction = fields.getOrNull(14)?.let {
            when (it) {
                "R" -> CourseDirection.Recorded
                "V" -> CourseDirection.Reverse
                else -> return null
            }
        }
        if (fields.size == 15 && listOf(profileId, revisionId, geometryId).any { it == null }) return null
        HudRemoteMessage.TimingLogMetadata(
            sessionId, size, crc, complete, duration, lapCount, best, path,
            profileId, revisionId, geometryId, direction,
        )
    }
    "DATA" -> {
        if (fields.size != 8) return null
        val sessionId = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val offset = fields[4].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val length = fields[5].toIntOrNull()?.takeIf { it in 1..HUD_LOG_CHUNK_BYTES } ?: return null
        val declaredCrc = fields[6].toUIntOrNull(16) ?: return null
        val bytes = decodeBase64(fields[7]) ?: return null
        if (bytes.size != length || crc32(bytes) != declaredCrc) return null
        HudRemoteMessage.TimingLogData(sessionId, offset, bytes, declaredCrc)
    }
    else -> null
}

private fun parseHudLog(fields: List<String>): HudRemoteMessage? = when (fields.getOrNull(2)) {
    "META" -> {
        if (fields.size != 6) return null
        val size = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val crc = fields[4].toUIntOrNull(16) ?: return null
        val path = fields[5].takeIf { it.startsWith('/') && !it.contains("..") } ?: return null
        HudRemoteMessage.MarkLogMetadata(sizeBytes = size, crc32 = crc, path = path)
    }
    "DATA" -> {
        if (fields.size != 7) return null
        val offset = fields[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val declaredLength = fields[4].toIntOrNull()?.takeIf { it in 1..HUD_LOG_CHUNK_BYTES } ?: return null
        val declaredCrc = fields[5].toUIntOrNull(16) ?: return null
        val bytes = decodeBase64(fields[6]) ?: return null
        if (bytes.size != declaredLength || crc32(bytes) != declaredCrc) return null
        HudRemoteMessage.MarkLogData(offset = offset, bytes = bytes, crc32 = declaredCrc)
    }
    else -> null
}

private fun parseHudState(fields: List<String>): HudRemoteMessage.State? {
    if (fields.size !in setOf(8, 9)) return null
    val phase = when (fields[2]) {
        "IDLE" -> HudSessionPhase.Idle
        "MARKING" -> HudSessionPhase.Marking
        "REVIEW" -> HudSessionPhase.Review
        "TIMING" -> HudSessionPhase.Timing
        "PAUSED" -> HudSessionPhase.Paused
        else -> return null
    }
    val elapsedMillis = fields[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val pointCount = fields[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val completedLoops = fields[5].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val referenceReady = when (fields[6]) {
        "0" -> false
        "1" -> true
        else -> return null
    }
    // Reserved protocol flags field. Version 1 currently requires zero.
    if (fields[7] != "0") return null
    val displayPage = fields.getOrNull(8)?.let { wireName ->
        HudDisplayPage.entries.firstOrNull { it.wireName == wireName } ?: return null
    }
    return HudRemoteMessage.State(
        HudRuntimeState(
            phase = phase,
            elapsedMillis = elapsedMillis,
            pointCount = pointCount,
            completedLoops = completedLoops,
            referenceReady = referenceReady,
            displayPage = displayPage,
        ),
    )
}

fun crc32(bytes: ByteArray): UInt {
    var crc = 0xFFFFFFFFu
    bytes.forEach { byte ->
        crc = crc xor byte.toUByte().toUInt()
        repeat(8) {
            crc = (crc shr 1) xor if ((crc and 1u) != 0u) 0xEDB88320u else 0u
        }
    }
    return crc xor 0xFFFFFFFFu
}

fun encodeBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = StringBuilder(4 * ((bytes.size + 2) / 3))
    var index = 0
    while (index < bytes.size) {
        val remaining = bytes.size - index
        val a = bytes[index++].toInt() and 0xFF
        val b = if (remaining > 1) bytes[index++].toInt() and 0xFF else 0
        val c = if (remaining > 2) bytes[index++].toInt() and 0xFF else 0
        val packed = (a shl 16) or (b shl 8) or c
        output.append(alphabet[(packed shr 18) and 0x3F])
        output.append(alphabet[(packed shr 12) and 0x3F])
        output.append(if (remaining > 1) alphabet[(packed shr 6) and 0x3F] else '=')
        output.append(if (remaining > 2) alphabet[packed and 0x3F] else '=')
    }
    return output.toString()
}

private fun UInt.toHex8(): String = toString(16).uppercase().padStart(8, '0')

/**
 * Converts the integrated HUD's version-1 CSV into the same samples used by
 * phone-only marking. Invalid/no-fix rows and repeated event rows are ignored.
 */
fun parseHudMarkingCsv(csv: String): List<LocationSample> {
    val samples = mutableListOf<LocationSample>()
    csv.lineSequence().drop(1).forEach { line ->
        if (line.isBlank()) return@forEach
        val fields = line.split(',')
        if (fields.size < 17) return@forEach
        val elapsed = fields[0].toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
        val latitude = fields[3].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@forEach
        val longitude = fields[4].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@forEach
        if (latitude == 0.0 && longitude == 0.0) return@forEach
        val speedMetersPerSecond = fields[5].toDoubleOrNull()
            ?.takeIf { it >= 0.0 }
            ?.div(3.6)
        val satellites = fields[6].toIntOrNull()?.takeIf { it >= 0 }
        val previous = samples.lastOrNull()
        if (
            previous?.elapsedMillis == elapsed &&
            previous.latitude == latitude &&
            previous.longitude == longitude
        ) {
            return@forEach
        }
        samples += LocationSample(
            elapsedMillis = elapsed,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyMeters = null,
            speedMetersPerSecond = speedMetersPerSecond,
            headingDegrees = null,
            altitudeMeters = null,
            source = LocationSource.ExternalGnss,
            satellitesInUse = satellites,
        )
    }
    return samples
}

fun parseHudTimingCsv(csv: String): HudTimingLogContents {
    val samples = mutableListOf<LocationSample>()
    val laps = mutableListOf<HudTimingLogLap>()
    val sectors = mutableListOf<HudTimingLogSector>()
    csv.lineSequence().drop(1).forEach { line ->
        if (line.isBlank()) return@forEach
        val fields = line.split(',')
        if (fields.size != 13) return@forEach
        when (fields[0]) {
            "FIX" -> {
                val elapsed = fields[1].toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
                val latitude = fields[8].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@forEach
                val longitude = fields[9].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@forEach
                val speed = fields[10].toDoubleOrNull()?.takeIf { it >= 0.0 }?.div(3.6)
                val satellites = fields[11].toIntOrNull()?.takeIf { it >= 0 }
                samples += LocationSample(
                    elapsedMillis = elapsed,
                    latitude = latitude,
                    longitude = longitude,
                    horizontalAccuracyMeters = null,
                    speedMetersPerSecond = speed,
                    headingDegrees = null,
                    altitudeMeters = null,
                    source = LocationSource.ExternalGnss,
                    satellitesInUse = satellites,
                )
            }
            "LAP" -> {
                val completedAt = fields[1].toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
                val lap = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val duration = fields[5].toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                val best = fields[6].toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                if (completedAt < duration) return@forEach
                laps += HudTimingLogLap(lap, duration, best, completedAt)
            }
            "SECTOR" -> {
                val crossing = fields[1].toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
                val lap = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val cumulative = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                val sector = fields[4].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val duration = fields[5].toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                val best = fields[6].toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                val delta = if (fields[7] == "N") null else fields[7].toLongOrNull() ?: return@forEach
                if (crossing < duration || cumulative < duration) return@forEach
                sectors += HudTimingLogSector(lap, sector, duration, best, delta, crossing, cumulative)
            }
        }
    }
    return HudTimingLogContents(samples, laps, sectors)
}

private fun decodeBase64(text: String): ByteArray? {
    if (text.isEmpty() || text.length % 4 != 0) return null
    val output = mutableListOf<Byte>()
    text.chunked(4).forEachIndexed { groupIndex, group ->
        val padding = group.count { it == '=' }
        if (padding > 2 || (padding > 0 && groupIndex != text.length / 4 - 1)) return null
        val values = IntArray(4)
        group.forEachIndexed { index, char ->
            values[index] = when (char) {
                in 'A'..'Z' -> char.code - 'A'.code
                in 'a'..'z' -> char.code - 'a'.code + 26
                in '0'..'9' -> char.code - '0'.code + 52
                '+' -> 62
                '/' -> 63
                '=' -> 0
                else -> return null
            }
        }
        val packed = (values[0] shl 18) or (values[1] shl 12) or (values[2] shl 6) or values[3]
        output += ((packed shr 16) and 0xFF).toByte()
        if (padding < 2) output += ((packed shr 8) and 0xFF).toByte()
        if (padding == 0) output += (packed and 0xFF).toByte()
    }
    return output.toByteArray()
}

private fun decodeBase64StringOrNull(text: String): String? {
    if (text == "-") return null
    val bytes = decodeBase64(text) ?: return null
    return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && !it.contains(',') && !it.contains('\n') && !it.contains('\r') }
}
