rootProject.name = "krtc"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":krtc-idl")
include(":krtc-protocol")
include(":krtc-sfu")
