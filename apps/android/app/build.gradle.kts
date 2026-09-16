plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val gitCommitAbbrev: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifBlank { "unknown" }

android {
    namespace = "com.viroreach.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.viroreach.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.3.8-network-bind"
        buildConfigField("String", "API_BASE_URL", "\"https://reach.viro3.online\"")
        buildConfigField("String", "WSS_URL", "\"wss://reach.viro3.online/api/v1/signaling/ws\"")
        buildConfigField("boolean", "FORCE_TURN_RELAY", "false")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommitAbbrev\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:contacts"))
    implementation(project(":feature:discovery"))
    implementation(project(":feature:calling"))
    implementation(project(":feature:contacts"))
    implementation(project(":feature:history"))
    implementation(project(":feature:subscription"))
    implementation(project(":feature:settings"))
    implementation(project(":transport:lan"))
    implementation(project(":transport:wifidirect"))
    implementation(project(":transport:internet"))
    implementation(project(":voice:api"))
    implementation(project(":voice:webrtc"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${libs.versions.lifecycle.get()}")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":feature:calling"))
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
