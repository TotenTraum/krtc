import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// krtc-meet: an example video call app on Compose Multiplatform over krtc-client and krtc-native (ADR-0018).
// Desktop (JVM): ./gradlew :krtc-meet:run. Android: the krtc-meet-android app module. iOS: an app built from the
// iosSimulatorArm64 / iosArm64 executable here (assembleIosSimulatorApp, see README).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    android {
        namespace = "krtc.meet"
        compileSdk = 37
        minSdk = 24
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "krtc.meet.main"
            baseName = "krtc-meet"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":krtc-client"))
            implementation(project(":krtc-native"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
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

// iOS simulator app without an Xcode project: the Kotlin executable (UIApplicationMain + ComposeUIViewController,
// iosMain/Main.ios.kt) with an Info.plist, ad-hoc signed. Install: xcrun simctl install booted <app>.
val iosSimulatorAppDir = layout.buildDirectory.dir("ios/krtc-meet.app")
val iosSimulatorAppFiles = tasks.register<Sync>("iosSimulatorAppFiles") {
    from(tasks.named("linkDebugExecutableIosSimulatorArm64")) {
        include("krtc-meet.kexe")
        rename { "krtc-meet" }
        filePermissions { unix("rwxr-xr-x") }
    }
    from("src/iosMain/Info.plist")
    into(iosSimulatorAppDir)
}
tasks.register<Exec>("assembleIosSimulatorApp") {
    group = "build"
    dependsOn(iosSimulatorAppFiles)
    inputs.dir(iosSimulatorAppDir)
    commandLine("codesign", "--force", "--sign", "-", iosSimulatorAppDir.get().asFile.absolutePath)
}
