---
phase: 06-external-gnss-and-sensor-ingestion
plan: 01
subsystem: external-gnss
tags: [nmea0183, external-gnss, replay-provider, location-sample-provider]

requires:
  - phase: 05.1-mvp-field-validation-and-hardening-gate
    provides: "Validated phone-owned LocationSampleProvider timing seam and replay-first verification discipline"
provides:
  - "Platform-free external GNSS protocol/source metadata models"
  - "Pure NMEA 0183 parser for RMC, GGA, GNS, and VTG with typed rejected/ignored outcomes"
  - "Replay-backed ExternalGnss LocationSampleProvider preserving elapsed timing and backlog order"
affects: [phase-06, EXT-01, EXT-02, EXT-03]

tech-stack:
  added: []
  patterns:
    - "Protocol parsers live in commonMain and return data outcomes instead of throwing for bad receiver input"
    - "External receiver replay feeds the existing LocationSampleProvider boundary with LocationSource.ExternalGnss"

key-files:
  created:
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssModels.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProvider.kt
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183ParserTest.kt
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProviderTest.kt
  modified: []

key-decisions:
  - "06-01 is a protocol compatibility preview foundation only; no real receiver or BLE hardware validation is claimed."
  - "NMEA checksum, malformed coordinate, no-fix, fragmentation, and burst handling are typed parser outcomes rather than exceptions."
  - "External GNSS replay enters timing through LocationSampleProvider and LocationSource.ExternalGnss; lap engine/UI/platform APIs remain decoupled."

patterns-established:
  - "External GNSS source metadata carries protocol, transport, receiver identity, and hardware-validation status separately from LocationSample."
  - "Optional telemetry metadata is nullable and non-blocking; GNSS timing remains location-driven."

requirements-completed: [EXT-01, EXT-02, EXT-03]

duration: 12min
completed: 2026-07-07
---

# Phase 6 Plan 01: Shared External GNSS Contracts, NMEA Parser, and Replay Provider Summary

**Protocol-first external GNSS foundation with replay-backed NMEA decoding into the existing phone-owned LocationSampleProvider seam**

## Performance

- **Duration:** 12 min
- **Started:** 2026-07-07T21:10:42Z
- **Completed:** 2026-07-07T21:22:42Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Added commonMain external GNSS models for protocol, transport, receiver identity, connection state, hardware-validation status, decoded fix quality, and optional telemetry metadata.
- Implemented a pure NMEA 0183 parser that accepts text/byte streams, validates checksums when present, supports RMC/GGA/GNS/VTG, and treats bad receiver data as typed outcomes.
- Added `ExternalGnssReplayProvider`, which converts decoded external fixes into `LocationSample(source = LocationSource.ExternalGnss)` through the existing provider seam.
- Added deterministic host tests for valid fixes, no-fix, checksum failure, malformed coordinates, fragmented sentences, high-rate bursts, source metadata, backlog drain order, and reset behavior.

## Task Commits

Each task was committed atomically:

1. **Task 1: External GNSS shared models** - `12698d3` (feat)
2. **Task 2: NMEA 0183 parser with deterministic fixtures** - `cd48126` (feat)
3. **Task 3: Replay-backed external provider** - `e32eff5` (feat)

**Plan metadata:** committed in the final close-out commit.

## Files Created/Modified

- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssModels.kt` - Platform-free protocol/source/fix/telemetry metadata.
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt` - Pure NMEA parser with stream buffering, checksum validation, supported sentence decoding, and typed outcomes.
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProvider.kt` - Replay provider that emits normalized external GNSS samples through `LocationSampleProvider`.
- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183ParserTest.kt` - Parser fixture coverage for valid, invalid, fragmented, and burst NMEA input.
- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProviderTest.kt` - Provider coverage for source tagging, backlog drain, invalid fix skipping, and reset.

## Decisions Made

- Kept hardware validation explicit in data: `ExternalGnssHardwareValidationStatus` defaults to `Unverified` or `Unknown`, and this summary makes no real receiver validation claim.
- Kept parser input handling clean-room and replay-backed. No GPL or unlicensed RaceBox code was copied.
- Kept optional telemetry metadata separate from `LocationSample`; timing remains driven by location samples only.

## Automated Verification

- `./gradlew :shared:testAndroidHostTest --tests "*Nmea0183ParserTest*"` - passed.
- `./gradlew :shared:testAndroidHostTest --tests "*ExternalGnssReplayProviderTest*"` - passed.
- `./gradlew :shared:testAndroidHostTest` - passed.

No connected/device tests, `adb uninstall`, `pm clear`, or app-data deletion commands were run.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed parser/test compile issues found during Task 2 verification**
- **Found during:** Task 2 (NMEA 0183 parser with deterministic fixtures)
- **Issue:** The first parser verification surfaced nullable field parsing mistakes in the new parser and an unescaped `$` in a checksum-failure test fixture.
- **Fix:** Switched NMEA numeric field parsing to safe nullable calls and escaped the literal `$GPRMC` test string.
- **Files modified:** `Nmea0183Parser.kt`, `Nmea0183ParserTest.kt`
- **Verification:** `./gradlew :shared:testAndroidHostTest --tests "*Nmea0183ParserTest*"` passed.
- **Committed in:** `cd48126`

---

**Total deviations:** 1 auto-fixed (1 bug).
**Impact on plan:** No scope change. The fix was required for the planned parser/tests to compile and pass.

## Issues Encountered

- A guessed compile-only task, `:shared:compileDebugKotlinAndroid`, does not exist in this project. Verification used the documented safe host-test task, `:shared:testAndroidHostTest`, which compiles commonMain and runs host tests.
- Gradle repeated pre-existing warnings for disabled iOS simulator tests on Windows and existing Kotlin warning noise outside this plan; these did not block host verification.
- `REQUIREMENTS.md` was not marked complete for EXT-01/EXT-02/EXT-03 during this plan closeout. The plan frontmatter keeps those IDs for traceability, but 06-01 only delivers the shared protocol/replay foundation; marking the user-visible external receiver requirements complete here would overclaim hardware/source-selection capability.

## Known Stubs

None. The nullable fields in the external GNSS models represent absent receiver metadata or optional telemetry, not UI placeholders or unwired mock data.

## User Setup Required

None - no external service or receiver configuration is required for this protocol-preview foundation.

## Hardware Validation Boundary

This plan is protocol-complete for its replay-backed scope only. It does not validate any real NMEA receiver, RaceBox device, BLE service, antenna placement, firmware behavior, or on-track precision.

## Next Phase Readiness

Ready for `06-02-PLAN.md` to add RaceBox-family protocol parsing and synthetic replay corpus. The phone app remains the source of truth, and external GNSS still feeds only the shared provider seam.

## Self-Check: PASSED

- All five created code/test files exist.
- Task commits found: `12698d3`, `cd48126`, `e32eff5`.
- Stub scan found no TODO/FIXME/placeholder text in the 06-01 files; nullable defaults are intentional optional metadata.
- Threat surface scan found no unplanned network endpoint, auth path, file access pattern, or schema change. The new parser/provider trust boundary is the planned Phase 6 protocol-preview surface.

---
*Phase: 06-external-gnss-and-sensor-ingestion*
*Completed: 2026-07-07*
