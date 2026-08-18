<div align="center">

[English](README.md) | [中文](Documents/README_zh.md)

<br/>

<img src="Assets/LapSight_Banner.png" alt="LapSight" width="100%" />

<br/>

[![Status: Phase 5.1](https://img.shields.io/badge/Status-Phase_5.1_Validation-4285F4?style=for-the-badge)](.planning/STATE.md)
[![Platform: Android | iOS](https://img.shields.io/badge/Platform-Android_%7C_iOS-6C63FF?style=for-the-badge)](#)
[![Tech: Kotlin Multiplatform](https://img.shields.io/badge/Tech-Kotlin_Multiplatform-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg?style=for-the-badge)](LICENSE)

<br/>

### **LapSight is a phone-first lap timing and ghost-delta app for karting, track driving, and cycling.**

*The phone companion app is the source of truth for GPS samples, track profiles,*
*timing state, saved sessions, review data, and future Meta glasses HUD output.*
*It is being built as a mounted-phone timing instrument for closed-course and private-track use.*

</div>

---

## 🏎️ The Vision

LapSight is not a generic fitness tracker. It is a precise **mounted-phone timing instrument**. 
We're building a clean-room shared lap engine that handles real-world telemetry with deterministic replay capabilities.

---

## 🏎️ Current Status

The app has completed the Phase 5 course-profile work and is in the Phase 5.1 field-validation / hardening gate. The Android app is runnable, supports a switchable Phone GPS / Simulated feed, and has been build-validated with Android Fused Location Provider wired into the existing shared provider interface. The iOS app can also receive live NMEA data from a Cardputer ADV over Bluetooth LE.

Latest local Android builds have also been installed and launched on an ADB-connected Pixel 10 Pro. Remaining MVP gate work is real-world field evidence: outdoor Phone GPS validation, mounted-display UAT, and the final Go / Hardening Required / No-Go decision.

### ✨ Implemented
- **KMP Foundation**: Kotlin Multiplatform shared domain logic and Compose Multiplatform UI shared by Android and iOS.
- **Lap Engine**: Clean-room engine with start/finish crossing, sector timing, lap filters, and deterministic replay tests.
- **Local-first Storage**: Captures, V2 course profiles, timing sessions, ghost references, and exports.
- **Track Setup**: Closed reference path, start/finish boundary, sectors, profile revisions, duplicate/rename/archive, and wrong-course preflight.
- **Mounted-phone Drive UI**: Portrait and landscape timing surfaces, live lap state, speed trace, GPS diagnostics, recovery for unfinished sessions, a course-first pre-timing screen, and a two-pane landscape cockpit.
- **Ghost Laps**: Ghost lap and delta-to-best support gated by exact course compatibility.
- **Review UI**: Grouped by sessions, tracks, and raw captures, with push-navigation detail screens, lap x sector tables, telemetry chart/replay, trace rendering, and JSON/GPX export.
- **Course Rendering**: Track detail and editor rendering that use one beautified course map. Aspect-correct, zoom-to-fit course rendering for Drive, Review, and editor canvases.
- **Theming**: System, Dark, and Light modes, plus unit and display preferences, backed by a shared semantic theme/component layer.
- **Android GPS Integration**: Android Fused Location Provider feed mapped into the same shared `LocationSampleProvider` and `LocationSample` model used by the simulator. Runtime Android fine-location permission request path.
- **Cardputer External GNSS**: Cardputer ADV + Cap LoRa-1262/ATGM336H firmware forwards raw GNSS NMEA over BLE, and the iOS app discovers, reconnects, parses, and identifies that receiver through the existing external-GNSS provider boundary.

### 🚧 Coming Soon
- Live iOS Core Location feed.
- Field-tested GPS smoothing and telemetry quality tuning.
- Meta glasses HUD bridge.

---

## 🏎️ Real GPS Status

Android now injects an `AndroidFusedLocationSampleProvider` into the same shared `LocationSampleProvider` boundary as the deterministic simulator. The Drive and Settings UI can switch between:

- 🛰️ **PhoneGps**: Android Fused Location Provider fixes, requiring precise location permission.
- 🧪 **Simulated**: Deterministic replay fixtures for regression testing, demos, and offline UAT.

Both feeds produce the same shared `LocationSample` values and are persisted with explicit `LocationSource` provenance, so Review can distinguish real phone GPS sessions from simulated data.

### Cardputer ADV external GNSS

The preferred Cardputer firmware is a clean extension of M5Stack's factory-style Cardputer ADV UserDemo. It preserves the stock launcher and bundled applications, then adds `LapSight` as app 14. Opening it starts only the ATGM336H GNSS receiver and advertises `LapSight-Cardputer` over BLE Nordic UART; LoRa remains disabled in this app.

- Firmware, build, flash, and recovery instructions: [`firmware/cardputer-adv-userdemo-lapsight/README.md`](firmware/cardputer-adv-userdemo-lapsight/README.md)
- Initial standalone hardware bring-up firmware: [`firmware/cardputer-adv-gnss/README.md`](firmware/cardputer-adv-gnss/README.md)

**Remaining real-GPS work:**
- Validate Android Phone GPS outdoors on a closed course with the current Ready thresholds: 25 m horizontal accuracy, 15 s fix freshness, and 0.9 Hz minimum sample rate.
- Tune smoothing and telemetry quality thresholds with recorded data.
- Implement the iOS Core Location provider against the same shared interface.
- Preserve replay-based tests so algorithmic behavior remains verifiable.

---

## 🏎️ Architecture & Project Structure

<div align="center">

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-4285F4?style=flat-square&logo=android&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-000000?style=flat-square&logo=apple&logoColor=white)

</div>

```text
LapSight/
├─ androidApp/   Android application package, manifest, and activity entry point
├─ firmware/     Cardputer ADV external-GNSS firmware
├─ iosApp/       Xcode project and SwiftUI entry point
├─ shared/       Shared KMP domain logic, Compose UI, storage, and tests
└─ .planning/    Project context, requirements, roadmap, phase plans, and state
```

---

## 🏎️ Build And Test

### Android Debug Build

```powershell
.\gradlew.bat :androidApp:assembleDebug
```

Install on a connected Android device:

```powershell
.\gradlew.bat :androidApp:installDebug
```

### Shared Tests

```powershell
.\gradlew.bat :shared:testAndroidHostTest
```

Full local verification used for the latest hardening pass:

```powershell
.\gradlew.bat :shared:testAndroidHostTest :androidApp:assembleDebug
```

Launch after install, if `adb` is not on `PATH`:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell monkey -p com.huanfuli.lapsight -c android.intent.category.LAUNCHER 1
```

### iOS

Open `iosApp/` in Xcode on macOS and run the `iosApp` target. iOS runtime verification requires macOS and a simulator or physical iOS device.

---

## 🏎️ Development Notes

- Android builds require an Android SDK configured through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties`.
- The shared lap engine must remain independent from UI and platform location APIs.
- Algorithmic behavior should be covered with synthetic or recorded replay data.
- Local device screenshots, window dumps, logcat files, spreadsheets, and `.planning/evidence/` captures should stay out of Git.

---

## 🏎️ Safety Positioning

> [!WARNING]
> LapSight is intended for closed courses, karting tracks, private test areas, and training contexts. **It must not be positioned for public-road racing.**

The app should remain **passive while moving**. Users should configure sessions while stopped, mount the phone securely, and treat phone GPS as approximate telemetry rather than racing-grade timing equipment.

---

## 🏎️ Planning Docs

- 📄 Project context: `.planning/PROJECT.md`
- 📄 Requirements: `.planning/REQUIREMENTS.md`
- 📄 Roadmap: `.planning/ROADMAP.md`
- 📄 Current state: `.planning/STATE.md`
- 📄 Stack research: `.planning/research/STACK.md`
- 📄 Lap engine research: `.planning/research/LAP_ENGINE.md`

---

## 📜 License

This project is licensed under the GNU Affero General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

---

<div align="center">

<img src="Assets/LapSight_Logo.png" alt="LapSight Logo" width="64" />

<br/>

*Track your lines. Chase your ghosts.* 🏎️

</div>
