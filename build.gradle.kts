import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
}

// Modules published as libraries for host applications (ADR-0013). Coordinates:
//   <krtc.group>:<module>:<krtc.version>  (gradle.properties; the group is a placeholder until chosen)
val libraries = setOf("krtc-protocol", "krtc-signaling", "krtc-sfu", "krtc-sfu-ktor", "krtc-core", "krtc-client")

subprojects {
    group = providers.gradleProperty("krtc.group").get()
    version = providers.gradleProperty("krtc.version").get()
    if (name in libraries) {
        apply(plugin = "maven-publish")
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            // Public API dumps under api/; `checkKotlinAbi` fails the build on unintended API changes.
            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation {
                    // Hosts that cannot build every target (CI checks on Linux, without Apple targets) keep the
                    // reference dumps of those targets instead of dropping them.
                    keepLocallyUnsupportedTargets.set(true)
                }
            }
        }
    }
}
