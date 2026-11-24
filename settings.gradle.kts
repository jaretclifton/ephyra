enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "findroid"

include(":app:phone")
include(":app:tv")
include(":core")
include(":downloads")
include(":data")
include(":player:cast")
include(":player:core")
include(":player:local")
include(":setup")
include(":modes:film")
include(":server:jellyfin")
include(":ratings:mdblist")
include(":settings")
include(":baselineprofile")
include(":baselineprofile-tv")
include(":architecture")
include(":domain")
include(":http")
include(":application")
include(":testing")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}