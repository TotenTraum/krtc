package krtc.meet

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCValues
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/**
 * The app's screen, for an iOS host (an Xcode project's SwiftUI or UIKit code can show it). Defaults come from the
 * environment like on desktop (KRTC_MEET_*; with simctl: SIMCTL_CHILD_KRTC_MEET_*).
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
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
    remember { if (get("KRTC_MEET_AUTOJOIN") == "1") session.join(defaults) }
    App(session, defaults)
}

/** Standalone app entry (no Xcode project): UIKit with [MainViewController] as the root. */
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

    @kotlinx.cinterop.ObjCObjectBase.OverrideInit
    constructor() : super()

    private var appWindow: UIWindow? = null

    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
        appWindow = UIWindow(frame = UIScreen.mainScreen.bounds).apply {
            rootViewController = MainViewController()
            makeKeyAndVisible()
        }
        return true
    }
}
