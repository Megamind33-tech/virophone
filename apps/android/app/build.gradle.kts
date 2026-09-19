plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// google-services reads app/google-services.json and fails the build outright
// when it is missing. Applying it conditionally keeps the module buildable for
// anyone who has not pulled the Firebase config yet (it is gitignored), and it
// switches itself on the moment the file appears — no build-file edit needed.
val googleServicesConfig = file("google-services.json")
if (googleServicesConfig.exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.lifecycle(
        "Firebase: app/google-services.json not found — Auth, Firestore and push are inert in this build.",
    )
}

// Commit count as the version code: every build gets a distinct, increasing
// number without anyone remembering to bump it. Until now versionCode was
// hardcoded to 1, so every APK ever distributed was "0.3.8-network-bind (1)" —
// identical to Firebase App Distribution and to Android's installer, which made
// it impossible to tell which build a tester actually had when they reported a
// bug, and meant an install might not register as an update at all.
val gitCommitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

val gitCommitAbbrev: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifBlank { "unknown" }

android {
    namespace = "com.viroreach.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.viroreach.app"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        // Carries the commit it was built from, so a bug report identifies the
        // exact source state rather than a name that never changes.
        versionName = "0.4.$gitCommitCount-$gitCommitAbbrev"
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
    // Firebase — BoM pins every Firebase artifact to one compatible set,
    // so the individual dependencies below intentionally carry no version.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    // Gives Task<T>.await(), so the FCM token read is a plain suspend call.
    implementation(libs.kotlinx.coroutines.play.services)

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
    // Messaging: media upload/download on the authenticated client, and the
    // JSON columns of the local message store.
    implementation(libs.okhttp)
    implementation("com.google.code.gson:gson:2.10.1")
    // Photos in chat, fetched with the auth header.
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    // Relationship reminders and the morning brief run on schedules that
    // must survive the app being closed.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Chat lock and hidden chats.
    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":feature:calling"))
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
