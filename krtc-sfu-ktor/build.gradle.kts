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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.client.cio)
        }
    }
}
