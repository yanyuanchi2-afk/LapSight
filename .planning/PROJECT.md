# LapSight

## What This Is

LapSight is a mobile companion app for GPS-based lap timing, ghost lap comparison, and session review across karting, track driving, and cycling. The first product is a phone app mounted on a kart, car dashboard, or bicycle handlebar; the later extension is a lightweight Meta Ray-Ban Display glasses HUD driven by the phone app.

The app should feel like a practical motorsport/cycling instrument, not a generic fitness tracker. It prioritizes a readable live dash, reliable lap detection, and a clean shared lap engine that can later feed glasses, watches, external GNSS devices, and post-session analysis.

## Core Value

The user can mount a phone, record a session, and see trustworthy live lap timing plus delta-to-best with minimal interaction while moving.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Build a dual-platform phone app for Android and iOS.
- [ ] Show a readable portrait and landscape live dash suitable for vehicle/bike mounting.
- [ ] Acquire live phone GPS samples with accuracy, speed, heading/course, and timestamp metadata.
- [ ] Record complete sessions locally for replay and debugging.
- [ ] Implement a clean-room lap engine that detects start/finish line crossings from GPS samples.
- [ ] Calculate current lap, last lap, best lap, sector-ready timing, and lap count.
- [ ] Calculate ghost/reference-lap delta using distance-normalized progress along a reference lap.
- [ ] Export sessions in portable formats for future analysis and glasses integration.
- [ ] Keep safety constraints explicit: passive display, large readable UI, no complex interaction while moving.

### Out of Scope

- Public-road racing or speed competition features — unsafe and legally sensitive; position the app for closed courses, tracks, karting facilities, and training contexts.
- Cloud accounts, leaderboards, and social sharing — not needed to validate the core timing product.
- Native watch app — useful later, but not required for the phone companion MVP.
- Meta DAT Display bridge — later integration target; phone companion comes first.
- Racing-grade timing guarantee — phone GPS is not precise enough for pro-grade timing; v1 should be honest about accuracy and support future external GNSS.
- Direct GPL code reuse from DovesLapTimer or DovesDataViewer — useful references, but GPL-licensed code must not be copied into a non-GPL product without an explicit licensing decision.

## Context

The project emerged from a Meta Ray-Ban Display glasses app discussion. A glasses-only lap timer is technically possible because the glasses web runtime can obtain paired-phone location, but the better architecture is phone-first: the phone owns GPS, storage, session management, external sensor integration, and the richer dash UI. The glasses later become a passive HUD client.

Prior open-source research found useful references:

- DovesLapTimer: strong GPS lap-timing algorithm reference, including start/finish line crossing, interpolation, sectors, direction detection, and GPS replay tests; GPL-3.0.
- DovesDataViewer: strong post-session telemetry and ghost/reference-lap UI reference; GPL-3.0.
- Blackbox: MIT-licensed ESP32 GPS/IMU lap timer with useful line detection and delta design notes.
- PUTM_VP_LAPTIMER: Apache-2.0 ROS2 lap timer with GPS lap time and delta-to-reference concepts.

The current recommended app stack is Kotlin Multiplatform plus Compose Multiplatform, with platform-native location providers. This keeps business logic and UI mostly shared while preserving access to Android and iOS native services.

## Constraints

- **Platform**: Android and iOS are both in scope; Android-only is acceptable only as an early local spike.
- **Safety**: Moving users should not need to interact deeply with the UI; the live screen must be glanceable and passive.
- **Accuracy**: Phone GPS can be noisy and low-frequency; the app must show GPS quality and design filters around accuracy, speed, heading/course, and debounce rules.
- **Licensing**: Avoid copying GPL code unless the whole app is intentionally GPL-compatible.
- **Architecture**: The lap engine must be independent of UI and platform APIs so it can be tested with replay files and reused by future glasses bridge code.
- **Offline-first**: Session recording and review must work without network access.
- **MR path**: Future glasses integration should consume summarized timing state from the phone, not duplicate the full lap engine inside the glasses display layer.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Phone app before glasses app | Phone is the correct owner for GPS, storage, external sensors, and dash UI. | — Pending validation |
| Use LapSight as project name | Short, product-like, and compatible with future MR/HUD positioning. | — Pending availability checks |
| Prefer Kotlin Multiplatform + Compose Multiplatform | KMP shares domain logic across Android/iOS while retaining native access; CMP can share UI for the dash-heavy app. | — Pending implementation spike |
| Build a clean-room lap engine | Avoid GPL contamination and keep algorithmic behavior testable. | — Pending implementation |
| Treat external GNSS as v2 capability | Phone GPS validates product flow; high-accuracy timing can be added via BLE/TCP/NMEA later. | Phase 6 complete: RaceBox + NMEA parsing, replay pipeline, and Settings connection-status UI shipped protocol-complete. Remains hardware-unvalidated — real receiver testing still pending. |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition**:
1. Requirements invalidated? Move to Out of Scope with reason.
2. Requirements validated? Move to Validated with phase reference.
3. New requirements emerged? Add to Active.
4. Decisions to log? Add to Key Decisions.
5. "What This Is" still accurate? Update if drifted.

**After each milestone**:
1. Full review of all sections.
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state.

---
*Last updated: 2026-07-08 after Phase 6 (External GNSS and Sensor Ingestion) completion*
