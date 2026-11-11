package krtc.meet.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import krtc.meet.AndroidMediaPlatform
import krtc.meet.App
import krtc.meet.CameraChoice
import krtc.meet.JoinSettings
import krtc.meet.MeetSession

/**
 * The call app. Defaults (and test automation) come from intent extras:
 *   adb shell am start -n krtc.meet/krtc.meet.android.MainActivity --es server ws://<LAN-IP>:8080 --es room test \
 *     --es name phone --es camera test|none --ez testAudio true --ez autojoin true
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)

        val platform = AndroidMediaPlatform(this)
        val extras = intent.extras
        val defaults = JoinSettings(
            server = extras?.getString("server") ?: "ws://10.0.2.2:8080", // the host machine, seen from the emulator
            room = extras?.getString("room") ?: "test",
            name = extras?.getString("name") ?: android.os.Build.MODEL,
            camera = when (extras?.getString("camera")) {
                "none" -> CameraChoice.None
                "test" -> CameraChoice.TestPattern
                else -> platform.cameras().firstOrNull { it.name.startsWith("Фронтальная") } ?: platform.cameras().firstOrNull() ?: CameraChoice.TestPattern
            },
            testAudio = extras?.getBoolean("testAudio") ?: false,
        )
        val autojoin = extras?.getBoolean("autojoin") ?: false
        setContent {
            val scope = rememberCoroutineScope()
            val session = remember { MeetSession(scope, platform) }
            remember { if (autojoin) session.join(defaults) }
            DisposableEffect(Unit) { onDispose { if (isFinishing) session.close() } }
            App(session, defaults)
        }
    }
}
