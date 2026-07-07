---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: in_progress
stopped_at: Completed 06-02-PLAN.md
last_updated: "2026-07-07T21:38:53.555Z"
progress:
  total_phases: 10
  completed_phases: 7
  total_plans: 47
  completed_plans: 45
  percent: 70
---

# State: LapSight

**Initialized:** 2026-06-25
**Current Status:** Phase 5.1 complete and Phase 7 is complete through the optional 07-06 fallback. Phase 7 delivered DAT build integration, SessionController/HudModel seam, GlassesBridge lifecycle/render loop, full DAT HUD renderer, phone-side Settings/Drive glasses controls, real-glasses readability/passivity acceptance, and a documented Display-click fallback after raw captouch receive proved unavailable in DAT 0.8 public APIs. The Android field-UAT gate is accepted by the product owner; iOS real-device UAT remains an explicit unvalidated risk. Phase 6 external GNSS has been replanned and plan-verified as a protocol-first compatibility preview because suitable receivers are too expensive/unavailable for local validation; real hardware behavior remains a tracked unvalidated risk.

## Project Reference

See: `.planning/PROJECT.md`

**Core value:** The user can mount a phone, record a session, and see trustworthy live lap timing plus delta-to-best with minimal interaction while moving.

## Current Focus

**Phase 6 plan verified — ready for execution**

Phase 5.1 froze feature expansion, hardened the mounted-phone Android MVP, and
closed the validation gate with an Android-phone Go decision. The phone app
remains the source of truth for GPS, sessions, lap engine state, and future HUD
outputs.

Next step: execute Phase 6 plan 06-03 from clean `main`. Phase 6 can proceed as
protocol-complete from parser/replay/build verification, but it must remain
explicitly hardware-unvalidated until a real RaceBox/NMEA receiver or user
feedback confirms behavior.

## Working Assumptions

- Project is greenfield.
- Repository: `https://github.com/HuanfuLi/LapSight.git`
- Phone companion app remains the source of truth before and during Meta glasses integration.
- Kotlin Multiplatform + Compose Multiplatform is the current preferred stack.
- Lap engine must be clean-room and testable.
- Open-source references are research inputs, not direct copy sources.
- Android uses Fused Location Provider through the shared `LocationSampleProvider` boundary.
- iOS still needs a Core Location provider through the same boundary.

## Next Command Candidates

- Continue Phase 6 plan 06-03 after workspace cleanup: Android external GNSS provider shell and Settings source UX.
- Use `.planning/phases/06-external-gnss-and-sensor-ingestion/06-VALIDATION.md` and `06-PLAN-REVIEW.md` as the execution gate: Phase 6 is protocol-first, clean-room, replay-backed, and hardware-unvalidated.
- Phase 7 07-06 is closed as a Display-click fallback; raw captouch/tap-and-hold remains a future SDK/API-dependent item.
- Preserve the Android phone source-of-truth boundary; the DAT Display layer must consume summarized timing state from the existing timing pipeline.
- Keep Phase 6 wording honest: protocol-complete is allowed from replay/build verification, but hardware validation remains open until a receiver or user feedback exists.
- Keep iOS real-device UAT tracked as an explicit platform risk.
- Commit the Phase 5.1 completion docs and current workspace changes when ready.

## Review Checklist

- [x] Confirm project name: LapSight.
- [x] Confirm stack direction: KMP + Compose Multiplatform.
- [ ] Confirm app license policy.
- [x] Complete Android runtime UAT (Phase 1).
- [x] Complete Android on-device UAT (Phase 2 lap timing).
- [ ] Complete iOS Xcode runtime UAT.
- [x] Review Phase 2 plan.
- [x] Phase 3 complete (8/8 plans, all tests pass, androidApp:assembleDebug succeeds).
- [x] Phase 4 planned (4/4 plans, decision coverage 24/24).
- [x] Phase 4 executed (4/4 plans, shared checks and Android debug build pass).
- [x] Phase 4 verified via UAT.
- [x] Phase 5 planned (14 plans).
- [x] Phase 5 executed (14/14 plans complete).
- [x] Phase 5 Android hardening UAT completed before Phase 5.1.
- [x] Phase 5.1 Android phone MVP validation gate complete.
- [x] Android Fused Location Provider wired into shared `LocationSampleProvider`.
- [x] Android real GPS outdoor field sample captured and reviewed by owner acceptance.

## Phase 3 Completion Summary

All 8 plans executed with atomic TDD commits:

| Plan | Name | Key Commits |
|------|------|-------------|
| 03-01 | Simulated GPS feed and fixture-backed Drive slice | TDD: test + feat + refactor |
| 03-02 | Blocking package verification gate | auto |
| 03-03 | Versioned local storage foundation | TDD: test + feat |
| 03-04 | Reference-line extraction domain | TDD: test + feat |
| 03-05 | Three-tab shell, Mark New Track, Track Review UI | auto + auto |
| 03-06 | Timing session drafts, save/discard, recovery | TDD: test + feat + feat |
| 03-07 | Offline vector trace rendering | TDD: test + feat + feat |
| 03-08 | JSON/GPX export with platform share handoff | TDD: test + feat + feat |

Requirements satisfied: SESS-01, SESS-02, SESS-03, SESS-04, SESS-05

## Phase 4 Plan Summary

| Plan | Name | Wave |
|------|------|------|
| 04-01 | Ghost progress-curve and live-delta domain | 1 |
| 04-02 | Reference-lap storage and timing-session integration | 2 |
| 04-03 | Minimal mounted-phone live delta UI | 3 |
| 04-04 | Variable-pace simulator, UAT, and roadmap reminder | 4 |

Requirements targeted: GHOST-01, GHOST-02, GHOST-03, GHOST-04

Requirements satisfied: GHOST-01, GHOST-02, GHOST-03, GHOST-04

## Phase 5 Execution Summary

- Plans 05-01 through 05-14 are complete.
- A post-execution hardening pass addressed field-test blockers discovered before Phase 5.1.
- `CourseCompatibilityKey(profileId, geometryCompatibilityId, direction, isSimulated)` remains the exact Ghost persistence/session identity.
- Course matching is independent from `LapEngine`; unmatched samples display `--` and rematch automatically.
- Wrong-course preflight blocks only trustworthy accuracy-adjusted distances over 250 m; explicit override starts normal Timing and persists evidence.
- Drive now resets the feed at formal timing start, uses a perpendicular start/finish boundary for closed-path captures, and no longer stalls the first lap display around 1.994 s.
- Landscape timing always enters fullscreen; the old optional landscape-fullscreen setting has been removed to avoid trapping controls behind system navigation.
- Review is grouped into Sessions, Tracks, and Raw captures; session rows use timestamps instead of copied track names, and timing details include telemetry chart/replay.
- Theme mode supports System, Dark, and Light.
- Android Phone GPS and Simulated feeds now share the same `LocationSampleProvider` interface and `LocationSample` model, with Settings-based source switching.
- Android precise location permission is requested from the shared UI path; no automatic fallback from Phone GPS to Simulated occurs.

## Recent Decisions

- Course matching requires full compatibility-key equality and matching provider provenance.
- Live delta uses normalized direction-relative course progress, not accumulated traveled distance.
- Dense local reference segments are excluded from nonlocal ambiguity competition.
- Legacy Recorded references may derive a bounded path from their exact-key raw lap; Reverse fails closed without explicit recorded-orientation geometry.
- Only a trustworthy accuracy-adjusted whole-course distance over 250 m produces a wrong-course block; unavailable evidence remains non-blocking.
- Wrong-course override consumes the captured profile revision, direction, source, course, and block without rerunning preflight.
- Plan 05-13 preserves the exact compatibility key and keeps preflight outside `LapEngine` and matcher behavior.
- Phase 5.1 remains a validation/hardening gate, not a new feature phase. Telemetry replay added in hardening is scoped to reviewing saved session data already captured by the phone app.
- Real and simulated feeds must remain explicitly labeled and source-gated; simulated data must never silently stand in for a selected Phone GPS run.
- Phase 5.1 host-test Gradle task is `:shared:testAndroidHostTest` (the AGP KMP `withHostTest` task), NOT `:shared:testDebugUnitTest` (does not exist). Warm runtime ~5-6s; 326 host tests pass (pure execution 0.570s). Confirmed in plan 05.1-01.
- SessionReplayDecoder treats malformed/truncated bytes and degenerate (zero-length) start/finish as typed data (Corrupt/NoCourse), never a thrown exception — the export→replay determinism link for D-25/D-28.
- Conservative Ready thresholds now used for field testing: horizontal accuracy 25 m, fix freshness 15 s, sample rate 0.9 Hz — injectable and validated. Quick task 260629-wwd lowered the previous 1.0 Hz floor after real-device testing showed Fused Location commonly hovering at 0.9-1.0 Hz.
- The aggregate Ready gate in `SessionController.startTiming` is opt-in (`requireReady`); production Drive UI opts in (D-13), engine/determinism tests keep legacy behavior. Wrong-course override (D-18) runs before the Ready gate and bypasses it.
- Session source now follows the live feed (PhoneGps/Simulated) via `AppShell` `sourceForTrack` injection, not the Track's marking source — the confirmed P1 evidence-integrity fix (D-04/D-42).
- Replay determinism (D-25..D-28) is now an automated standing gate: `FullPipelineDeterminismTest` asserts the full `SessionController` pipeline (laps, completedSectorResults, ordered LiveDeltaSnapshot sequence, CourseCompatibilityKey/direction) is byte-identical across runs and across export→decode→replay; the legacy finalState-only replay/recovery/ghost/direction tests were widened to full algorithmic output (Plan 05.1-02).
- Oval GPS fixtures complete laps through `SessionController` under `CourseDirection.Reverse` (the explicit accepted-sign is enforced even under lenient config); the Recorded config deterministically rejects the same physical crossings.
- UI hardening (Plan 05.1-05): Drive dash + shell colors/typography/spacing now flow through `MaterialTheme` semantic tokens (`ui/Theme.kt`) + a 4dp `LocalSpacing` scale (`ui/Spacing.kt`); 0 inline hex and 0 inline `fontSize`/`.sp` remain. Hero readouts use `TextAutoSize.StepBased` so glance sizing is clip-safe. Six D-36 pillars re-audited: all >= 3/4 (overall 19/24); no Hardening-Required UI blocker. Information hierarchy and manual orientation toggle unchanged.
- Field-UAT protocol authored (Plan 05.1-06) and updated by quick task 260629-wwd: `5.1-UAT.md` is the device-independent Android-only protocol (on-device smoke S1-S6, mounted-display glance over the 7 core elements portrait/landscape x daylight/low-light, Ready-before-timing with the 25.0 m / 15000 ms / 0.9 Hz thresholds, the 3+2 five-valid-session matrix, lap-count-100% hard blocker, <=0.5 s video target, replay-diagnosis-first via `SessionReplayDecoder`, and Review + JSON/GPX export smoke R1-R5 with concrete acceptance). `5.1-FIELD-TEST-LOG.md` is the 5-row (3 primary + 2 secondary) evidence-index skeleton on the D-48 template. Both are pending real field evidence (D-54), filled during Plan 07. Build/install uses the corrected `:shared:testAndroidHostTest` + `:androidApp:assembleDebug`.
- Code audit complete (Plan 05.1-04): `5.1-CODE-REVIEW.md` is the deep severity-tagged audit of every Phase 1-5 core path. Verdict: core-path P0/P1/P2 CLEAR for the Go gate (D-42) — zero open findings; the confirmed source-provenance P1 (AppShell.kt) is verified fixed by Plan 03; 4 P3/info backlog items (Okio-in-I/O-seam is not an ARCH-01 breach, belt-and-suspenders `..` replacement, optional first-run safety gate, undecided app license). Clean-room boundary verified (engine imports zero Compose/platform; Okio only in storage/export I/O), engine pure (no Random/clock in `lap/`), no network/analytics in `commonMain`, no `doves`/`gpl` in `shared/src`, export-filename path traversal mitigated + tested, bad-input-as-data confirmed, manual orientation confirmed. `docs/THIRD-PARTY-LICENSES.md` delivers the owed ARCH-03 inventory (all deps Apache-2.0 except proprietary Play Services Location + test-only JUnit; none GPL) + ARCH-04 clean-room attestation + local-GPS privacy note. The app's own license remains an open product decision (tracked risk, not a core-timing blocker). REQUIREMENTS.md reconciled: PLAT-01, SAFE-03, ARCH-01/03/04 marked complete; PLAT-02 correctly pending (iOS out of scope, D-02).
- Quick task 260702-uju hardened Track editor after field feedback: start/finish and sector boundaries now drag by relative course progress instead of repeated nearest-point taps; the circuit illustration uses thicker outer/inner strokes and larger handles; Review refreshes after profile mutations; duplicated V2-only profiles stay editable. Verification: focused editor/profile tests, full `:shared:testAndroidHostTest`, and `:androidApp:assembleDebug` passed.
- Quick task 260702-vn1 fixed the Track detail duplicate-map regression: the original `Trace` now uses the shared beautified circuit canvas, and `Edit course` switches that same map into edit mode in place. Verification: `:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, `:androidApp:installDebug`, and ADB launch on device `25053RT47C` passed.
- Phase 6 external GNSS is resumed as a protocol-first compatibility preview: no receiver purchase for now; implement NMEA/RaceBox parsers, replay-backed providers, Android source UX, provenance, and documentation, then label the feature protocol-complete but hardware-unvalidated until real receiver evidence arrives. Phase 7 is allowed to proceed because the glasses bridge consumes summarized phone-owned timing state rather than owning GPS, lap detection, storage, or ghost logic.
- Phase 7 plan 07-04 completed the full DAT HudRenderer/DeltaPill path and replaced the 07-03 placeholder render. `HudRenderSmokeTest` compiles and inspects generated DAT content trees directly; full MockDeviceKit Display-session testing remains impossible with `mwdat-mockdevice:0.8.0` because it has no Display capability handler. Real-glasses validation is deferred to 07-05.
- Phase 7 plan 07-05 added the Settings Glasses area, Android `GlassesActions` wiring, selected-device persistence, Drive "Cast to glasses" controls, non-blocking reconnect chip, phone-side HUD page selector, and phone speed-unit mirroring in `GlassesBridge`. Verification passed: `:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, and `:androidApp:compileDebugAndroidTestKotlin`. Product-owner real-glasses UAT accepted the HUD readability and passive display behavior on 2026-07-07, closing the 07-05 Task 3 gate.
- Phase 7 plan 07-06 closed the optional captouch input stretch as a hardware/API-gated fallback: DAT 0.8 public APIs do not expose confirmed real-device raw captouch receive events, so `GlassesInput` implements only a Display root-click page-cycle fallback and ignores unsupported tap-and-hold/unknown events. Verification passed: `:shared:testAndroidHostTest`, `:androidApp:assembleDebug`, and `:androidApp:compileDebugAndroidTestKotlin`.

## Performance Metrics

| Phase-Plan | Duration | Tasks | Files |
|------------|----------|-------|-------|
| 05-12 | 20min | 2 | 10 |
| Phase 05 P13 | 13min | 2 tasks | 7 files |
| 05.1-01 | 35min | 2 tasks | 4 files |
| 05.1-03 | 50min | 3 tasks | 8 files |
| 05.1-02 | 45min | 2 tasks | 6 files |
| 05.1-05 | 14min | 3 tasks | 6 files |
| 05.1-06 | 12min | 2 tasks | 2 files |
| 05.1-04 | 30min | 2 tasks | 2 files |
| Phase 07 P04 | 45 min | 3 tasks | 4 files |
| Phase 06 P01 | 12min | 3 tasks | 5 files |
| Phase 06 P02 | 9min | 2 tasks | 3 files |

## Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260629-wwd | Phase 5.1 field-test blocker hardening: relax GPS Ready rate, tidy pre-timing Drive layout, replace rotate icon | 2026-06-30 | 04fff42 | [260629-wwd-phase-5-1-field-test-blocker-hardening-r](./quick/260629-wwd-phase-5-1-field-test-blocker-hardening-r/) |
| 260702-uju | Track editor field-test hardening: fix start/finish drag behavior, editable sectors, live review refresh, duplicated profile editability, and track rendering polish | 2026-07-03 | ed5e81b | [260702-uju-track-editor-field-test-hardening-fix-st](./quick/260702-uju-track-editor-field-test-hardening-fix-st/) |
| 260702-vn1 | Unify Track detail trace and edit course map so the original Trace is beautified and editing does not render a duplicate map | 2026-07-03 | ee84fef | [260702-vn1-unify-track-detail-trace-and-edit-course](./quick/260702-vn1-unify-track-detail-trace-and-edit-course/) |

## Session Continuity

**Last session:** 2026-07-07T21:38:53.535Z
**Stopped At:** Completed 06-02-PLAN.md
**Resume File:** None

---

*Last updated: 2026-07-07 after Phase 7 07-06 fallback closeout*

## Decisions

- [Phase 06]: 06-01 closes as protocol-preview only; NMEA parser and replay provider are verified by host tests, with no real receiver or BLE hardware validation claimed.
- [Phase 06]: External GNSS samples continue through LocationSampleProvider as LocationSource.ExternalGnss; lap engine, UI, and platform APIs remain decoupled.
- [Phase 06]: 06-02 closes as RaceBox protocol-preview only; clean-room synthetic frames verify parser, telemetry metadata, 25 Hz replay, and reconnect-gap behavior with hardware validation explicitly Unverified.
- [Phase 06]: RaceBox basic telemetry is optional metadata parsed from explicit protocol fields only and never feeds lap timing or LocationSample emission by itself.
