pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Checked first, and normally empty. libsignal's native library is
        // 114 MB, and on a connection that stalls part-way Gradle gives up
        // where curl can resume; installing it here once lets the build
        // proceed. On any machine without it, resolution falls straight
        // through to mavenCentral below.
        mavenLocal()
        google()
        mavenCentral()
        // LiveKit Android SDK pulls its bundled audioswitch fork from JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ViroReach"

include(":app")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:security")
include(":core:e2ee")
include(":core:designsystem")
include(":feature:auth")
include(":feature:contacts")
include(":feature:discovery")
include(":feature:calling")
include(":feature:history")
include(":feature:subscription")
include(":feature:settings")
include(":transport:lan")
include(":transport:wifidirect")
include(":transport:internet")
include(":voice:api")
include(":voice:webrtc")
