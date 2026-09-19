plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.viroreach.core.network"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "API_BASE_URL", "\"https://reach.viro3.online\"")
        buildConfigField("String", "WSS_URL", "\"wss://reach.viro3.online/api/v1/signaling/ws\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.security.crypto)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
}
