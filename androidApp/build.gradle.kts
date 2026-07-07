import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.google.playServices.location)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.display)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // MockDeviceKit (Phase 7 07-03): instrumented tests only, never shipped in
    // the app itself.
    androidTestImplementation(libs.mwdat.mockdevice)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // External GNSS provider shell (Phase 6 06-03): plain JVM unit tests with
    // a fake byte-stream client, no BLE hardware or instrumentation required.
    testImplementation(libs.junit)
}

android {
    namespace = "com.huanfuli.lapsight"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.huanfuli.lapsight"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Meta DAT SDK Developer Mode credentials (0/0 = no Wearables Developer Center
        // account needed to build). Real credentials are a production-signing concern,
        // out of scope for this phase.
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
