plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.viroreach.feature.calling"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":feature:discovery"))
    implementation(project(":transport:lan"))
    implementation(project(":transport:wifidirect"))
    implementation(project(":transport:internet"))
    implementation(project(":voice:api"))
    implementation(project(":voice:webrtc"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("io.getstream:stream-webrtc-android:1.1.3")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
