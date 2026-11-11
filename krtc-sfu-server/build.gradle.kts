import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The standalone SFU application: krtc-sfu + krtc-sfu-ktor + a browser test page.
// Hosts that embed the SFU depend on krtc-sfu (and optionally krtc-sfu-ktor) instead.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "krtc.sfu.server.main"
            baseName = "krtc-sfu"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/web"))
            dependencies {
                implementation(project(":krtc-sfu-ktor"))
                implementation(libs.ktor.server.cio)
            }
        }
        jvmMain.dependencies {
            runtimeOnly(libs.slf4j.simple)
        }
    }
}

// The browser test page is compiled into the binary (Kotlin/Native has no classpath resources).
val generateWeb by tasks.registering {
    val input = file("src/commonMain/web/index.html")
    val output = layout.buildDirectory.file("generated/web/krtc/sfu/server/WebPage.kt")
    inputs.file(input)
    outputs.file(output)
    doLast {
        val html = input.readText().replace("$", "\${'$'}")
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "// Generated from src/commonMain/web/index.html by the generateWeb task.\n" +
                    "package krtc.sfu.server\n\ninternal const val INDEX_HTML: String = \"\"\"" + html + "\"\"\"\n",
            )
        }
    }
}
tasks.matching { it.name.startsWith("compileKotlin") || it.name == "compileCommonMainKotlinMetadata" || it.name.endsWith("SourcesJar") }
    .configureEach { dependsOn(generateWeb) }

// ./gradlew :krtc-sfu-server:runJvm -PsfuArgs="--http-port 8080"
tasks.register<JavaExec>("runJvm") {
    group = "application"
    val main = kotlin.jvm().compilations.getByName("main")
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("krtc.sfu.server.MainKt")
    jvmArgs("-XX:+UseZGC", "-Xms256m")
    args = ((findProperty("sfuArgs") as String?) ?: "").split(" ").filter { it.isNotBlank() }
}

// A runnable JVM build of the server for testing on any OS with Java 21+ (CI artifact): unzip, then
//   krtc-sfu-jvm/bin/krtc-sfu --announce <LAN-IP>        (Windows: krtc-sfu-jvm\bin\krtc-sfu.bat)
val jvmStartScripts = tasks.register("jvmStartScripts") {
    val dir = layout.buildDirectory.dir("dist/scripts")
    outputs.dir(dir)
    doLast {
        val out = dir.get().asFile.apply { mkdirs() }
        out.resolve("krtc-sfu").writeText(
            "#!/bin/sh\n" +
                "DIR=\"\$(cd \"\$(dirname \"\$0\")/..\" && pwd)\"\n" +
                "exec java -XX:+UseZGC -cp \"\$DIR/lib/*\" krtc.sfu.server.MainKt \"\$@\"\n",
        )
        out.resolve("krtc-sfu.bat").writeText(
            "@echo off\r\n" +
                "java -XX:+UseZGC -cp \"%~dp0..\\lib\\*\" krtc.sfu.server.MainKt %*\r\n",
        )
    }
}
tasks.register<Zip>("jvmDistZip") {
    group = "distribution"
    archiveFileName.set("krtc-sfu-jvm.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    into("krtc-sfu-jvm/lib") {
        from(tasks.named("jvmJar"))
        from(kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles)
    }
    into("krtc-sfu-jvm/bin") {
        from(jvmStartScripts)
        filePermissions { unix("rwxr-xr-x") }
    }
}
