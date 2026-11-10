// Android demo for krtc-native (JNI backend): runs the shared demo (krtc.native.demo.runDemo) in an app.
//   adb shell am start -n krtc.demo.android/.DemoActivity --es args "ws://<sfu>:8092 test android-client 30"
// Output goes to logcat (tag System.out).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "krtc.demo.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "krtc.demo.android"
        minSdk = 24
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" } // the only ABI krtc-native ships so far
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":krtc-native"))
    implementation(libs.kotlinx.coroutines.core)
}
