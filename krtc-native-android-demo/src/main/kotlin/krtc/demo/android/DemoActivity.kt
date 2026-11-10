package krtc.demo.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import krtc.native.demo.runDemo
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/** Runs the shared krtc-native demo with the `args` intent extra (space-separated, as on the command line). */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = intent.getStringExtra("args")?.split(' ')?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray()
        setContentView(TextView(this).apply { text = "krtc demo: ${args.joinToString(" ")}\nsee logcat (System.out)" })
        thread(name = "krtc-demo") {
            runCatching { runBlocking { runDemo(args) } }.onFailure { it.printStackTrace() }
            println("demo finished")
        }
    }
}
