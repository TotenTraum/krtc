rootProject.name = "krtc"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // kaop (ru.ttraum) until the version krtc-meet needs is on Maven Central: publishToMavenLocal in the kaop repo.
        mavenLocal { content { includeGroupByRegex("ru\\.ttraum.*") } }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal { content { includeGroupByRegex("ru\\.ttraum.*") } }
    }
}

include(":krtc-idl")
include(":krtc-protocol")
include(":krtc-sfu")
include(":krtc-core")
include(":krtc-client")
include(":krtc-signaling")
include(":krtc-sfu-ktor")
include(":krtc-sfu-server")
include(":krtc-native")
include(":krtc-native-android-demo")
include(":krtc-meet")
include(":krtc-meet-android")
