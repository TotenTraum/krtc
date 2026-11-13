package krtc.meet

import krtc.native.AudioDevice
import krtc.native.NativeAudioSource
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeVideoSource
import krtc.native.ScreenSource
import krtc.native.nativeCameras
import krtc.native.nativeScreenSources
import krtc.native.nativeScreenThumbnail
import krtc.native.VideoFrame

/** A camera opened for a call: its source, and whatever else must be closed with it (e.g. Android's Camera2 capture). */
class OpenedCamera(val source: NativeVideoSource, private val capture: AutoCloseable? = null) : AutoCloseable {
    override fun close() {
        capture?.close()
        source.close()
    }
}

/** Something to share: a display or a window ([source]), or null where the platform asks the user itself. */
/**
 * Something to share: a display or a window ([source]), or null where the platform asks the user itself. [note]
 * explains a missing list (no permission yet, or the system dialog that follows).
 */
class ScreenChoice(val label: String, val source: ScreenSource? = null, val note: String? = null) {
    val isWindow: Boolean get() = source?.isWindow == true
}

/** A running screen capture: its picture, its sound if asked for and available, and the capture itself. */
class OpenedScreen(val video: NativeVideoSource, val audio: NativeAudioSource?, private val capture: AutoCloseable? = null) : AutoCloseable {
    override fun close() {
        capture?.close()
        video.close()
        audio?.close()
    }
}

/** What differs between platforms: where the call's audio comes from and how cameras are listed and opened. */
interface MediaPlatform {
    /**
     * Whether one factory with the system's audio can serve the whole app: it lists microphones and speakers before a
     * call and keeps the choice across calls (desktop, iOS). Otherwise every call gets a factory of its own.
     */
    val sharedSystemAudio: Boolean

    /** A factory for a call; [testAudio]: a test tone, no sound hardware. */
    fun createFactory(testAudio: Boolean): NativePeerConnectionFactory

    /** Starts the platform's own microphone and speaker plumbing, if it needs one (Android); closed when the call ends. */
    fun startAudio(factory: NativePeerConnectionFactory, testAudio: Boolean): AutoCloseable? = null

    fun cameras(): List<CameraChoice.Device>

    /** Opens [camera] at about 1280 × 720; null if it cannot be opened (or the user denied access). */
    fun openCamera(factory: NativePeerConnectionFactory, camera: CameraChoice.Device): OpenedCamera?

    /** Displays and windows to share; a single choice without a source where the platform asks itself. */
    fun screens(): List<ScreenChoice>

    /** Whether builds of this platform are released for download (desktop, Android): the app looks for updates. */
    val updatable: Boolean get() = true

    /** Where the app's settings are saved. */
    val settings: SettingsStore get() = sharedDefaultSettings

    /** A still image of [choice] for the picker (call off the UI thread); null where there is none. */
    fun screenThumbnail(choice: ScreenChoice, maxWidth: Int, maxHeight: Int): VideoFrame? = null

    /**
     * Starts capturing [choice] at up to [fps] frames per second, scaled down to fit [maxWidth] × [maxHeight], with
     * the system's sound if [withAudio] and the platform has it; null if the capture cannot start (no permission, or
     * the user declined).
     */
    suspend fun openScreen(
        factory: NativePeerConnectionFactory, choice: ScreenChoice, withAudio: Boolean, fps: Int, maxWidth: Int, maxHeight: Int,
    ): OpenedScreen?
}

/** Desktop and iOS: libwebrtc's own audio devices and camera capture (krtc-native). */
object NativeMediaPlatform : MediaPlatform {
    override val sharedSystemAudio: Boolean = true

    // iOS builds are not released for download (only a simulator app in CI).
    override val updatable: Boolean = !isIos

    override fun createFactory(testAudio: Boolean): NativePeerConnectionFactory =
        NativePeerConnectionFactory(audioDevice = if (testAudio) AudioDevice.SYNTHETIC else AudioDevice.PLATFORM)

    override fun cameras(): List<CameraChoice.Device> = nativeCameras().mapIndexed { i, name -> CameraChoice.Device(i, name) }

    override fun openCamera(factory: NativePeerConnectionFactory, camera: CameraChoice.Device): OpenedCamera? =
        factory.createCameraSource(camera.index, 1280, 720, 30)?.let(::OpenedCamera)

    override fun screens(): List<ScreenChoice> {
        // Like Discord: windows with a title (not the menu bar's and panels' helper windows), and not this app's own.
        val sources = nativeScreenSources().filter { !it.isWindow || (it.title.isNotBlank() && !it.title.startsWith("Window ") && it.title != APP_WINDOW_TITLE) }
        // macOS before Screen Recording is permitted: no list; starting asks for the permission.
        if (sources.isEmpty()) return listOf(ScreenChoice(
            "Весь экран", note = "Список окон и экранов появится, когда разрешите запись экрана: Системные настройки → " +
                "Конфиденциальность и безопасность → Запись экрана → krtc-meet, затем перезапустите приложение.",
        ))
        var display = 0
        return sources.map { s ->
            ScreenChoice(if (s.isWindow) "Окно: ${s.title.ifBlank { s.id.toString() }}" else s.title.ifBlank { "Экран ${++display}" }, s)
        }
    }

    override fun screenThumbnail(choice: ScreenChoice, maxWidth: Int, maxHeight: Int): VideoFrame? =
        choice.source?.let { nativeScreenThumbnail(it, maxWidth, maxHeight) }

    override suspend fun openScreen(
        factory: NativePeerConnectionFactory, choice: ScreenChoice, withAudio: Boolean, fps: Int, maxWidth: Int, maxHeight: Int,
    ): OpenedScreen? {
        // Starting a capture waits for the system (ScreenCaptureKit, permissions): not on the UI thread.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val video = factory.createScreenSource(choice.source, fps = fps, maxWidth = maxWidth, maxHeight = maxHeight) ?: return@withContext null
            OpenedScreen(video, if (withAudio) factory.createSystemAudioSource() else null)
        }
    }
}

/** The desktop window's title; its own window is left out of the screen share picker. */
const val APP_WINDOW_TITLE: String = "krtc meet"

/** Whether this is iOS (its builds are not released for download). */
expect val isIos: Boolean
