---
phase: 06-external-gnss-and-sensor-ingestion
reviewed: 2026-07-08T00:00:00Z
depth: standard
files_reviewed: 20
files_reviewed_list:
  - androidApp/build.gradle.kts
  - androidApp/src/main/AndroidManifest.xml
  - androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt
  - androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProvider.kt
  - androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt
  - androidApp/src/test/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProviderTest.kt
  - docs/EXTERNAL-GNSS.md
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/App.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/DisplaySettings.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssModels.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProvider.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameBuilder.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParser.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/AppShell.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/Localization.kt
  - shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt
  - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssReplayProviderTest.kt
  - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssTimingPipelineTest.kt
  - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183ParserTest.kt
  - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParserTest.kt
  - shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/ui/ExternalGnssSettingsTest.kt
findings:
  critical: 0
  warning: 5
  info: 2
  total: 7
status: issues_found
---

# Phase 06: Code Review Report

**Reviewed:** 2026-07-08T00:00:00Z
**Depth:** standard
**Files Reviewed:** 20 (files_reviewed_list above lists 21 entries; `androidApp/build.gradle.kts` was reviewed as pure config with no logic findings)
**Status:** issues_found

## Summary

This is a gap-closure re-review after Plan 06-05 (connection-status UI) and Plan 06-06 (RaceBox/NMEA parser hardening) were merged. All four findings the gap-closure work was scoped to close are verified fixed with matching regression coverage:

- **CR-01** (RaceBox sync-byte-split data loss): fixed exactly as previously recommended — `RaceBoxFrameParser.accept()` now retains a trailing lone `0xB5` byte instead of wiping the buffer, and `RaceBoxFrameParserTest.fragmentedInputSplitExactlyAtSyncBoundaryStillDecodes` directly exercises the split-at-sync-boundary case that was previously silently dropped.
- **WR-03 (prior report)** (unbounded NMEA buffer growth): fixed — `Nmea0183Parser` now caps `streamBuffer` at `MAX_BUFFERED_NMEA_CHARS` (4096), and `Nmea0183ParserTest.unterminatedOversizedStreamDoesNotWedgeTheParser` proves the parser recovers after an oversized unterminated stream. (See WR-01 below for a related but distinct ordering defect introduced by this same fix.)
- **WR-04 (prior report)** (HDOP mislabeled as horizontalAccuracyMeters): fixed — `Nmea0183Parser.snapshot()` now leaves `horizontalAccuracyMeters = null` for NMEA fixes with an explanatory comment, and `Nmea0183ParserTest.hdopIsNotMislabeledAsHorizontalAccuracyMeters` asserts it directly.
- **IN-01 (prior report)** (Settings source-note ordering hid the External-GNSS note behind the generic Phone-GPS-unavailable note): fixed — `resolveSourceNote()` now checks the `ExternalGnss` branch before the `!phoneGpsAvailable` branch, with `ExternalGnssSettingsTest.resolveSourceNotePrefersExternalGnssNoteOverGenericPhoneGpsUnavailable` covering the regression.

Beyond verifying those four fixes, this full pass found one new correctness gap introduced by the buffer-cap hardening itself (the size-cap check runs *before* draining already-complete sentences, so it can silently discard valid, terminated data alongside the garbage it's meant to bound), one previously-unflagged thread-safety gap in the Android BLE/provider layer, and several still-open carryforward issues from the previous review (`WR-01`/`WR-02`/`WR-05`/`IN-02` in the prior report) that were not in this gap-closure's scope and remain unfixed. None of the newly-found issues are rated Critical — they are narrower or lower-probability than the fixed CR-01, but are real and worth tracking.

## Warnings

### WR-01: `Nmea0183Parser`'s oversized-buffer guard can silently discard complete, valid, already-terminated sentences

**File:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt:17-22`
**Issue:** The buffer-cap fix appends new bytes and applies the size cap *before* the sentence-draining loop runs:
```kotlin
fun accept(text: String): List<Nmea0183ParseResult> {
    streamBuffer.append(text)
    if (streamBuffer.length > MAX_BUFFERED_NMEA_CHARS) {
        streamBuffer.clear()   // wipes EVERYTHING, including complete unprocessed sentences
    }
    val results = mutableListOf<Nmea0183ParseResult>()
    while (true) {
        val lineEnd = streamBuffer.indexOfLineEnd()
        ...
```
If the residual buffer from a prior malformed/unterminated stretch is already near the 4096-char cap, and the *next* chunk passed to `accept()` both pushes the total over the cap **and** contains one or more complete, correctly `\r`/`\n`-terminated sentences (e.g. a flaky receiver that sends a long garbage run and then recovers mid-chunk, or several buffered sentences delivered in one burst after a reconnect), `streamBuffer.clear()` wipes all of it — including the complete, valid, checksummed sentences — before the parsing loop ever gets a chance to drain them. No `Rejected`/`Ignored` result is emitted for the lost data either, so this failure mode is invisible to any caller. This is a smaller-blast-radius sibling of the now-fixed CR-01 (silent data loss with no diagnostic trace), introduced by the very fix meant to hedge against a different failure mode. It requires a specific timing (buffer already near-cap, then a chunk that both exceeds the cap and carries complete terminated sentences) so it is much less likely to trigger than CR-01 was, especially given typical BLE MTU sizes, but it is a genuine defect in code that was *just* hardened for exactly this class of problem.
**Fix:** Drain all currently-completable sentences first, and only apply the size cap to whatever unterminated residue remains afterward:
```kotlin
fun accept(text: String): List<Nmea0183ParseResult> {
    streamBuffer.append(text)
    val results = mutableListOf<Nmea0183ParseResult>()

    while (true) {
        val lineEnd = streamBuffer.indexOfLineEnd()
        if (lineEnd < 0) break
        ...
    }

    if (streamBuffer.length > MAX_BUFFERED_NMEA_CHARS) {
        streamBuffer.clear()
    }

    return results
}
```
Add a regression test that appends a >4096-char unterminated prefix in one `accept()` call followed immediately (same call) by a complete valid sentence, and asserts the valid sentence is still decoded.

### WR-02: `AndroidExternalGnssBleClient` leaks the previous `BluetoothGatt` on every automatic reconnect (carryforward from prior review's WR-01, unfixed)

**File:** `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt:107-116`
**Issue:** Still present exactly as previously reported. On an unexpected disconnect while `running` is true, `onConnectionStateChange` reports `Reconnecting` and calls `startScanInternal()` again, but never calls `gatt?.close()` on the stale `BluetoothGatt`:
```kotlin
BluetoothProfile.STATE_DISCONNECTED -> {
    if (running) {
        reportPhase(ExternalGnssConnectionPhase.Reconnecting)
        startScanInternal()   // old `gatt` field is never closed
    } else {
        reportPhase(ExternalGnssConnectionPhase.Disconnected)
    }
}
```
When `connect(device)` later runs, `gatt = device.connectGatt(...)` silently overwrites the field, orphaning the previous `BluetoothGatt` without ever calling `close()`. This was not in scope for the 06-05/06-06 gap-closure plans and remains unfixed. Android's BLE stack has a small system-wide cap on concurrent GATT client registrations; repeated reconnect cycles (the exact "reconnect gap" scenario this phase's D-06 coverage represents) will progressively exhaust it.
**Fix:** Call `closeGatt()` before starting a new scan/connect cycle on both branches of the disconnect handler.

### WR-03: `discoverServices()` swallows exceptions with no failure feedback (carryforward from prior review's WR-02, unfixed)

**File:** `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt:223-227`
**Issue:** Still present exactly as previously reported:
```kotlin
private fun discoverServices(g: BluetoothGatt) {
    if (!hasBlePermission()) return
    runCatching { g.discoverServices() }
}
```
Unlike `startScanInternal()`, this discards both the permission-check-fails path and any exception from `g.discoverServices()` without ever transitioning the connection phase away from `Connecting`. If discovery never starts, `onServicesDiscovered` never fires, and the Settings UI (now that Plan 06-05 wires a live connection-status display) is left showing "Connecting" indefinitely with no way for the user to know the attempt failed.
**Fix:** Report `ExternalGnssConnectionPhase.Failed` on both the permission-gate-fails path and a caught exception.

### WR-04: `running`/parser/gatt state is mutated on the main thread but read from BLE callback threads without synchronization

**File:** `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProvider.kt:40-44, 64-77, 121-132`; `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt:78-98, 155-181`
**Issue:** `ExternalGnssLocationProvider.running`, `nmeaParser`, and `raceBoxParser` are plain (non-`@Volatile`, unsynchronized) `var`s. `start()`/`stop()`/`reset()` run on the Compose/main thread (invoked from `AppShell`/`MainActivity`), but `handleBytes()` — which reads `running` and dereferences `nmeaParser`/`raceBoxParser` — is invoked from `AndroidExternalGnssBleClient`'s BLE callback (`BluetoothGattCallback.onCharacteristicChanged`), which Android runs on a Binder/background thread, not the main thread. Symmetrically, `AndroidExternalGnssBleClient.running`, `gatt`, `onBytes`, and `onPhase` are written from `start()`/`stop()` (main thread) and read from `scanCallback`/`gattCallback` (BLE callback thread) with no `@Volatile`/synchronization either. Without a happens-before edge, a callback thread can observe a stale `running == true` (or a stale/replaced parser reference) after `stop()`/`reset()` has already run on the main thread, risking continued scan/connect activity or byte processing against state the caller believes has already been torn down. The `queue` field is the only piece of this class's state that is correctly guarded (`synchronized(queue)`).
**Fix:** Mark `running` (in both classes) `@Volatile`, and either guard `nmeaParser`/`raceBoxParser` swaps with the same lock used for `queue` or make them `@Volatile` as well, so writes from `start()`/`stop()`/`reset()` are visible to the BLE callback thread before it acts on new bytes.

### WR-05: `AndroidExternalGnssBleClient` has no automated test coverage (carryforward from prior review's WR-05, unfixed)

**File:** `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt`
**Issue:** `ExternalGnssLocationProviderTest.kt` only exercises `ExternalGnssLocationProvider` against a hand-written fake `ExternalGnssByteStreamClient`; the real `AndroidExternalGnssBleClient` implementation (scan/connect/reconnect/notification-subscription state machine) still has zero test coverage of any kind. WR-02 and WR-04 above are both bugs in code paths no test in this phase would catch.
**Fix:** Not blocking, but still worth tracking: extract the scan/connect/gatt-callback logic behind a seam that can be exercised with fakes (e.g. a thin `BluetoothAdapter`/`BluetoothGatt` wrapper), at least covering the reconnect-leak and discover-services-failure paths.

## Info

### IN-01: `ExternalGnssConnectionState.message` is never populated by production code, making the new error-detail UI branch dead

**File:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt:435-443`; `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssModels.kt:42-50`; `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssLocationProvider.kt:121-123`
**Issue:** Plan 06-05's `ExternalGnssSettingsCard` renders an extra detail line when the connection has failed:
```kotlin
state.message.takeIf { state.phase == ExternalGnssConnectionPhase.Failed }?.let { message -> ... }
```
but nothing in the production code path ever sets `ExternalGnssConnectionState.message` to a non-null value. `ExternalGnssLocationProvider.handlePhase()` only ever does `_connectionState.value.copy(phase = phase)`, and `AndroidExternalGnssBleClient`'s `reportPhase()` only ever carries a phase, never a message string. `message` therefore stays at its `null` default for the lifetime of the app, and this UI branch can never render anything — it's effectively dead code today. (Contrast with `GlassesConnectionState.Error.message`, which the same file does populate and display, at `SettingsScreen.kt:366-374`.) This isn't a functional break — the static `externalGnssConnectionFailed` label already gives the user actionable guidance — but it's a latent gap that could mask itself as "the message plumbing already works" to a future reader/implementer wiring in more specific failure reasons.
**Fix:** Either wire a real failure reason through (e.g. have `AndroidExternalGnssBleClient` report a short reason string alongside `Failed`, distinguishing "no permission" / "adapter off" / "scan failed" / "GATT error"), or remove the unreachable UI branch until that plumbing exists, so the code doesn't imply capability that isn't there.

### IN-02: Manifest comment overstates how narrowly the BLE scan is filtered (carryforward from prior review's IN-02, unfixed)

**File:** `androidApp/src/main/AndroidManifest.xml:15-18`; `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt:194-200`
**Issue:** Still present exactly as previously reported. The manifest comment for `BLUETOOTH_SCAN`/`neverForLocation` states "the scan only looks for a device by name/address," but `startScanInternal()` calls `scanner.startScan(emptyList(), ...)` — an unfiltered scan that returns every nearby BLE advertisement — with name/address matching happening only afterward in `scanCallback.onScanResult`. The `neverForLocation` accuracy claim itself still holds, but the comment inaccurately implies OS-level `ScanFilter`s are already in place.
**Fix:** Either add real `ScanFilter`s (by device name prefix / address) to `startScan()`, or correct the comment to say filtering happens in the app-level callback.

---

_Reviewed: 2026-07-08T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
