---
phase: 06-external-gnss-and-sensor-ingestion
plan: 02
subsystem: external-gnss
tags: [racebox, external-gnss, replay-provider, telemetry, protocol-preview]

requires:
  - phase: 06-external-gnss-and-sensor-ingestion
    provides: "06-01 external GNSS metadata models and replay-backed LocationSampleProvider seam"
provides:
  - "Clean-room RaceBox-family binary frame parser for synthetic live GNSS fixtures"
  - "Synthetic RaceBox frame builder for deterministic parser/replay corpus"
  - "RaceBox 25 Hz replay compatibility through ExternalGnssReplayProvider"
  - "Basic protocol-field telemetry parsing isolated from lap timing"
affects: [phase-06, EXT-01, EXT-03]

tech-stack:
  added: []
  patterns:
    - "RaceBox protocol support is clean-room, replay-backed, and hardware-unvalidated"
    - "Telemetry frames decode as optional metadata and never produce timing samples"

key-files:
  created:
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParser.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameBuilder.kt
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParserTest.kt
  modified: []

key-decisions:
  - "RaceBox Mini/Mini S/Micro remain one protocol-preview family until real fixtures prove model-specific differences."
  - "RaceBox hardware validation remains explicitly Unverified; tests prove parser/replay behavior only after bytes are available."
  - "Basic telemetry is parsed only from the synthetic clean-room frame fields and remains separate from LocationSample timing."
  - "EXT-01 and EXT-03 are traced to this plan but not marked complete in REQUIREMENTS.md because BLE connection UX and full receiver support land in later Phase 6 plans."

patterns-established:
  - "Binary external receiver parsers return typed Snapshot, Telemetry, Ignored, UnsupportedTelemetry, or Rejected outcomes instead of throwing for bad receiver bytes."
  - "Per-fix RaceBox source metadata carries protocol, transport, update rate, receiver identity, and Unverified hardware-validation status."

requirements-completed: [EXT-01, EXT-03]

duration: 9min
completed: 2026-07-07
---

# Phase 6 Plan 02: RaceBox Protocol Parser and Synthetic Replay Corpus Summary

**Clean-room RaceBox protocol-preview parser with synthetic 25 Hz replay into the existing external GNSS provider seam**

## Performance

- **Duration:** 9 min
- **Started:** 2026-07-07T21:28:10Z
- **Completed:** 2026-07-07T21:37:18Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Added a RaceBox-family binary frame parser for synthetic live GNSS fixtures with UBX-like framing, length checks, and checksum validation.
- Added a synthetic RaceBox frame builder authored in this repo for deterministic clean-room fix, no-fix, unsupported, checksum-failure, telemetry, fragmentation, and burst tests.
- Normalized valid RaceBox fixes into `ExternalGnssFixSnapshot` with degrees, meters, meters/second, degrees heading, accuracy meters, satellites, dual-frequency flag, GPS iTOW, calendar time metadata, protocol/source provenance, and `Unverified` hardware status.
- Verified synthetic 25 Hz RaceBox fix streams drain through `ExternalGnssReplayProvider` as ordered `LocationSample(source = ExternalGnss)` values.
- Parsed basic acceleration, gyro, and vehicle-speed telemetry only from explicit clean-room fields; telemetry-only frames do not produce timing samples.

## Task Commits

Each task was committed atomically:

1. **Task 1: RaceBox frame parser and synthetic builder** - `a664e2b` (feat)
2. **Task 2: RaceBox replay into external provider** - `d1827e7` (test)

**Plan metadata:** committed in the final close-out commit.

## Files Created/Modified

- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParser.kt` - Pure RaceBox protocol-preview parser with typed snapshot, telemetry, ignored, unsupported telemetry, and rejected outcomes.
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameBuilder.kt` - Synthetic clean-room frame builder for deterministic RaceBox fixture generation.
- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParserTest.kt` - Parser, telemetry, fragmentation, checksum, burst, replay-provider, rate, and reconnect-gap coverage.

## Decisions Made

- Kept RaceBox Mini/Mini S/Micro as one protocol family because the phase context says to avoid model-specific timing logic without real fixtures.
- Kept hardware validation in data as `ExternalGnssHardwareValidationStatus.Unverified`; this plan makes no RaceBox BLE, firmware, antenna, or track-precision validation claim.
- Kept telemetry optional and separate from `LocationSample`; lap timing remains driven only by normalized location fixes through the 06-01 provider seam.
- Did not mark `EXT-01` or `EXT-03` complete in `REQUIREMENTS.md`. This plan advances those requirements, but user-visible receiver connection and full external-source UX are still planned for 06-03/06-04.

## Automated Verification

- `./gradlew :shared:testAndroidHostTest --tests "*RaceBoxFrameParserTest*"` - passed.
- `./gradlew :shared:testAndroidHostTest --tests "*RaceBox*"` - passed.
- `./gradlew :shared:testAndroidHostTest` - passed.

No connected/device tests, `adb uninstall`, `pm clear`, or app-data deletion commands were run.

## Deviations from Plan

None - plan executed exactly as written.

**Total deviations:** 0 auto-fixed.
**Impact on plan:** No scope change.

## Issues Encountered

- None blocking. Gradle repeated existing Windows/iOS simulator-disabled and Kotlin warning noise outside this plan; host verification passed.
- A local stub regex scan produced broad false positives on ordinary default parameters and nullable optional metadata. Manual review found no TODO/FIXME/placeholder text or UI-facing unwired stubs in the 06-02 files.

## Known Stubs

None. Nullable fields and `Unverified` hardware validation values are intentional protocol-preview metadata, not placeholders.

## User Setup Required

None - no external service, receiver, BLE pairing, or hardware setup is required for this parser/replay plan.

## Hardware Validation Boundary

This plan is protocol-preview complete for clean-room synthetic RaceBox frames and replay/provider compatibility only. It does not validate real RaceBox Mini, Mini S, or Micro hardware; BLE services/characteristics; firmware behavior; antenna placement; or on-track precision.

## Next Phase Readiness

Ready for `06-03-PLAN.md` to add the Android external GNSS provider shell and Settings source UX. The phone companion remains the source of truth, and RaceBox-derived samples still enter only through the shared `LocationSampleProvider` seam.

## Self-Check: PASSED

- All three created code/test files exist.
- Task commits found: `a664e2b`, `d1827e7`.
- Stub scan found no TODO/FIXME/placeholder text; nullable values are intentional optional metadata.
- License-boundary scan found no `doves` or `gpl` references in the 06-02 files.
- Threat surface scan found no unplanned network endpoint, auth path, file access pattern, schema change, or platform/hardware integration. The planned new trust boundary is a byte parser returning typed data outcomes.

---
*Phase: 06-external-gnss-and-sensor-ingestion*
*Completed: 2026-07-07*
