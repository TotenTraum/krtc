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
include(":krtc-core")
include(":krtc-client")
include(":krtc-signaling")
include(":krtc-sfu-ktor")
include(":krtc-sfu-server")
include(":krtc-native")
include(":krtc-native-android-demo")
include(":krtc-meet")
