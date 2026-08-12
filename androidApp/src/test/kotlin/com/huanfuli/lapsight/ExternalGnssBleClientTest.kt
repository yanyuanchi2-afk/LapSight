package com.huanfuli.lapsight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalGnssBleClientTest {
    private val prefixes = listOf("LapSight-HUD", "LapSight-Cardputer", "RaceBox")

    @Test
    fun acceptsLapSightHudCardputerAndLegacyRaceBoxNames() {
        assertTrue(matchesExternalGnssDevice("LapSight-HUD", "AA:BB", null, prefixes))
        assertTrue(matchesExternalGnssDevice("lapsight-hud-01", "AA:BB", null, prefixes))
        assertTrue(matchesExternalGnssDevice("LapSight-Cardputer", "AA:BB", null, prefixes))
        assertTrue(matchesExternalGnssDevice("lapsight-cardputer-adv", "AA:BB", null, prefixes))
        assertTrue(matchesExternalGnssDevice("RaceBox Mini", "AA:BB", null, prefixes))
        assertFalse(matchesExternalGnssDevice("Unrelated Sensor", "AA:BB", null, prefixes))
    }

    @Test
    fun explicitAddressOverridesAdvertisedName() {
        assertTrue(matchesExternalGnssDevice(null, "AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff", prefixes))
        assertFalse(matchesExternalGnssDevice("LapSight-HUD", "11:22", "AA:BB", prefixes))
    }

    @Test
    fun extractsFragmentedHudFramesWithoutTreatingNmeaAsControl() {
        val decoder = HudControlLineDecoder()

        assertEquals(emptyList<String>(), decoder.accept("\$GNRMC,abc\r\nLS1,S,MARK".encodeToByteArray()))
        assertEquals(
            listOf("LS1,S,MARKING,1000,12,0,0,0"),
            decoder.accept("ING,1000,12,0,0,0\r\n".encodeToByteArray()),
        )
    }

    @Test
    fun discardsOversizedControlLineAndRecoversAtNewline() {
        val decoder = HudControlLineDecoder(maxLineBytes = 12)

        assertEquals(emptyList<String>(), decoder.accept("LS1,S,MARKING,too-long\n".encodeToByteArray()))
        assertEquals(listOf("LS1,A,X,OK"), decoder.accept("LS1,A,X,OK\n".encodeToByteArray()))
    }

    @Test
    fun framesBinaryRtcmWithinNegotiatedGattWriteSize() {
        val rtcm = ByteArray(37) { it.toByte() }
        val packets = frameRtcmForBle(rtcm, maxWriteBytes = 20)

        assertEquals(3, packets.size)
        assertTrue(packets.all { it.size <= 20 })
        assertTrue(packets.all { it.copyOfRange(0, 4).contentEquals(byteArrayOf(76, 83, 82, 1)) })
        assertTrue(
            packets.flatMap { it.copyOfRange(4, it.size).asList() }.toByteArray().contentEquals(rtcm),
        )
    }

    @Test
    fun extractsFragmentedGgaAndIgnoresOtherHudLines() {
        val decoder = NmeaGgaLineDecoder()

        assertEquals(emptyList<String>(), decoder.accept("LS1,S,IDLE\n\$GNG".encodeToByteArray()))
        assertEquals(
            listOf("\$GNGGA,123519.00,3900.0,N,08600.0,W,4,30,0.4,0.0,M,0.0,M,,"),
            decoder.accept("GA,123519.00,3900.0,N,08600.0,W,4,30,0.4,0.0,M,0.0,M,,\r\n".encodeToByteArray()),
        )
    }
}
