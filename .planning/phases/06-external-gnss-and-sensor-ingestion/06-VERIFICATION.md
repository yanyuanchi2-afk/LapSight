---
phase: 06-external-gnss-and-sensor-ingestion
verified: 2026-07-08T05:36:55Z
status: passed
score: 7/7 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 6/7
  gaps_closed:
    - "UI exposes external GNSS source quality and connection/status without returning diagnostic raw GPS tools to the main Drive surface (ROADMAP Success Criterion 4) — the live BLE connection/status half is now threaded into Settings"
  gaps_remaining: []
  regressions: []
deferred: []
human_verification: []
---

# Phase 6: External GNSS and Sensor Ingestion — Verification Report (Re-verification)

**Phase Goal:** As a user who needs better timing precision, I want to connect an external GPS receiver, so that LapSight can exceed phone GPS limitations. (Mode: mvp, explicitly narrowed by ROADMAP/06-VALIDATION.md/06-HARDWARE-RISK.md to "protocol-complete, hardware-unvalidated" — real hardware validation is deliberately deferred, and that is the accepted closeout bar for this phase, not full hardware/UAT testing.)

**Verified:** 2026-07-08T05:36:55Z
**Status:** passed
**Re-verification:** Yes — after gap-closure plans 06-05 (connection-status UI wiring) and 06-06 (parser hardening) executed against the prior `06-VERIFICATION.md` gap and `06-REVIEW.md` findings.

## Goal Achievement

### Observable Truths

Same 7-truth set as the initial verification (ROADMAP's 5 Success Criteria plus D-07/D-08 cross-plan must-haves). Truths 1, 2, 3, 5, 6, 7 previously passed and received a regression check only; truth 4 previously failed and received full 3-level re-verification.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | App can decode external GNSS samples from protocol fixtures/replay, including NMEA 0183 and RaceBox-family synthetic frames (SC1) | VERIFIED (regression) | `./gradlew :shared:testAndroidHostTest` independently re-run during this verification — BUILD SUCCESSFUL, all `Nmea0183ParserTest` (9 tests) and `RaceBoxFrameParserTest` (11 tests) pass, verified via test-result XML (`tests=` counts, `failures="0" errors="0"`), not just console output. |
| 2 | App records sample source, protocol, update frequency, and hardware-validation status (SC2) | VERIFIED (regression) | `ExternalGnssSourceMetadata`/`ExternalGnssHardwareValidationStatus` unchanged by 06-05/06-06; no code path touched this must-have. |
| 3 | Lap engine works unchanged regardless of phone GPS, simulated replay, or external GNSS input (SC3) | VERIFIED (regression) | `ExternalGnssTimingPipelineTest` (6 tests) re-run as part of the full `:shared:testAndroidHostTest` pass, still green; `grep -rn "ExternalGnss" .../lap/LapEngine.kt` still returns zero matches. |
| 4 | UI exposes external GNSS source quality and connection/status without returning diagnostic raw GPS tools to the main Drive surface (SC4) | **VERIFIED (gap closed)** | Full 3-level check performed (see Key Link Verification and Data-Flow Trace below). `ExternalGnssLocationProvider.connectionState` (Android, `StateFlow<ExternalGnssConnectionState>`) is now threaded `MainActivity.kt:241` → `App.kt:57/97` → `AppShell.kt:96/394` → `SettingsScreen.kt:140` → rendered by a new `ExternalGnssSettingsCard` (`SettingsScreen.kt:422-445`) that calls `connectionState.collectAsState()` and shows `s.externalGnssConnectionLabel(state.phase)` (Not connected/Scanning…/Connecting/Connected/Reconnecting…/Connection Failed…) plus a capped failure-message caption. Confirmed by direct read of every hop, not by trusting SUMMARY.md. Drive surface (`AppShell.kt:370-382`, `DriveScreen.kt`) still has zero references to `externalGnssConnectionState` — D-08 boundary intact. |
| 5 | Documentation clearly separates protocol support from unvalidated hardware support (SC5) | VERIFIED (regression) | `docs/EXTERNAL-GNSS.md` unchanged by 06-05/06-06; `06-HARDWARE-RISK.md` gained a new section 5 (see Artifacts) but its existing sections 1-4 are unchanged. |
| 6 | RaceBox implementation is clean-room; no GPL or unlicensed RaceBox client/emulator code copied (D-07) | VERIFIED (regression) | No new external code introduced by 06-05/06-06; both plans only modified existing first-party files. |
| 7 | Drive remains low-interaction; detailed diagnostics do not return to the main Drive surface (D-08) | VERIFIED (strengthened) | `grep -n "externalGnssConnectionState" shared/.../ui/*.kt` returns matches only in `SettingsScreen.kt` and `AppShell.kt` (the AppShell match is the passthrough parameter and the `SettingsScreen(...)` call site only) — zero matches in `DriveScreen.kt` or `drive/*.kt`. The new UI surface added by 06-05 is Settings-only, as designed. |

**Score:** 7/7 truths verified

### Gap Closure Confirmation (from previous VERIFICATION.md)

The single previously-failed truth (SC4, "connection/status" half) is closed by plan 06-05:

- **Before:** `ExternalGnssLocationProvider.connectionState` was constructed in `MainActivity.kt` but had zero UI consumers anywhere in the codebase (confirmed NOT_WIRED by exhaustive grep in the prior verification pass).
- **After:** The StateFlow is threaded through 4 layers (`MainActivity` → `App` → `AppShell` → `SettingsScreen`) using the exact `glassesConnectionState` idiom already established for the Meta glasses feature, and rendered by a dedicated `ExternalGnssSettingsCard`. This was independently re-traced hop-by-hop in this verification (see truth 4 evidence and Key Link table), not accepted from SUMMARY.md's claim alone.
- **Regression check:** No new diagnostic UI reached the Drive surface (`grep` confirms zero `externalGnssConnectionState` references outside `SettingsScreen.kt`/`AppShell.kt`'s passthrough), preserving D-08 exactly as the gap-closure plan promised.

### Review Findings Closure Confirmation (from 06-REVIEW.md, prior pass)

The task explicitly asked to confirm CR-01/WR-03/WR-04/IN-01 are closed, not just that tasks ran. Each was independently re-verified by reading the actual diff, not the SUMMARY narrative:

| Finding | Claim | Verified in code |
|---------|-------|-------------------|
| CR-01 (Critical) | RaceBox sync-byte-split data loss fixed | `RaceBoxFrameParser.kt:20-34` — `syncIndex < 0` branch now computes `keepTrailingByte`/`garbageLength` instead of unconditionally clearing `streamBuffer`; new test `fragmentedInputSplitExactlyAtSyncBoundaryStillDecodes` (`RaceBoxFrameParserTest.kt:95-112`) genuinely exercises the exact 1-byte/rest split and asserts snapshot parity with the whole-frame parse. Test passes (11 tests total in the class, 0 failures per XML report). |
| WR-03 (prior report numbering) | Unbounded NMEA buffer growth fixed | `Nmea0183Parser.kt:6,19-21` — `MAX_BUFFERED_NMEA_CHARS = 4_096` cap-and-clear added. New test `unterminatedOversizedStreamDoesNotWedgeTheParser` (`Nmea0183ParserTest.kt:103-120`) asserts `parser.bufferedCharCount == 0` after an oversized unterminated feed (a genuine RED/GREEN assertion, per the SUMMARY's own noted TDD strengthening) and that the parser recovers on the next valid sentence. Passes. |
| WR-04 (prior report numbering) | HDOP no longer mislabeled as `horizontalAccuracyMeters` | `Nmea0183Parser.kt:258` — `horizontalAccuracyMeters = null` (was `currentFix.hdop`). New test `hdopIsNotMislabeledAsHorizontalAccuracyMeters` (`Nmea0183ParserTest.kt:122-131`) asserts `quality.hdop == 0.9` (raw HDOP preserved) and `quality.horizontalAccuracyMeters == null`. Passes. |
| IN-01 (prior report numbering) | Settings source-note ordering hid the External-GNSS note | `SettingsScreen.kt:111-125` — `resolveSourceNote()` now checks `effectiveLocationFeedMode == LocationFeedMode.ExternalGnss` (line 120) before `!phoneGpsAvailable` (line 121). New test `resolveSourceNotePrefersExternalGnssNoteOverGenericPhoneGpsUnavailable` (`ExternalGnssSettingsTest.kt:111-125`) asserts the External-GNSS note wins. Passes. |

All four fixes are genuine (not stubs), each has a dedicated regression test that would fail without the fix (confirmed by reading the test logic, and for WR-03 the SUMMARY's own documented RED-phase investigation independently corroborates this), and the full `:shared:testAndroidHostTest` suite was independently re-run in this verification session (not merely read from SUMMARY.md) with `BUILD SUCCESSFUL`.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `shared/.../ui/SettingsScreen.kt` | `ExternalGnssSettingsCard`, `resolveSourceNote`, `externalGnssConnectionLabel` | VERIFIED | All three present, substantive (not stubs — real `when`/StateFlow-collection logic), and wired (see Key Links) |
| `shared/.../ui/Localization.kt` | 6 new connection-status strings × 6 locales | VERIFIED | `grep -c "externalGnssConnectionFailed"` returns exactly 7 (1 constructor param + 6 locale assignments); all 6 locale blocks (en/zh/ko/ja/fr/es) confirmed present with distinct translated values, not copy-pasted placeholders |
| `shared/.../App.kt`, `shared/.../ui/AppShell.kt` | `externalGnssConnectionState` param + passthrough | VERIFIED | 2 occurrences each as the plan's acceptance criteria specified; AppShell passthrough confirmed to reach only `SettingsScreen(...)`, not `DriveScreen(...)` |
| `androidApp/.../MainActivity.kt` | `externalGnssConnectionState = externalGnssProvider!!.connectionState` | VERIFIED | Exactly one matching line (`MainActivity.kt:241`), reading the real provider's live StateFlow, not a fake/hardcoded value |
| `shared/.../external/RaceBoxFrameParser.kt` | CR-01 fix (`keepTrailingByte`) | VERIFIED | Present at lines 20-34; regression test passes |
| `shared/.../external/Nmea0183Parser.kt` | WR-03/WR-04 fixes (`MAX_BUFFERED_NMEA_CHARS`, `horizontalAccuracyMeters = null`) | VERIFIED | Both present; regression tests pass |
| `.planning/.../06-HARDWARE-RISK.md` | New section 5 documenting WR-01/WR-02/WR-05/IN-02 deferral | VERIFIED | Section 5 exists (lines 76-104), non-silently records all 4 deferred findings with rationale and exact `06-REVIEW.md` cross-references |
| `shared/src/commonTest/.../ExternalGnssSettingsTest.kt` | 2 new regression tests | VERIFIED | File grew from 6 to 8 tests; both new tests genuinely assert behavior that would fail without the corresponding fix |
| `shared/src/commonTest/.../RaceBoxFrameParserTest.kt` | New sync-boundary regression test | VERIFIED | Class now has 11 `@Test` methods (SUMMARY described "9 to 10"; actual baseline was higher than the plan assumed — a narrative discrepancy, not a functional gap, since the new test itself is present, correct, and passing) |
| `shared/src/commonTest/.../Nmea0183ParserTest.kt` | 2 new regression tests | VERIFIED | Class has 9 tests per XML report, matching the "7 to 9" claim exactly |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `ExternalGnssLocationProvider.connectionState` | `MainActivity.kt` `setContent { App(...) }` | `externalGnssConnectionState = externalGnssProvider!!.connectionState` | **WIRED** (was NOT_WIRED) | `MainActivity.kt:241`, single exact match |
| `App.kt` | `AppShell.kt` | `externalGnssConnectionState = externalGnssConnectionState` passthrough | WIRED | `App.kt:57` (param), `App.kt:97` (passthrough into `AppShell(...)`) |
| `AppShell.kt` | `SettingsScreen.kt` (Settings tab only) | `externalGnssConnectionState = externalGnssConnectionState` in the `AppTab.Settings -> SettingsScreen(...)` branch | WIRED | `AppShell.kt:96` (param), `AppShell.kt:394` (passthrough, confirmed inside the `AppTab.Settings` branch at lines 389-405, NOT the `AppTab.Drive` branch at lines ~360-382) |
| `SettingsScreen.kt` | `ExternalGnssSettingsCard` | `connectionState.collectAsState()` inside the card composable, called conditionally on `externalGnssAvailable` | WIRED | `SettingsScreen.kt:294-296` (call site), `SettingsScreen.kt:423-426` (composable body reading the live state) |
| `RaceBoxFrameParser`/`Nmea0183Parser` | `SessionController`/`LapEngine` | decode → `LocationSample` → real timing pipeline | WIRED (unchanged, regression-checked) | `ExternalGnssTimingPipelineTest` re-run, still passes |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| `ExternalGnssSettingsCard` | `state.phase` (via `connectionState.collectAsState()`) | `ExternalGnssLocationProvider._connectionState`, mutated by `handlePhase(phase)` (`ExternalGnssLocationProvider.kt:121-123`), which is registered as the `onConnectionPhase` callback of the real `AndroidExternalGnssBleClient` (`ExternalGnssLocationProvider.kt:69`) | Yes — the phase value originates from the actual BLE scan/connect/reconnect state machine, not a hardcoded constant. (A real device is still required to exercise phases beyond `Disconnected` — the *plumbing* is real, hardware validation itself remains explicitly out of scope per phase status.) | FLOWING |
| Settings "External GNSS" source note (`resolveSourceNote`) | `sourceNote` string | Now correctly branches on `effectiveLocationFeedMode == ExternalGnss` before the generic Phone-GPS-unavailable check (IN-01 fix) | Yes — pure function, deterministic on real app state, not static regardless of mode | FLOWING (previously flagged STATIC/contributing to the SC4 gap in the prior verification; the note itself was always correct content-wise, the prior gap was specifically about the *missing* connection-phase indicator, now resolved separately) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Targeted external-GNSS test classes pass (independently re-run, not read from cache alone) | `./gradlew :shared:testAndroidHostTest --tests "*Nmea0183ParserTest*" --tests "*RaceBoxFrameParserTest*" --tests "*ExternalGnssSettingsTest*" --rerun` | BUILD SUCCESSFUL | PASS |
| Full shared host test suite (regression check) | `./gradlew :shared:testAndroidHostTest` | BUILD SUCCESSFUL | PASS |
| Test result XML confirms exact pass counts, not just console text | `grep tests=".*failures="0"` on `TEST-...Nmea0183ParserTest.xml` (9 tests), `TEST-...RaceBoxFrameParserTest.xml` (11 tests), `TEST-...ExternalGnssSettingsTest.xml` (8 tests) | All `failures="0" errors="0"` | PASS |
| Android debug build compiles with the full 4-layer StateFlow chain | `./gradlew :androidApp:assembleDebug` | BUILD SUCCESSFUL | PASS |
| CR-01 fix genuinely closes the sync-boundary data-loss window | Direct code read of `RaceBoxFrameParser.kt:20-34` + `fragmentedInputSplitExactlyAtSyncBoundaryStillDecodes` test | `keepTrailingByte` preserves the lone `0xB5` byte instead of wiping `streamBuffer` | CONFIRMED FIXED |
| D-08 boundary: no External-GNSS diagnostic reaches Drive | `grep -n "externalGnssConnectionState" shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/*.kt` and `DriveScreen.kt`/`drive/*.kt` | Zero matches outside `SettingsScreen.kt`/`AppShell.kt` | CONFIRMED |

### Probe Execution

Not applicable — this is a Kotlin Multiplatform mobile app phase with no `scripts/*/tests/probe-*.sh` convention in this repository; the equivalent automated verification is the Gradle test/build gates above, all independently re-run in this session.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| EXT-01 | 06-01, 06-02, 06-03, 06-04, 06-05, 06-06 | User can connect an external GNSS receiver over BLE, Bluetooth serial, Wi-Fi, or TCP/NMEA | SATISFIED (protocol-preview scope, unchanged conclusion) | NMEA/RaceBox parsers hardened (CR-01/WR-03/WR-04 closed) + live connection-status UI added; real hardware connection remains explicitly and correctly unvalidated per the phase's accepted closeout bar |
| EXT-02 | 06-03, 06-04, 06-05 | App can prefer external GNSS over phone GPS when connected | SATISFIED (manual-preference interpretation, unchanged conclusion) | Same design-choice basis as the prior verification; now additionally strengthened because the user can *see* the connection phase before/while selecting External GNSS, partially closing the "when connected" ambiguity that the prior verification flagged as related to the SC4 gap |
| EXT-03 | 06-01, 06-02, 06-04, 06-06 | App can ingest basic IMU or vehicle telemetry when available | SATISFIED (unchanged conclusion) | `ExternalGnssTelemetryMetadata` unaffected by 06-05/06-06; `ExternalGnssTimingPipelineTest.raceBoxBasicTelemetryIsPreservedAsCaptureButNeverEntersTimingSamples` re-run, still passes |

REQUIREMENTS.md lists EXT-01/EXT-02/EXT-03 as unchecked v2 bullets (no `- [ ]` checkbox format), consistent with every other v2 entry in the document (v2 section uses plain `- **ID**:` bullets throughout, unlike v1's `- [x]` format) — not a phase-specific omission. No orphaned Phase 6 requirement IDs: REQUIREMENTS.md has no "Phase 6" phase-mapping section, and all three declared IDs are covered across all six plans' `requirements:` frontmatter (06-05 declares `[EXT-01, EXT-02]`, 06-06 declares `[EXT-01, EXT-03]`, both consistent with the earlier plans).

### Anti-Patterns Found

Re-scanned all files touched by 06-05/06-06 for debt markers (`TBD`/`FIXME`/`XXX`) — **zero found** (an initial broad grep produced false positives matching substrings like `toDoubleOrNull`/`metadata`; confirmed false via the precise Grep tool). No blocker-level anti-patterns exist in this re-verification.

The fresh `06-REVIEW.md` pass (this re-review, done after 06-05/06-06 merged) found 0 critical / 5 warning / 2 info issues. None of these are newly-introduced blockers to this phase's must-haves; they are either carryforward accepted risk (already deferred non-silently in `06-HARDWARE-RISK.md` section 5) or new but narrow WARNING/INFO findings consistent with the phase's "hardware-unvalidated" framing:

| File | Finding | Severity | Impact | Status |
|------|---------|----------|--------|--------|
| `Nmea0183Parser.kt:17-22` | Buffer-cap check runs *before* the sentence-drain loop, so a chunk that both exceeds the cap and contains complete terminated sentences can silently discard valid data alongside garbage (WR-01 in fresh review) | WARNING | Narrower/lower-probability sibling of the now-fixed CR-01; introduced by the WR-03 hardening fix itself | NEW, not fixed in this pass, correctly identified in the fresh review, not blocking any literal must_have wording |
| `ExternalGnssBleClient.kt:107-118,209-221,253-260` | `BluetoothGatt` leak on reconnect | WARNING | Carryforward, unfixed, explicitly deferred in `06-HARDWARE-RISK.md` §5 | Documented, non-silent, accepted |
| `ExternalGnssBleClient.kt:223-227` | `discoverServices()` swallows failures | WARNING | Carryforward, unfixed, explicitly deferred | Documented, non-silent, accepted |
| `ExternalGnssLocationProvider.kt`/`ExternalGnssBleClient.kt` | `running`/parser/gatt state mutated cross-thread without `@Volatile`/synchronization (WR-04 in fresh review) | WARNING | New finding, untested BLE layer, real but low-likelihood-to-trigger given current test coverage (zero for the class in question) | NEW, not fixed, not blocking (BLE hardware path already accepted risk under D-02) |
| `ExternalGnssBleClient.kt` (whole file) | Zero automated test coverage of the real BLE class | WARNING | Carryforward, explicitly deferred with rationale | Documented, non-silent, accepted |
| `SettingsScreen.kt:435-443` | `ExternalGnssConnectionState.message` never populated by production code — the Failed-state caption branch is currently dead | INFO | Non-blocking; the static `externalGnssConnectionFailed` label still gives actionable guidance | NEW, informational only |
| `AndroidManifest.xml:15-18` | Manifest comment overstates BLE scan filtering | INFO | Carryforward, cosmetic | Documented, non-silent, accepted |

None of these findings contradict any must_have's literal wording, none are debt markers (TBD/FIXME/XXX), and all are either already-accepted deferred risk or freshly-surfaced WARNING/INFO items consistent with a phase whose own accepted closeout bar is "protocol-complete, hardware-unvalidated" rather than "defect-free." They do not change the status determination.

### Human Verification Required

None. All must-haves — including the previously-failed SC4 gap — were resolvable through direct code inspection (reading every hop of the 4-layer StateFlow chain), grep-based wiring traces, XML test-report inspection (not just console text), and independently re-running the phase's Gradle test/build gates in this verification session (not by trusting SUMMARY.md's reported pass/fail). Visual/pixel-level rendering of the new `ExternalGnssSettingsCard` against `06-UI-SPEC.md`'s typography/spacing/color tokens was checked via source-code token usage (`MaterialTheme.typography.bodyLarge/bodySmall`, `LapSightTheme.colors.statusCaution`, `LapCard` wrapper matching the existing `GlassesSettingsCard` pattern) rather than a running-app screenshot; this is consistent with the phase's own accepted "protocol-complete, hardware-unvalidated" bar and the prior verification's precedent of not requiring human UAT for this phase.

### Gaps Summary

No gaps remain. The one gap from the prior `06-VERIFICATION.md` (SC4's "connection/status" half — `ExternalGnssLocationProvider.connectionState` orphaned, never reaching the UI) is closed by plan 06-05: the StateFlow is now genuinely threaded through 4 layers to a real, tested Compose card, with the D-08 Drive-surface boundary independently re-confirmed intact. The four review findings this re-verification was specifically asked to confirm (CR-01, WR-03, WR-04, IN-01) are all closed with real, non-vacuous regression tests that were independently re-run and pass (verified via test-result XML, not console text or SUMMARY narrative). WR-01/WR-02/WR-05/IN-02 (prior numbering) remain open exactly as documented — non-silently, with rationale, in `06-HARDWARE-RISK.md` §5 — consistent with the phase's own scope decision, not silently dropped.

A fresh code review pass (this session's `06-REVIEW.md`, post-merge) surfaced 2 new non-blocking WARNING/INFO findings (a narrower sibling of CR-01 introduced by the WR-03 fix itself, and a cross-thread state-mutation concern in the untested BLE layer) plus 1 new INFO item (a dead UI branch for an unpopulated `message` field). None of these change the phase's pass/fail determination — they are real but narrow, don't touch any must-have's literal wording, and are consistent with the phase's explicit "hardware-unvalidated" closeout bar. They are worth tracking as follow-up work alongside the already-deferred WR-01/WR-02/WR-05/IN-02 register, but do not block Phase 6 closure or Phase 7 readiness.

Phase 6 goal is achieved for its explicitly narrowed scope: a user can select External GNSS, see live connection-phase feedback (Scanning/Connecting/Connected/Reconnecting/Failed) instead of a silent generic no-fix warning, the underlying protocol decoding is correct and now more robust against a known fragmentation edge case, and the entire surface remains honestly labeled as hardware-unvalidated pending real receiver testing.

---

*Verified: 2026-07-08T05:36:55Z*
*Verifier: Claude (gsd-verifier)*
