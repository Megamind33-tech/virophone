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
        google()
        mavenCentral()
    }
}

rootProject.name = "ViroReach"

include(":app")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:security")
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
