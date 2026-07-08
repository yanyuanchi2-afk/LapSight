# Roadmap: LapSight

**Created:** 2026-06-25
**Mode:** Vertical MVP

## Milestone 1: Phone Companion MVP

Build the phone-first product that proves live GPS capture, lap timing, local session storage, and ghost delta. Do not build the Meta glasses app until the phone companion can produce reliable timing state.

### Phase 1: Mobile Walking Skeleton + GPS Probe

**Goal:** As a track user, I want to launch LapSight on Android/iOS and see live GPS quality in a mounted-phone dash, so that I can verify the app can collect usable timing data before lap detection exists.
**Mode:** mvp

**Requirements:** PLAT-01, PLAT-02, PLAT-03, PLAT-04, PLAT-05, GPS-01, GPS-02, GPS-03, GPS-04, SAFE-01, SAFE-02, SAFE-03, ARCH-01, ARCH-03, ARCH-04

**Success Criteria:**

1. Android and iOS apps build from the same repository.
2. Live dash shows speed, GPS accuracy, update rate, and permission/fix state.
3. Portrait and landscape layouts are usable without redesigning the information hierarchy.
4. A session can collect timestamped GPS samples in memory or temporary storage.
5. The app clearly states closed-course use and GPS accuracy limits.

**Implementation Notes:**

- Use Kotlin Multiplatform for shared state/models.
- Use Compose Multiplatform for the initial shared UI unless a platform blocker appears.
- Use Android Fused Location Provider and iOS Core Location behind a shared `LocationSampleProvider` interface.
- Keep the first UI intentionally small: live speed, accuracy, Hz/update interval, elapsed time, sample count, start/stop.

### Phase 2: Clean-Room Lap Engine V0

**Goal:** As a track user, I want LapSight to detect laps from GPS crossing a start/finish line, so that I can see current, last, and best lap timing during a session.
**Mode:** mvp

**Requirements:** GPS-05, LAP-01, LAP-02, LAP-03, LAP-04, LAP-05, LAP-06, LAP-07, SAFE-04, ARCH-02

**Success Criteria:**

1. User can define a start/finish line using two points or current position plus heading.
2. Lap engine detects segment crossing between consecutive GPS samples.
3. Crossing timestamp is interpolated and stable under replay.
4. False positives are reduced by minimum lap time, speed, direction, cooldown, and accuracy filters.
5. Live dash shows current lap, last lap, best lap, lap count, and speed.
6. Live dash shows compact sector split information from sector-line detection.
7. Engine tests cover geometry, crossing, interpolation, filters, sector detection, and replay fixtures.

**Implementation Notes:**

- Do not copy DovesLapTimer code; reproduce behavior from first principles and tests.
- Convert GPS samples to a local meter coordinate space near the session origin for geometry.
- Treat start/finish as a line segment/corridor, not a single point.
- Treat sector lines as first-class timing lines in Phase 2, including data model, detection, tests, and compact live UI.
- Use synthetic replay fixtures before testing on a real track.

### Phase 3: Local Sessions, Review, and Export

**Goal:** As a track user, I want to save and review sessions after driving/riding, so that I can inspect laps and reuse the data for debugging or future analysis.
**Mode:** mvp

**Requirements:** SESS-01, SESS-02, SESS-03, SESS-04, SESS-05

**Plans:** 8/8 plans executed

Plans:
**Wave 1**

- [x] 03-01-PLAN.md - Simulated GPS feed and fixture-backed Drive slice

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 03-02-PLAN.md - Blocking package verification gate

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 03-03-PLAN.md - Versioned local storage foundation (deps, schema, file store)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 03-04-PLAN.md - Reference-line extraction domain and Track Review state

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 03-05-PLAN.md - Three-tab shell, Mark New Track, and Track Review UI

**Wave 6** *(blocked on Wave 5 completion)*

- [x] 03-06-PLAN.md - Timing session drafts, save/discard, and review summaries

**Wave 7** *(blocked on Wave 6 completion)*

- [x] 03-07-PLAN.md - Offline vector trace review

**Wave 8** *(blocked on Wave 7 completion)*

- [x] 03-08-PLAN.md - Explicit JSON and GPX export with platform share handoff

**Success Criteria:**

1. User can start, stop, save, discard, and reopen a session.
2. Session review shows lap list, best lap, total duration, sample count, and GPS quality summary.
3. Session review shows a basic track trace.
4. JSON export preserves full samples, laps, start/finish line, and app metadata.
5. GPX or equivalent GPS export works for external tools.

**Implementation Notes:**

- Prefer local-first storage.
- Keep schema versioned from the first saved format.
- Export should support future web/PWA analysis tools and future glasses bridge debugging.

### Phase 4: Ghost Lap + Live Delta

**Goal:** As a track user, I want to compare my current lap against a saved best lap, so that I can see whether I am ahead or behind without waiting until the lap ends.
**Mode:** mvp

**Requirements:** GHOST-01, GHOST-02, GHOST-03, GHOST-04

**Plans:** 4/4 plans complete

Plans:
**Wave 1**

- [x] 04-01-PLAN.md - Ghost progress-curve and live-delta domain

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 04-02-PLAN.md - Reference-lap storage and timing-session integration

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 04-03-PLAN.md - Minimal mounted-phone live delta UI

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 04-04-PLAN.md - Variable-pace simulator, UAT, and roadmap reminder

**Success Criteria:**

1. User can save/select a reference lap.
2. Engine calculates current progress distance along the lap.
3. Engine calculates delta against the reference lap at equivalent progress distance.
4. Live dash shows ahead/behind delta with clear color/state and minimal text.
5. Reference lap data persists across sessions.

**Implementation Notes:**

- Use distance-normalized matching before attempting coordinate-nearest matching.
- Keep ghost visualization simple in v1: delta number and ahead/behind state are more important than a map animation.
- Later map ghost can reuse the same reference-lap model.
- Keep ghost math in shared Kotlin and independent from Compose/platform APIs.
- Preserve simulated-vs-real reference isolation.

## Post-MVP Backlog / Reminders

- Build telemetry charts and analysis from Phase 4 progress curves after the MVP is complete: speed trace, delta-over-distance graph, braking/acceleration zones, sector delta chart, lap comparison overlays, and exportable telemetry data.
- Add map ghost animation only after the phone app produces reliable timing/reference state.
- Revisit coordinate-nearest or map-matched ghost comparison after distance-normalized matching is validated on real tracks.

## Milestone 2: Track Usability and Accuracy Expansion

### Phase 5: Track Setup and Course Profiles
# Roadmap: LapSight

**Created:** 2026-06-25
**Mode:** Vertical MVP

## Milestone 1: Phone Companion MVP

Build the phone-first product that proves live GPS capture, lap timing, local session storage, and ghost delta. Do not build the Meta glasses app until the phone companion can produce reliable timing state.

### Phase 1: Mobile Walking Skeleton + GPS Probe

**Goal:** As a track user, I want to launch LapSight on Android/iOS and see live GPS quality in a mounted-phone dash, so that I can verify the app can collect usable timing data before lap detection exists.
**Mode:** mvp

**Requirements:** PLAT-01, PLAT-02, PLAT-03, PLAT-04, PLAT-05, GPS-01, GPS-02, GPS-03, GPS-04, SAFE-01, SAFE-02, SAFE-03, ARCH-01, ARCH-03, ARCH-04

**Success Criteria:**

1. Android and iOS apps build from the same repository.
2. Live dash shows speed, GPS accuracy, update rate, and permission/fix state.
3. Portrait and landscape layouts are usable without redesigning the information hierarchy.
4. A session can collect timestamped GPS samples in memory or temporary storage.
5. The app clearly states closed-course use and GPS accuracy limits.

**Implementation Notes:**

- Use Kotlin Multiplatform for shared state/models.
- Use Compose Multiplatform for the initial shared UI unless a platform blocker appears.
- Use Android Fused Location Provider and iOS Core Location behind a shared `LocationSampleProvider` interface.
- Keep the first UI intentionally small: live speed, accuracy, Hz/update interval, elapsed time, sample count, start/stop.

### Phase 2: Clean-Room Lap Engine V0

**Goal:** As a track user, I want LapSight to detect laps from GPS crossing a start/finish line, so that I can see current, last, and best lap timing during a session.
**Mode:** mvp

**Requirements:** GPS-05, LAP-01, LAP-02, LAP-03, LAP-04, LAP-05, LAP-06, LAP-07, SAFE-04, ARCH-02

**Success Criteria:**

1. User can define a start/finish line using two points or current position plus heading.
2. Lap engine detects segment crossing between consecutive GPS samples.
3. Crossing timestamp is interpolated and stable under replay.
4. False positives are reduced by minimum lap time, speed, direction, cooldown, and accuracy filters.
5. Live dash shows current lap, last lap, best lap, lap count, and speed.
6. Live dash shows compact sector split information from sector-line detection.
7. Engine tests cover geometry, crossing, interpolation, filters, sector detection, and replay fixtures.

**Implementation Notes:**

- Do not copy DovesLapTimer code; reproduce behavior from first principles and tests.
- Convert GPS samples to a local meter coordinate space near the session origin for geometry.
- Treat start/finish as a line segment/corridor, not a single point.
- Treat sector lines as first-class timing lines in Phase 2, including data model, detection, tests, and compact live UI.
- Use synthetic replay fixtures before testing on a real track.

### Phase 3: Local Sessions, Review, and Export

**Goal:** As a track user, I want to save and review sessions after driving/riding, so that I can inspect laps and reuse the data for debugging or future analysis.
**Mode:** mvp

**Requirements:** SESS-01, SESS-02, SESS-03, SESS-04, SESS-05

**Plans:** 8/8 plans executed

Plans:
**Wave 1**

- [x] 03-01-PLAN.md - Simulated GPS feed and fixture-backed Drive slice

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 03-02-PLAN.md - Blocking package verification gate

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 03-03-PLAN.md - Versioned local storage foundation (deps, schema, file store)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 03-04-PLAN.md - Reference-line extraction domain and Track Review state

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 03-05-PLAN.md - Three-tab shell, Mark New Track, and Track Review UI

**Wave 6** *(blocked on Wave 5 completion)*

- [x] 03-06-PLAN.md - Timing session drafts, save/discard, and review summaries

**Wave 7** *(blocked on Wave 6 completion)*

- [x] 03-07-PLAN.md - Offline vector trace review

**Wave 8** *(blocked on Wave 7 completion)*

- [x] 03-08-PLAN.md - Explicit JSON and GPX export with platform share handoff

**Success Criteria:**

1. User can start, stop, save, discard, and reopen a session.
2. Session review shows lap list, best lap, total duration, sample count, and GPS quality summary.
3. Session review shows a basic track trace.
4. JSON export preserves full samples, laps, start/finish line, and app metadata.
5. GPX or equivalent GPS export works for external tools.

**Implementation Notes:**

- Prefer local-first storage.
- Keep schema versioned from the first saved format.
- Export should support future web/PWA analysis tools and future glasses bridge debugging.

### Phase 4: Ghost Lap + Live Delta

**Goal:** As a track user, I want to compare my current lap against a saved best lap, so that I can see whether I am ahead or behind without waiting until the lap ends.
**Mode:** mvp

**Requirements:** GHOST-01, GHOST-02, GHOST-03, GHOST-04

**Plans:** 4/4 plans complete

Plans:
**Wave 1**

- [x] 04-01-PLAN.md - Ghost progress-curve and live-delta domain

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 04-02-PLAN.md - Reference-lap storage and timing-session integration

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 04-03-PLAN.md - Minimal mounted-phone live delta UI

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 04-04-PLAN.md - Variable-pace simulator, UAT, and roadmap reminder

**Success Criteria:**

1. User can save/select a reference lap.
2. Engine calculates current progress distance along the lap.
3. Engine calculates delta against the reference lap at equivalent progress distance.
4. Live dash shows ahead/behind delta with clear color/state and minimal text.
5. Reference lap data persists across sessions.

**Implementation Notes:**

- Use distance-normalized matching before attempting coordinate-nearest matching.
- Keep ghost visualization simple in v1: delta number and ahead/behind state are more important than a map animation.
- Later map ghost can reuse the same reference-lap model.
- Keep ghost math in shared Kotlin and independent from Compose/platform APIs.
- Preserve simulated-vs-real reference isolation.

## Post-MVP Backlog / Reminders

- Build telemetry charts and analysis from Phase 4 progress curves after the MVP is complete: speed trace, delta-over-distance graph, braking/acceleration zones, sector delta chart, lap comparison overlays, and exportable telemetry data.
- Add map ghost animation only after the phone app produces reliable timing/reference state.
- Revisit coordinate-nearest or map-matched ghost comparison after distance-normalized matching is validated on real tracks.

## Milestone 2: Track Usability and Accuracy Expansion

### Phase 5: Track Setup and Course Profiles

**Goal:** As a repeat track user, I want to save start/finish and course configuration, so that I do not have to reconfigure every session.
**Mode:** mvp

**Requirements:** New requirements to be defined after Milestone 1 validation.

**Plans:** 15/15 plans complete

Plans:

**Wave 1**

- [x] 05-01-PLAN.md - V1 freeze, V2 contracts, and pure V1->V2 migration mapping

**Wave 2** *(blocked on Wave 1)*

- [x] 05-02-PLAN.md - Side-by-side idempotent store migration wiring

**Wave 3** *(blocked on Wave 2)*

- [x] 05-03-PLAN.md - Explicit current Track selection and no-fallback resolution

**Wave 4** *(blocked on Wave 3)*

- [x] 05-04-PLAN.md - New-user create+name+select first profile and iOS bootstrap

**Wave 5** *(blocked on Wave 4)*

- [x] 05-05-PLAN.md - Closed reference path and constrained editor geometry

**Wave 6** *(blocked on Wave 5)*

- [x] 05-06-PLAN.md - Offline editor UI and immutable revision save

**Wave 7** *(blocked on Wave 6; parallel write sets)*

- [x] 05-07-PLAN.md - Complete-Sector timing semantics and persistence
- [x] 05-08-PLAN.md - Profile lifecycle, archive, duplicate, and history

**Wave 8** *(blocked on both Wave 7 plans)*

- [x] 05-09-PLAN.md - Course Direction (Recorded/Reverse) and turnaround integrity

**Wave 9** *(blocked on Wave 8)*

- [x] 05-10-PLAN.md - Ghost compatibility key contract and key-gated selection

**Wave 10** *(blocked on Wave 9)*

- [x] 05-11-PLAN.md - Exact-key reference persistence and provider provenance

**Wave 11** *(blocked on Wave 10)*

- [x] 05-12-PLAN.md - Course-progress Ghost matcher and recovery

**Wave 12** *(blocked on Wave 11)*

- [x] 05-13-PLAN.md - Wrong-course preflight, override, and vertical integration

**Wave 13** *(blocked on Wave 12)*

- [x] 05-14-PLAN.md - Review override evidence and Phase 5 UAT gates

**Success Criteria:**

1. User can save named track/course profiles.
2. User can edit start/finish line and optional sector lines.
3. App can reuse prior course setup for new sessions.
4. App can detect obvious wrong-direction or wrong-course usage.

### Phase 5.1: MVP Field Validation and Hardening Gate

**Goal:** As the product owner and tester, I want to freeze feature expansion after course profiles and validate the phone app in real use, so that external GNSS and glasses work only begins after the mounted-phone MVP is trustworthy.
**Mode:** validation

**Requirements:** Validate the implemented PLAT, GPS, LAP, SESS, GHOST, SAFE, ARCH, and Phase 5 course-profile requirements. No new feature scope unless required to fix validation blockers.

**Plans:** 8/8 plans complete

Plans:

**Wave 1**

- [x] 05.1-01-PLAN.md - Replay-from-export decoder + GPS degradation/perpendicular fixtures (test-infra foundation)
- [x] 05.1-03-PLAN.md - Aggregate Ready gate + raw-recording seam + source-provenance P1 fix

**Wave 2** *(after Wave 1)*

- [x] 05.1-02-PLAN.md - Full-pipeline N-run determinism suite + widen existing replay tests
- [x] 05.1-05-PLAN.md - UI tokenization + glance hardening + six-pillar D-36 re-audit (5.1-UI-REVIEW.md)
- [x] 05.1-06-PLAN.md - Author 5.1-UAT.md protocol + 5.1-FIELD-TEST-LOG.md skeleton

**Wave 3** *(after Wave 2)*

- [x] 05.1-04-PLAN.md - Deep core-path code audit + ARCH-03/04 license/privacy docs (5.1-CODE-REVIEW.md)

**Wave 4** *(after Wave 3; manual evidence, gated per D-54)*

- [x] 05.1-07-PLAN.md - On-device + mounted-display UAT + five closed-course sessions + replay verification

**Wave 5** *(after Wave 4)*

- [x] 05.1-08-PLAN.md - Go / Hardening Required / No-Go decision (5.1-GO-NOGO.md)

**Success Criteria:**

1. Code audit of Phase 1-5 core paths has no unresolved P0/P1 findings across architecture boundaries, lap engine logic, location ingestion, storage, session recovery, licensing, privacy, and safety positioning.
2. Replay validation passes repeatably for lap detection, GPS quality degradation, course-profile selection, direction handling, session recovery, and ghost delta behavior using synthetic or recorded data.
3. On-device UAT verifies Android and iOS smoke paths plus at least three complete real-world walking, cycling, or closed-course sessions with preserved replay/debug evidence.
4. Mounted-phone display UAT confirms the live dash is readable in portrait/landscape, sunlight/low light, and moving-use conditions without requiring complex interaction while moving.
5. UI audit passes a 6-pillar review with all pillars scoring at least 3/4 and no blocking issues in domain fit, hierarchy, state clarity, motion safety, responsiveness, or accessibility.
6. Field-test logs compare lap count and lap timing against manual or reference observations, and a documented Go/No-Go decision is made before any post-5.1 peripheral phase starts.
7. Phase 6 and Phase 7 remain blocked until all P0/P1 validation defects are fixed or explicitly deferred with rationale.

**Implementation Notes:**

- Treat this as a validation and hardening phase, not a product expansion phase.
- Produce `5.1-CODE-REVIEW.md`, `5.1-UAT.md`, `5.1-UI-REVIEW.md`, `5.1-FIELD-TEST-LOG.md`, and `5.1-GO-NOGO.md`.
- Use replayable raw samples, exported sessions, screenshots, and field notes as evidence.
- Keep any new instrumentation minimal and focused on reproducibility or diagnosis.
- Re-check safety language: closed-course/private-track use, passive UI while moving, and no public-road racing positioning.
- Do not begin Meta glasses, external GNSS, or other peripheral adaptation work in this phase.

### Phase 6: External GNSS and Sensor Ingestion

**Goal:** As a user who needs better timing precision, I want to connect an external GPS receiver, so that LapSight can exceed phone GPS limitations.
**Mode:** mvp
**Status:** Planned and verified as a protocol-first compatibility preview. Real hardware validation is explicitly deferred until a receiver or user feedback is available.

**Requirements:** EXT-01, EXT-02, EXT-03
**Planning Gate:** Approved for execution on 2026-07-07; see `06-VALIDATION.md` and `06-PLAN-REVIEW.md`.

**Plans:** 4/4 plans complete; 2 gap-closure plans added after 06-VERIFICATION.md found the connection-status UI gap (Success Criterion 4)

Plans:

**Wave 1**

- [x] 06-01-PLAN.md - Shared external GNSS contracts, NMEA parser, and replay provider

**Wave 2** *(blocked on Wave 1)*

- [x] 06-02-PLAN.md - RaceBox protocol parser and synthetic replay corpus

**Wave 3** *(blocked on Wave 2)*

- [x] 06-03-PLAN.md - Android external GNSS provider shell and Settings source UX

**Wave 4** *(blocked on Wave 3)*

- [x] 06-04-PLAN.md - End-to-end timing replay, documentation, and hardware-risk closeout

**Gap closure (Wave 1, parallel, after 06-VERIFICATION.md)**

- [ ] 06-05-PLAN.md - Wire ExternalGnssLocationProvider.connectionState into Settings UI (closes Success Criterion 4) + IN-01 fix
- [ ] 06-06-PLAN.md - Fix CR-01 (RaceBox sync-byte-split data loss) and WR-03/WR-04 (Nmea0183Parser buffer/accuracy bugs); document WR-01/WR-02/WR-05/IN-02 as explicitly deferred

**Success Criteria:**

1. App can decode external GNSS samples from protocol fixtures/replay, including NMEA 0183 and RaceBox-family synthetic frames.
2. App records sample source, protocol, update frequency, and hardware-validation status.
3. Lap engine works unchanged regardless of phone GPS, simulated replay, or external GNSS input.
4. UI exposes external GNSS source quality and connection/status without returning diagnostic raw GPS tools to the main Drive surface.
5. Documentation clearly separates protocol support from unvalidated hardware support.

**Implementation Notes:**

- Phase 6 may be marked protocol-complete from parser/replay/build verification, but must remain hardware-unvalidated until a real receiver is tested.
- Keep the existing `LocationSampleProvider` boundary as the ingestion seam so Phase 7 can consume the same phone-owned timing state regardless of whether samples come from phone GPS now or external GNSS later.
- Do not copy GPL or unlicensed RaceBox client/emulator code. Use official RaceBox protocol documentation when available and clean-room tests/fixtures for parser implementation.
- Supported-hardware wording must be conservative: RaceBox and NMEA can be listed as protocol targets, not guaranteed hardware compatibility, until field feedback confirms real receivers.
- EXT-03 is limited in this phase to optional basic telemetry metadata/capture when a supported protocol exposes those fields; no IMU fusion, vehicle-CAN integration, or lap-timing dependence on telemetry.

## Milestone 3: Meta DAT Display Extension

### Phase 7: Phone-to-Glasses DAT Display Bridge

**Goal:** As a Meta Display Glasses user, I want a passive DAT Display HUD fed by the phone app, so that I can see lap timing without looking down at the phone.
**Mode:** mvp

**Requirements:** MR-01, MR-02, MR-03

**Plans:** 6/6 plans executed

Plans:

**Wave 1**

- [x] 07-01-PLAN.md — DAT build integration, credentials, manifest, minSdk (enabler)
- [x] 07-02-PLAN.md — SessionController hoist seam + pure host-tested HudModel mapper

**Wave 2** *(after Wave 1)*

- [x] 07-03-PLAN.md — GlassesBridge DAT lifecycle + 2 Hz loop + silent reconnect + MockDeviceKit tests

**Wave 3** *(after Wave 2)*

- [x] 07-04-PLAN.md — HudRenderer + DeltaPill: three pages, delta pill, idle/stale/sector-flash

**Wave 4** *(after Wave 3)*

- [x] 07-05-PLAN.md — Phone UX: Settings Glasses area + Drive cast/status/page controls + real-glasses gate

**Wave 5** *(after Wave 4; optional/experimental)*

- [x] 07-06-PLAN.md — Experimental captouch input fallback (raw captouch unavailable; Display click cycles pages)

**Success Criteria:**

1. Phone app exposes a minimal live timing state stream derived from the existing lap engine/session controller.
2. Android app uses Meta Wearables DAT `mwdat-display` to render current lap, last lap, best lap, speed, and delta on display-capable glasses.
3. Glasses HUD remains passive and readable on a 600x600 display with no complex interaction while driving/riding.
4. Phone app remains the source of truth for GPS, storage, and lap logic.

**Implementation Notes:**

- Phase 7 may start before Phase 6 because it consumes phone-owned timing state, not raw external sensor samples.
- Use the locally cloned Meta Wearables DAT Android SDK references and the DisplayAccess sample as canonical implementation references.
- Validate with real Meta Display Glasses when available; MockDeviceKit remains useful for automated lifecycle, permission, and disconnection scenarios.
- Do not duplicate GPS acquisition, lap detection, storage, or ghost-delta logic inside the glasses display layer.

## Open Decisions

1. Confirm final app license before importing or adapting any external code.
2. Decide whether Phase 1 should build both Android and iOS immediately or use Android-first for the location spike.
3. Decide whether Compose Multiplatform UI is acceptable on iOS after a real-device prototype.
4. Decide first supported external GNSS transport when Phase 6 resumes with suitable hardware available.

## Backlog

### Phase 999.1: Advanced Map Visualization, Ghost Overlay, and AI Driving Analysis (BACKLOG)

**Goal:** Captured for future planning: provide a richer map-based analysis surface after the phone companion has reliable timing state and higher-quality positioning options.
**Requirements:** TBD
**Plans:** 0 plans

Captured scope:

- Render real-world satellite/imagery basemaps behind recorded and live racing lines so users can see entry, apex, exit, and line placement relative to the physical circuit.
- Support multiple map anchoring modes similar to navigation apps: north-up fixed map, current-position-centered map, and heading-up/car-up mode where the vehicle arrow stays oriented upward while the map pans/rotates.
- Render a live ghost car position on future map timing panels so users can compare their current position and line against the fastest/reference lap in spatial context, not only as a numeric delta.
- When positioning precision improves enough, analyze driving traces with AI to score line quality and provide coaching suggestions while preserving closed-course/private-track safety positioning.

Plans:

- [ ] TBD (promote with `$gsd-review-backlog` when ready)

### Phase 999.2: GPS Filter Track Validation and Timing-Path Integration (BACKLOG)

**Goal:** Finish validating the velocity-aided GPS filter under real racing dynamics and decide its role in the timing path. The filter (`VelocityAidedGpsFilter`, tuned 2026-07-06 against public-road A/B sessions) is display-only and deliberately light-touch: it tracks the chip closely and its real payload is wild-fix rejection.
**Requirements:** TBD
**Plans:** 0 plans

Captured scope:

- **Track-session validation (hard braking):** the 2026-07-06 tuning data was a safe-pace public-road drive (~6.5 m/s, no hard braking), so filter behavior under racing deceleration (~10 m/s²) is unvalidated. Record a 5-10 lap timed session on an actual track with High-rate GNSS on, export the JSON, and replay it through the tuning harness to confirm no corner-entry lag (along-track lag should stay near zero; retune `ACCELERATION_SIGMA` upward if not).
- **Fused vs direct GNSS at pace:** at gentle pace Fused overlaid slightly tighter (1.73 m vs 2.08 m lap-to-lap); its IMU smoothing may instead turn into lag under racing dynamics. The same track session, run once per engine, answers which engine should be the racing default.
- **Step 2 — timing-path integration decision:** replay recorded sessions through crossing detection twice (raw vs filtered fixes); confirm lap counts identical and lap-time deltas within milliseconds. Given the tuned filter is overlay-neutral, the only timing value left is spike protection at the start/finish line — integrate only if the regression is clean and a session with real wild fixes shows a benefit; otherwise close this item and keep the filter display-only.
- Tuning method (reusable): exact Python port of the filter replayed over exported session JSON; metrics = lap-to-lap overlay distance, along-track lag vs Doppler heading, RMS second-difference smoothness. Sessions live under `Debug/Sessions/` (gitignored — real GPS traces).

Plans:

- [ ] TBD (promote with `$gsd-review-backlog` when ready)

---
*Roadmap created: 2026-06-25*
