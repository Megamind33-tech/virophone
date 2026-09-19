plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.viroreach.voice.webrtc"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":voice:api"))
    implementation(project(":core:model"))
    // Stream-maintained WebRTC build (Apache 2.0) — kept only for transport/*
    // route-selection code that still references its ICE types; no longer
    // used for actual call media, which LiveKit now owns (see LiveKitCallEngine).
    implementation("io.getstream:stream-webrtc-android:1.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("io.livekit:livekit-android:2.18.2")
    testImplementation("junit:junit:4.13.2")
}
