// Cross-platform diagnostic client. The native backend's only project dependency is krtc-core.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    android {
        namespace = "krtc.native.demo"
        compileSdk = 36
        minSdk = 24
    }
    listOf(macosArm64(), linuxArm64(), linuxX64(), iosArm64(), iosSimulatorArm64(), mingwX64()).forEach { target ->
        target.binaries.executable("demo") {
            entryPoint = "krtc.native.demo.main"
            baseName = "krtc-native-demo"
            if (target.name == "mingwX64") {
                // The DLL belongs to krtc-native; the demo ships it beside its Windows executable.
                val dll = rootProject.layout.projectDirectory.file("krtc-native/.deps/build/win-x64/krtc.dll")
                val outDir = outputDirectoryProperty
                linkTaskProvider.configure {
                    dependsOn(":krtc-native:buildShimMingwX64")
                    inputs.file(dll)
                    doLast { dll.asFile.copyTo(outDir.get().asFile.resolve("krtc.dll"), overwrite = true) }
                }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":krtc-native")) // Android's demo host also uses its camera, capture and audio APIs.
            implementation(project(":krtc-client"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// Demo on the desktop JVM: ./gradlew :krtc-native-demo:runJvmDemo --args="ws://<sfu>:8092 test jvm-client 30"
tasks.register<JavaExec>("runJvmDemo") {
    val jvm = kotlin.jvm()
    dependsOn(jvm.compilations.getByName("main").compileTaskProvider, "jvmProcessResources")
    classpath(jvm.compilations.getByName("main").output.allOutputs, jvm.compilations.getByName("main").runtimeDependencyFiles)
    mainClass.set("krtc.native.demo.MainKt")
}

// A runnable JVM demo for testing on another machine (CI artifact): unzip, then
//   krtc-native-jvm-demo/bin/krtc-native-demo ws://<sfu>:8092 test jvm-client 30      (Windows: bin\krtc-native-demo.bat)
val jvmDemoStartScript = tasks.register("jvmDemoStartScript") {
    val dir = layout.buildDirectory.dir("dist/scripts")
    outputs.dir(dir)
    doLast {
        val out = dir.get().asFile.apply { mkdirs() }
        out.resolve("krtc-native-demo.bat").writeText(
            "@echo off\r\n" +
                "java --enable-native-access=ALL-UNNAMED -cp \"%~dp0..\\lib\\*\" krtc.native.demo.MainKt %*\r\n",
        )
        out.resolve("krtc-native-demo").writeText(
            "#!/bin/sh\n" +
                "# Needs Java 21+.\n" +
                    $$"DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                    $$"exec java --enable-native-access=ALL-UNNAMED -cp \"$DIR/lib/*\" krtc.native.demo.MainKt \"$@\"\n",
        )
    }
}
tasks.register<Zip>("jvmDemoDistZip") {
    group = "distribution"
    archiveFileName.set("krtc-native-jvm-demo.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    into("krtc-native-jvm-demo/lib") {
        from(tasks.named("jvmJar"))
        from(kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles)
    }
    into("krtc-native-jvm-demo/bin") {
        from(jvmDemoStartScript)
        filePermissions { unix("rwxr-xr-x") }
    }
}
