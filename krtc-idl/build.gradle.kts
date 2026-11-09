import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Raw W3C-shaped API generated from WebIDL by tools/spec-inventory (npm run generate:kotlin).
// Generated sources live in src/commonMain/generated; hand-written glue in src/commonMain/kotlin.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    explicitApi()

    android {
        namespace = "krtc.idl"
        compileSdk = 36
        minSdk = 24
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
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
        all {
            languageSettings.optIn("krtc.idl.KrtcExtendedApi")
        }
        commonMain {
            kotlin.srcDir("src/commonMain/generated")
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
