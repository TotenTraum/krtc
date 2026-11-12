package krtc.meet

import krtc.native.AudioDevice
import krtc.native.NativeAudioSource
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeVideoSource
import krtc.native.ScreenSource
import krtc.native.nativeCameras
import krtc.native.nativeScreenSources

/** A camera opened for a call: its source, and whatever else must be closed with it (e.g. Android's Camera2 capture). */
class OpenedCamera(val source: NativeVideoSource, private val capture: AutoCloseable? = null) : AutoCloseable {
    override fun close() {
        capture?.close()
        source.close()
    }
}

/** Something to share: a display or a window ([source]), or null where the platform asks the user itself. */
class ScreenChoice(val label: String, val source: ScreenSource? = null)

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

    override fun createFactory(testAudio: Boolean): NativePeerConnectionFactory =
        NativePeerConnectionFactory(audioDevice = if (testAudio) AudioDevice.SYNTHETIC else AudioDevice.PLATFORM)

    override fun cameras(): List<CameraChoice.Device> = nativeCameras().mapIndexed { i, name -> CameraChoice.Device(i, name) }

    override fun openCamera(factory: NativePeerConnectionFactory, camera: CameraChoice.Device): OpenedCamera? =
        factory.createCameraSource(camera.index, 1280, 720, 30)?.let(::OpenedCamera)

    override fun screens(): List<ScreenChoice> {
        val sources = nativeScreenSources()
        if (sources.isEmpty()) return listOf(ScreenChoice("Весь экран")) // macOS before Screen Recording is permitted: asks
        var display = 0
        return sources.map { s ->
            ScreenChoice(if (s.isWindow) "Окно: ${s.title.ifBlank { s.id.toString() }}" else s.title.ifBlank { "Экран ${++display}" }, s)
        }
    }

    override suspend fun openScreen(
        factory: NativePeerConnectionFactory, choice: ScreenChoice, withAudio: Boolean, fps: Int, maxWidth: Int, maxHeight: Int,
    ): OpenedScreen? {
        val video = factory.createScreenSource(choice.source, fps = fps, maxWidth = maxWidth, maxHeight = maxHeight) ?: return null
        return OpenedScreen(video, if (withAudio) factory.createSystemAudioSource() else null)
    }
}
