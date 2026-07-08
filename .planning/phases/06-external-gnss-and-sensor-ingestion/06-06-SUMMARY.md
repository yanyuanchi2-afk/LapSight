---
phase: 06-external-gnss-and-sensor-ingestion
plan: 06
subsystem: external-gnss-parsers
tags: [kotlin-multiplatform, raceboxparser, nmea0183, tdd, gap-closure, dos-mitigation]

# Dependency graph
requires:
  - phase: 06-external-gnss-and-sensor-ingestion
    provides: RaceBoxFrameParser, Nmea0183Parser, 06-REVIEW.md findings, 06-HARDWARE-RISK.md risk register
provides:
  - RaceBoxFrameParser.accept() that preserves a trailing lone SYNC_1 byte across accept() calls instead of wiping it (CR-01 closed)
  - Nmea0183Parser.accept() bounded stream buffer (MAX_BUFFERED_NMEA_CHARS = 4096) that self-recovers after an oversized/unterminated input (WR-03 closed)
  - Nmea0183Parser horizontalAccuracyMeters always null for NMEA-derived fixes instead of fabricated from unitless HDOP (WR-04 closed)
  - Explicit, non-silent deferral record for WR-01/WR-02/WR-05/IN-02 in 06-HARDWARE-RISK.md section 5
affects: [06-external-gnss-and-sensor-ingestion, future-android-ble-client-hardening]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Testability hook: internal accessor (bufferedCharCount) exposed on a parser purely so a unit test can observe internal buffer state that would otherwise be masked by garbage-tolerant parsing logic"

key-files:
  created: []
  modified:
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParser.kt
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParserTest.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183ParserTest.kt
    - .planning/phases/06-external-gnss-and-sensor-ingestion/06-HARDWARE-RISK.md

key-decisions:
  - "CR-01, WR-03, WR-04 fixed here because they are pure-Kotlin, hardware-independent, and already covered (or coverable) by the existing host-test suite without new test infrastructure"
  - "WR-01, WR-02, WR-05, IN-02 deferred together as one future unit of work: fixing WR-01/WR-02 without WR-05's testable BluetoothAdapter/BluetoothGatt seam would violate the Nyquist rule (no automated verification possible)"
  - "Added an internal bufferedCharCount accessor to Nmea0183Parser purely for test observability, because Nmea0183Parser.parseSentence()'s leading-garbage-tolerant indexOf('$') logic makes the WR-03 buffer-bound fix invisible through parse-result equality assertions alone"

patterns-established:
  - "When a DoS/memory-bound fix has no externally observable behavioral difference through public API return values, expose a minimal internal (module-visible) accessor for test observability rather than writing a test that cannot fail without the fix"

requirements-completed: [EXT-01, EXT-03]

# Metrics
duration: 20min
completed: 2026-07-08
---

# Phase 06 Plan 06: RaceBox/NMEA Parser Gap-Closure Summary

**Closed CR-01 (RaceBox sync-boundary frame loss), WR-03 (unbounded NMEA buffer growth), and WR-04 (HDOP mislabeled as horizontalAccuracyMeters) from 06-REVIEW.md, with WR-01/WR-02/WR-05/IN-02 explicitly recorded as deferred in 06-HARDWARE-RISK.md.**

## Performance

- **Duration:** ~20 min
- **Completed:** 2026-07-08
- **Tasks:** 3 completed
- **Files modified:** 5

## Accomplishments

- `RaceBoxFrameParser.accept()` no longer silently drops a frame whose 2-byte sync sequence (`0xB5 0x62`) is split exactly across two `accept()` calls — the lone trailing `SYNC_1` byte is now preserved instead of wiped by the `MissingSync` branch.
- `Nmea0183Parser.accept()` now caps `streamBuffer` at `MAX_BUFFERED_NMEA_CHARS` (4096 chars) and clears it when exceeded, bounding memory growth from an unterminated/oversized byte stream (a nearby BLE device spoofing the `"RaceBox"` name prefix and streaming bytes with no `\r`/`\n` could previously grow this buffer without limit).
- `ExternalGnssFixQuality.horizontalAccuracyMeters` is now always `null` for NMEA-derived fixes instead of being populated directly from the unitless HDOP value; `quality.hdop` continues to carry the raw HDOP figure unchanged.
- `06-HARDWARE-RISK.md` gained an explicit "Deferred code-level follow-ups" section distinguishing WR-01/WR-02/WR-05/IN-02 (known code defects, provable by inspection) from the pre-existing pure hardware-unknown risk register, with rationale for why they are deferred as one future unit of work.

## Task Commits

Each task was committed with a RED/GREEN TDD pair (except Task 3, which is docs-only):

1. **Task 1: Fix CR-01** — `c74d7b7` (test, RED) then `015f31c` (fix, GREEN)
2. **Task 2: Fix WR-03/WR-04** — `a1073ef` (test, RED) then `e492362` (fix, GREEN)
3. **Task 3: Record deferral** — `218b384` (docs)

## Files Created/Modified

- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParser.kt` — `accept()`'s `MissingSync` branch now computes `keepTrailingByte`/`garbageLength` instead of unconditionally discarding `streamBuffer`
- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParserTest.kt` — added `fragmentedInputSplitExactlyAtSyncBoundaryStillDecodes`
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt` — added `MAX_BUFFERED_NMEA_CHARS` cap-and-clear logic, `internal val bufferedCharCount` test accessor, and `horizontalAccuracyMeters = null` in `snapshot()`
- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183ParserTest.kt` — added `unterminatedOversizedStreamDoesNotWedgeTheParser` and `hdopIsNotMislabeledAsHorizontalAccuracyMeters`
- `.planning/phases/06-external-gnss-and-sensor-ingestion/06-HARDWARE-RISK.md` — new "5. Deferred code-level follow-ups" section

## Decisions Made

- Followed the plan's explicit in-scope/deferred split exactly: CR-01/WR-03/WR-04 fixed (pure Kotlin, hardware-independent, testable today); WR-01/WR-02/WR-05/IN-02 deferred with rationale rather than fixed blind or silently dropped.
- Added a small testability hook (`internal val bufferedCharCount`) to `Nmea0183Parser` beyond the plan's literal test description — see Deviations below for why this was necessary to make the WR-03 regression test a genuine RED test rather than one that passes regardless of the fix.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug/Test-design] Strengthened the WR-03 regression test with an internal buffer-size accessor**
- **Found during:** Task 2 (WR-03/WR-04 fix), during the RED phase
- **Issue:** The plan's literal test spec for `unterminatedOversizedStreamDoesNotWedgeTheParser` (feed `"X".repeat(5_000)`, assert empty result list, then feed a valid sentence and assert it decodes) passes identically with or without the `MAX_BUFFERED_NMEA_CHARS` fix. This is because `parseSentence()` already skips all leading garbage via `indexOf('$')`, so a giant unterminated prefix never breaks correctness of a subsequently-completed sentence — the unbounded-buffer defect is a pure memory/DoS concern, not something observable through parse-result equality alone. Per the executor's fail-fast TDD rule ("if a test passes unexpectedly during RED, investigate and fix the test before proceeding to GREEN"), this test would have silently passed in RED, violating genuine TDD.
- **Fix:** Added `internal val bufferedCharCount: Int get() = streamBuffer.length` to `Nmea0183Parser` (module-internal, visible to `commonTest` via KMP friend visibility, same pattern as `RaceBoxFrameParser`'s existing `internal const val SYNC_1`). The test now additionally asserts `parser.bufferedCharCount == 0` after the oversized feed, which genuinely fails before the fix (buffer would hold all 5000 chars) and passes after it (buffer is cleared).
- **Files modified:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt`, `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183ParserTest.kt`
- **Verification:** Confirmed the strengthened test fails without the cap (`java.lang.AssertionError` at the `bufferedCharCount` assertion) and passes after the fix; full `:shared:testAndroidHostTest` suite green.
- **Committed in:** `a1073ef` (RED test commit), `e492362` (GREEN fix commit)

**2. [Rule 3 - Blocking] Created local `local.properties` with `sdk.dir` for the worktree**
- **Found during:** Task 1, first Gradle invocation
- **Issue:** `./gradlew :shared:testAndroidHostTest` failed with "SDK location not found" because the git worktree has no `local.properties` (correctly gitignored, not copied from the main checkout).
- **Fix:** Wrote a worktree-local `local.properties` containing only `sdk.dir` (pointed at the existing Android SDK install), matching the main repo's SDK path but omitting the main repo's `github_token` secret.
- **Files modified:** `local.properties` (gitignored, not committed)
- **Verification:** Subsequent Gradle test runs succeeded.
- **Committed in:** N/A (gitignored, correctly not committed)

---

**Total deviations:** 2 auto-fixed (1 test-design fix for genuine TDD compliance, 1 blocking environment fix)
**Impact on plan:** Both were necessary to execute the plan correctly and are scoped strictly to this plan's files. No functional scope creep beyond the plan's stated CR-01/WR-03/WR-04 fixes.

## Issues Encountered

None beyond the deviations documented above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- CR-01, WR-03, WR-04 are closed and regression-tested; `RaceBoxFrameParser` and `Nmea0183Parser` remain hardware-unvalidated overall (per `06-HARDWARE-RISK.md`) but these three specific defects are fixed and covered by automated tests.
- WR-01, WR-02, WR-05, and IN-02 remain open, explicitly documented in `06-HARDWARE-RISK.md` section 5 as a deferred future unit of work (BLE testable-seam extraction + fix, plus a trivial manifest-comment correction).
- Full `:shared:testAndroidHostTest` suite passes with no regressions; no other test in the shared module was affected by the `horizontalAccuracyMeters = null` change for NMEA fixes.

---
*Phase: 06-external-gnss-and-sensor-ingestion*
*Completed: 2026-07-08*
