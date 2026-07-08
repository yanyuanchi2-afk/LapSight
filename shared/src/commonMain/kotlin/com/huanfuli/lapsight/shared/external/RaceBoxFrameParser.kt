package com.huanfuli.lapsight.shared.external

import kotlin.math.PI

class RaceBoxFrameParser(
    private val transport: ExternalGnssTransport = ExternalGnssTransport.Replay,
    private val receiver: ExternalGnssReceiverIdentity = defaultReceiverIdentity(),
) {
    private var streamBuffer = ByteArray(0)
    private var previousFixElapsedMillis: Long? = null

    fun accept(bytes: ByteArray): List<RaceBoxFrameParseResult> {
        if (bytes.isEmpty()) return emptyList()
        streamBuffer = streamBuffer + bytes
        val results = mutableListOf<RaceBoxFrameParseResult>()

        while (true) {
            val syncIndex = streamBuffer.indexOfSync()
            when {
                syncIndex < 0 -> {
                    val keepTrailingByte = streamBuffer.isNotEmpty() &&
                        streamBuffer.last().toUnsignedInt() == SYNC_1
                    val garbageLength = if (keepTrailingByte) streamBuffer.size - 1 else streamBuffer.size
                    if (garbageLength > 0) {
                        results += RaceBoxFrameParseResult.Rejected(
                            messageClass = null,
                            messageId = null,
                            reason = RaceBoxFrameRejectReason.MissingSync,
                            rawFrame = streamBuffer.copyOfRange(0, garbageLength),
                        )
                    }
                    streamBuffer = if (keepTrailingByte) {
                        streamBuffer.copyOfRange(garbageLength, streamBuffer.size)
                    } else {
                        ByteArray(0)
                    }
                    break
                }
                syncIndex > 0 -> {
                    results += RaceBoxFrameParseResult.Rejected(
                        messageClass = null,
                        messageId = null,
                        reason = RaceBoxFrameRejectReason.MissingSync,
                        rawFrame = streamBuffer.copyOfRange(0, syncIndex),
                    )
                    streamBuffer = streamBuffer.copyOfRange(syncIndex, streamBuffer.size)
                }
            }

            if (streamBuffer.size < FRAME_HEADER_SIZE) break
            val payloadLength = streamBuffer.readUInt16Le(PAYLOAD_LENGTH_OFFSET)
            val frameLength = FRAME_HEADER_SIZE + payloadLength + CHECKSUM_SIZE
            if (streamBuffer.size < frameLength) break

            val frame = streamBuffer.copyOfRange(0, frameLength)
            streamBuffer = streamBuffer.copyOfRange(frameLength, streamBuffer.size)
            results += parseFrame(frame)
        }

        return results
    }

    private fun parseFrame(frame: ByteArray): RaceBoxFrameParseResult {
        val messageClass = frame[MESSAGE_CLASS_OFFSET].toUnsignedInt()
        val messageId = frame[MESSAGE_ID_OFFSET].toUnsignedInt()
        val payloadLength = frame.readUInt16Le(PAYLOAD_LENGTH_OFFSET)
        val payload = frame.copyOfRange(FRAME_HEADER_SIZE, FRAME_HEADER_SIZE + payloadLength)

        if (!frame.hasValidChecksum()) {
            return RaceBoxFrameParseResult.Rejected(
                messageClass = messageClass,
                messageId = messageId,
                reason = RaceBoxFrameRejectReason.ChecksumMismatch,
                rawFrame = frame,
            )
        }

        return when {
            messageClass == MESSAGE_CLASS_GNSS && messageId == MESSAGE_ID_LIVE_FIX -> parseLiveFix(payload)
            messageClass == MESSAGE_CLASS_TELEMETRY && messageId == MESSAGE_ID_BASIC_TELEMETRY -> {
                parseTelemetry(payload)
            }
            messageClass == MESSAGE_CLASS_TELEMETRY -> RaceBoxFrameParseResult.UnsupportedTelemetry(
                messageClass = messageClass,
                messageId = messageId,
                reason = RaceBoxFrameUnsupportedTelemetryReason.UnsupportedMessage,
            )
            else -> RaceBoxFrameParseResult.Ignored(
                messageClass = messageClass,
                messageId = messageId,
                reason = RaceBoxFrameIgnoredReason.UnsupportedMessage,
            )
        }
    }

    private fun parseLiveFix(payload: ByteArray): RaceBoxFrameParseResult {
        if (payload.size < LIVE_FIX_PAYLOAD_SIZE) {
            return RaceBoxFrameParseResult.Rejected(
                messageClass = MESSAGE_CLASS_GNSS,
                messageId = MESSAGE_ID_LIVE_FIX,
                reason = RaceBoxFrameRejectReason.PayloadTooShort,
                rawFrame = payload,
            )
        }

        val iTowMillis = payload.readUInt32Le(0)
        val elapsedMillis = iTowMillis.toLong()
        val calendarTime = RaceBoxCalendarTime(
            year = payload.readUInt16Le(4),
            month = payload[6].toUnsignedInt(),
            day = payload[7].toUnsignedInt(),
            hour = payload[8].toUnsignedInt(),
            minute = payload[9].toUnsignedInt(),
            second = payload[10].toUnsignedInt(),
            isValid = payload[11].toUnsignedInt() and TIME_FLAG_VALID != 0,
        )
        val fixStatusCode = payload[48].toUnsignedInt()
        val flags = payload[50].toUnsignedInt()
        val hasValidLocation = fixStatusCode != FIX_STATUS_NO_FIX && flags and FIX_FLAG_VALID_LOCATION != 0
        val updateRateHz = updateRateHz(elapsedMillis)
        val source = sourceMetadata(
            messageName = "RaceBoxLiveFix",
            updateRateHz = updateRateHz,
        )

        if (!hasValidLocation) {
            previousFixElapsedMillis = elapsedMillis
            return RaceBoxFrameParseResult.Snapshot(
                snapshot = ExternalGnssFixSnapshot(
                    elapsedMillis = elapsedMillis,
                    latitude = null,
                    longitude = null,
                    speedMetersPerSecond = null,
                    headingDegrees = null,
                    altitudeMeters = null,
                    quality = ExternalGnssFixQuality(
                        isValid = false,
                        fixType = ExternalGnssFixType.NoFix,
                        satellitesInUse = payload[49].toUnsignedInt(),
                    ),
                    source = source,
                ),
                metadata = RaceBoxFixMetadata(
                    iTowMillis = iTowMillis,
                    calendarTime = calendarTime,
                    fixStatusCode = fixStatusCode,
                ),
            )
        }

        val latitude = payload.readInt32Le(12) / COORDINATE_SCALE
        val longitude = payload.readInt32Le(16) / COORDINATE_SCALE
        val altitudeMeters = payload.readInt32Le(20) / MILLIMETERS_PER_METER
        val horizontalAccuracyMeters = payload.readUInt32Le(24).toDouble() / MILLIMETERS_PER_METER
        val verticalAccuracyMeters = payload.readUInt32Le(28).toDouble() / MILLIMETERS_PER_METER
        val speedMetersPerSecond = payload.readInt32Le(32) / MILLIMETERS_PER_METER
        val headingDegrees = normalizeHeading(payload.readInt32Le(36) / HEADING_SCALE)
        val speedAccuracyMetersPerSecond = payload.readUInt32Le(40).toDouble() / MILLIMETERS_PER_METER
        val headingAccuracyDegrees = payload.readUInt32Le(44).toDouble() / HEADING_SCALE
        previousFixElapsedMillis = elapsedMillis

        return RaceBoxFrameParseResult.Snapshot(
            snapshot = ExternalGnssFixSnapshot(
                elapsedMillis = elapsedMillis,
                latitude = latitude,
                longitude = longitude,
                speedMetersPerSecond = speedMetersPerSecond,
                headingDegrees = headingDegrees,
                altitudeMeters = altitudeMeters,
                quality = ExternalGnssFixQuality(
                    isValid = true,
                    fixType = fixType(fixStatusCode),
                    satellitesInUse = payload[49].toUnsignedInt(),
                    horizontalAccuracyMeters = horizontalAccuracyMeters,
                    verticalAccuracyMeters = verticalAccuracyMeters,
                    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
                    headingAccuracyDegrees = headingAccuracyDegrees,
                    usesDualFrequency = flags and FIX_FLAG_DUAL_FREQUENCY != 0,
                ),
                source = source,
            ),
            metadata = RaceBoxFixMetadata(
                iTowMillis = iTowMillis,
                calendarTime = calendarTime,
                fixStatusCode = fixStatusCode,
            ),
        )
    }

    private fun parseTelemetry(payload: ByteArray): RaceBoxFrameParseResult {
        if (payload.size < BASIC_TELEMETRY_PAYLOAD_SIZE) {
            return RaceBoxFrameParseResult.Rejected(
                messageClass = MESSAGE_CLASS_TELEMETRY,
                messageId = MESSAGE_ID_BASIC_TELEMETRY,
                reason = RaceBoxFrameRejectReason.PayloadTooShort,
                rawFrame = payload,
            )
        }

        return RaceBoxFrameParseResult.Telemetry(
            elapsedMillis = payload.readUInt32Le(0).toLong(),
            telemetry = ExternalGnssTelemetryMetadata(
                accelerationMetersPerSecondSquared = ExternalGnssVector3(
                    x = payload.readInt32Le(4) / TELEMETRY_SCALE,
                    y = payload.readInt32Le(8) / TELEMETRY_SCALE,
                    z = payload.readInt32Le(12) / TELEMETRY_SCALE,
                ),
                gyroRadiansPerSecond = ExternalGnssVector3(
                    x = payload.readInt32Le(16) / TELEMETRY_SCALE,
                    y = payload.readInt32Le(20) / TELEMETRY_SCALE,
                    z = payload.readInt32Le(24) / TELEMETRY_SCALE,
                ),
                vehicleSpeedMetersPerSecond = payload.readInt32Le(28) / MILLIMETERS_PER_METER,
            ),
            source = sourceMetadata("RaceBoxBasicTelemetry"),
        )
    }

    private fun updateRateHz(elapsedMillis: Long): Double? {
        val previous = previousFixElapsedMillis ?: return null
        val deltaMillis = elapsedMillis - previous
        return if (deltaMillis in 1L..MAX_RATE_DELTA_MILLIS) {
            1_000.0 / deltaMillis
        } else {
            null
        }
    }

    private fun sourceMetadata(
        messageName: String,
        updateRateHz: Double? = null,
    ): ExternalGnssSourceMetadata =
        ExternalGnssSourceMetadata(
            protocol = ExternalGnssProtocol.RaceBox,
            transport = transport,
            receiver = receiver,
            sentenceId = messageName,
            updateRateHz = updateRateHz,
            hardwareValidationStatus = ExternalGnssHardwareValidationStatus.Unverified,
        )

    companion object {
        internal const val SYNC_1 = 0xB5
        internal const val SYNC_2 = 0x62
        internal const val MESSAGE_CLASS_GNSS = 0x01
        internal const val MESSAGE_ID_LIVE_FIX = 0x01
        internal const val MESSAGE_CLASS_TELEMETRY = 0x02
        internal const val MESSAGE_ID_BASIC_TELEMETRY = 0x01
        internal const val LIVE_FIX_PAYLOAD_SIZE = 52
        internal const val BASIC_TELEMETRY_PAYLOAD_SIZE = 32

        fun defaultReceiverIdentity(): ExternalGnssReceiverIdentity =
            ExternalGnssReceiverIdentity(
                protocol = ExternalGnssProtocol.RaceBox,
                displayName = "RaceBox protocol preview",
                modelName = "Mini/Mini S/Micro family",
                hardwareValidationStatus = ExternalGnssHardwareValidationStatus.Unverified,
            )
    }
}

sealed class RaceBoxFrameParseResult {
    data class Snapshot(
        val snapshot: ExternalGnssFixSnapshot,
        val metadata: RaceBoxFixMetadata,
    ) : RaceBoxFrameParseResult()

    data class Telemetry(
        val elapsedMillis: Long,
        val telemetry: ExternalGnssTelemetryMetadata,
        val source: ExternalGnssSourceMetadata,
    ) : RaceBoxFrameParseResult()

    data class UnsupportedTelemetry(
        val messageClass: Int,
        val messageId: Int,
        val reason: RaceBoxFrameUnsupportedTelemetryReason,
    ) : RaceBoxFrameParseResult()

    data class Rejected(
        val messageClass: Int?,
        val messageId: Int?,
        val reason: RaceBoxFrameRejectReason,
        val rawFrame: ByteArray,
    ) : RaceBoxFrameParseResult()

    data class Ignored(
        val messageClass: Int,
        val messageId: Int,
        val reason: RaceBoxFrameIgnoredReason,
    ) : RaceBoxFrameParseResult()
}

data class RaceBoxFixMetadata(
    val iTowMillis: UInt,
    val calendarTime: RaceBoxCalendarTime,
    val fixStatusCode: Int,
)

data class RaceBoxCalendarTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val isValid: Boolean,
)

enum class RaceBoxFrameRejectReason {
    MissingSync,
    ChecksumMismatch,
    PayloadTooShort,
}

enum class RaceBoxFrameIgnoredReason {
    UnsupportedMessage,
}

enum class RaceBoxFrameUnsupportedTelemetryReason {
    UnsupportedMessage,
}

internal const val FRAME_HEADER_SIZE = 6
internal const val CHECKSUM_SIZE = 2
private const val MESSAGE_CLASS_OFFSET = 2
private const val MESSAGE_ID_OFFSET = 3
private const val PAYLOAD_LENGTH_OFFSET = 4
private const val FIX_STATUS_NO_FIX = 0
private const val FIX_STATUS_RTK_FIXED = 4
private const val FIX_STATUS_RTK_FLOAT = 5
private const val TIME_FLAG_VALID = 0x01
private const val FIX_FLAG_VALID_LOCATION = 0x01
private const val FIX_FLAG_DUAL_FREQUENCY = 0x02
private const val MAX_RATE_DELTA_MILLIS = 1_000L
private const val COORDINATE_SCALE = 10_000_000.0
private const val MILLIMETERS_PER_METER = 1_000.0
private const val HEADING_SCALE = 100_000.0
private const val TELEMETRY_SCALE = 1_000.0

private fun ByteArray.indexOfSync(): Int {
    for (index in 0 until size - 1) {
        if (this[index].toUnsignedInt() == RaceBoxFrameParser.SYNC_1 &&
            this[index + 1].toUnsignedInt() == RaceBoxFrameParser.SYNC_2
        ) {
            return index
        }
    }
    return -1
}

internal fun ByteArray.hasValidChecksum(): Boolean {
    if (size < FRAME_HEADER_SIZE + CHECKSUM_SIZE) return false
    val checksumStart = size - CHECKSUM_SIZE
    val (actualA, actualB) = checksum(copyOfRange(MESSAGE_CLASS_OFFSET, checksumStart))
    return actualA == this[checksumStart].toUnsignedInt() &&
        actualB == this[checksumStart + 1].toUnsignedInt()
}

internal fun checksum(bytes: ByteArray): Pair<Int, Int> {
    var checksumA = 0
    var checksumB = 0
    bytes.forEach { byte ->
        checksumA = (checksumA + byte.toUnsignedInt()) and 0xFF
        checksumB = (checksumB + checksumA) and 0xFF
    }
    return checksumA to checksumB
}

internal fun ByteArray.readUInt16Le(offset: Int): Int =
    this[offset].toUnsignedInt() or (this[offset + 1].toUnsignedInt() shl 8)

internal fun ByteArray.readUInt32Le(offset: Int): UInt =
    (this[offset].toUnsignedInt().toUInt()) or
        (this[offset + 1].toUnsignedInt().toUInt() shl 8) or
        (this[offset + 2].toUnsignedInt().toUInt() shl 16) or
        (this[offset + 3].toUnsignedInt().toUInt() shl 24)

internal fun ByteArray.readInt32Le(offset: Int): Int =
    readUInt32Le(offset).toInt()

internal fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

private fun fixType(fixStatusCode: Int): ExternalGnssFixType =
    when (fixStatusCode) {
        FIX_STATUS_NO_FIX -> ExternalGnssFixType.NoFix
        FIX_STATUS_RTK_FIXED -> ExternalGnssFixType.RtkFixed
        FIX_STATUS_RTK_FLOAT -> ExternalGnssFixType.RtkFloat
        else -> ExternalGnssFixType.Gps
    }

private fun normalizeHeading(heading: Double): Double {
    val normalized = heading % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

internal fun degreesToRadians(degrees: Double): Double = degrees * PI / 180.0
