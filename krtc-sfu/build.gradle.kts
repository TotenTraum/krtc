import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Selective forwarding unit for voice rooms (ADR-0009, ADR-0010): ICE-lite, DTLS-SRTP, RTP fan-out.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":krtc-protocol"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.tls)
    implementation(libs.bouncycastle.pkix)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}

application {
    mainClass.set("krtc.sfu.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "-Xms256m")
}

tasks.test {
    useJUnitPlatform()
}

// Forwarding throughput benchmark: ./gradlew :krtc-sfu:benchmark
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("krtc.sfu.ForwardingBenchmarkKt")
    jvmArgs("-XX:+UseZGC", "-Xms512m")
}
