package krtc.demo.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import krtc.native.AndroidCamera
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = intent.getStringExtra("args")?.split(' ')?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray()
        setContentView(TextView(this).apply { text = "krtc demo: ${args.joinToString(" ")}\nsee logcat (System.out)" })
        if ("camera" in args && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        if (running) return // the activity was recreated (configuration change): the call goes on
        running = true
        thread(name = "krtc-demo") {
            runCatching {
                runBlocking {
                    runDemo(args) { factory ->
                        factory.createVideoSource().also { source -> camera = AndroidCamera(this@DemoActivity, source).apply { start() } }
                    }
                }
            }.onFailure { it.printStackTrace() }
            camera?.close()
            println("demo finished")
            running = false
        }
    }

    private companion object {
        @Volatile var running = false
    }
}
