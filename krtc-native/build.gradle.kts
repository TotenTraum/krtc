// krtc-core backend on libwebrtc through the krtc C shim (ADR-0002, ADR-0014: macOS arm64, Linux arm64).
// libwebrtc: LiveKit's prebuilt static library (krtc-native/.deps, not committed); the shim is built by
// build-shim.sh against it (on Linux inside Docker, see docker-build-shim.sh). Both are packed into the
// klib, so applications need no native build setup.
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * One libwebrtc platform: `.deps/<name>/<name>-release` and the shim built into `.deps/build/<name>`.
 * [isolated]: the shim and libwebrtc are merged into `libkrtc_webrtc.a` exporting only krtc_* (Linux: its
 * bundled libc++abi and BoringSSL would otherwise collide with Kotlin/Native's libstdc++ and OpenSSL).
 */
class WebRtcPlatform(val name: String, val shimCommand: List<String>, val isolated: Boolean) {
    val webrtcDir = layout.projectDirectory.dir(".deps/$name/$name-release")
    val shimOut = layout.projectDirectory.dir(".deps/build/$name")
    val libraries: List<Pair<String, String>>
        get() = if (isolated) listOf(shimOut.asFile.path to "libkrtc_webrtc.a")
        else listOf(shimOut.asFile.path to "libkrtc_shim.a", webrtcDir.dir("lib").asFile.path to "libwebrtc.a")
}

val platforms = mapOf(
    "macosArm64" to WebRtcPlatform("mac-arm64", listOf("./build-shim.sh", ".deps/mac-arm64/mac-arm64-release", ".deps/build/mac-arm64"), isolated = false),
    "linuxArm64" to WebRtcPlatform("linux-arm64", listOf("./docker-build-shim.sh", "arm64"), isolated = true),
)

val shimTasks = platforms.mapValues { (target, platform) ->
    tasks.register<Exec>("buildShim${target.replaceFirstChar { it.uppercase() }}") {
        inputs.dir("src/shim")
        inputs.file("build-shim.sh")
        outputs.files(platform.libraries.map { (dir, lib) -> "$dir/$lib" })
        commandLine(platform.shimCommand)
    }
}

kotlin {
    explicitApi()

    fun KotlinNativeTarget.krtcNative() {
        val platform = platforms.getValue(name)
        compilations.getByName("main").cinterops.create("krtc") {
            defFile(project.file("src/nativeInterop/cinterop/krtc.def"))
            includeDirs("src/shim")
            extraOpts(platform.libraries.flatMap { (dir, lib) -> listOf("-libraryPath", dir, "-staticLibrary", lib) })
        }
        binaries.executable("demo") {
            entryPoint = "krtc.native.demo.main"
            baseName = "krtc-native-demo"
        }
    }
    macosArm64 { krtcNative() }
    linuxArm64 { krtcNative() }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api(project(":krtc-core"))
            implementation(project(":krtc-client"))
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.matching { it.name.startsWith("cinteropKrtc") }.configureEach {
    val target = name.removePrefix("cinteropKrtc").replaceFirstChar { it.lowercase() }
    shimTasks[target]?.let { dependsOn(it); inputs.files(it) } // the static libraries are packed into the klib
    inputs.file("src/shim/krtc.h") // includeDirs alone does not make header edits rerun cinterop
}
