plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aurora.music"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Classical fork ships side-by-side with upstream Aurora
        applicationId = "com.aurora.music.classical"
        // Classical fork: floor is API 24 (Android 7.0) per plan §2.1. API 26+ calls must be
        // guarded with SDK_INT checks (plan §63); lint NewApi findings are tracked as follow-ups.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
        // Native AcoustID/Chromaprint fingerprinter (4.4c). arm64 for the phone, x86_64 for emulators.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/aurora-release.jks")
            storePassword = "aurora1234"
            keyAlias = "aurora"
            keyPassword = "aurora1234"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Classical fork: java.time works on API 24 via desugaring (plan §63).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Classical fork regression baseline (plan §66 v0.1.0): pre-existing findings frozen here.
        baseline = file("lint-baseline.xml")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.lottie.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.jaudiotagger)
    // Experimental USB bit-perfect audio driver (vendored decent-player, MIT).
    implementation(project(":decent-usb-audio-wrapper-media3"))
    // FFmpeg decoder (Jellyfin build) — float32 output for all formats, required for bit-perfect
    // non-local / non-FLAC content through the USB driver. Picked up by EXTENSION_RENDERER_MODE_PREFER.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    debugImplementation(libs.androidx.ui.tooling)
}
