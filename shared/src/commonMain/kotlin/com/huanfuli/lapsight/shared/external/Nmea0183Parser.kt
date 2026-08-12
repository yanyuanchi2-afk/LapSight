package com.huanfuli.lapsight.shared.external

// NMEA sentences are <=82 characters per the standard; 4096 tolerates dozens of concatenated
// unterminated fragments before the buffer is reset, bounding memory growth from a
// malfunctioning or malicious peripheral that never sends \r/\n (WR-03).
private const val MAX_BUFFERED_NMEA_CHARS = 4_096

class Nmea0183Parser {

    private val streamBuffer = StringBuilder()
    private var currentFix = NmeaFixAccumulator()

    internal val bufferedCharCount: Int get() = streamBuffer.length

    fun accept(bytes: ByteArray): List<Nmea0183ParseResult> = accept(bytes.decodeToString())

    fun accept(text: String): List<Nmea0183ParseResult> {
        streamBuffer.append(text)
        if (streamBuffer.length > MAX_BUFFERED_NMEA_CHARS) {
            streamBuffer.clear()
        }
        val results = mutableListOf<Nmea0183ParseResult>()

        while (true) {
            val lineEnd = streamBuffer.indexOfLineEnd()
            if (lineEnd < 0) break

            val sentence = streamBuffer.substring(0, lineEnd)
            val consumeCount = if (
                lineEnd + 1 < streamBuffer.length &&
                streamBuffer[lineEnd] == '\r' &&
                streamBuffer[lineEnd + 1] == '\n'
            ) {
                lineEnd + 2
            } else {
                lineEnd + 1
            }
            streamBuffer.deleteRange(0, consumeCount)
            parseSentence(sentence).let { result ->
                if (result !is Nmea0183ParseResult.Ignored || result.reason != Nmea0183IgnoredReason.Empty) {
                    results += result
                }
            }
        }

        return results
    }

    fun parseSentence(sentence: String): Nmea0183ParseResult {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) {
            return Nmea0183ParseResult.Ignored(null, Nmea0183IgnoredReason.Empty)
        }

        val startIndex = trimmed.indexOf('$')
        if (startIndex < 0) {
            return Nmea0183ParseResult.Rejected(
                sentenceType = null,
                reason = Nmea0183RejectReason.MissingStart,
                rawSentence = trimmed,
            )
        }

        val normalized = trimmed.substring(startIndex)
        val starIndex = normalized.indexOf('*')
        val payload = if (starIndex >= 0) {
            normalized.substring(1, starIndex)
        } else {
            normalized.drop(1)
        }
        val checksumText = if (starIndex >= 0) {
            normalized.substring(starIndex + 1).take(2)
        } else {
            null
        }

        val sentenceType = payload.substringBefore(',').takeLast(3).uppercase()
        if (payload.isBlank() || sentenceType.isBlank()) {
            return reject(sentenceType.ifBlank { null }, Nmea0183RejectReason.MissingFields, normalized)
        }

        if (checksumText != null) {
            val expected = checksumText.toIntOrNull(16)
            if (checksumText.length != 2 || expected == null) {
                return reject(sentenceType, Nmea0183RejectReason.BadChecksumFormat, normalized)
            }
            val actual = nmeaChecksum(payload)
            if (actual != expected) {
                return reject(sentenceType, Nmea0183RejectReason.ChecksumMismatch, normalized)
            }
        }

        val fields = payload.split(',')
        return when {
            fields.firstOrNull()?.uppercase() == "PQTMEPE" -> parsePqtmEpe(fields, normalized)
            else -> when (sentenceType) {
            "RMC" -> parseRmc(fields, normalized)
            "GGA" -> parseGga(fields, normalized)
            "GNS" -> parseGns(fields, normalized)
            "GST" -> parseGst(fields, normalized)
            "VTG" -> parseVtg(fields, normalized)
            "GSA", "ZDA" -> Nmea0183ParseResult.Ignored(
                sentenceType = sentenceType,
                reason = Nmea0183IgnoredReason.DiagnosticOnly,
            )
            else -> Nmea0183ParseResult.Ignored(
                sentenceType = sentenceType,
                reason = Nmea0183IgnoredReason.UnsupportedSentence,
            )
            }
        }
    }

    private fun parseRmc(fields: List<String>, raw: String): Nmea0183ParseResult {
        if (fields.size < 9) return reject("RMC", Nmea0183RejectReason.MissingFields, raw)
        val time = parseTimeMillis(fields.getOrNull(1))
            ?: return reject("RMC", Nmea0183RejectReason.MalformedTime, raw)
        val status = fields.getOrNull(2).orEmpty().uppercase()
        val isValid = status == "A"

        currentFix = currentFix.atElapsed(time)
        if (!isValid) {
            currentFix = currentFix.copy(
                latitude = null,
                longitude = null,
                isValid = false,
                fixType = ExternalGnssFixType.NoFix,
            )
            return snapshot("RMC")
        }

        val latitude = parseCoordinate(fields.getOrNull(3), fields.getOrNull(4))
            ?: return reject("RMC", Nmea0183RejectReason.MalformedCoordinate, raw)
        val longitude = parseCoordinate(fields.getOrNull(5), fields.getOrNull(6))
            ?: return reject("RMC", Nmea0183RejectReason.MalformedCoordinate, raw)

        currentFix = currentFix.copy(
            latitude = latitude,
            longitude = longitude,
            speedMetersPerSecond = knotsToMetersPerSecond(fields.getOrNull(7)?.toDoubleOrNull()),
            headingDegrees = normalizeHeading(fields.getOrNull(8)?.toDoubleOrNull()),
            isValid = true,
            fixType = if (currentFix.fixType == ExternalGnssFixType.NoFix) {
                ExternalGnssFixType.Gps
            } else {
                currentFix.fixType
            },
        )
        return snapshot("RMC")
    }

    private fun parseGga(fields: List<String>, raw: String): Nmea0183ParseResult {
        if (fields.size < 10) return reject("GGA", Nmea0183RejectReason.MissingFields, raw)
        val time = parseTimeMillis(fields.getOrNull(1))
            ?: return reject("GGA", Nmea0183RejectReason.MalformedTime, raw)
        val qualityCode = fields.getOrNull(6)?.toIntOrNull() ?: 0
        val isValid = qualityCode > 0

        currentFix = currentFix.atElapsed(time)
        if (!isValid) {
            currentFix = currentFix.copy(
                latitude = null,
                longitude = null,
                altitudeMeters = null,
                satellitesInUse = fields.getOrNull(7)?.toIntOrNull(),
                hdop = fields.getOrNull(8)?.toDoubleOrNull(),
                isValid = false,
                fixType = ExternalGnssFixType.NoFix,
            )
            return snapshot("GGA")
        }

        val latitude = parseCoordinate(fields.getOrNull(2), fields.getOrNull(3))
            ?: return reject("GGA", Nmea0183RejectReason.MalformedCoordinate, raw)
        val longitude = parseCoordinate(fields.getOrNull(4), fields.getOrNull(5))
            ?: return reject("GGA", Nmea0183RejectReason.MalformedCoordinate, raw)

        currentFix = currentFix.copy(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = fields.getOrNull(9)?.toDoubleOrNull(),
            satellitesInUse = fields.getOrNull(7)?.toIntOrNull(),
            hdop = fields.getOrNull(8)?.toDoubleOrNull(),
            isValid = true,
            fixType = ggaFixType(qualityCode),
        )
        return snapshot("GGA")
    }

    private fun parseGns(fields: List<String>, raw: String): Nmea0183ParseResult {
        if (fields.size < 10) return reject("GNS", Nmea0183RejectReason.MissingFields, raw)
        val time = parseTimeMillis(fields.getOrNull(1))
            ?: return reject("GNS", Nmea0183RejectReason.MalformedTime, raw)
        val mode = fields.getOrNull(6).orEmpty().uppercase()
        val isValid = mode.isNotBlank() && mode.any { it != 'N' }

        currentFix = currentFix.atElapsed(time)
        if (!isValid) {
            currentFix = currentFix.copy(
                latitude = null,
                longitude = null,
                altitudeMeters = null,
                satellitesInUse = fields.getOrNull(7)?.toIntOrNull(),
                hdop = fields.getOrNull(8)?.toDoubleOrNull(),
                isValid = false,
                fixType = ExternalGnssFixType.NoFix,
            )
            return snapshot("GNS")
        }

        val latitude = parseCoordinate(fields.getOrNull(2), fields.getOrNull(3))
            ?: return reject("GNS", Nmea0183RejectReason.MalformedCoordinate, raw)
        val longitude = parseCoordinate(fields.getOrNull(4), fields.getOrNull(5))
            ?: return reject("GNS", Nmea0183RejectReason.MalformedCoordinate, raw)

        currentFix = currentFix.copy(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = fields.getOrNull(9)?.toDoubleOrNull(),
            satellitesInUse = fields.getOrNull(7)?.toIntOrNull(),
            hdop = fields.getOrNull(8)?.toDoubleOrNull(),
            isValid = true,
            fixType = gnsFixType(mode),
        )
        return snapshot("GNS")
    }

    private fun parseVtg(fields: List<String>, raw: String): Nmea0183ParseResult {
        if (fields.size < 8) return reject("VTG", Nmea0183RejectReason.MissingFields, raw)
        val heading = normalizeHeading(fields.getOrNull(1)?.toDoubleOrNull())
        val kmhSpeed = fields.getOrNull(7)?.toDoubleOrNull()?.let { it / 3.6 }
        val knotsSpeed = knotsToMetersPerSecond(fields.getOrNull(5)?.toDoubleOrNull())

        currentFix = currentFix.copy(
            headingDegrees = heading ?: currentFix.headingDegrees,
            speedMetersPerSecond = kmhSpeed ?: knotsSpeed ?: currentFix.speedMetersPerSecond,
        )

        return if (currentFix.latitude != null || currentFix.longitude != null || !currentFix.isValid) {
            snapshot("VTG")
        } else {
            Nmea0183ParseResult.Ignored("VTG", Nmea0183IgnoredReason.NoCurrentFix)
        }
    }

    private fun parseGst(fields: List<String>, raw: String): Nmea0183ParseResult {
        if (fields.size < 9) return reject("GST", Nmea0183RejectReason.MissingFields, raw)
        val time = parseTimeMillis(fields.getOrNull(1))
            ?: return reject("GST", Nmea0183RejectReason.MalformedTime, raw)
        val semiMajorMeters = fields.getOrNull(3)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val latitudeSigmaMeters = fields.getOrNull(6)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val longitudeSigmaMeters = fields.getOrNull(7)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val horizontalAccuracyMeters = semiMajorMeters
            ?: listOfNotNull(latitudeSigmaMeters, longitudeSigmaMeters).maxOrNull()
            ?: return reject("GST", Nmea0183RejectReason.MissingFields, raw)
        val verticalAccuracyMeters = fields.getOrNull(8)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

        currentFix = currentFix.atElapsed(time).copy(
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            verticalAccuracyMeters = verticalAccuracyMeters,
        )
        return if (currentFix.latitude != null || currentFix.longitude != null || !currentFix.isValid) {
            snapshot("GST")
        } else {
            Nmea0183ParseResult.Ignored("GST", Nmea0183IgnoredReason.NoCurrentFix)
        }
    }

    private fun parsePqtmEpe(fields: List<String>, raw: String): Nmea0183ParseResult {
        if (fields.size < 7 || fields[1] != "2") {
            return reject("PQTMEPE", Nmea0183RejectReason.MissingFields, raw)
        }
        val verticalAccuracyMeters = fields[4].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val horizontalAccuracyMeters = fields[5].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return reject("PQTMEPE", Nmea0183RejectReason.MissingFields, raw)
        currentFix = currentFix.copy(
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            verticalAccuracyMeters = verticalAccuracyMeters,
        )
        return if (currentFix.latitude != null || currentFix.longitude != null || !currentFix.isValid) {
            snapshot("PQTMEPE")
        } else {
            Nmea0183ParseResult.Ignored("PQTMEPE", Nmea0183IgnoredReason.NoCurrentFix)
        }
    }

    private fun snapshot(sentenceType: String): Nmea0183ParseResult.Snapshot =
        Nmea0183ParseResult.Snapshot(
            ExternalGnssFixSnapshot(
                elapsedMillis = currentFix.elapsedMillis,
                latitude = currentFix.latitude,
                longitude = currentFix.longitude,
                speedMetersPerSecond = currentFix.speedMetersPerSecond,
                headingDegrees = currentFix.headingDegrees,
                altitudeMeters = currentFix.altitudeMeters,
                quality = ExternalGnssFixQuality(
                    isValid = currentFix.isValid,
                    fixType = if (currentFix.isValid) currentFix.fixType else ExternalGnssFixType.NoFix,
                    satellitesInUse = currentFix.satellitesInUse,
                    hdop = currentFix.hdop,
                    horizontalAccuracyMeters = currentFix.horizontalAccuracyMeters,
                    verticalAccuracyMeters = currentFix.verticalAccuracyMeters,
                ),
                source = ExternalGnssSourceMetadata(
                    protocol = ExternalGnssProtocol.Nmea0183,
                    sentenceId = sentenceType,
                    hardwareValidationStatus = ExternalGnssHardwareValidationStatus.Unverified,
                ),
            ),
        )

    private fun reject(
        sentenceType: String?,
        reason: Nmea0183RejectReason,
        raw: String,
    ): Nmea0183ParseResult.Rejected =
        Nmea0183ParseResult.Rejected(
            sentenceType = sentenceType,
            reason = reason,
            rawSentence = raw,
        )
}

sealed class Nmea0183ParseResult {
    data class Snapshot(val snapshot: ExternalGnssFixSnapshot) : Nmea0183ParseResult()
    data class Rejected(
        val sentenceType: String?,
        val reason: Nmea0183RejectReason,
        val rawSentence: String,
    ) : Nmea0183ParseResult()
    data class Ignored(
        val sentenceType: String?,
        val reason: Nmea0183IgnoredReason,
    ) : Nmea0183ParseResult()
}

enum class Nmea0183RejectReason {
    MissingStart,
    BadChecksumFormat,
    ChecksumMismatch,
    MissingFields,
    MalformedTime,
    MalformedCoordinate,
}

enum class Nmea0183IgnoredReason {
    Empty,
    UnsupportedSentence,
    DiagnosticOnly,
    NoCurrentFix,
}

private data class NmeaFixAccumulator(
    val elapsedMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val headingDegrees: Double? = null,
    val altitudeMeters: Double? = null,
    val satellitesInUse: Int? = null,
    val hdop: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
    val isValid: Boolean = false,
    val fixType: ExternalGnssFixType = ExternalGnssFixType.NoFix,
)

private fun NmeaFixAccumulator.atElapsed(value: Long): NmeaFixAccumulator =
    if (elapsedMillis == null || elapsedMillis == value) {
        copy(elapsedMillis = value)
    } else {
        copy(
            elapsedMillis = value,
            horizontalAccuracyMeters = null,
            verticalAccuracyMeters = null,
        )
    }

private fun StringBuilder.indexOfLineEnd(): Int {
    for (index in 0 until length) {
        val char = this[index]
        if (char == '\r' || char == '\n') return index
    }
    return -1
}

private fun nmeaChecksum(payload: String): Int =
    payload.fold(0) { checksum, char -> checksum xor char.code }

private fun parseTimeMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val whole = raw.substringBefore('.')
    if (whole.length < 6) return null
    val hours = whole.substring(0, 2).toIntOrNull() ?: return null
    val minutes = whole.substring(2, 4).toIntOrNull() ?: return null
    val seconds = whole.substring(4, 6).toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59 || seconds !in 0..59) return null

    val fraction = raw.substringAfter('.', missingDelimiterValue = "")
    val fractionalMillis = if (fraction.isBlank()) {
        0
    } else {
        val scaled = "0.$fraction".toDoubleOrNull() ?: return null
        (scaled * 1_000.0).toInt()
    }

    return ((hours * 60L + minutes) * 60L + seconds) * 1_000L + fractionalMillis
}

private fun parseCoordinate(raw: String?, directionRaw: String?): Double? {
    if (raw.isNullOrBlank() || directionRaw.isNullOrBlank()) return null
    val direction = directionRaw.uppercase()
    val degreeDigits = when (direction) {
        "N", "S" -> 2
        "E", "W" -> 3
        else -> return null
    }
    if (raw.length <= degreeDigits) return null

    val degrees = raw.take(degreeDigits).toDoubleOrNull() ?: return null
    val minutes = raw.drop(degreeDigits).toDoubleOrNull() ?: return null
    if (minutes < 0.0 || minutes >= 60.0) return null

    val signed = degrees + minutes / 60.0
    val coordinate = if (direction == "S" || direction == "W") -signed else signed
    return when (direction) {
        "N", "S" -> coordinate.takeIf { it in -90.0..90.0 }
        else -> coordinate.takeIf { it in -180.0..180.0 }
    }
}

private fun knotsToMetersPerSecond(knots: Double?): Double? =
    knots?.let { it * 0.514444 }

private fun normalizeHeading(heading: Double?): Double? =
    heading?.let {
        val normalized = it % 360.0
        if (normalized < 0.0) normalized + 360.0 else normalized
    }

private fun ggaFixType(qualityCode: Int): ExternalGnssFixType =
    when (qualityCode) {
        0 -> ExternalGnssFixType.NoFix
        2 -> ExternalGnssFixType.DifferentialGps
        4 -> ExternalGnssFixType.RtkFixed
        5 -> ExternalGnssFixType.RtkFloat
        6 -> ExternalGnssFixType.Estimated
        else -> ExternalGnssFixType.Gps
    }

private fun gnsFixType(mode: String): ExternalGnssFixType =
    when {
        'R' in mode -> ExternalGnssFixType.RtkFixed
        'F' in mode -> ExternalGnssFixType.RtkFloat
        'D' in mode -> ExternalGnssFixType.DifferentialGps
        'P' in mode -> ExternalGnssFixType.Precise
        'E' in mode -> ExternalGnssFixType.Estimated
        else -> ExternalGnssFixType.Gps
    }
