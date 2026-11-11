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
    Window(onCloseRequest = { current?.close(); exitApplication() }, title = "krtc meet", state = state) {
        val scope = rememberCoroutineScope()
        val session = remember { MeetSession(scope).also { if (env["KRTC_MEET_LAYOUT"] == "speaker") it.setLayout(Layout.SPEAKER) } }.also { current = it }
        remember { if (env["KRTC_MEET_AUTOJOIN"] == "1") session.join(defaults) }
        env["KRTC_MEET_SNAPSHOT"]?.let { file ->
            Snapshot.schedule(1100, 760, file, (env["KRTC_MEET_SNAPSHOT_AFTER"] ?: "10").toLong()) { App(session, defaults) }
        }
        App(session, defaults)
    }
}
