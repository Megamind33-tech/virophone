plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.viroreach.core.e2ee"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs {
            // libsignal ships a second native library used only by its own test
            // suite. It is the same size as the real one, and shipping it would
            // add tens of megabytes to every download for nothing.
            excludes += "**/libsignal_jni_testing.so"
        }
    }
}
dependencies {
    api(libs.libsignal.android)
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
}
