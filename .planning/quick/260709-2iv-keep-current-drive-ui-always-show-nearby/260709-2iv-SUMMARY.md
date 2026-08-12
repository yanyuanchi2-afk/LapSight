---
quick_id: 260709-2iv
status: complete
code_commit: 973a332
---

# Drive nearby map and Track Center

- Preserved the existing Drive hierarchy and styling.
- Replaced the no-track billboard with a stable 500 m nearby-location canvas showing GPS position, accuracy, scale, and nearby selected-course geometry.
- Replaced both portrait and landscape Track picker dialogs with a full-screen Track Center list.
- Kept custom Track recording secondary: it is in Track Center overflow, with an explicit empty-library fallback.
- Hid bottom navigation while Track Center is open and restored it on back/selection.

## Verification

- `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidMain`
- `./gradlew :shared:allTests`
- Signed iPhone XR build with Xcode: succeeded.
- Installed on connected iPhone XR: succeeded.
- Automatic launch was denied only because the iPhone was locked.
