package com.huanfuli.lapsight.shared.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Nmea0183ParserTest {

    @Test
    fun decodesValidRmcGgaVtgFix() {
        val parser = Nmea0183Parser()
        val results = parser.accept(
            sentence("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W") +
                sentence("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,") +
                sentence("GPVTG,084.4,T,,M,022.4,N,041.5,K"),
        )

        val snapshots = results.snapshots()
        assertEquals(3, snapshots.size)
        val latest = snapshots.last()
        assertTrue(latest.quality.isValid)
        assertTrue(latest.hasUsableLocation)
        assertEquals(48.1173, latest.latitude ?: 0.0, 0.000001)
        assertEquals(11.516666666666667, latest.longitude ?: 0.0, 0.000001)
        assertEquals(84.4, latest.headingDegrees ?: 0.0, 0.000001)
        assertEquals(41.5 / 3.6, latest.speedMetersPerSecond ?: 0.0, 0.000001)
        assertEquals(545.4, latest.altitudeMeters ?: 0.0, 0.000001)
        assertEquals(8, latest.quality.satellitesInUse)
        assertEquals(0.9, latest.quality.hdop ?: 0.0, 0.000001)
        assertEquals(ExternalGnssProtocol.Nmea0183, latest.source.protocol)
        assertEquals(ExternalGnssHardwareValidationStatus.Unverified, latest.source.hardwareValidationStatus)
    }

    @Test
    fun decodesGnsFixQuality() {
        val snapshot = Nmea0183Parser()
            .accept(sentence("GNGNS,123521,4807.100,N,01131.100,E,AN,09,0.8,546.0,46.9,,"))
            .snapshots()
            .single()

        assertTrue(snapshot.quality.isValid)
        assertEquals(ExternalGnssFixType.Gps, snapshot.quality.fixType)
        assertEquals(9, snapshot.quality.satellitesInUse)
        assertEquals(546.0, snapshot.altitudeMeters ?: 0.0, 0.000001)
    }

    @Test
    fun noFixIsTypedDataNotCrash() {
        val snapshot = Nmea0183Parser()
            .accept(sentence("GPRMC,123520,V,,,,,,,230394,,,N"))
            .snapshots()
            .single()

        assertFalse(snapshot.quality.isValid)
        assertFalse(snapshot.hasUsableLocation)
        assertEquals(ExternalGnssFixType.NoFix, snapshot.quality.fixType)
    }

    @Test
    fun badChecksumIsRejectedAsData() {
        val rejected = Nmea0183Parser()
            .parseSentence("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*00")
            as? Nmea0183ParseResult.Rejected

        assertNotNull(rejected)
        assertEquals(Nmea0183RejectReason.ChecksumMismatch, rejected.reason)
    }

    @Test
    fun malformedCoordinateIsRejectedAsData() {
        val rejected = Nmea0183Parser()
            .parseSentence(sentence("GPRMC,123519,A,480X.038,N,01131.000,E,022.4,084.4,230394,003.1,W"))
            as? Nmea0183ParseResult.Rejected

        assertNotNull(rejected)
        assertEquals(Nmea0183RejectReason.MalformedCoordinate, rejected.reason)
    }

    @Test
    fun fragmentedInputMatchesWholeSentenceInput() {
        val burst =
            sentence("GPRMC,123519,A,4807.038,N,01131.000,E,010.0,084.4,230394,,,A") +
                sentence("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,") +
                sentence("GPVTG,084.4,T,,M,010.0,N,018.5,K")

        val whole = Nmea0183Parser().accept(burst).snapshots()
        val fragmentedParser = Nmea0183Parser()
        val fragmentedResults = mutableListOf<Nmea0183ParseResult>()
        listOf(
            burst.substring(0, 9),
            burst.substring(9, 31),
            burst.substring(31, 72),
            burst.substring(72),
        ).forEach { chunk ->
            fragmentedResults += fragmentedParser.accept(chunk.encodeToByteArray())
        }

        assertEquals(whole, fragmentedResults.snapshots())
    }

    @Test
    fun unterminatedOversizedStreamDoesNotWedgeTheParser() {
        val parser = Nmea0183Parser()
        val results = parser.accept("X".repeat(5_000))

        assertEquals(emptyList(), results)
        assertEquals(
            0,
            parser.bufferedCharCount,
            "an oversized unterminated stream must not grow the buffer without bound",
        )

        val recovered = parser
            .accept(sentence("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"))
            .snapshots()
            .single()
        assertTrue(recovered.quality.isValid)
    }

    @Test
    fun hdopIsNotMislabeledAsHorizontalAccuracyMeters() {
        val snapshot = Nmea0183Parser()
            .accept(sentence("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
            .snapshots()
            .single()

        assertEquals(0.9, snapshot.quality.hdop ?: 0.0, 0.000001)
        assertEquals(null, snapshot.quality.horizontalAccuracyMeters)
    }

    @Test
    fun gstSuppliesMeterAccuracyForTheExistingLocationSampleContract() {
        val snapshots = Nmea0183Parser().accept(
            sentence("GPGGA,123519,4807.038,N,01131.000,E,4,18,0.7,545.4,M,46.9,M,,") +
                sentence("GNGST,123519,0.42,0.36,0.21,74.0,0.25,0.31,0.52"),
        ).snapshots()

        val latest = snapshots.last()
        assertEquals(0.36, latest.quality.horizontalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(0.52, latest.quality.verticalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(18, latest.quality.satellitesInUse)
        assertEquals(ExternalGnssFixType.RtkFixed, latest.quality.fixType)
    }

    @Test
    fun quectelEpeSuppliesMeterAccuracyWhenLc29hDoesNotOutputGst() {
        val snapshots = Nmea0183Parser().accept(
            sentence("GNGGA,123519,4807.038,N,01131.000,E,4,38,0.7,545.4,M,46.9,M,,") +
                sentence("PQTMEPE,2,0.031,0.028,0.052,0.042,0.067"),
        ).snapshots()

        val latest = snapshots.last()
        assertEquals(0.042, latest.quality.horizontalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(0.052, latest.quality.verticalAccuracyMeters ?: 0.0, 0.000001)
        assertEquals(38, latest.quality.satellitesInUse)
    }

    @Test
    fun highRateBurstEmitsEveryDecodedFixInOrder() {
        val burst = (0 until 12).joinToString(separator = "") { index ->
            val seconds = index.toString().padStart(2, '0')
            val latFraction = (38 + index).toString().padStart(3, '0')
            val lonFraction = (100 + index).toString().padStart(3, '0')
            sentence(
                "GPRMC,1235$seconds,A,4807.$latFraction,N,01131.$lonFraction,E,012.0,091.0,230394,,,A",
            )
        }

        val snapshots = Nmea0183Parser().accept(burst).snapshots()

        assertEquals(12, snapshots.size)
        snapshots.zipWithNext().forEach { (previous, next) ->
            assertTrue(next.elapsedMillis ?: 0L > previous.elapsedMillis ?: 0L)
            assertTrue(next.latitude ?: 0.0 > previous.latitude ?: 0.0)
        }
    }
}

private fun List<Nmea0183ParseResult>.snapshots(): List<ExternalGnssFixSnapshot> =
    filterIsInstance<Nmea0183ParseResult.Snapshot>().map { it.snapshot }

private fun sentence(payload: String): String {
    val checksum = payload.fold(0) { current, char -> current xor char.code }
    return "\$$payload*${checksum.toString(16).uppercase().padStart(2, '0')}\r\n"
}
