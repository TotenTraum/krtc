import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

// Selective forwarding unit (ADR-0010). Kotlin Multiplatform: the whole SFU lives in commonMain;
// only crypto primitives, DTLS and UDP sockets are platform-specific:
//   jvm    — JCE (AES-NI), BouncyCastle DTLS, NIO DatagramChannel
//   native — OpenSSL 3 (EVP, DTLS) via cinterop, POSIX sockets
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// OpenSSL 3 location for Kotlin/Native. macOS default: Homebrew (linked statically).
// Linux targets compile against these headers and link the system libssl/libcrypto dynamically.
val opensslPrefix: String = (findProperty("openssl.prefix") as String?) ?: "/opt/homebrew/opt/openssl@3"

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    val nativeTargets = listOf(macosArm64(), linuxX64(), linuxArm64())
    nativeTargets.forEach { target: KotlinNativeTarget ->
        target.compilations.getByName("main").cinterops.create("openssl") {
            defFile(project.file("src/nativeInterop/cinterop/openssl.def"))
            includeDirs("$opensslPrefix/include")
        }
        val isMac = target.konanTarget.family.isAppleFamily
        target.binaries {
            executable {
                entryPoint = "krtc.sfu.main"
                baseName = "krtc-sfu"
            }
            all {
                linkerOpts(
                    if (isMac) listOf("$opensslPrefix/lib/libssl.a", "$opensslPrefix/lib/libcrypto.a")
                    else listOf("-lssl", "-lcrypto"),
                )
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlinx.coroutines.DelicateCoroutinesApi")
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/web"))
            dependencies {
                implementation(project(":krtc-protocol"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.atomicfu)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.tls)
            implementation(libs.bouncycastle.pkix)
            runtimeOnly(libs.slf4j.simple)
        }
    }
}

// The browser test page is compiled into the binary (Kotlin/Native has no classpath resources).
val generateWeb by tasks.registering {
    val input = file("src/commonMain/web/index.html")
    val output = layout.buildDirectory.file("generated/web/krtc/sfu/signaling/WebPage.kt")
    inputs.file(input)
    outputs.file(output)
    doLast {
        val html = input.readText().replace("$", "\${'$'}")
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "// Generated from src/commonMain/web/index.html by the generateWeb task.\n" +
                    "package krtc.sfu.signaling\n\ninternal const val INDEX_HTML: String = \"\"\"" + html + "\"\"\"\n",
            )
        }
    }
}
tasks.matching { it.name.startsWith("compileKotlin") || it.name == "compileCommonMainKotlinMetadata" || it.name.endsWith("SourcesJar") }
    .configureEach { dependsOn(generateWeb) }

// ./gradlew :krtc-sfu:runJvm  — run the SFU on the JVM
tasks.register<JavaExec>("runJvm") {
    group = "application"
    val jvm = kotlin.jvm()
    val main = jvm.compilations.getByName("main")
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("krtc.sfu.MainKt")
    jvmArgs("-XX:+UseZGC", "-Xms256m")
    args = ((findProperty("sfuArgs") as String?) ?: "").split(" ").filter { it.isNotBlank() }
}

// ./gradlew :krtc-sfu:benchmark  — forwarding throughput on the JVM
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass.set("krtc.sfu.ForwardingBenchmarkKt")
    jvmArgs("-XX:+UseZGC", "-Xms512m")
}
