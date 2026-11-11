// krtc-meet on Android: the shared Compose UI (krtc-meet) in an activity (ADR-0018).
//   ./gradlew :krtc-meet-android:installDebug
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "krtc.meet.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "krtc.meet"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" } // the only ABI krtc-native ships so far
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":krtc-meet"))
    implementation("androidx.activity:activity-compose:1.13.0")
}
