import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Room SDK for krtc-sfu on top of krtc-core (ADR-0012): signaling, negotiation, participants, quality.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    explicitApi()

    // Mobile, desktop JVM, native desktop (Linux, Windows, macOS) and web.
    android {
        namespace = "krtc.client"
        compileSdk = 36
        minSdk = 24
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    js { browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(project(":krtc-core"))
            api(project(":krtc-signaling"))
            implementation(project(":krtc-protocol"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        // Each platform's native HTTP/WebSocket stack, so wss:// uses the system TLS.
        androidMain.dependencies { implementation(libs.ktor.client.cio) } // OkHttp 5 would require compileSdk 37
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        appleMain.dependencies { implementation(libs.ktor.client.darwin) }
        // CIO, not curl: the curl engine links a static OpenSSL that clashes with the BoringSSL inside libwebrtc (ADR-0014).
        linuxMain.dependencies { implementation(libs.ktor.client.cio) }
        mingwMain.dependencies { implementation(libs.ktor.client.winhttp) }
        jsMain.dependencies { implementation(libs.ktor.client.js) }
        wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":krtc-sfu-ktor"))
            implementation(libs.ktor.server.cio)
        }
    }
}
