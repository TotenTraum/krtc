import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Ktor integration for krtc-sfu: `routing { krtcSfu(sfu, "/ws") }` serves krtc signaling over WebSocket.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":krtc-sfu"))
            api(libs.ktor.server.core)
            api(libs.ktor.server.websockets)
        }
    }
}
