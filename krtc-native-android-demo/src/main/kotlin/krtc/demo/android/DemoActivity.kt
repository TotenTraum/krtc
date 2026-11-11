package krtc.demo.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import krtc.native.AndroidCamera
import krtc.native.AndroidScreenCapture
import krtc.native.demo.runDemo
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Runs the shared krtc-native demo with the `args` intent extra (space-separated, as on the command line).
 * The `camera` flag captures with Camera2 ([AndroidCamera]); grant the permission first
 * (`adb shell pm grant krtc.demo.android android.permission.CAMERA`) or accept the prompt.
 */
class DemoActivity : Activity() {
    private var camera: AndroidCamera? = null
    private var screen: AndroidScreenCapture? = null
    private var args: Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        args = intent.getStringExtra("args")?.split(' ')?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray()
        setContentView(TextView(this).apply { text = "krtc demo: ${args.joinToString(" ")}\nsee logcat (System.out)" })
        if ("camera" in args && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        if (running) return // the activity was recreated (configuration change): the call goes on
        running = true
        // `screen`: ask for the user's consent first (system dialog), then run with its result.
        if ("screen" in args) startActivityForResult(AndroidScreenCapture.consentIntent(this), SCREEN_CONSENT) else start(null)
    }

    @Deprecated("Activity result API kept simple for the demo")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCREEN_CONSENT) return
        if (resultCode != RESULT_OK || data == null) println("screen sharing was not allowed")
        start(data?.takeIf { resultCode == RESULT_OK }?.let { resultCode to it })
    }

    private fun start(screenConsent: Pair<Int, Intent>?) {
        thread(name = "krtc-demo") {
            runCatching {
                runBlocking {
                    runDemo(
                        args,
                        openCamera = { factory ->
                            factory.createVideoSource().also { source -> camera = AndroidCamera(this@DemoActivity, source).apply { start() } }
                        },
                        openScreen = { factory ->
                            screenConsent?.let { (code, data) ->
                                factory.createScreencastSource().also { source ->
                                    screen = AndroidScreenCapture(this@DemoActivity, code, data, source).apply { start() }
                                }
                            }
                        },
                    )
                }
            }.onFailure { it.printStackTrace() }
            camera?.close()
            screen?.close()
            println("demo finished")
            running = false
        }
    }

    private companion object {
        const val SCREEN_CONSENT = 1
        @Volatile var running = false
    }
}
