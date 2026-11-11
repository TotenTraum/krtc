// krtc-core backend on libwebrtc through the krtc C shim (ADR-0002, ADR-0014).
// Kotlin/Native (macOS, iOS, Linux): cinterop; the shim and libwebrtc are packed into the klib.
// Kotlin/Native (Windows, mingwX64): cinterop against krtc.dll through its MinGW import library (packed into the
// klib); the application ships krtc.dll next to its .exe. libwebrtc is MSVC-built, so it cannot be linked
// statically into a MinGW binary; the flat C ABI of krtc.h is the DLL boundary.
// JVM (macOS, Windows) and Android: one JNI layer (krtc_jni.cpp) built into libkrtc_jni (Windows: krtc.dll),
// packed into the jar / AAR.
// libwebrtc: LiveKit's prebuilt static library (krtc-native/.deps, not committed); the shim is built by
// build-shim.sh against it (on Linux and Windows inside Docker, see docker-build-shim.sh), the JNI library by
// build-jni.sh.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

/** How a Kotlin/Native target links the shim and libwebrtc. */
enum class Linkage {
    /** libkrtc_shim.a and libwebrtc.a. */
    STATIC,
    /** One libkrtc_webrtc.a exporting only krtc_* (Linux: libwebrtc's bundled libc++abi and BoringSSL would
     * otherwise collide with Kotlin/Native's libstdc++ and OpenSSL). */
    ISOLATED,
    /** krtc.dll through its import library libkrtc.dll.a (Windows). */
    DLL,
}

/** One libwebrtc platform: `.deps/<name>/<name>-release` and the shim built into `.deps/build/<name>`. */
class WebRtcPlatform(val name: String, val shimCommand: List<String>, val linkage: Linkage) {
    val webrtcDir = layout.projectDirectory.dir(".deps/$name/$name-release")
    val shimOut = layout.projectDirectory.dir(".deps/build/$name")
    val libraries: List<Pair<String, String>>
        get() = when (linkage) {
            Linkage.STATIC -> listOf(shimOut.asFile.path to "libkrtc_shim.a", webrtcDir.dir("lib").asFile.path to "libwebrtc.a")
            Linkage.ISOLATED -> listOf(shimOut.asFile.path to "libkrtc_webrtc.a")
            Linkage.DLL -> listOf(shimOut.asFile.path to "libkrtc.dll.a")
        }
    val outputs: List<String>
        get() = libraries.map { (dir, lib) -> "$dir/$lib" } + if (linkage == Linkage.DLL) listOf(shimOut.file("krtc.dll").asFile.path) else emptyList()
}

val platforms = mapOf(
    "macosArm64" to WebRtcPlatform("mac-arm64", listOf("./build-shim.sh", ".deps/mac-arm64/mac-arm64-release", ".deps/build/mac-arm64"), linkage = Linkage.STATIC),
    "iosArm64" to WebRtcPlatform("ios-device-arm64", listOf("./build-shim.sh", ".deps/ios-device-arm64/ios-device-arm64-release", ".deps/build/ios-device-arm64", "ios"), linkage = Linkage.STATIC),
    "iosSimulatorArm64" to WebRtcPlatform("ios-simulator-arm64", listOf("./build-shim.sh", ".deps/ios-simulator-arm64/ios-simulator-arm64-release", ".deps/build/ios-simulator-arm64", "ios-simulator"), linkage = Linkage.STATIC),
    "linuxArm64" to WebRtcPlatform("linux-arm64", listOf("./docker-build-shim.sh", "arm64"), linkage = Linkage.ISOLATED),
    "linuxX64" to WebRtcPlatform("linux-x64", listOf("./docker-build-shim.sh", "x64"), linkage = Linkage.ISOLATED),
    // krtc.dll (shim + libwebrtc + JNI), shared with the desktop JVM.
    "mingwX64" to WebRtcPlatform("win-x64", listOf("./docker-build-shim.sh", "win"), linkage = Linkage.DLL),
)

val shimTasks = platforms.mapValues { (target, platform) ->
    tasks.register<Exec>("buildShim${target.replaceFirstChar { it.uppercase() }}") {
        inputs.dir("src/shim")
        inputs.files("build-shim.sh", "toolchain.sh", "build-jni.sh", "docker-build-shim.sh")
        outputs.files(platform.outputs)
        commandLine(platform.shimCommand)
    }
}

/** JNI libraries: (Gradle name, libwebrtc platform, build-jni.sh target, library file). */
val jniLibraries = listOf(
    listOf("MacosArm64", "mac-arm64", "macos", "libkrtc_jni.dylib"),
    listOf("AndroidArm64", "android-arm64", "android-arm64", "libkrtc_jni.so"),
).associate { (task, platform, target, file) ->
    val out = layout.projectDirectory.dir(".deps/build/$platform")
    task to tasks.register<Exec>("buildJni$task") {
        inputs.dir("src/shim")
        inputs.files("build-shim.sh", "build-jni.sh", "toolchain.sh")
        outputs.file(out.file(file))
        val webrtc = ".deps/$platform/$platform-release"
        commandLine("sh", "-c", "./build-shim.sh $webrtc ${out.asFile.path} $target && ./build-jni.sh $webrtc ${out.asFile.path} $target")
    }
}

// The desktop JVM loads libkrtc_jni from the jar (krtc/native/<os>-<arch>/, see Jni.kt).
val jvmJniResources = tasks.register<Sync>("jvmJniResources") {
    from(jniLibraries.getValue("MacosArm64")) { into("krtc/native/macos-arm64") }
    from(shimTasks.getValue("mingwX64")) { include("krtc.dll"); into("krtc/native/windows-x64") }
    into(layout.buildDirectory.dir("generated/jni/jvm"))
}

/** Android: puts libkrtc_jni.so into the jniLibs layout (<abi>/lib.so) that AGP merges into the AAR. */
abstract class AndroidJniLibs : DefaultTask() {
    @get:InputFiles abstract val libraries: ConfigurableFileCollection
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val abi = output.get().dir("arm64-v8a").asFile.apply { deleteRecursively(); mkdirs() }
        libraries.files.forEach { it.copyTo(abi.resolve(it.name), overwrite = true) }
    }
}

val androidJniLibs = tasks.register<AndroidJniLibs>("androidJniLibs") {
    libraries.from(jniLibraries.getValue("AndroidArm64"))
}

kotlin {
    explicitApi()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    // jvmAndAndroidMain: the JNI backend shared by the desktop JVM and Android.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withCompilations { it.platformType == KotlinPlatformType.jvm || it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    android {
        namespace = "krtc.native"
        compileSdk = 36
        minSdk = 24
    }

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
            if (platform.linkage == Linkage.DLL) {
                // Windows: the executable loads krtc.dll from its own directory.
                val dll = platform.shimOut.file("krtc.dll").asFile
                val outDir = outputDirectoryProperty
                linkTaskProvider.configure {
                    inputs.file(dll)
                    doLast { dll.copyTo(outDir.get().asFile.resolve(dll.name), overwrite = true) }
                }
            }
        }
    }
    macosArm64 { krtcNative() }
    linuxArm64 { krtcNative() }
    linuxX64 { krtcNative() }
    iosArm64 { krtcNative() }
    iosSimulatorArm64 { krtcNative() }
    mingwX64 { krtcNative() }

    sourceSets {
        matching { it.name.startsWith("native") || it.name.startsWith("apple") || it.name.startsWith("macos") || it.name.startsWith("ios") || it.name.startsWith("linux") || it.name.startsWith("mingw") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api(project(":krtc-core"))
            implementation(project(":krtc-client"))
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain { resources.srcDir(jvmJniResources) }
    }
}

extensions.getByType<com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(androidJniLibs, AndroidJniLibs::output)
}

// Demo on the desktop JVM: ./gradlew :krtc-native:runJvmDemo --args="ws://<sfu>:8092 test jvm-client 30"
tasks.register<JavaExec>("runJvmDemo") {
    val jvm = kotlin.jvm()
    dependsOn(jvm.compilations.getByName("main").compileTaskProvider, "jvmProcessResources")
    classpath(jvm.compilations.getByName("main").output.allOutputs, jvm.compilations.getByName("main").runtimeDependencyFiles)
    mainClass.set("krtc.native.demo.MainKt")
}

tasks.matching { it.name.startsWith("cinteropKrtc") }.configureEach {
    val target = name.removePrefix("cinteropKrtc").replaceFirstChar { it.lowercase() }
    shimTasks[target]?.let { dependsOn(it); inputs.files(it) } // the static libraries are packed into the klib
    inputs.file("src/shim/krtc.h") // includeDirs alone does not make header edits rerun cinterop
}
