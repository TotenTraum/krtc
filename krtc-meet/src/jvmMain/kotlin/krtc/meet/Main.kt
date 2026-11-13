package krtc.meet

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Desktop entry point. Defaults come from the environment, which also lets tests drive the app:
 *   KRTC_MEET_SERVER (ws://localhost:8080), KRTC_MEET_ROOM (test), KRTC_MEET_NAME, KRTC_MEET_TEST_AUDIO=1,
 *   KRTC_MEET_CAMERA=test|none, KRTC_MEET_AUTOJOIN=1 joins at once, KRTC_MEET_LAYOUT=speaker,
 *   KRTC_MEET_SNAPSHOT=<file.png> renders the window to a PNG after KRTC_MEET_SNAPSHOT_AFTER seconds (default 10).
 */
fun main() = application {
    val env = System.getenv()
    remember {
        krtc.meet.log.ClientLog.i("app", "krtc-meet started: ${platformName()}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            krtc.meet.log.ClientLog.e("app", "uncaught on ${t.name}: ${e.stackTraceToString()}")
            previous?.uncaughtException(t, e)
        }
    }
    val defaults = JoinSettings(
        server = env["KRTC_MEET_SERVER"] ?: "ws://localhost:8080",
        room = env["KRTC_MEET_ROOM"] ?: "test",
        name = env["KRTC_MEET_NAME"] ?: System.getProperty("user.name"),
        camera = when (env["KRTC_MEET_CAMERA"]) {
            "none" -> CameraChoice.None
            "test", null -> if (env["KRTC_MEET_CAMERA"] == null && krtc.native.nativeCameras().isNotEmpty()) CameraChoice.Device(0, krtc.native.nativeCameras()[0]) else CameraChoice.TestPattern
            else -> CameraChoice.TestPattern
        },
        testAudio = env["KRTC_MEET_TEST_AUDIO"] == "1",
    )
    val state = rememberWindowState(width = 1100.dp, height = 760.dp)
    var current: MeetSession? = null
    Window(onCloseRequest = { current?.close(); exitApplication() }, title = APP_WINDOW_TITLE, state = state) {
        val scope = rememberCoroutineScope()
        val session = remember { MeetSession(scope).also { if (env["KRTC_MEET_LAYOUT"] == "speaker") it.setLayout(Layout.SPEAKER) } }.also { current = it }
        // What the environment sets is used as it is; the rest of the form as it was last time.
        val given = remember { mapOf("server" to "KRTC_MEET_SERVER", "room" to "KRTC_MEET_ROOM", "name" to "KRTC_MEET_NAME", "camera" to "KRTC_MEET_CAMERA", "testAudio" to "KRTC_MEET_TEST_AUDIO").filterValues { env[it] != null }.keys }
        val remembered = remember { session.rememberedJoin(defaults, given) }
        remember { if (env["KRTC_MEET_AUTOJOIN"] == "1") session.join(remembered) }
        remember { if (env["KRTC_MEET_OPEN_PICKER"] == "1") session.setScreenPicker(true) }
        env["KRTC_MEET_SNAPSHOT"]?.let { file ->
            Snapshot.schedule(1100, 760, file, (env["KRTC_MEET_SNAPSHOT_AFTER"] ?: "10").toLong()) { App(session, remembered) }
        }
        App(session, remembered)
    }
}
