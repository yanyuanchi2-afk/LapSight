package com.huanfuli.lapsight.shared

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.huanfuli.lapsight.shared.storage.StoragePaths
import platform.UIKit.UIViewController

/**
 * iOS entry point.
 *
 * Injects the app-private [StoragePaths.fileSessionStore] so created profiles and
 * the current-Track selection survive a cold launch — mirroring Android
 * `MainActivity` (Plan 05-04). Without this, iOS would fall back to the
 * preview/test [com.huanfuli.lapsight.shared.storage.InMemorySessionStore] default
 * and silently lose every saved Track on relaunch (T-05-09). The iOS
 * `StoragePaths` actual resolves the sandbox `NSDocumentDirectory` directly, so no
 * `initialize` step is needed here.
 */
fun MainViewController(
    orientationController: OrientationController = NoOpOrientationController,
): UIViewController {
    val phoneGpsProvider = IosCoreLocationSampleProvider()

    return ComposeUIViewController {
        val permissionGranted by phoneGpsProvider.permissionGranted.collectAsState()

        DisposableEffect(phoneGpsProvider) {
            onDispose {
                phoneGpsProvider.stop()
            }
        }

        App(
            orientationController = orientationController,
            displaySettingsStore = IosDisplaySettingsStore(),
            phoneGpsProvider = phoneGpsProvider,
            phoneGpsPermission = PhoneGpsPermissionState(
                isSupported = phoneGpsProvider.isSupported,
                isGranted = permissionGranted,
                requestPermission = phoneGpsProvider::requestPermission,
            ),
            sessionStore = StoragePaths.fileSessionStore(),
        )
    }
}
