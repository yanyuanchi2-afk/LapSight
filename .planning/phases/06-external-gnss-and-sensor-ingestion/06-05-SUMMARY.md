---
phase: 06-external-gnss-and-sensor-ingestion
plan: 05
subsystem: ui
tags: [compose-multiplatform, stateflow, settings, external-gnss, localization, kotlin-multiplatform]

# Dependency graph
requires:
  - phase: 06-external-gnss-and-sensor-ingestion
    provides: ExternalGnssLocationProvider with connectionState StateFlow<ExternalGnssConnectionState> (built in earlier 06-0x plans), ExternalGnssModels.kt data types
provides:
  - Live External GNSS connection-status card in Settings (ExternalGnssSettingsCard), mirroring the existing GlassesSettingsCard pattern
  - externalGnssConnectionState StateFlow threaded end-to-end: MainActivity -> App -> AppShell -> SettingsScreen
  - resolveSourceNote(...) pure function fixing IN-01 (06-REVIEW.md) note-priority ordering
  - externalGnssConnectionLabel LocalizedStrings extension mapping all 6 ExternalGnssConnectionPhase values
  - 6 new localized connection-status strings (Disconnected/Scanning/Connecting/Connected/Reconnecting/Failed) across all 6 locales
affects: [06-verification, phase-6-closure]

# Tech tracking
tech-stack:
  added: []
  patterns: [StateFlow-through-Compose-tree threading (copied glassesConnectionState idiom), pure-function extraction for testable Settings note-priority logic]

key-files:
  created: []
  modified:
    - androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/App.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/AppShell.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt
    - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/Localization.kt
    - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/ui/ExternalGnssSettingsTest.kt

key-decisions:
  - "externalGnssConnectionState is passed straight from MainActivity's synchronously-constructed ExternalGnssLocationProvider (externalGnssProvider!!.connectionState) with no forwarding MutableStateFlow field, unlike the glasses case (which builds its bridge asynchronously)."
  - "ExternalGnssSettingsCard reads state.message directly (no `as?` cast) because ExternalGnssConnectionState is a flat data class, not a sealed interface like GlassesConnectionState."
  - "IN-01 fix: resolveSourceNote checks the ExternalGnss branch before the generic !phoneGpsAvailable branch, so a user with External GNSS selected but Phone GPS unavailable sees the External-GNSS-specific note."

patterns-established:
  - "New platform-status StateFlow parameters thread through App/AppShell/SettingsScreen using the exact glassesConnectionState idiom: add param with a MutableStateFlow default, pass straight through at each layer, wire only into the SettingsScreen call site (never DriveScreen, preserving D-08)."

requirements-completed: [EXT-01, EXT-02]

# Metrics
duration: 20min
completed: 2026-07-08
---

# Phase 6 Plan 05: External GNSS connection-status Settings card Summary

**Threaded the existing `ExternalGnssLocationProvider.connectionState` StateFlow into Compose UI as a live status card in Settings, and fixed the IN-01 source-note priority bug in the same file.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-07-08T01:13:27-04:00 (first task commit)
- **Completed:** 2026-07-08T01:16:49-04:00 (last task commit)
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- `externalGnssConnectionState: StateFlow<ExternalGnssConnectionState>` now flows from the real Android provider through `MainActivity` -> `App` -> `AppShell` -> `SettingsScreen`, following the exact `glassesConnectionState` pattern already established in the same 4 files.
- Settings shows a live `ExternalGnssSettingsCard` (phase label + capped error caption on Failed) whenever `externalGnssAvailable` is true, closing ROADMAP Phase 6 Success Criterion 4's "connection/status" half.
- Fixed 06-REVIEW.md finding IN-01: `resolveSourceNote` now checks the External-GNSS branch before the generic Phone-GPS-unavailable branch, so a user with External GNSS selected and Phone GPS unavailable sees the correct protocol-preview note instead of the generic message.
- All 6 locales (en/zh/ja/ko/fr/es) define the 6 new connection-status strings; `StringsEn` widened to `internal` for direct test access (matching existing `locationSourceOptions`/`resolveEffectiveLocationFeedMode` precedent).

## Task Commits

Each task was committed atomically:

1. **Task 1: Thread externalGnssConnectionState StateFlow through MainActivity -> App -> AppShell -> SettingsScreen, and add the 6-locale status strings** - `b254fb3` (feat)
2. **Task 2 RED: add failing tests for externalGnssConnectionLabel and resolveSourceNote** - `f5ad403` (test)
3. **Task 2 GREEN: Render ExternalGnssSettingsCard in Settings and fix IN-01 source-note priority** - `846a8a3` (feat)

**Plan metadata:** committed separately by the wave orchestrator after merge (worktree mode — this executor does not touch STATE.md/ROADMAP.md).

_Note: Task 2 used tdd="true" as specified in the plan — RED test commit precedes the GREEN implementation commit._

## Files Created/Modified
- `androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt` - passes `externalGnssProvider!!.connectionState` into `App(...)`
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/App.kt` - new `externalGnssConnectionState` parameter, passthrough to `AppShell`
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/AppShell.kt` - new parameter, passthrough only into the `AppTab.Settings -> SettingsScreen(...)` call (not `DriveScreen`, preserving D-08)
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt` - new parameter; `resolveSourceNote(...)` extracted pure function (IN-01 fix); `externalGnssConnectionLabel` mapping helper; new `ExternalGnssSettingsCard` composable called after `GlassesSettingsCard` when `externalGnssAvailable`
- `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/Localization.kt` - 6 new `LocalizedStrings` fields + 6 locale value sets; `StringsEn` widened `private` -> `internal`
- `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/ui/ExternalGnssSettingsTest.kt` - 2 new regression tests (label coverage + IN-01 note-priority case), bringing the file from 6 to 8 tests

## Decisions Made
- Followed the plan's explicit interface contract exactly: no codebase exploration was needed for the `ExternalGnssConnectionState`/`ExternalGnssConnectionPhase` shape, matching what was read in `ExternalGnssModels.kt`.
- No new interactive device-management/connect-forget flow was added — this plan implements only the read-only live status half of the UI-SPEC connectivity contract, per the plan's explicit scope decision.

## Deviations from Plan

None - plan executed exactly as written. One local build-environment fix was required (see Issues Encountered) but it did not touch any plan-scoped source file.

## Issues Encountered
- This worktree had no `local.properties` (gitignored, not present in a fresh worktree checkout), so the first `./gradlew :androidApp:assembleDebug` failed with "SDK location not found." Created a worktree-local `local.properties` pointing at the same Android SDK path already configured in the main repo's `local.properties` (untracked file, not committed — purely a local build-environment fix, not a plan deviation).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- ROADMAP Phase 6 Success Criterion 4 ("connection/status") is now satisfied for the UI half; External GNSS hardware validation itself remains an explicitly tracked open risk (unchanged by this plan).
- No new diagnostic UI appears on the Drive surface (D-08 intact) — verified via grep that `externalGnssConnectionState` is only passed into the `SettingsScreen(...)` call in `AppShell.kt`, never `DriveScreen(...)`.
- `./gradlew :shared:testAndroidHostTest` (full suite), `./gradlew :androidApp:assembleDebug`, and `./gradlew :androidApp:compileDebugAndroidTestKotlin` all pass.

---
*Phase: 06-external-gnss-and-sensor-ingestion*
*Completed: 2026-07-08*
