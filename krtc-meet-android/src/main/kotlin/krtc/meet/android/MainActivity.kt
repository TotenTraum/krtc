package krtc.meet.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import krtc.meet.AndroidMediaPlatform
import krtc.meet.App
import krtc.meet.CameraChoice
import krtc.meet.JoinSettings
import krtc.meet.MeetSession
import kotlinx.coroutines.CompletableDeferred

/**
 * The call app. Defaults (and test automation) come from intent extras:
 *   adb shell am start -n krtc.meet/krtc.meet.android.MainActivity --es server ws://<LAN-IP>:8080 --es room test \
 *     --es name phone --es camera test|none --ez testAudio true --ez autojoin true
 */
class MainActivity : ComponentActivity() {
    private var consent: CompletableDeferred<Pair<Int, Intent>?>? = null
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        consent?.complete(r.data?.takeIf { r.resultCode == Activity.RESULT_OK }?.let { r.resultCode to it })
        consent = null
    }

    /** Shows the system's screen capture dialog; the result, or null if the user declined. */
    private suspend fun requestScreenConsent(): Pair<Int, Intent>? {
        val d = CompletableDeferred<Pair<Int, Intent>?>()
        consent = d
        consentLauncher.launch(AndroidMediaPlatform.screenConsentIntent(this))
        return d.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)

        val platform = AndroidMediaPlatform(this, ::requestScreenConsent)
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
            val given = remember { listOf("server", "room", "name", "camera", "testAudio").filter { extras?.containsKey(it) == true }.toSet() }
            val remembered = remember { session.rememberedJoin(defaults, given) }
            remember { if (autojoin) session.join(remembered) }
            DisposableEffect(Unit) { onDispose { if (isFinishing) session.close() } }
            App(session, remembered)
        }
    }
}
