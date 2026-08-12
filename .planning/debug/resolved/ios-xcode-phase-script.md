---
status: resolved
trigger: "Xcode cannot build the iOS app: Command PhaseScriptExecution failed with a nonzero exit code; target is named iosApp"
created: 2026-07-09T00:00:00-04:00
updated: 2026-07-09T00:31:05-04:00
---

# iOS Xcode PhaseScriptExecution failure

## Symptoms

- Expected: Xcode builds and runs LapSight on iOS for usability testing.
- Actual: the build stops in a shell-script phase.
- Error: `Command PhaseScriptExecution failed with a nonzero exit code`.
- Timeline: reported after continuing development on a Mac; prior successful iOS runtime build is unknown.
- Reproduction: build the `iosApp` scheme/target from Xcode.

## Current Focus

- hypothesis: Confirmed and fixed.
- test: Full signed Xcode device build, install, launch, iOS tests, and dual-architecture Kotlin compilation.
- expecting: `BUILD SUCCEEDED`, running process on the connected iPhone XR, and real Core Location available through the shared provider boundary.
- next_action: Product owner performs outdoor iPhone XR GPS usability steps.
- reasoning_checkpoint:
- tdd_checkpoint:

## Evidence

- `gradlew` was mode `100644`; Xcode's script failed at line 7 with `./gradlew: Permission denied`.
- After restoring executable mode, Kotlin/Native reported unresolved Foundation extension members in the iOS source set.
- Explicit Foundation extension imports made both `iosArm64` and `iosSimulatorArm64` compile.
- Xcode simulator build succeeded before and after renaming the native target/scheme to `LapSight`.
- The iOS entry point had no Core Location provider and therefore could only expose the simulated feed.
- `:shared:iosSimulatorArm64Test` passed after Core Location integration.
- Signed device build succeeded with the user's Apple Development identity and provisioning profile.
- Final app installed and launched on connected iPhone XR (iOS 18.7.9); PID 2072 was observed running.

## Eliminated

- The old `iosApp` target name was not the cause of the script failure.
- Xcode signing was not the cause; signed device build succeeded once the script and Kotlin sources compiled.

## Resolution

- root_cause: The Gradle wrapper lacked its executable bit. Once that blocker was removed, three Kotlin 2.4 Foundation extension imports were also missing. Separately, iOS had no Core Location provider, which blocked meaningful real-device GPS UAT.
- fix: Restored the wrapper executable bit, added explicit Foundation imports, renamed the Xcode target/scheme to `LapSight`, and injected an iOS Core Location implementation of `LocationSampleProvider` with live permission state.
- verification: iOS arm64/simulator compilation passed, iOS tests passed, Xcode signed device build reported `BUILD SUCCEEDED`, and LapSight installed/launched on the connected iPhone XR.
- files_changed: gradlew; iosApp/iosApp.xcodeproj/project.pbxproj; LocalTimeZone.ios.kt; SystemLanguage.ios.kt; IosCoreLocationSampleProvider.kt; MainViewController.kt
