package krtc.meet

import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeVideoSource
import krtc.native.nativeCameras

/** A camera opened for a call: its source, and whatever else must be closed with it (e.g. Android's Camera2 capture). */
class OpenedCamera(val source: NativeVideoSource, private val capture: AutoCloseable? = null) : AutoCloseable {
    override fun close() {
        capture?.close()
        source.close()
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
}

/** Desktop and iOS: libwebrtc's own audio devices and camera capture (krtc-native). */
object NativeMediaPlatform : MediaPlatform {
    override val sharedSystemAudio: Boolean = true

    override fun createFactory(testAudio: Boolean): NativePeerConnectionFactory =
        NativePeerConnectionFactory(audioDevice = if (testAudio) AudioDevice.SYNTHETIC else AudioDevice.PLATFORM)

    override fun cameras(): List<CameraChoice.Device> = nativeCameras().mapIndexed { i, name -> CameraChoice.Device(i, name) }

    override fun openCamera(factory: NativePeerConnectionFactory, camera: CameraChoice.Device): OpenedCamera? =
        factory.createCameraSource(camera.index, 1280, 720, 30)?.let(::OpenedCamera)
}
