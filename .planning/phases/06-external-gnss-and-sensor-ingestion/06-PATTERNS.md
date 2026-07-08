# Phase 6 (Gap Closure): External GNSS Connection-State UI — Pattern Map

**Mapped:** 2026-07-07
**Scope:** Close the single VERIFICATION.md gap — thread
`ExternalGnssLocationProvider.connectionState` (Android,
`StateFlow<ExternalGnssConnectionState>`) into Compose UI, mirroring the
existing `GlassesConnectionState`/`GlassesSettingsCard` wiring in the same
files.
**Files analyzed:** 5 (4 modified, 1 new-strings-in-existing-file)
**Analogs found:** 5 / 5 (all exact — the glasses connection-state feature
already solves this exact problem end-to-end, in the same files)

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `androidApp/src/main/kotlin/com/huanfuli/lapsight/MainActivity.kt` | provider/composition-root (Android entrypoint) | event-driven (StateFlow passthrough) | same file, `glassesConnectionState` field + `installGlassesBridge` collection (lines 73, 304-330) | exact |
| `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/App.kt` | root composable / prop threading | request-response (Compose param passthrough) | same file, `glassesConnectionState` param (lines 59-60, 97) | exact |
| `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/AppShell.kt` | shell composable / prop threading | request-response (Compose param passthrough) | same file, `glassesConnectionState` param (lines 98-99, 372, 391) | exact |
| `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/SettingsScreen.kt` | component (Settings card) | streaming (StateFlow → `collectAsState`) | `GlassesSettingsCard` in same file (lines 259-264, 307-388) | exact |
| `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/ui/Localization.kt` | config (i18n string table) | CRUD (string constants, 6 locales) | existing `glassesIdle`/`glassesConnecting`/`glassesConnected`/`glassesReconnecting` + `externalGnss`/`externalGnssUnvalidatedNote` entries (lines 61-62, 72, 86-88, 341-368 for `en`, repeated per-locale) | exact |

No files are role/flow mismatches — this gap is a pure "copy the existing
glasses wiring, swap the type" task. No new architectural pattern is needed.

---

## Pattern Assignments

### `androidApp/.../MainActivity.kt` (composition root, event-driven passthrough)

**Analog:** same file — glasses connection-state field + forwarding (lines 70-78, 226-232, 258, 304-330)

**Key structural difference to note for the planner:** glasses needs a local
*forwarding* `MutableStateFlow` (`glassesConnectionState`) because
`GlassesBridge` is constructed **later**, asynchronously, inside
`installGlassesBridge(controller)` (only once `onSessionControllerReady`
fires). External GNSS does **not** have this problem:
`externalGnssProvider` (and therefore its `.connectionState` StateFlow) is
already constructed synchronously in `onCreate` at line 226-232, *before*
`setContent { App(...) }` runs. So no forwarding field/collect job is
needed — `externalGnssProvider!!.connectionState` can be passed directly
into `App(...)` as a constructor argument.

**Existing field/property pattern to mirror (glasses, for reference only — do NOT replicate the forwarding step for external GNSS):**
```kotlin
// lines 70-78
private var glassesBridge: GlassesBridge? = null
private val glassesScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private var glassesBridgeCollectionJobs: List<Job> = emptyList()
private val glassesConnectionState = MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Idle)
```

**Existing construction site to copy the "already have a live provider, pass its own StateFlow through" idea from — external GNSS provider construction itself (lines 222-232):**
```kotlin
// External GNSS (Phase 6, protocol preview, D-01/D-02): NMEA 0183 over a
// BLE UART-style notify characteristic. Injectable byte-stream client
// seam so this is testable without hardware; the BLE transport itself
// remains explicitly unvalidated until a real receiver confirms it.
externalGnssProvider = ExternalGnssLocationProvider(
    client = AndroidExternalGnssBleClient(
        context = this,
        hasBlePermission = { AndroidExternalGnssBleClient.hasBlePermission(this) },
    ),
    protocol = ExternalGnssProtocol.Nmea0183,
)
```

**`setContent { App(...) }` call site to extend (lines 234-274) — add one new argument alongside the existing `externalGnssProvider = externalGnssProvider` line and the glasses equivalents:**
```kotlin
setContent {
    App(
        ...
        externalGnssProvider = externalGnssProvider,
        ...
        glassesConnectionState = glassesConnectionState,
        glassesDevices = glassesDevices,
        glassesSelectedDeviceId = selectedGlassesDeviceId,
        ...
    )
}
```
Add: `externalGnssConnectionState = externalGnssProvider!!.connectionState,`
(or a null-safe `?:` default if `externalGnssProvider` can legitimately be
null on this platform — it currently is not, per line 226, but keep the
same nullability discipline `phoneGpsProvider`/`externalGnssProvider`
already use elsewhere).

**Import to add (mirrors `com.huanfuli.lapsight.shared.glasses.GlassesConnectionState` import at line 35):**
```kotlin
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
```
(`ExternalGnssProtocol` is already imported at line 33; `ExternalGnssConnectionState` lives in the same file, `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/external/ExternalGnssModels.kt`.)

---

### `shared/.../App.kt` (root composable, prop threading)

**Analog:** same file, `glassesConnectionState` parameter (lines 59-60) and passthrough (line 97)

**Imports pattern already present (lines 13-26)** — add `ExternalGnssConnectionState`/`ExternalGnssConnectionPhase` alongside the existing glasses imports:
```kotlin
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
```
becomes (add, do not remove):
```kotlin
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
```

**Parameter pattern to copy exactly (lines 53-54 existing `externalGnssProvider` param, and lines 59-60 the glasses StateFlow-with-default idiom):**
```kotlin
phoneGpsProvider: LocationSampleProvider? = null,
externalGnssProvider: LocationSampleProvider? = null,
...
glassesConnectionState: StateFlow<GlassesConnectionState> =
    MutableStateFlow(GlassesConnectionState.Idle),
```
New parameter to add, same idiom, default built from the enum default
(`ExternalGnssConnectionPhase.Disconnected`) exactly as
`ExternalGnssLocationProvider` itself does at construction (see
`ExternalGnssLocationProvider.kt:46-48`):
```kotlin
externalGnssConnectionState: StateFlow<ExternalGnssConnectionState> =
    MutableStateFlow(ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected)),
```

**Passthrough into `AppShell(...)` (line 92 `externalGnssProvider = externalGnssProvider,` and line 97 `glassesConnectionState = glassesConnectionState,`)** — add one line in the same block:
```kotlin
AppShell(
    ...
    externalGnssProvider = externalGnssProvider,
    ...
    glassesConnectionState = glassesConnectionState,
    ...
)
```
→ add `externalGnssConnectionState = externalGnssConnectionState,` next to `externalGnssProvider = externalGnssProvider,`.

---

### `shared/.../ui/AppShell.kt` (shell composable, prop threading)

**Analog:** same file, `glassesConnectionState` parameter (lines 98-99) and both consumption sites (line 372 `DriveScreen`, line 391 `SettingsScreen`)

**Parameter block to extend (lines 92-99):**
```kotlin
phoneGpsProvider: LocationSampleProvider? = null,
externalGnssProvider: LocationSampleProvider? = null,
phoneGpsPermission: PhoneGpsPermissionState = PhoneGpsPermissionState(),
sessionStore: LocalSessionStore = InMemorySessionStore(),
exportShareTarget: ExportShareTarget = NoOpExportShareTarget,
onSessionControllerReady: (SessionController) -> Unit = {},
glassesConnectionState: StateFlow<GlassesConnectionState> =
    MutableStateFlow(GlassesConnectionState.Idle),
```
→ add `externalGnssConnectionState: StateFlow<ExternalGnssConnectionState> = MutableStateFlow(ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected)),` next to `externalGnssProvider`.

**Consumption site — SettingsScreen is the ONLY place this needs to be passed** (line 385-399, this is the actual gap-closing call site):
```kotlin
AppTab.Settings -> SettingsScreen(
    settings = displaySettings,
    phoneGpsAvailable = phoneGpsProvider != null && phoneGpsPermission.isSupported,
    phoneGpsPermissionGranted = phoneGpsPermission.isGranted,
    externalGnssAvailable = externalGnssProvider != null,
    locationFeedLocked = driveTimingActive,
    glassesConnectionState = glassesConnectionState,
    glassesDevices = glassesDevices,
    glassesSelectedDeviceId = glassesSelectedDeviceId,
    glassesActions = glassesActions,
    onRequestPhoneGps = { ... },
    onSettingsChanged = onDisplaySettingsChanged,
)
```
→ add `externalGnssConnectionState = externalGnssConnectionState,` next to `externalGnssAvailable = externalGnssProvider != null,`.

Note: unlike glasses, `DriveScreen` (line 372) does **not** need this new
param — D-08 explicitly keeps detailed connection diagnostics out of the
Drive surface; only Settings gets the live indicator. Drive's existing
generic `gpsQualityWarning`/accuracy indicators (already source-agnostic)
are untouched.

**Import to add (mirrors line 41 `GlassesConnectionState` import):**
```kotlin
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
```

---

### `shared/.../ui/SettingsScreen.kt` (component — this is where the actual visible fix happens)

**Analog:** `GlassesSettingsCard` (lines 307-388) + its call site (lines 259-264) + its label-mapping helper (lines 458-464), all in this same file.

**Imports to add (mirrors lines 38-41):**
```kotlin
import com.huanfuli.lapsight.shared.glasses.GlassesActions
import com.huanfuli.lapsight.shared.glasses.GlassesConnectionState
import com.huanfuli.lapsight.shared.glasses.GlassesDeviceSummary
import com.huanfuli.lapsight.shared.glasses.NoOpGlassesActions
```
→ add:
```kotlin
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
```

**Function signature to extend (lines 108-123) — add a StateFlow param with the same default-construction idiom used for `glassesConnectionState` (lines 115-116):**
```kotlin
@Composable
internal fun SettingsScreen(
    settings: DriveDisplaySettings,
    phoneGpsAvailable: Boolean,
    phoneGpsPermissionGranted: Boolean,
    externalGnssAvailable: Boolean = false,
    locationFeedLocked: Boolean,
    glassesConnectionState: StateFlow<GlassesConnectionState> =
        MutableStateFlow(GlassesConnectionState.Idle),
    ...
)
```
→ add `externalGnssConnectionState: StateFlow<ExternalGnssConnectionState> = MutableStateFlow(ExternalGnssConnectionState(phase = ExternalGnssConnectionPhase.Disconnected)),`.

**Core pattern — the existing static-note block to replace/augment (lines 204-218), currently the ONLY thing a user sees for External GNSS:**
```kotlin
val sourceNote = when {
    locationFeedLocked -> s.locationLockedWhileTiming
    !phoneGpsAvailable -> s.phoneGpsUnavailable
    settings.locationFeedMode == LocationFeedMode.PhoneGps && !phoneGpsPermissionGranted ->
        s.phoneGpsPermissionRequired
    effectiveLocationFeedMode == LocationFeedMode.ExternalGnss -> s.externalGnssUnvalidatedNote
    else -> null
}
sourceNote?.let {
    Text(
        text = it,
        color = LapSightTheme.colors.statusCaution,
        style = MaterialTheme.typography.bodySmall,
    )
}
```
This static branch is the direct root cause named in VERIFICATION.md — it
never reads `externalGnssConnectionState`. Two viable approaches (planner's
discretion, both follow existing precedent in this exact file):
1. **Minimal fix, inline in the `LapCard(title = s.locationSource)` block**: keep the static disclaimer note AND append a live phase line sourced from `externalGnssConnectionState.collectAsState()` only when `effectiveLocationFeedMode == LocationFeedMode.ExternalGnss` — cheapest change, smallest diff.
2. **Full-card fix mirroring `GlassesSettingsCard` exactly** (recommended, since 06-UI-SPEC.md already specifies copy for a real connect/error/empty state card): add a new `ExternalGnssSettingsCard` composable, called right after `GlassesSettingsCard` (line 264) the same way `GlassesSettingsCard` is called after the language card — same file, same section ordering convention (each hardware/connectivity concern gets its own `LapCard`-wrapped composable).

**`GlassesSettingsCard` call site to copy the calling convention from (lines 259-264):**
```kotlin
GlassesSettingsCard(
    connectionState = glassesConnectionState,
    devices = glassesDevices,
    selectedDeviceId = glassesSelectedDeviceId,
    actions = glassesActions,
)
```

**`GlassesSettingsCard` composable body to copy the structure from (lines 307-349, trimmed to the state-driven parts) — this is the direct template for a new `ExternalGnssSettingsCard`:**
```kotlin
@Composable
private fun GlassesSettingsCard(
    connectionState: StateFlow<GlassesConnectionState>,
    devices: StateFlow<List<GlassesDeviceSummary>>,
    selectedDeviceId: StateFlow<String?>,
    actions: GlassesActions,
) {
    val state by connectionState.collectAsState()
    val deviceList by devices.collectAsState()
    val selectedId by selectedDeviceId.collectAsState()
    val spacing = LapSightTheme.spacing
    val s = strings
    val firmwareUpdateRequired = deviceList.any { it.requiresFirmwareUpdate }
    val appUpdateRequired = (state as? GlassesConnectionState.Error)?.datAppUpdateRequired == true

    LapCard(title = s.glasses) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.glassesConnectionLabel(state),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                (state as? GlassesConnectionState.Error)?.message?.let { message ->
                    Text(
                        text = message,
                        color = LapSightTheme.colors.statusCaution,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LapButton(
                text = s.registerPair,
                onClick = actions::register,
                style = LapButtonStyle.Secondary,
            )
        }
        ...
    }
}
```
For `ExternalGnssConnectionState`, the model is an enum-backed data class
(`phase: ExternalGnssConnectionPhase`, `message: String?`), not a sealed
interface — the `when` mapping is on `.phase`, and the `Error`-style caption
should read `state.message` directly (no `as?` cast needed):
```kotlin
val state by externalGnssConnectionState.collectAsState()
Text(text = s.externalGnssConnectionLabel(state.phase), ...)
state.message?.takeIf { state.phase == ExternalGnssConnectionPhase.Failed }?.let { message ->
    Text(text = message, color = LapSightTheme.colors.statusCaution, ...)
}
```

**Label-mapping helper to copy the shape of (lines 458-464) — write an analogous `externalGnssConnectionLabel` extension:**
```kotlin
private fun LocalizedStrings.glassesConnectionLabel(state: GlassesConnectionState): String = when (state) {
    GlassesConnectionState.Idle -> glassesIdle
    GlassesConnectionState.Connecting -> glassesConnecting
    GlassesConnectionState.Connected -> glassesConnected
    is GlassesConnectionState.Reconnecting -> glassesReconnecting
    is GlassesConnectionState.Error -> state.message
}
```
New analog (six-way `ExternalGnssConnectionPhase` enum from
`ExternalGnssModels.kt:33-40`: `Disconnected, Scanning, Connecting,
Connected, Reconnecting, Failed`):
```kotlin
private fun LocalizedStrings.externalGnssConnectionLabel(
    phase: ExternalGnssConnectionPhase,
): String = when (phase) {
    ExternalGnssConnectionPhase.Disconnected -> externalGnssDisconnected
    ExternalGnssConnectionPhase.Scanning -> externalGnssScanning
    ExternalGnssConnectionPhase.Connecting -> externalGnssConnecting
    ExternalGnssConnectionPhase.Connected -> externalGnssConnected
    ExternalGnssConnectionPhase.Reconnecting -> externalGnssReconnecting
    ExternalGnssConnectionPhase.Failed -> externalGnssConnectionFailed
}
```

**Validation pattern:** none beyond the existing `sourceNote`/enabled-state
`when` blocks already shown above — this file has no schema validation, only
UI-state derivation functions (`locationSourceOptions`,
`resolveEffectiveLocationFeedMode`), both already pure and unit-tested (see
Testing pattern below).

---

### `shared/.../ui/Localization.kt` (config — i18n string table, 6 locales)

**Analog:** existing glasses status strings (`glassesIdle`, `glassesConnecting`, `glassesConnected`, `glassesReconnecting`) declared in the `LocalizedStrings` interface (lines 72, 86-88) and defined per-locale (6 blocks: `en` 366-368/352, `zh` 518-520/504, `ko` 670-672/656, `ja` 822-824/808, `fr` 974-976/960, `es` 1126-1128/1112).

**Interface entries to add (mirrors lines 61-62, 86-88):**
```kotlin
val externalGnss: String,
val externalGnssUnvalidatedNote: String,
```
→ add after `externalGnssUnvalidatedNote` (line 62):
```kotlin
val externalGnssDisconnected: String,
val externalGnssScanning: String,
val externalGnssConnecting: String,
val externalGnssConnected: String,
val externalGnssReconnecting: String,
val externalGnssConnectionFailed: String,
```

**Per-locale value pattern (English, lines 341-342 + 366-368, to copy the tone/format from):**
```kotlin
externalGnss = "External GNSS",
externalGnssUnvalidatedNote = "Protocol preview (NMEA 0183) — connection behavior is not validated against real receiver hardware yet.",
...
glassesIdle = "Not connected",
glassesConnecting = "Connecting",
glassesConnected = "Connected",
```
New English values must reuse the exact 06-UI-SPEC.md copywriting contract
verbatim where it applies (Error state copy is locked, others follow the
established "Not connected"/"Connecting"/"Connected" tone):
```kotlin
externalGnssDisconnected = "Not connected",
externalGnssScanning = "Scanning…",
externalGnssConnecting = "Connecting",
externalGnssConnected = "Connected",
externalGnssReconnecting = "Reconnecting…",
externalGnssConnectionFailed = "Connection Failed. Ensure the receiver is powered on and within range.",
```
This must be repeated for all 6 locale blocks (`en`, `zh`, `ja`, `ko`, `fr`,
`es`) — each locale's block is a flat `val`-assignment list in the same
declaration order as the interface, so grep each `glassesIdle =` occurrence
to find the correct insertion point per locale (6 occurrences total, listed
above under Analog).

---

## Shared Patterns

### StateFlow-through-Compose-tree threading (the core pattern for this whole gap)
**Source:** `glassesConnectionState` param chain: `MainActivity.kt:73/258` → `App.kt:59-60/97` → `AppShell.kt:98-99/391` → `SettingsScreen.kt:115-116/259-260`
**Apply to:** `MainActivity.kt`, `App.kt`, `AppShell.kt`, `SettingsScreen.kt` (all 4 files above)
```kotlin
// Declaration idiom, repeated identically at every layer:
someConnectionState: StateFlow<SomeConnectionState> =
    MutableStateFlow(SomeConnectionState.DefaultOrIdleValue),
// Consumption idiom, only at the leaf Composable that renders it:
val state by someConnectionState.collectAsState()
```
Every intermediate layer (`App`, `AppShell`) is pure passthrough — it never
calls `.collectAsState()` itself, only the final `SettingsScreen`/
`GlassesSettingsCard`-equivalent leaf does. Do not add `.collectAsState()`
calls to `App.kt`/`AppShell.kt`.

### Enum-phase vs. sealed-interface state shape difference
**Source:** `GlassesConnectionState` (`shared/.../glasses/GlassesConnectionState.kt`) vs. `ExternalGnssConnectionState`/`ExternalGnssConnectionPhase` (`shared/.../external/ExternalGnssModels.kt:33-50`)
**Apply to:** `SettingsScreen.kt` label-mapping helper and error-message rendering
Glasses uses a `sealed interface` with per-case payload (`Reconnecting(reason)`, `Error(message, datAppUpdateRequired)`), so its `when` is exhaustive over types and casts (`state as? GlassesConnectionState.Error`) are needed to reach payload fields. External GNSS instead uses a flat `data class ExternalGnssConnectionState(phase: ExternalGnssConnectionPhase, message: String?, ...)` — the `when` must switch on `.phase` (an enum), and `.message` is read directly with no cast, gated by `phase == Failed` (or shown whenever non-null, planner's choice — `message` is already nullable/absent in most phases per the constructor default).

### Localization per-locale insertion discipline
**Source:** `Localization.kt` interface block (lines 51-100ish) + 6 flat per-locale `val =` blocks
**Apply to:** `Localization.kt` only
New interface fields must be added once; new value assignments must be
duplicated across all 6 locale objects (`en`/`zh`/`ja`/`ko`/`fr`/`es`) in the
same relative order as the interface declares them, or the Kotlin compiler
will fail with "missing parameter" for whichever locale block is
incomplete — this is a strict, compiler-enforced pattern already relied on
by every existing string in this file.

### Testing pattern for pure UI-state derivation
**Source:** `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/ui/ExternalGnssSettingsTest.kt` (existing, 6 tests covering `locationSourceOptions`/`resolveEffectiveLocationFeedMode`)
**Apply to:** any new pure function extracted from `SettingsScreen.kt` (e.g. `externalGnssConnectionLabel`, or a new `internal fun` deriving whether to show the error caption)
```kotlin
class ExternalGnssSettingsTest {
    @Test
    fun externalGnssOptionAbsentWhenPlatformHasNoProvider() {
        val options = locationSourceOptions(
            phoneGpsAvailable = true,
            externalGnssAvailable = false,
            locationFeedLocked = false,
        )
        assertEquals(
            listOf(LocationFeedMode.PhoneGps, LocationFeedMode.Simulated),
            options.map { it.mode },
        )
    }
    // ... etc, one behavior per test, no Compose runtime needed.
}
```
This existing test file is also the natural home for new tests of any pure
`internal fun` the planner extracts for the connection-phase label mapping
(mark the mapping helper `internal` instead of `private` if it needs direct
unit-test coverage, matching how `locationSourceOptions`/
`resolveEffectiveLocationFeedMode` are already `internal fun` for
testability — see `SettingsScreen.kt:73` and `:91`).

---

## No Analog Found

None — every file in scope for this gap already has an exact, in-repo
analog because the glasses feature solved the identical
"Android-constructed StateFlow → Compose Settings card" problem one phase
earlier in the exact same files.

---

## Metadata

**Analog search scope:** `shared/src/commonMain/kotlin/com/huanfuli/lapsight/shared/` (App.kt, ui/AppShell.kt, ui/SettingsScreen.kt, ui/Localization.kt, glasses/GlassesConnectionState.kt, external/ExternalGnssModels.kt), `androidApp/src/main/kotlin/com/huanfuli/lapsight/` (MainActivity.kt, ExternalGnssLocationProvider.kt, ExternalGnssBleClient.kt), `shared/src/commonTest/kotlin/com/huanfuli/lapsight/shared/ui/` (ExternalGnssSettingsTest.kt)
**Files scanned:** 9 read directly, 2 grepped for wiring confirmation
**Pattern extraction date:** 2026-07-07
