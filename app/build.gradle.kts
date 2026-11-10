plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Build identity shown in the app and in exports, e.g. "1.0.0 · 97a7c24"
val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "dev" }

android {
    namespace = "io.github.brickystudios.funkblick"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.brickystudios.funkblick"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.13.0"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    }

    // Release key lives only in CI secrets (and ~/.config/funkblick on the maintainer's machine).
    // Without it, local release builds fall back to the debug key so they still install.
    val storePass = System.getenv("SIGNING_STORE_PASSWORD")
    signingConfigs {
        if (storePass != null) create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "release.jks")
            storePassword = storePass
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // MapLibre is native: 64-bit ARM covers GrapheneOS (Pixels only) and practically every Android 8+ phone;
            // 32-bit would add ~10 MB. x86 only matters for the emulator (debug builds keep it)
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // no Google dependency info block: F-Droid/GrapheneOS users can check the APK has nothing to hide
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.gson)
    // vector map with OpenStreetMap data (OpenFreeMap), offline regions; no Google services
    implementation(libs.maplibre)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
