import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Embeddable SFU library (ADR-0010, ADR-0011, ADR-0013). The whole SFU lives in commonMain; only crypto
// primitives, DTLS and UDP sockets are platform-specific:
//   jvm    — JCE (AES-NI), BouncyCastle DTLS, NIO DatagramChannel
//   native — OpenSSL 3 (EVP, DTLS) via cinterop, POSIX sockets
// No HTTP server here: signaling transport belongs to the host (or krtc-sfu-ktor).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// OpenSSL 3 for Kotlin/Native, packed statically into the klib, so consumers link it without extra flags and
// binaries need no OpenSSL on the machine. macOS: Homebrew's static libraries (-Popenssl.prefix to override).
// Linux: built from source by build-openssl.sh with Kotlin/Native's own clang and sysroot (a system OpenSSL is
// built against a newer glibc than Kotlin/Native links with).
val opensslPrefix: String = (findProperty("openssl.prefix") as String?) ?: "/opt/homebrew/opt/openssl@3"
val linuxOpenSsl = mapOf("linuxX64" to "linux-x64", "linuxArm64" to "linux-arm64").mapValues { (target, platform) ->
    val dir = layout.projectDirectory.dir(".deps/openssl-$platform")
    dir to tasks.register<Exec>("buildOpenSsl${target.replaceFirstChar { it.uppercase() }}") {
        inputs.file("build-openssl.sh")
        outputs.files(dir.file("lib/libssl.a"), dir.file("lib/libcrypto.a"))
        commandLine("./build-openssl.sh", platform)
    }
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        val prefix = linuxOpenSsl[target.name]?.first?.asFile?.path ?: opensslPrefix
        target.compilations.getByName("main").cinterops.create("openssl") {
            defFile(project.file("src/nativeInterop/cinterop/openssl.def"))
            includeDirs("$prefix/include")
            extraOpts("-libraryPath", "$prefix/lib", "-staticLibrary", "libssl.a", "-staticLibrary", "libcrypto.a")
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlinx.coroutines.DelicateCoroutinesApi")
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api(project(":krtc-signaling"))
            implementation(project(":krtc-protocol"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.utils)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.tls)
            implementation(libs.bouncycastle.pkix)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.slf4j.simple)
        }
    }
}

// ./gradlew :krtc-sfu:benchmark  — forwarding throughput on the JVM
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass.set("krtc.sfu.ForwardingBenchmarkKt")
    jvmArgs("-XX:+UseZGC", "-Xms512m")
}

// The static OpenSSL must exist before cinterop packs it (Linux: built on demand, once).
tasks.matching { it.name.startsWith("cinteropOpenssl") }.configureEach {
    val target = name.removePrefix("cinteropOpenssl").replaceFirstChar { it.lowercase() }
    linuxOpenSsl[target]?.let { (_, build) -> dependsOn(build); inputs.files(build) }
}
