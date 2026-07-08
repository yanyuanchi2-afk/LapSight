package com.huanfuli.lapsight.shared.ui

import com.huanfuli.lapsight.shared.LocationFeedMode
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers Phase 6 06-03 Task 2 acceptance criteria for the External GNSS
 * source option: selectable separately from Phone GPS/Simulated only when
 * the platform provides it, locked while timing exactly like the existing
 * sources, and never silently substituted when unavailable (D-01/D-05/D-08).
 */
class ExternalGnssSettingsTest {

    @Test
    fun externalGnssOptionAbsentWhenPlatformHasNoProvider() {
        val options = locationSourceOptions(
            phoneGpsAvailable = true,
            externalGnssAvailable = false,
            locationFeedLocked = false,
        )
        assertEquals(
            listOf(LocationFeedMode.PhoneGps, LocationFeedMode.Simulated),
            options.map { it.mode },
        )
    }

    @Test
    fun externalGnssOptionPresentAndEnabledWhenAvailable() {
        val options = locationSourceOptions(
            phoneGpsAvailable = true,
            externalGnssAvailable = true,
            locationFeedLocked = false,
        )
        assertEquals(
            listOf(LocationFeedMode.PhoneGps, LocationFeedMode.Simulated, LocationFeedMode.ExternalGnss),
            options.map { it.mode },
        )
        assertTrue(options.single { it.mode == LocationFeedMode.ExternalGnss }.enabled)
    }

    @Test
    fun allSourcesLockTogetherWhileTiming() {
        val options = locationSourceOptions(
            phoneGpsAvailable = true,
            externalGnssAvailable = true,
            locationFeedLocked = true,
        )
        assertTrue(options.all { !it.enabled })
    }

    @Test
    fun requestedExternalGnssFallsBackToSimulatedWhenUnavailable() {
        assertEquals(
            LocationFeedMode.Simulated,
            resolveEffectiveLocationFeedMode(
                requested = LocationFeedMode.ExternalGnss,
                phoneGpsAvailable = true,
                externalGnssAvailable = false,
            ),
        )
    }

    @Test
    fun requestedExternalGnssIsHonoredWhenAvailable() {
        assertEquals(
            LocationFeedMode.ExternalGnss,
            resolveEffectiveLocationFeedMode(
                requested = LocationFeedMode.ExternalGnss,
                phoneGpsAvailable = true,
                externalGnssAvailable = true,
            ),
        )
    }

    @Test
    fun externalGnssSelectionIsIndependentOfPhoneGpsAvailability() {
        // External GNSS must be selectable even when Phone GPS is unavailable
        // on this platform (e.g. permission not yet granted) — the two
        // sources are independent, not a fallback chain into each other.
        assertEquals(
            LocationFeedMode.ExternalGnss,
            resolveEffectiveLocationFeedMode(
                requested = LocationFeedMode.ExternalGnss,
                phoneGpsAvailable = false,
                externalGnssAvailable = true,
            ),
        )
        val options = locationSourceOptions(
            phoneGpsAvailable = false,
            externalGnssAvailable = true,
            locationFeedLocked = false,
        )
        assertFalse(options.single { it.mode == LocationFeedMode.PhoneGps }.enabled)
        assertTrue(options.single { it.mode == LocationFeedMode.ExternalGnss }.enabled)
    }

    @Test
    fun externalGnssConnectionLabelCoversAllPhases() {
        assertEquals(StringsEn.externalGnssDisconnected, StringsEn.externalGnssConnectionLabel(ExternalGnssConnectionPhase.Disconnected))
        assertEquals(StringsEn.externalGnssScanning, StringsEn.externalGnssConnectionLabel(ExternalGnssConnectionPhase.Scanning))
        assertEquals(StringsEn.externalGnssConnecting, StringsEn.externalGnssConnectionLabel(ExternalGnssConnectionPhase.Connecting))
        assertEquals(StringsEn.externalGnssConnected, StringsEn.externalGnssConnectionLabel(ExternalGnssConnectionPhase.Connected))
        assertEquals(StringsEn.externalGnssReconnecting, StringsEn.externalGnssConnectionLabel(ExternalGnssConnectionPhase.Reconnecting))
        assertEquals(StringsEn.externalGnssConnectionFailed, StringsEn.externalGnssConnectionLabel(ExternalGnssConnectionPhase.Failed))
    }

    @Test
    fun resolveSourceNotePrefersExternalGnssNoteOverGenericPhoneGpsUnavailable() {
        // IN-01 regression: when External GNSS is the effective mode and Phone
        // GPS happens to be unavailable, the External-GNSS-specific note must
        // win, not the generic Phone-GPS-not-wired message.
        val note = resolveSourceNote(
            locationFeedLocked = false,
            effectiveLocationFeedMode = LocationFeedMode.ExternalGnss,
            phoneGpsAvailable = false,
            requestedLocationFeedMode = LocationFeedMode.ExternalGnss,
            phoneGpsPermissionGranted = false,
            strings = StringsEn,
        )
        assertEquals(StringsEn.externalGnssUnvalidatedNote, note)
    }
}
