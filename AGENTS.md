# LapSight Agent Guide

## Project Scope

LapSight is a lap-timing product with one phone UX and two runtime backends: phone-only, or a connected LapSight hardware HUD. The APP remains the complete user-facing workflow in both modes; connecting the HUD must not replace or simplify that workflow.

Do not treat this as a generic fitness tracker or smartwatch-style dashboard. The product is a mounted-phone timing instrument with a future MR display extension.

## Runtime Authority Contract

- Without a LapSight HUD, the APP uses phone GPS and runs marking/timing locally.
- With a LapSight HUD selected, the same APP buttons send session intents over BLE while the HUD uses its LC29H for authoritative live positioning, logging, lap/sector timing, and delta calculations.
- APP-local operations such as navigation, history, track editing, sharing, and phone page selection remain local. HUD display-page commands are a separate explicit operation.
- The APP is a controller and a large-screen live dashboard when connected. It continuously receives HUD telemetry, results, acknowledgements, and state snapshots.
- The HUD must continue an active session if BLE disconnects or the APP is killed. On reconnect, the APP adopts the HUD snapshot and resumes the live stream; it must never reset an active HUD session merely because its own process restarted.
- Physical HUD controls and APP controls are two input surfaces for the same HUD session. HUD runtime state and persisted event IDs resolve reconnect/conflict cases; commands are not phone-computed timing-state mirrors.
- Track creation uses the normal APP flow: command HUD capture, review/edit the resulting geometry on the phone, then synchronize the confirmed course revision back to the HUD before hardware timing.

## Current Architecture Direction

- Kotlin Multiplatform for shared domain logic.
- Compose Multiplatform for initial shared UI, pending real-device validation.
- Android location via Fused Location Provider.
- iOS location via Core Location.
- Clean-room shared lap engine.
- Local-first session storage.
- External GNSS/HUD transport is a backend boundary, not a second Drive user flow.

## Non-Negotiables

- Do not copy GPL-licensed code from DovesLapTimer or DovesDataViewer unless the project license decision explicitly allows it.
- Keep lap engine logic independent from UI and platform APIs.
- Every algorithmic behavior must be testable with synthetic or recorded replay data.
- Safety language must remain explicit: closed-course/private-track use, passive UI while moving, no public-road racing positioning.
- Do not build the glasses app before the phone companion produces reliable timing state.

## Planning References

- Project context: `.planning/PROJECT.md`
- Requirements: `.planning/REQUIREMENTS.md`
- Roadmap: `.planning/ROADMAP.md`
- Current state: `.planning/STATE.md`
- Stack research: `.planning/research/STACK.md`
- Lap engine research: `.planning/research/LAP_ENGINE.md`

## Preferred Workflow

1. Review `.planning/STATE.md`.
2. Work phase by phase from `.planning/ROADMAP.md`.
3. For each phase, produce or update a concrete implementation plan before coding.
4. Keep changes vertical: after each phase, the user should be able to do something observable.
5. Verify with automated tests where possible, especially for lap engine logic.

## Current Integration Step

The phone walking skeleton and physical LapSight-HUD NMEA link are already validated. Continue with vertical backend slices in this order: remote marking lifecycle and reconnect recovery, versioned course transfer, then HUD-local timing/pause/resume/stop with authoritative telemetry returned to the unchanged APP flow.
