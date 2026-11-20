package krtc.meet

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.*
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSStringFromClass
import platform.UIKit.*

/**
 * The app's screen, for an iOS host (an Xcode project's SwiftUI or UIKit code can show it). Defaults come from the
 * environment like on desktop (KRTC_MEET_*; with simctl: SIMCTL_CHILD_KRTC_MEET_*).
 */
fun mainViewController(): UIViewController = ComposeUIViewController {
    val env = NSProcessInfo.processInfo.environment
    fun get(name: String): String? = env[name] as String?
    val defaults = JoinSettings(
        server = get("KRTC_MEET_SERVER") ?: "ws://localhost:8080",
        room = get("KRTC_MEET_ROOM") ?: "test",
        name = get("KRTC_MEET_NAME") ?: "iOS",
        camera = when (get("KRTC_MEET_CAMERA")) {
            "none" -> CameraChoice.None
            "test" -> CameraChoice.TestPattern
            else -> NativeMediaPlatform.cameras().firstOrNull() ?: CameraChoice.TestPattern
        },
        testAudio = get("KRTC_MEET_TEST_AUDIO") == "1",
    )
    val scope = rememberCoroutineScope()
    val session = remember { MeetSession(scope, NativeMediaPlatform) }
    val given = remember { mapOf("server" to "KRTC_MEET_SERVER", "room" to "KRTC_MEET_ROOM", "name" to "KRTC_MEET_NAME", "camera" to "KRTC_MEET_CAMERA", "testAudio" to "KRTC_MEET_TEST_AUDIO").filterValues { get(it) != null }.keys }
    val remembered = remember { session.rememberedJoin(defaults, given) }
    remember { if (get("KRTC_MEET_AUTOJOIN") == "1") session.join(remembered) }
    App(session, remembered)
}

/** Standalone app entry (no Xcode project): UIKit with [mainViewController] as the root. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun main(args: Array<String>) {
    memScoped {
        val argv = (arrayOf("krtc-meet") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool {
            UIApplicationMain(args.size + 1, argv, null, NSStringFromClass(AppDelegate))
        }
    }
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class AppDelegate : UIResponder, UIApplicationDelegateProtocol {
    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

    @OverrideInit
    constructor() : super()

    private var appWindow: UIWindow? = null

    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
        appWindow = UIWindow(frame = UIScreen.mainScreen.bounds).apply {
            rootViewController = mainViewController()
            makeKeyAndVisible()
        }
        return true
    }
}
