import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// krtc-meet: an example video call app on Compose Multiplatform over krtc-client and krtc-native (ADR-0018).
// M1: the desktop JVM (macOS). Run: ./gradlew :krtc-meet:run
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":krtc-client"))
            implementation(project(":krtc-native"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
        }
    }
}

compose.desktop {
    application {
        mainClass = "krtc.meet.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "krtc-meet"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "krtc.meet"
                // Access descriptions: without them macOS denies the camera, microphone and screen silently.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSCameraUsageDescription</key><string>krtc meet shows your camera to the people in the call.</string>
                        <key>NSMicrophoneUsageDescription</key><string>krtc meet sends your voice to the people in the call.</string>
                    """.trimIndent()
                }
            }
        }
    }
}
