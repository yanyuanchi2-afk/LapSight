---
phase: 06-external-gnss-and-sensor-ingestion
plan: 04
subsystem: external-gnss
tags: [nmea0183, racebox, external-gnss, timing-pipeline, replay, documentation, risk-register]

# Dependency graph
requires:
  - phase: 06-external-gnss-and-sensor-ingestion (06-01)
    provides: "External GNSS shared models, NMEA 0183 parser, replay-backed LocationSampleProvider"
  - phase: 06-external-gnss-and-sensor-ingestion (06-02)
    provides: "RaceBox frame parser and synthetic frame builder"
  - phase: 06-external-gnss-and-sensor-ingestion (06-03)
    provides: "Android ExternalGnssLocationProvider, Settings source UX, end-to-end provider wiring"
provides:
  - "ExternalGnssTimingPipelineTest: full SessionController replay proof that decoded NMEA/RaceBox samples produce the same lap count/timing, ghost-delta transitions, and distinct source provenance as phone/simulated GPS"
  - "docs/EXTERNAL-GNSS.md: user/developer-facing protocol support, telemetry-vs-timing boundary, and real-receiver feedback checklist"
  - "06-HARDWARE-RISK.md: seven-item unvalidated-hardware risk register with a future validation checklist"
  - "Phase 6 closeout: protocol-complete, hardware-unvalidated"
affects: [phase-06-closeout, EXT-01, EXT-02, EXT-03, any future hardware-validation phase]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Test-only fixture-to-wire-format encoders (LocationSample -> NMEA RMC sentence / RaceBox live-fix frame) let the SAME LapEngine/SessionController fixture geometry double as a full-pipeline external-GNSS replay proof, instead of writing new lap-timing fixtures per protocol"

key-files:
  created:
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssTimingPipelineTest.kt
    - docs/EXTERNAL-GNSS.md
    - .planning/phases/06-external-gnss-and-sensor-ingestion/06-HARDWARE-RISK.md
  modified: []

key-decisions:
  - "Reused ReplayFixtures.DEMO_COURSE/multiLapLoop (the existing rectangular-loop lap-engine fixture) as the pipeline test's source geometry instead of the oval fixture, so precision loss from NMEA/RaceBox re-encoding stays small and easy to reason about against a known-good baseline."
  - "Lap-timing parity between the raw-fixture baseline and the NMEA/RaceBox-decoded replay is asserted with a small millisecond tolerance (25ms NMEA, 10ms RaceBox), not byte-exact equality, because coordinate re-encoding through each wire format's finite decimal/fixed-point precision is expected to shift interpolated line-crossing time by sub-millisecond amounts, not because of nondeterminism in the engine itself. The existing FullPipelineDeterminismTest still asserts exact byte-identical determinism for the SAME encoded input; this plan does not weaken that."
  - "06-HARDWARE-RISK.md is intentionally left OPEN at phase closeout (not resolved-with-caveats) so it cannot be mistaken for a completed validation pass; it is designed to be revisited when real hardware or user field evidence exists, not deleted."

patterns-established:
  - "Protocol-preview closeout evidence lives in three places: an automated full-pipeline replay test (proves software correctness), a user-facing doc separating protocol support from hardware validation status, and an explicit open risk register (prevents 'protocol-complete' from silently becoming 'hardware-validated')."

requirements-completed: [EXT-01, EXT-02, EXT-03]

# Metrics
duration: 35min
completed: 2026-07-07
---

# Phase 06 Plan 04: Full Timing Pipeline Replay, Documentation, and Hardware-Risk Closeout Summary

**End-to-end `SessionController` replay proof that decoded NMEA 0183 and synthetic RaceBox external samples complete the same laps, ghost-delta transitions, and distinct source provenance as phone/simulated GPS, closed out with EXTERNAL-GNSS.md docs and an explicit open hardware-risk register — Phase 6 is protocol-complete, hardware-unvalidated.**

## Performance

- **Duration:** ~35 min
- **Completed:** 2026-07-07T18:32:49-04:00
- **Tasks:** 3
- **Files modified:** 3 created, 0 modified

## Accomplishments

- `ExternalGnssTimingPipelineTest` (6 tests) feeds `ReplayFixtures.multiLapLoop` samples through test-only NMEA RMC and synthetic RaceBox live-fix wire-format encoders, decodes them back through `Nmea0183Parser`/`RaceBoxFrameParser` exactly as a real byte stream would be, and runs the result through the real `SessionController`/`TimingSessionRecorder` pipeline — the same pipeline phone GPS uses, with zero lap-engine hardware-specific branching (D-04).
- Proved lap count and lap-duration parity (within a small re-encoding tolerance) between the raw-fixture baseline and both external protocols, live-delta/ghost `NoReference` -> `Available` transitions matching phone/simulated behavior, absent-telemetry-handled-cleanly for NMEA (no telemetry channel), and that RaceBox basic telemetry frames are captured as provenance/capture data but never converted into a timing `LocationSample` (D-09).
- Proved `LocationSource.ExternalGnss`, `PhoneGps`, and `Simulated` sessions each persist a distinct `SourceMetadata.source` through save/load, matching D-05's session/review/export provenance requirement.
- Added `docs/EXTERNAL-GNSS.md`: separates NMEA 0183 (default, lowest risk) from the RaceBox protocol preview, states protocol-complete/hardware-unvalidated status plainly, documents the EXT-03 telemetry-vs-timing boundary, and gives a concrete checklist (receiver model/firmware, phone/Android version, connection logs, exported session/replay log) for real-receiver user feedback.
- Added `06-HARDWARE-RISK.md`: a seven-item register (RaceBox GATT UUIDs, binary frame layout, Mini/Mini S/Micro variance, Android BLE reconnect behavior, antenna/accuracy claims, telemetry axis/sign convention, NMEA transport fragmentation) plus what's already mitigated by design and a future validation checklist, deliberately left open rather than marked resolved.
- Final gates pass: `:shared:testAndroidHostTest` (full suite, including the new 6 tests) and `:androidApp:assembleDebug` both green.

## Task Commits

Each task was committed atomically:

1. **Task 1: Full timing pipeline replay with external samples** - `e4e5600` (test)
2. **Task 2: User/developer documentation** - `a3a6e2a` (docs)
3. **Task 3: Hardware-risk register and phase closeout** - `408edbb` (docs)

**Plan metadata:** committed together with this SUMMARY.

## Files Created/Modified

- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssTimingPipelineTest.kt` - Full-pipeline replay test: NMEA/RaceBox lap-count/timing parity, absent/present telemetry handling, ghost-delta transitions, and distinct source provenance, all against the real `SessionController` (6 tests).
- `docs/EXTERNAL-GNSS.md` - User/developer documentation: protocol support boundary, EXT-03 telemetry scope, and a real-receiver feedback checklist.
- `.planning/phases/06-external-gnss-and-sensor-ingestion/06-HARDWARE-RISK.md` - Open hardware-risk register with mitigation-today notes and a future validation checklist.

## Decisions Made

- Built the pipeline test on `ReplayFixtures.DEMO_COURSE`/`multiLapLoop` (the existing rectangular-loop lap-engine fixture already shared by `TimingGhostIntegrationTest`/`SessionControllerTest`) rather than the oval `GpsFixtureLibrary` geometry, so the wire-format re-encoding precision loss is easy to reason about against samples other tests already treat as a known-good baseline.
- Used a small millisecond tolerance (25ms for NMEA, 10ms for RaceBox) when comparing lap durations between the raw-fixture baseline and the decoded-external replay, because each wire format's finite coordinate precision (roughly 1.8cm for NMEA's 5-decimal minutes, ~1.1cm for RaceBox's 1e-7-degree fixed point) can shift an interpolated line-crossing timestamp by a sub-millisecond amount. This is a replay round-trip tolerance between two different wire encodings of the same physical fixture, not a weakening of the existing `FullPipelineDeterminismTest`, which still asserts byte-exact determinism for repeated runs of the SAME encoded input.
- Wrote NMEA sentence and RaceBox frame encoding as private test-only helpers in the new test file (not shared production code), since they exist only to simulate "what a real receiver would send," mirroring the existing `RaceBoxFrameBuilder`/`Nmea0183ParserTest.sentence()` pattern already used elsewhere in this phase.
- Kept `06-HARDWARE-RISK.md` explicitly open (not resolved) at phase closeout, per the plan's D-01/D-02 must-haves: Phase 6 completion means protocol-complete, not hardware-validated, and the risk register should stay actionable for a future validation pass or user field report rather than be marked done.
- Did not check off EXT-01/EXT-02/EXT-03 in `REQUIREMENTS.md`: those v2 entries are plain `- **EXT-0N**:` bullets (no `- [ ]` checkbox, unlike v1 requirements), and 06-01/06-02 already established the same decision — checking them off would overclaim a hardware-validated external receiver connection that this phase explicitly does not have. `requirements-completed` in this file's frontmatter exists for dependency-graph traceability only, mirroring the same distinction 06-01/06-02 documented.

## Deviations from Plan

None - plan executed exactly as written. `files_modified` in the plan frontmatter listed exactly the three files created here.

**Total deviations:** 0 auto-fixed.
**Impact on plan:** No scope change.

## Issues Encountered

- `local.properties` (Android SDK path) was missing in this fresh worktree, matching the same environment-setup step noted in 06-03's SUMMARY. Copied from the main checkout before Gradle could resolve the Android SDK; this is a worktree environment setup step, not a plan deviation, and the file remains gitignored/uncommitted.

## Known Stubs

None. All new files are a test (asserts real pipeline behavior, no stubbed data), a documentation file, and a risk-register document.

## User Setup Required

None - no external service, receiver, or hardware configuration required. Real external GNSS hardware validation remains an explicit open risk (`06-HARDWARE-RISK.md`), unchanged by this plan.

## Threat Flags

None. This plan adds a test file and two documentation files only; no new network endpoint, auth path, file access pattern, or schema change was introduced.

## Next Phase Readiness

- Phase 6 (external GNSS and sensor ingestion) is complete as a protocol compatibility preview across all four plans: shared contracts/NMEA parser/replay (06-01), RaceBox frame parser (06-02), Android provider/Settings UX (06-03), and this full-pipeline replay/documentation/risk closeout (06-04).
- `docs/EXTERNAL-GNSS.md` and `06-HARDWARE-RISK.md` give a concrete, honest path for real-hardware validation whenever a receiver or user field evidence becomes available; neither claims validation that hasn't happened.
- No blockers for the orchestrator to mark Phase 6 protocol-complete / hardware-unvalidated in `STATE.md`/`ROADMAP.md`.

## Self-Check: PASSED

- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssTimingPipelineTest.kt`: FOUND
- `docs/EXTERNAL-GNSS.md`: FOUND
- `.planning/phases/06-external-gnss-and-sensor-ingestion/06-HARDWARE-RISK.md`: FOUND
- Task commits found in `git log`: `e4e5600`, `a3a6e2a`, `408edbb`
- `:shared:testAndroidHostTest` (full suite) and `:androidApp:assembleDebug`: both PASSED

---
*Phase: 06-external-gnss-and-sensor-ingestion*
*Completed: 2026-07-07*
