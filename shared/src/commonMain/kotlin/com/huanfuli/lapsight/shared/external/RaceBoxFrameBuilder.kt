package com.huanfuli.lapsight.shared.external

import kotlin.math.roundToInt

object RaceBoxFrameBuilder {

    fun liveFix(
        iTowMillis: Long,
        latitudeDegrees: Double = 39.8123,
        longitudeDegrees: Double = -86.1064,
        altitudeMeters: Double = 221.5,
        horizontalAccuracyMeters: Double = 0.8,
        verticalAccuracyMeters: Double = 1.4,
        speedMetersPerSecond: Double = 22.6,
        headingDegrees: Double = 91.25,
        speedAccuracyMetersPerSecond: Double = 0.12,
        headingAccuracyDegrees: Double = 0.35,
        fixStatusCode: Int = 3,
        satellitesInUse: Int = 18,
        usesDualFrequency: Boolean = true,
        validLocation: Boolean = true,
        calendarTime: RaceBoxCalendarTime = RaceBoxCalendarTime(
            year = 2026,
            month = 7,
            day = 7,
            hour = 21,
            minute = 15,
            second = 10,
            isValid = true,
        ),
    ): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.addUInt32Le(iTowMillis.toUInt())
        payload.addUInt16Le(calendarTime.year)
        payload.addUByte(calendarTime.month)
        payload.addUByte(calendarTime.day)
        payload.addUByte(calendarTime.hour)
        payload.addUByte(calendarTime.minute)
        payload.addUByte(calendarTime.second)
        payload.addUByte(if (calendarTime.isValid) TIME_FLAG_VALID else 0)
        payload.addInt32Le((latitudeDegrees * COORDINATE_SCALE).roundToInt())
        payload.addInt32Le((longitudeDegrees * COORDINATE_SCALE).roundToInt())
        payload.addInt32Le((altitudeMeters * MILLIMETERS_PER_METER).roundToInt())
        payload.addUInt32Le((horizontalAccuracyMeters * MILLIMETERS_PER_METER).roundToInt().toUInt())
        payload.addUInt32Le((verticalAccuracyMeters * MILLIMETERS_PER_METER).roundToInt().toUInt())
        payload.addInt32Le((speedMetersPerSecond * MILLIMETERS_PER_METER).roundToInt())
        payload.addInt32Le((headingDegrees * HEADING_SCALE).roundToInt())
        payload.addUInt32Le((speedAccuracyMetersPerSecond * MILLIMETERS_PER_METER).roundToInt().toUInt())
        payload.addUInt32Le((headingAccuracyDegrees * HEADING_SCALE).roundToInt().toUInt())
        payload.addUByte(fixStatusCode)
        payload.addUByte(satellitesInUse)
        payload.addUByte(
            (if (validLocation) FIX_FLAG_VALID_LOCATION else 0) or
                (if (usesDualFrequency) FIX_FLAG_DUAL_FREQUENCY else 0),
        )
        payload.addUByte(0)

        return frame(
            messageClass = RaceBoxFrameParser.MESSAGE_CLASS_GNSS,
            messageId = RaceBoxFrameParser.MESSAGE_ID_LIVE_FIX,
            payload = payload.toByteArray(),
        )
    }

    fun noFix(
        iTowMillis: Long,
        satellitesInUse: Int = 0,
    ): ByteArray =
        liveFix(
            iTowMillis = iTowMillis,
            fixStatusCode = 0,
            satellitesInUse = satellitesInUse,
            usesDualFrequency = false,
            validLocation = false,
        )

    fun basicTelemetry(
        iTowMillis: Long,
        accelerationMetersPerSecondSquared: ExternalGnssVector3,
        gyroDegreesPerSecond: ExternalGnssVector3,
        vehicleSpeedMetersPerSecond: Double,
    ): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.addUInt32Le(iTowMillis.toUInt())
        payload.addInt32Le((accelerationMetersPerSecondSquared.x * TELEMETRY_SCALE).roundToInt())
        payload.addInt32Le((accelerationMetersPerSecondSquared.y * TELEMETRY_SCALE).roundToInt())
        payload.addInt32Le((accelerationMetersPerSecondSquared.z * TELEMETRY_SCALE).roundToInt())
        payload.addInt32Le((degreesToRadians(gyroDegreesPerSecond.x) * TELEMETRY_SCALE).roundToInt())
        payload.addInt32Le((degreesToRadians(gyroDegreesPerSecond.y) * TELEMETRY_SCALE).roundToInt())
        payload.addInt32Le((degreesToRadians(gyroDegreesPerSecond.z) * TELEMETRY_SCALE).roundToInt())
        payload.addInt32Le((vehicleSpeedMetersPerSecond * MILLIMETERS_PER_METER).roundToInt())
        return frame(
            messageClass = RaceBoxFrameParser.MESSAGE_CLASS_TELEMETRY,
            messageId = RaceBoxFrameParser.MESSAGE_ID_BASIC_TELEMETRY,
            payload = payload.toByteArray(),
        )
    }

    fun unsupportedTelemetry(iTowMillis: Long): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.addUInt32Le(iTowMillis.toUInt())
        return frame(
            messageClass = RaceBoxFrameParser.MESSAGE_CLASS_TELEMETRY,
            messageId = 0x7F,
            payload = payload.toByteArray(),
        )
    }

    fun unsupportedMessage(): ByteArray =
        frame(
            messageClass = 0x7E,
            messageId = 0x01,
            payload = byteArrayOf(0x01, 0x02, 0x03),
        )

    fun frame(
        messageClass: Int,
        messageId: Int,
        payload: ByteArray,
    ): ByteArray {
        val headerAndPayload = mutableListOf<Byte>()
        headerAndPayload.addUByte(messageClass)
        headerAndPayload.addUByte(messageId)
        headerAndPayload.addUInt16Le(payload.size)
        payload.forEach { headerAndPayload += it }
        val (checksumA, checksumB) = checksum(headerAndPayload.toByteArray())

        val frame = mutableListOf<Byte>()
        frame.addUByte(RaceBoxFrameParser.SYNC_1)
        frame.addUByte(RaceBoxFrameParser.SYNC_2)
        frame += headerAndPayload
        frame.addUByte(checksumA)
        frame.addUByte(checksumB)
        return frame.toByteArray()
    }
}

private const val TIME_FLAG_VALID = 0x01
private const val FIX_FLAG_VALID_LOCATION = 0x01
private const val FIX_FLAG_DUAL_FREQUENCY = 0x02
private const val COORDINATE_SCALE = 10_000_000.0
private const val MILLIMETERS_PER_METER = 1_000.0
private const val HEADING_SCALE = 100_000.0
private const val TELEMETRY_SCALE = 1_000.0

private fun MutableList<Byte>.addUByte(value: Int) {
    add((value and 0xFF).toByte())
}

private fun MutableList<Byte>.addUInt16Le(value: Int) {
    addUByte(value)
    addUByte(value shr 8)
}

private fun MutableList<Byte>.addUInt32Le(value: UInt) {
    addUByte(value.toInt())
    addUByte((value shr 8).toInt())
    addUByte((value shr 16).toInt())
    addUByte((value shr 24).toInt())
}

private fun MutableList<Byte>.addInt32Le(value: Int) {
    addUInt32Le(value.toUInt())
}
