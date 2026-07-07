---
phase: 06-external-gnss-and-sensor-ingestion
plan: 03
subsystem: android-location
tags: [android, ble, nmea0183, racebox, location-sample-provider, compose-settings, localization]

# Dependency graph
requires:
  - phase: 06-external-gnss-and-sensor-ingestion (06-01)
    provides: Nmea0183Parser, ExternalGnssModels, ExternalGnssReplayProvider
  - phase: 06-external-gnss-and-sensor-ingestion (06-02)
    provides: RaceBoxFrameParser, RaceBoxFrameBuilder, synthetic frame corpus
provides:
  - Android ExternalGnssByteStreamClient seam (interface + real BLE implementation) so any byte source, real or fake, can drive the provider
  - ExternalGnssLocationProvider: Android LocationSampleProvider wrapping the byte client with the shared NMEA/RaceBox parsers, connection-state and telemetry side channels
  - External GNSS as a third selectable Drive location source in Settings, locked while timing like Phone GPS/Simulated
  - LocationSource.ExternalGnss provenance flowing end-to-end from AppShell's SessionController.sourceForTrack
affects: [06-external-gnss-and-sensor-ingestion follow-ups, any future hardware-validation phase, glasses HUD idle-GPS state (already source-agnostic)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Injectable byte-stream client seam (ExternalGnssByteStreamClient) so protocol/queueing logic is unit-testable without BLE hardware"
    - "Pure pre-Compose helper functions (locationSourceOptions/resolveEffectiveLocationFeedMode) for settings selection logic, tested without a Compose test harness"

key-files:
  created:
    - androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt
    - androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProvider.kt
    - androidApp/src/test/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProviderTest.kt
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/ui/ExternalGnssSettingsTest.kt
  modified:
    - androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt
    - androidApp/src/main/AndroidManifest.xml
    - androidApp/build.gradle.kts
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/DisplaySettings.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/Localization.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/AppShell.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/App.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProvider.kt

key-decisions:
  - "ExternalGnssLocationProvider defaults to NMEA 0183 (lower risk, text-based, no undocumented GATT UUIDs required); RaceBox parsing is wired into the same provider class for a future protocol picker, but not yet exposed as a Settings choice"
  - "AndroidExternalGnssBleClient defaults to the Nordic UART Service GATT convention for the notify characteristic since RaceBox's official UUIDs are only published on request; both service/characteristic UUIDs are constructor-overridable for a confirmed receiver"
  - "External GNSS only appears in Settings when a real Android provider is wired (externalGnssAvailable); requesting it when unavailable (e.g. future iOS) silently falls back to Simulated, matching the existing phone-GPS-unavailable behavior"

patterns-established:
  - "Byte-stream provider seam: real BLE client and fake test double both implement ExternalGnssByteStreamClient, so ExternalGnssLocationProvider's parsing/queueing/telemetry logic is exercised by plain JVM unit tests with zero instrumentation"

requirements-completed: [EXT-01, EXT-02]

# Metrics
duration: 55min
completed: 2026-07-07
---

# Phase 06 Plan 03: Android External GNSS Provider and Settings UX Summary

**Android `ExternalGnssLocationProvider` (injectable BLE byte-stream client + shared NMEA0183/RaceBox parsers) wired into Settings as a third source alongside Phone GPS and Simulated, locked while timing and labeled protocol-preview/hardware-unvalidated.**

## Performance

- **Duration:** ~55 min
- **Completed:** 2026-07-07T22:20:21Z
- **Tasks:** 3 (2 code tasks + 1 verification-only task)
- **Files modified:** 9 modified, 4 created

## Accomplishments
- `ExternalGnssByteStreamClient` seam with a real Android BLE implementation (`AndroidExternalGnssBleClient`) that scans, connects, and subscribes to a UART-style notify characteristic, reporting connection phases (Scanning/Connecting/Connected/Reconnecting/Failed) without ever touching phone GPS.
- `ExternalGnssLocationProvider` implements `LocationSampleProvider` over that seam, decoding bytes through the shared `Nmea0183Parser`/`RaceBoxFrameParser`, bounding its queue at 1000 samples for high-rate bursts, and exposing telemetry as a side channel that never enters `nextSample`/`drainPending`.
- Settings shows External GNSS as a third segmented source option (only when Android has wired a provider), locked together with Phone GPS/Simulated while timing, with a localized "protocol preview / hardware-unvalidated" note in all six supported languages.
- `AppShell`/`App`/`MainActivity` wire the provider end-to-end: selecting External GNSS actually feeds the Drive dash, and `SessionController.sourceForTrack` tags saved sessions with `LocationSource.ExternalGnss` for honest provenance (Review already had "External GNSS" labeling from 06-01/06-02).
- Fake-byte-stream JVM unit tests (`androidApp/src/test`, no BLE/instrumentation) and pure-function shared tests (`ExternalGnssSettingsTest`) prove the acceptance criteria without hardware.

## Task Commits

Each task was committed atomically:

1. **Task 1: Android external provider shell** - `ce854de` (feat)
2. **Task 2: Settings source UX and localization** - `626fea8` (feat)
3. **Task 3: Build and regression verification** - no code changes; verification only (see below)

**Plan metadata:** committed together with this SUMMARY.

## Files Created/Modified
- `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt` - `ExternalGnssByteStreamClient` interface + `AndroidExternalGnssBleClient` real BLE implementation (scan/connect/notify)
- `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProvider.kt` - `LocationSampleProvider` composing the byte client with shared parsers, connection-state + telemetry StateFlows
- `androidApp/src/test/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProviderTest.kt` - fake byte-stream JVM tests (start/stop/reset, drain ordering, post-stop bytes ignored)
- `androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt` - constructs `ExternalGnssLocationProvider`, requests `BLUETOOTH_SCAN`, threads the provider into `App(...)`, treats it as a live feed for the timing foreground service
- `androidApp/src/main/AndroidManifest.xml` - adds `BLUETOOTH_SCAN` (`neverForLocation`)
- `androidApp/build.gradle.kts` - `testImplementation(libs.junit)` for the new plain-JVM test source set
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/DisplaySettings.kt` - `LocationFeedMode.ExternalGnss`
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt` - third segmented source option, `locationSourceOptions`/`resolveEffectiveLocationFeedMode` pure helpers, hardware-unvalidated note
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/Localization.kt` - `externalGnss`/`externalGnssUnvalidatedNote` strings in all 6 languages
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/AppShell.kt` - `externalGnssProvider` param, effective-mode/active-provider selection, `sourceForTrack` ExternalGnss branch
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/App.kt` - threads `externalGnssProvider` to `AppShell`
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProvider.kt` - `toLocationSample()` made public so the Android provider reuses the same fix-to-sample mapping as the shared replay provider
- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/ui/ExternalGnssSettingsTest.kt` - pure-function tests for source availability/locking/fallback

## Decisions Made
- Defaulted `ExternalGnssLocationProvider` to NMEA 0183 in this plan; the same class already supports RaceBox internally (constructor `protocol` parameter), but Settings does not yet expose a protocol picker — that stays a follow-up rather than scope creep here.
- `AndroidExternalGnssBleClient`'s GATT service/characteristic UUIDs default to the widely used Nordic UART Service convention (RaceBox's exact UUIDs are only published on request per 06-RESEARCH.md) and are constructor-overridable once a real receiver's documentation is available.
- Treated External GNSS as a "live" feed for the existing `TimingForegroundService` alongside Phone GPS, since it also represents an active location source while driving.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Wired the provider through AppShell/App/MainActivity, not just Settings display**
- **Found during:** Task 2 (Settings source UX)
- **Issue:** The plan's `files_modified` list covered `SettingsScreen.kt`/`Localization.kt`/`DisplaySettings.kt` but the acceptance criteria ("External GNSS can be selected separately... and feed injected external samples through the production provider seam") require the Drive dash to actually consume the provider when selected, not just show a UI toggle. Without wiring `AppShell`'s `effectiveLocationFeedMode`/`activeLocationProvider`/`sourceForTrack` and threading `externalGnssProvider` through `App.kt`/`MainActivity.kt`, the setting would be a non-functional stub.
- **Fix:** Added `externalGnssProvider` parameters to `AppShell`/`App`, extended the mode-resolution/provider-selection `when` blocks, added an `ExternalGnss` branch to `sourceForTrack`, and constructed/wired the real provider in `MainActivity`.
- **Files modified:** `shared/.../ui/AppShell.kt`, `shared/.../App.kt`, `androidApp/.../MainActivity.kt`
- **Verification:** `:shared:testAndroidHostTest` and `:androidApp:assembleDebug` both pass with the wiring in place.
- **Committed in:** `626fea8` (Task 2 commit)

**2. [Rule 2 - Missing Critical] Added BLUETOOTH_SCAN permission and JVM test infrastructure**
- **Found during:** Task 1 (Android provider shell)
- **Issue:** BLE scanning (device discovery) requires `BLUETOOTH_SCAN` on API 31+, not just the already-declared `BLUETOOTH_CONNECT`; without it the scan step of `AndroidExternalGnssBleClient` would silently fail at runtime. Separately, the acceptance criterion "fake byte-stream tests can produce external samples without hardware" had no existing JVM test source set in `androidApp` to exercise.
- **Fix:** Added the `BLUETOOTH_SCAN` (`neverForLocation`) manifest permission plus a runtime request alongside the existing `BLUETOOTH_CONNECT` request in `MainActivity`; added `androidApp/src/test/kotlin` with `testImplementation(libs.junit)` and a fake-byte-stream test class.
- **Files modified:** `androidApp/src/main/AndroidManifest.xml`, `androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt`, `androidApp/build.gradle.kts`, `androidApp/src/test/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProviderTest.kt`
- **Verification:** `:androidApp:testDebugUnitTest --tests "*ExternalGnssLocationProviderTest*"` and `:androidApp:assembleDebug` pass.
- **Committed in:** `ce854de` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (both Rule 2 - missing critical functionality)
**Impact on plan:** Both were necessary for the feature to be real and testable rather than a UI-only stub; no unrelated scope creep. `files_modified` in the plan frontmatter undercounted the wiring needed across `AppShell.kt`/`App.kt`/`MainActivity.kt`/`AndroidManifest.xml`/`build.gradle.kts`, which the plan's "agent's Discretion" note in `06-CONTEXT.md` explicitly permits.

## Issues Encountered
None - `local.properties` (SDK path + GitHub token) was missing in this worktree and had to be recreated from the main checkout's copy before Gradle could resolve dependencies; this is a worktree environment setup step, not a plan deviation, and the file remains gitignored/uncommitted.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: new-permission | androidApp/src/main/AndroidManifest.xml | Adds `BLUETOOTH_SCAN` (`neverForLocation`) so the app can discover nearby BLE devices by name/address when External GNSS is selected; scoped to device discovery only, no location derivation. |
| threat_flag: new-network-surface | androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt | New BLE GATT scan/connect/notify surface (`AndroidExternalGnssBleClient`); parses only through the existing clean-room NMEA/RaceBox parsers and never executes attacker-controlled data, but is a new local radio attack surface unvalidated against real hardware (D-02). |

## User Setup Required
None - no external service configuration required. Real external GNSS hardware validation remains an explicit open risk (D-02), unchanged by this plan.

## Next Phase Readiness
- External GNSS is now selectable end-to-end on Android (Settings -> Drive feed -> saved session provenance) as a protocol-preview source; real BLE receiver behavior remains untested until hardware is available.
- A future plan could expose a Settings protocol picker (NMEA vs RaceBox) now that `ExternalGnssLocationProvider` already supports both via its constructor `protocol` parameter.
- No blockers for closing out Phase 6 as a protocol-compatibility preview.

## Self-Check: PASSED

All 13 created/modified files verified present on disk; both task commits (`ce854de`, `626fea8`) verified present in `git log`.

---
*Phase: 06-external-gnss-and-sensor-ingestion*
*Completed: 2026-07-07*
