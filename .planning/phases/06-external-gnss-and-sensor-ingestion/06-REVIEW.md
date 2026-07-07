---
phase: 06-external-gnss-and-sensor-ingestion
reviewed: 2026-07-07T22:40:36Z
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
  critical: 1
  warning: 5
  info: 2
  total: 8
status: issues_found
---

# Phase 06: Code Review Report

**Reviewed:** 2026-07-07T22:40:36Z
**Depth:** standard
**Files Reviewed:** 20 (files_reviewed_list above lists 21 entries; `androidApp/build.gradle.kts` was reviewed as pure config with no logic findings)
**Status:** issues_found

## Summary

Phase 6 external GNSS ingestion (NMEA 0183 + RaceBox protocol preview) is well-tested at the parser/pipeline level — the replay-based test suite (`Nmea0183ParserTest`, `RaceBoxFrameParserTest`, `ExternalGnssTimingPipelineTest`, `ExternalGnssSettingsTest`) is thorough and the docs are honest about hardware-unvalidated status. However, direct code reading surfaced one confirmed data-loss bug in the RaceBox binary frame parser's fragmented-stream handling that directly contradicts the documented/tested "fragmented byte reads are handled" claim, plus several robustness gaps in the Android BLE client and NMEA parser that were not exercised by any test in this phase (the Android BLE client itself has zero test coverage — only the higher-level `ExternalGnssLocationProvider` is tested via a fake byte-stream double).

## Critical Issues

### CR-01: RaceBox frame parser drops a legitimate frame when its 2-byte sync sequence is split across two `accept()` calls

**File:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/RaceBoxFrameParser.kt:17-31`
**Issue:** `accept()` locates a frame boundary via `indexOfSync()`, which only matches when *both* sync bytes (`0xB5`, `0x62`) are present in the current buffer (`for (index in 0 until size - 1)` — the last byte alone is never checked). When `indexOfSync()` returns `-1` (no full 2-byte match anywhere in the buffer), the code treats the **entire buffer as garbage and discards it**:

```kotlin
syncIndex < 0 -> {
    if (streamBuffer.isNotEmpty()) {
        results += RaceBoxFrameParseResult.Rejected(..., rawFrame = streamBuffer)
    }
    streamBuffer = ByteArray(0)   // <-- wipes a legitimate trailing partial sync byte
    break
}
```

If a BLE notification chunk boundary happens to fall exactly between the two sync bytes (buffer ends with a lone `0xB5`, and `0x62` arrives in the *next* `accept()` call), the valid `0xB5` is discarded here. The next `accept()` call then receives a stream starting with `0x62` followed by the rest of the frame — `indexOfSync()` will never find `0xB5,0x62` in that data either, so the **entire real frame is silently lost** and mis-reported as two separate `MissingSync` rejections, even though the bytes were a well-formed frame that merely arrived in two chunks. This is exactly the "fragmented byte reads" scenario the docs (`docs/EXTERNAL-GNSS.md` §2.3) and `RaceBoxFrameParserTest.fragmentedInputMatchesWholeFrameInput` claim is handled — but that test only fragments at byte offsets that keep both sync bytes together (`copyOfRange(0, 3)` etc.), so it never exercises a split exactly between sync byte 1 and sync byte 2, and the bug goes undetected.
**Fix:** When no full sync match is found, only discard bytes that cannot possibly be the start of a future sync sequence — i.e. keep a trailing lone `0xB5` byte instead of wiping the whole buffer:
```kotlin
syncIndex < 0 -> {
    val keepTrailingByte = streamBuffer.isNotEmpty() &&
        streamBuffer.last().toUnsignedInt() == SYNC_1
    val garbageLength = if (keepTrailingByte) streamBuffer.size - 1 else streamBuffer.size
    if (garbageLength > 0) {
        results += RaceBoxFrameParseResult.Rejected(
            messageClass = null,
            messageId = null,
            reason = RaceBoxFrameRejectReason.MissingSync,
            rawFrame = streamBuffer.copyOfRange(0, garbageLength),
        )
    }
    streamBuffer = if (keepTrailingByte) streamBuffer.copyOfRange(garbageLength, streamBuffer.size) else ByteArray(0)
    break
}
```
Add a regression test that feeds a valid frame split exactly at the sync boundary (e.g. `burst.copyOfRange(0, 1)` then `burst.copyOfRange(1, burst.size)`) and asserts the frame still decodes.

## Warnings

### WR-01: `AndroidExternalGnssBleClient` leaks the previous `BluetoothGatt` on every automatic reconnect

**File:** `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt:107-118, 209-221, 253-260`
**Issue:** On an unexpected disconnect while `running` is still true, `onConnectionStateChange` reports `Reconnecting` and calls `startScanInternal()` again, but never calls `gatt?.close()` on the old, now-disconnected `BluetoothGatt` instance:
```kotlin
BluetoothProfile.STATE_DISCONNECTED -> {
    if (running) {
        reportPhase(ExternalGnssConnectionPhase.Reconnecting)
        startScanInternal()   // old `gatt` field is never closed
    } else { ... }
}
```
When the scan later matches and `connect(device)` runs, `gatt = device.connectGatt(...)` (line 216-220) silently overwrites the field, orphaning the previous `BluetoothGatt` object without ever calling `close()`. `closeGatt()` (which does call `disconnect()`/`close()`) is only invoked from `stop()`. Android's BLE stack has a small system-wide pool of concurrent GATT client registrations (commonly ~7 on stock AOSP); repeated reconnect cycles — the exact scenario this phase's "reconnect gap" coverage (D-06) is meant to represent — will progressively exhaust that pool and eventually make all BLE connections (including future attempts by this app) fail silently.
**Fix:** Close the stale `gatt` before starting a new scan/connect cycle:
```kotlin
BluetoothProfile.STATE_DISCONNECTED -> {
    if (running) {
        closeGatt() // close+null the old gatt before reconnecting
        reportPhase(ExternalGnssConnectionPhase.Reconnecting)
        startScanInternal()
    } else {
        closeGatt()
        reportPhase(ExternalGnssConnectionPhase.Disconnected)
    }
}
```

### WR-02: `discoverServices()` swallows exceptions with no failure feedback, leaving the connection state stuck at "Connecting"

**File:** `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt:223-227`
**Issue:**
```kotlin
private fun discoverServices(g: BluetoothGatt) {
    if (!hasBlePermission()) return
    runCatching { g.discoverServices() }
}
```
Unlike `startScanInternal()` (which does `.onFailure { reportPhase(Failed) }`), this method discards both the permission-check-fails path and any exception from `g.discoverServices()` without ever transitioning the connection phase away from `Connecting` (set immediately before this call in `onConnectionStateChange`). If discovery never starts, `onServicesDiscovered` never fires, and the Settings UI is left showing "Connecting" indefinitely with no way for the user to know the attempt failed.
**Fix:**
```kotlin
private fun discoverServices(g: BluetoothGatt) {
    if (!hasBlePermission()) {
        reportPhase(ExternalGnssConnectionPhase.Failed)
        return
    }
    runCatching { g.discoverServices() }.onFailure {
        reportPhase(ExternalGnssConnectionPhase.Failed)
    }
}
```

### WR-03: `Nmea0183Parser`'s stream buffer has no upper bound, allowing unbounded memory growth from an unterminated byte stream

**File:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt:5, 10-37`
**Issue:** `accept()` appends every incoming chunk to `streamBuffer` (a `StringBuilder`) and only trims it once a `\r`/`\n` line terminator is found. There is no cap on `streamBuffer.length`. `AndroidExternalGnssBleClient` auto-connects to any nearby BLE peripheral whose advertised name merely starts with `"RaceBox"` (case-insensitive, no pairing/bonding confirmation — see `deviceNamePrefix` default and `scanCallback.onScanResult`), so a nearby device that spoofs that name prefix and streams bytes that never contain `\r`/`\n` will make this buffer grow without bound for as long as the connection stays open, risking an OOM crash of the app. `RaceBoxFrameParser` has an (buggy, see CR-01) reset-on-garbage path that incidentally bounds its own buffer growth; `Nmea0183Parser` has no equivalent safeguard at all.
**Fix:** Cap `streamBuffer` length and drop/reset when a single "sentence" grows implausibly large (NMEA sentences are ≤82 characters per the standard):
```kotlin
if (streamBuffer.length > MAX_BUFFERED_CHARS) {
    streamBuffer.clear()
}
```
placed near the top of `accept(text: String)`, with `MAX_BUFFERED_CHARS` set generously above 82 (e.g. 4096) to tolerate several concatenated sentences without ever growing unbounded.

### WR-04: NMEA horizontal accuracy is populated directly from HDOP without unit conversion

**File:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/Nmea0183Parser.kt:241-247`
**Issue:** `snapshot()` builds `ExternalGnssFixQuality` with:
```kotlin
quality = ExternalGnssFixQuality(
    isValid = currentFix.isValid,
    fixType = ...,
    satellitesInUse = currentFix.satellitesInUse,
    hdop = currentFix.hdop,
    horizontalAccuracyMeters = currentFix.hdop,   // HDOP is dimensionless, not meters
),
```
HDOP (Horizontal Dilution of Precision) is a unitless multiplier typically in the range 0.5–20+, not a distance. Assigning it directly to `horizontalAccuracyMeters` produces a value that looks like a real accuracy figure (and is forwarded verbatim into `LocationSample.horizontalAccuracyMeters` via `toLocationSample()`) but is not actually meters — e.g. a healthy `hdop = 0.9` will be reported as "0.9 m accuracy," which is misleadingly precise and has no defined relationship to true positional error. `quality.hdop` already carries the raw HDOP value separately, so this field is redundant and wrong.
**Fix:** Either leave `horizontalAccuracyMeters` `null` for NMEA fixes (no NMEA sentence parsed here carries a real accuracy-in-meters field) or apply a documented estimation formula (e.g. `hdop * UERE_METERS`) with a comment explaining the approximation, rather than a raw unit-mismatched assignment.

### WR-05: `AndroidExternalGnssBleClient` has no automated test coverage

**File:** `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt`
**Issue:** `ExternalGnssLocationProviderTest.kt` only exercises `ExternalGnssLocationProvider` against a hand-written fake `ExternalGnssByteStreamClient`; the real `AndroidExternalGnssBleClient` implementation (scan/connect/reconnect/notification-subscription state machine) has zero test coverage of any kind (no instrumented test using a mock BLE stack either). CR-01/WR-01/WR-02 above are all bugs in code paths that no test in this phase would ever catch. Given the docs already flag this class as hardware-unvalidated, that's an accepted risk for real-hardware behavior, but the *state-machine logic itself* (permission gating, phase transitions, gatt lifecycle) is deterministic and could be unit-tested behind a thin `BluetoothAdapter`/`BluetoothGatt` seam without any real device.
**Fix:** Not blocking for this phase's stated scope, but worth tracking as follow-up: extract the scan/connect/gatt-callback logic behind an interface that can be exercised with fakes, at least covering the reconnect-leak and discover-services-failure paths flagged above.

## Info

### IN-01: Settings "source note" priority can show a Phone-GPS-unavailable message while External GNSS is actually selected and active

**File:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt:204-211`
**Issue:**
```kotlin
val sourceNote = when {
    locationFeedLocked -> s.locationLockedWhileTiming
    !phoneGpsAvailable -> s.phoneGpsUnavailable
    settings.locationFeedMode == LocationFeedMode.PhoneGps && !phoneGpsPermissionGranted -> s.phoneGpsPermissionRequired
    effectiveLocationFeedMode == LocationFeedMode.ExternalGnss -> s.externalGnssUnvalidatedNote
    else -> null
}
```
The `!phoneGpsAvailable` branch is checked unconditionally before the `ExternalGnss` branch, so on a platform where Phone GPS is unavailable but External GNSS is available and actively selected, the user would see "Phone GPS is not wired on this platform yet" instead of the intended `externalGnssUnvalidatedNote`. On Android today `phoneGpsAvailable` is always true (a `phoneGpsProvider` is always constructed in `MainActivity`), so this is currently unreachable in production, but it is a real ordering bug that will surface the moment a platform ships External GNSS without Phone GPS.
**Fix:** Reorder so mode-specific notes take priority over generic platform-unavailability notes, or scope the `!phoneGpsAvailable` check to only apply when the effective/selected mode is actually `PhoneGps`.

### IN-02: Manifest comment overstates how narrowly the BLE scan is filtered

**File:** `androidApp/src/main/AndroidManifest.xml:15-18`, `androidApp/src/main/kotlin/com/huanfuli/lapsight/ExternalGnssBleClient.kt:194-200`
**Issue:** The manifest comment for `BLUETOOTH_SCAN`/`neverForLocation` states "the scan only looks for a device by name/address," implying an OS-level filtered scan. In practice `startScanInternal()` calls `scanner.startScan(emptyList(), ...)` — an empty filter list, i.e. an unfiltered scan that returns *every* nearby BLE advertisement — and the name/address matching happens only afterward in `scanCallback.onScanResult`. The `neverForLocation` accuracy claim itself still holds (filtering-by-name-in-callback doesn't use location either way), but the comment inaccurately describes the scan as filtered at the API level, which could mislead a future reader into thinking OS-level ScanFilters are already in place.
**Fix:** Either add actual `ScanFilter`s (by device name prefix / address) to `startScan()` for tighter, more battery-friendly scanning, or correct the comment to say filtering happens in the app-level callback rather than via the scan API.

---

_Reviewed: 2026-07-07T22:40:36Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
