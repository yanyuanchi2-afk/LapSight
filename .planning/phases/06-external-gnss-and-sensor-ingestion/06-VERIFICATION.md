---
phase: 06-external-gnss-and-sensor-ingestion
verified: 2026-07-07T22:47:53Z
status: gaps_found
score: 6/7 must-haves verified
overrides_applied: 0
gaps:
  - truth: "UI exposes external GNSS source quality and connection/status without returning diagnostic raw GPS tools to the main Drive surface (ROADMAP Success Criterion 4)"
    status: partial
    reason: "Source 'quality' is exposed generically (Drive's existing source-agnostic accuracy/rate warnings apply to any LocationSampleProvider, including ExternalGnss), and Settings shows a static 'protocol preview / hardware-unvalidated' note. But the live BLE 'connection/status' signal is not exposed anywhere in the UI. ExternalGnssLocationProvider.connectionState (a StateFlow<ExternalGnssConnectionState> with Disconnected/Scanning/Connecting/Connected/Reconnecting/Failed phases, purpose-built in 06-03 per its own acceptance criteria 'Handle start/stop/reset, connection state, reconnect gaps') is never read by SettingsScreen.kt, AppShell.kt, App.kt, or MainActivity.kt — it is constructed in MainActivity.kt but never threaded into Compose UI. Confirmed by exhaustive grep: the only '.connectionState' consumer in the whole app is the unrelated glasses bridge (MainActivity.kt:319). A user who selects External GNSS while no receiver is in range/paired sees the same generic 'no GPS fix' warning phone GPS shows without a fix — no 'Scanning...', 'Connecting...', or 'Failed to connect' feedback, even though that exact state machine already exists in code."
    artifacts:
      - path: "androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProvider.kt"
        issue: "connectionState StateFlow is built and updated (start/stop/handlePhase) but has zero UI consumers anywhere in the codebase"
      - path: "shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt"
        issue: "Only a static hardware-unvalidated note string is shown for External GNSS (line ~209); no live connection-phase indicator, unlike the analogous GlassesSettingsCard pattern (lines 259-264) that already displays live glasses connection state in the same screen"
      - path: "androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt"
        issue: "externalGnssProvider is constructed and threaded into App(...) as a LocationSampleProvider (line 240) but its .connectionState is never passed to App/AppShell/Settings, unlike glassesConnectionState which is threaded at line 258"
    missing:
      - "Wire ExternalGnssLocationProvider.connectionState into SettingsScreen (or an equivalent low-interaction surface) following the same pattern already used for GlassesSettingsCard/glassesConnectionState, so a user can see Scanning/Connecting/Connected/Reconnecting/Failed instead of only a static disclaimer note"
deferred: []
human_verification: []
---

# Phase 6: External GNSS and Sensor Ingestion — Verification Report

**Phase Goal:** As a user who needs better timing precision, I want to connect an external GPS receiver, so that LapSight can exceed phone GPS limitations. (Mode: mvp, explicitly narrowed by ROADMAP/06-VALIDATION.md/06-HARDWARE-RISK.md to "protocol-complete, hardware-unvalidated" — real hardware validation is deliberately deferred, and that is the accepted closeout bar, not full hardware testing.)

**Verified:** 2026-07-07T22:47:53Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

Primary truths are ROADMAP.md's 5 Success Criteria for Phase 6 (the non-negotiable contract), plus two cross-plan must-haves (D-07 licensing boundary, D-08 Drive-surface UX scope) that are repeated verbatim across multiple PLAN frontmatters and are independently testable.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | App can decode external GNSS samples from protocol fixtures/replay, including NMEA 0183 and RaceBox-family synthetic frames (SC1) | VERIFIED | `Nmea0183Parser.kt` (RMC/GGA/GNS/VTG, checksum validation, typed reject/ignore outcomes) and `RaceBoxFrameParser.kt` (UBX-like binary framing, checksum, telemetry frames) both exist and compile. `Nmea0183ParserTest`, `RaceBoxFrameParserTest` pass under `./gradlew :shared:testAndroidHostTest` (re-run independently during this verification, BUILD SUCCESSFUL). |
| 2 | App records sample source, protocol, update frequency, and hardware-validation status (SC2) | VERIFIED | `ExternalGnssSourceMetadata` (`ExternalGnssModels.kt:79-86`) carries `protocol`, `updateRateHz`, `hardwareValidationStatus` (defaults to `Unverified`). `RaceBoxFrameParser.kt:113/212` computes `updateRateHz` per decoded fix; `RaceBoxFrameParserTest.kt:111-220` asserts 25 Hz detection and that reconnect gaps are NOT smoothed into a false rate. |
| 3 | Lap engine works unchanged regardless of phone GPS, simulated replay, or external GNSS input (SC3) | VERIFIED | `grep -rn "ExternalGnss" shared/.../lap/LapEngine.kt` returns zero matches — no source-specific branching in the lap engine. `ExternalGnssTimingPipelineTest.kt` (6 tests) feeds decoded NMEA/RaceBox samples through the real `SessionController`/`TimingSessionRecorder` and asserts lap-count/lap-duration parity with the raw-fixture baseline; test passes. |
| 4 | UI exposes external GNSS source quality and connection/status without returning diagnostic raw GPS tools to the main Drive surface (SC4) | **FAILED (partial)** | "Quality" half holds: Drive's existing source-agnostic `gpsQualityWarning`/`ReadyBlocker.PoorAccuracy`/`LowSampleRate` indicators (`DriveTelemetry.kt:209-223`) apply automatically to ExternalGnss samples since they share `LocationSampleProvider`/`DriveMarkingSnapshot`, and no new diagnostic panel was added to `DriveScreen.kt`/`DriveConfigSurface.kt` (D-08 honored). "Connection/status" half fails: `ExternalGnssLocationProvider.connectionState` (a full Scanning/Connecting/Connected/Reconnecting/Failed state machine) is constructed in `MainActivity.kt:226-232` but never threaded to `App`/`AppShell`/`SettingsScreen` — confirmed by exhaustive grep, the only live `.connectionState` UI consumer in the app is the unrelated glasses bridge. Settings shows only a static localized note string (`externalGnssUnvalidatedNote`), not live connection phase. See gap below. |
| 5 | Documentation clearly separates protocol support from unvalidated hardware support (SC5) | VERIFIED | `docs/EXTERNAL-GNSS.md` (163 lines) explicitly states "Status: Protocol-complete, hardware-unvalidated" in its header, separates NMEA 0183 vs. RaceBox-preview sections, states the EXT-03 telemetry-vs-timing boundary, and gives a concrete real-receiver feedback checklist. `06-HARDWARE-RISK.md` (81 lines) is a 7-item open risk register explicitly left unresolved at closeout. |
| 6 | RaceBox implementation is clean-room; no GPL or unlicensed RaceBox client/emulator code copied (D-07, repeated in 06-02/06-CONTEXT) | VERIFIED | `grep -rniE "gpl\|copyright.*racebox\|racebox.*emulator"` across all external-GNSS source files returns zero matches. |
| 7 | Drive remains low-interaction; detailed diagnostics do not return to the main Drive surface (D-08, 06-03 must_have) | VERIFIED | `grep -n "ExternalGnss" DriveScreen.kt DriveConfigSurface.kt` shows only the existing `GpsFixStatus` mapping (1 line); no new External-GNSS-specific diagnostic UI was added to the Drive surface. |

**Score:** 6/7 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `shared/.../external/ExternalGnssModels.kt` | Platform-free protocol/source/fix/telemetry metadata | VERIFIED | 3,304 bytes; no Android/iOS/BLE imports; `ExternalGnssHardwareValidationStatus` defaults to Unverified/Unknown |
| `shared/.../external/Nmea0183Parser.kt` | Pure NMEA byte/sentence parser → decoded fix state | VERIFIED | Contains RMC/GGA/GNS/VTG parsing, typed `Rejected`/`Ignored`/`Snapshot` outcomes, no throws for bad input |
| `shared/.../external/ExternalGnssReplayProvider.kt` | Replay-backed `LocationSampleProvider` for decoded external GNSS | VERIFIED | Implements `LocationSampleProvider`; `toLocationSample()` maps to `LocationSource.ExternalGnss`; public helper reused by Android provider |
| `shared/.../external/RaceBoxFrameParser.kt` | RaceBox binary frame parser → decoded external fix | VERIFIED (with known bug, see Anti-Patterns) | Contains checksum/iTOW/fix handling; CR-01 (unfixed) causes data loss when the 2-byte sync sequence is split exactly across two `accept()` calls — a real defect, but does not contradict any literal must_have wording (fragmentation tests pass since none split exactly at the sync boundary) |
| `shared/.../external/RaceBoxFrameBuilder.kt` | Synthetic clean-room RaceBox frame builder for tests | VERIFIED | Test-fixture-only, no production coupling |
| `androidApp/.../ExternalGnssLocationProvider.kt` | Android provider wrapper for external GNSS byte streams | VERIFIED, WIRED for samples / ORPHANED for connection state | Implements `LocationSampleProvider`; threaded through `App`/`AppShell`/`MainActivity` for samples (WIRED). `connectionState` StateFlow exists but has no UI consumer (ORPHANED) — see gap |
| `androidApp/.../ExternalGnssBleClient.kt` | Real Android BLE client + injectable interface | VERIFIED | `AndroidExternalGnssBleClient` scans/connects/subscribes via Nordic UART Service convention (constructor-overridable); zero automated test coverage of this class itself (flagged WR-05 in 06-REVIEW.md, accepted risk since it requires real/mocked BLE stack) |
| `docs/EXTERNAL-GNSS.md` | User/developer documentation | VERIFIED | Present, substantive, conservative claims |
| `.planning/.../06-HARDWARE-RISK.md` | Hardware risk register | VERIFIED | Present, 7-item register, explicitly left open |
| `shared/src/commonTest/.../ExternalGnssTimingPipelineTest.kt` | Full pipeline replay proof | VERIFIED | 6 tests, all passing, exercises real `SessionController` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `ExternalGnssReplayProvider` | `LocationSampleProvider` | `drainPending emits queued LocationSample(source = ExternalGnss)` | WIRED | `toLocationSample()` sets `source = LocationSource.ExternalGnss`; `ExternalGnssReplayProviderTest` asserts this |
| `ExternalGnssLocationProvider` (Android) | `AppShell`/`App`/`MainActivity` | constructor param + `effectiveLocationFeedMode`/`sourceForTrack` `when` branches | WIRED | `AppShell.kt:116-192`, `App.kt:54/92`, `MainActivity.kt:226-240` all thread the provider through; confirmed by direct read, not just SUMMARY claim |
| `ExternalGnssLocationProvider.connectionState` | Settings/Drive UI | (expected) StateFlow collected by a Compose surface | **NOT_WIRED** | Zero UI consumers found anywhere in `androidApp/src` or `shared/src` (grep confirmed); this is the direct cause of the SC4 gap above |
| `RaceBoxFrameParser`/`Nmea0183Parser` | `SessionController`/`LapEngine` | decode → `LocationSample` → real timing pipeline | WIRED | `ExternalGnssTimingPipelineTest` proves this end-to-end with lap-count/timing parity assertions |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| `ExternalGnssLocationProvider` (Android, sample path) | `LocationSample` queue | `Nmea0183Parser`/`RaceBoxFrameParser` decode of injected/BLE bytes | Yes (real decode logic, proven by fake-byte-stream JVM tests) | FLOWING |
| Settings "External GNSS" source note | `sourceNote` string | Static localized string (`externalGnssUnvalidatedNote`), not `connectionState` | No — static text regardless of actual connection phase | STATIC (contributes to the SC4 gap) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Shared host tests for all 4 phase plans compile and pass | `./gradlew :shared:testAndroidHostTest --tests "*Nmea0183ParserTest*" --tests "*RaceBoxFrameParserTest*" --tests "*ExternalGnssReplayProviderTest*" --tests "*ExternalGnssTimingPipelineTest*" --tests "*ExternalGnssSettingsTest*"` | BUILD SUCCESSFUL | PASS |
| Full shared host test suite (regression check) | `./gradlew :shared:testAndroidHostTest` | BUILD SUCCESSFUL | PASS |
| Android debug build compiles with all Phase 6 wiring | `./gradlew :androidApp:assembleDebug` | BUILD SUCCESSFUL | PASS |
| RaceBox sync-byte-split fragmentation (CR-01) is a real, reproducible defect | Direct code read of `RaceBoxFrameParser.kt:17-31` `indexOfSync()`/`syncIndex < 0` branch | `streamBuffer = ByteArray(0)` unconditionally wipes a trailing lone `0xB5` byte instead of preserving it | CONFIRMED (matches 06-REVIEW.md CR-01 exactly; unfixed) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| EXT-01 | 06-01, 06-02, 06-03, 06-04 | User can connect an external GNSS receiver over BLE, Bluetooth serial, Wi-Fi, or TCP/NMEA | SATISFIED (protocol-preview scope) | NMEA/RaceBox parsers + `AndroidExternalGnssBleClient` (BLE transport) implemented and unit-tested with fake byte streams; real hardware connection explicitly and correctly left unvalidated per phase's accepted closeout bar |
| EXT-02 | 06-03, 06-04 | App can prefer external GNSS over phone GPS when connected | SATISFIED (manual-preference interpretation) | `SettingsScreen`/`AppShell` let the user manually select External GNSS ahead of Phone GPS as the active/locked source; this is a documented design choice (06-VALIDATION.md Requirement Coverage table) rather than automatic detection-based failover — reasonable given no reliable "connected" signal reaches the UI (see SC4 gap, which is directly related) |
| EXT-03 | 06-01, 06-02, 06-04 | App can ingest basic IMU or vehicle telemetry when available | SATISFIED | `ExternalGnssTelemetryMetadata` modeled, parsed from RaceBox basic-telemetry frames, proven never to enter `LocationSample`/timing via `ExternalGnssTimingPipelineTest.raceBoxBasicTelemetryIsPreservedAsCaptureButNeverEntersTimingSamples` |

REQUIREMENTS.md lists EXT-01/EXT-02/EXT-03 as unchecked v2 bullets (no `- [ ]` checkbox format, consistent with all other v2 entries) — this is intentional per every SUMMARY.md in this phase, which explicitly declined to check them off to avoid overclaiming hardware-validated capability. No orphaned Phase 6 requirement IDs were found (REQUIREMENTS.md has no "Phase 6" phase-mapping section to cross-check against; all three declared IDs are covered across the four plans' `requirements:` frontmatter).

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `shared/.../external/RaceBoxFrameParser.kt` | 17-31 | Sync-byte-split data loss (CR-01 in 06-REVIEW.md) | WARNING (known, documented, unfixed) | A legitimate RaceBox frame split by BLE notification chunk boundary exactly between its two sync bytes is silently discarded and never decoded. Contradicts the `docs/EXTERNAL-GNSS.md` §2.3 / `RaceBoxFrameParserTest.fragmentedInputMatchesWholeFrameInput` implication that "fragmented byte reads" are fully handled — that test only fragments at points that keep both sync bytes together, so this specific edge case escapes coverage. Does not contradict any literal must_have wording (the test that exists does pass), so not classified as a blocking must_have failure, but it directly undermines the "reconnect gap"/fragmentation robustness story for a protocol this phase claims to support. |
| `androidApp/.../ExternalGnssBleClient.kt` | 107-118, 209-221, 253-260 | `BluetoothGatt` leak on every automatic reconnect (WR-01) | WARNING (known, documented, unfixed) | Never exercised by any test in this phase (zero coverage of `AndroidExternalGnssBleClient` itself, WR-05); accepted as hardware-unvalidated risk per phase scope, but a real reliability concern for the eventual hardware-validation pass. |
| `androidApp/.../ExternalGnssBleClient.kt` | 223-227 | `discoverServices()` swallows failures, connection state sticks at "Connecting" (WR-02) | WARNING (known, documented, unfixed) | Same as above — untested, accepted risk, but compounds the SC4 gap: even if connection state *were* wired to UI, this defect would make "Failed" under-reported. |
| `shared/.../external/Nmea0183Parser.kt` | 5, 10-37 | Unbounded stream buffer growth (WR-03) | WARNING (known, documented, unfixed) | Only exploitable by a spoofed/malicious nearby BLE peripheral advertising a matching name prefix; real risk is low but non-zero given the unfiltered `scanner.startScan(emptyList(), ...)` call (IN-02). |
| `shared/.../external/Nmea0183Parser.kt` | 241-247 | HDOP assigned directly to `horizontalAccuracyMeters` without unit conversion (WR-04) | WARNING (known, documented, unfixed) | Unitless HDOP (e.g. 0.9) is reported as "0.9 m accuracy" for NMEA fixes — misleadingly precise, though this is metadata precision, not a lap-timing correctness issue. |
| `androidApp/.../ExternalGnssBleClient.kt` | entire file | Zero automated test coverage (WR-05) | INFO (known, documented) | Only the higher-level `ExternalGnssLocationProvider` is tested via a fake byte-stream double; the real BLE state machine itself is untested. Explicitly flagged as non-blocking follow-up in 06-REVIEW.md. |

All five items above are pre-existing findings from `06-REVIEW.md` (1 critical, 5 warning across the file set) that were independently re-confirmed by direct code inspection during this verification rather than merely trusted from the review report. Per this verification's scope instructions, none of them are treated as blocking must_have failures on their own (they don't contradict any literal must_have wording, and the phase's stated bar is "protocol-complete, hardware-unvalidated," not "defect-free"). They are listed here as known-issue context for the human reviewer, consistent with 06-REVIEW.md's own classification.

### Human Verification Required

None. All must-haves were resolvable through direct code inspection, grep-based wiring traces, and re-running the phase's own automated test/build gates independently (not by trusting SUMMARY.md's reported pass/fail).

### Gaps Summary

Phase 6 is, on the whole, a genuinely strong protocol-first compatibility preview: the NMEA 0183 and RaceBox parsers are real (not stubs), the replay-backed provider seam is correctly wired into the production `LocationSampleProvider`/`SessionController` pipeline end-to-end (Settings → Drive dash → saved session provenance), the lap engine has zero source-specific branching, documentation is honest and conservative, and the hardware-risk register is deliberately left open rather than papered over. Automated tests (`Nmea0183ParserTest`, `RaceBoxFrameParserTest`, `ExternalGnssReplayProviderTest`, `ExternalGnssSettingsTest`, `ExternalGnssTimingPipelineTest`) and both build gates (`:shared:testAndroidHostTest`, `:androidApp:assembleDebug`) were independently re-run during this verification and pass.

One concrete gap blocks a clean pass: ROADMAP Success Criterion 4 requires the UI to expose external GNSS "connection/status," and while a full `ExternalGnssConnectionState` state machine (Scanning/Connecting/Connected/Reconnecting/Failed) was built specifically for this purpose in 06-03, it was never threaded into any Compose UI — Settings shows only a static disclaimer string regardless of actual connection state. This is a narrow, well-isolated wiring gap (not a design or architecture problem) and the codebase already has an established pattern to copy from (`GlassesSettingsCard`/`glassesConnectionState` in the same `SettingsScreen.kt`). It does not affect protocol correctness, lap timing, or session provenance, but it does mean a real user attempting External GNSS today would get no feedback beyond a generic "no GPS fix" message while a receiver is scanning/connecting/failing.

This looks like an incomplete task rather than an intentional deviation — there is no override-worthy alternative implementation, so no override is suggested. Recommend a short follow-up plan (or amendment to 06-03) to wire `ExternalGnssLocationProvider.connectionState` into Settings before treating Phase 6's UI surface as fully closed. This does not need to block starting Phase 7, since Phase 7 (glasses HUD) does not depend on External GNSS connection-status UI.

---

*Verified: 2026-07-07T22:47:53Z*
*Verifier: Claude (gsd-verifier)*
