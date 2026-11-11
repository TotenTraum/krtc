package krtc.meet

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import krtc.native.AndroidAudio
import krtc.native.AndroidCamera
import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory

/**
 * Android: libwebrtc has no audio device here, so every call gets a factory with synthetic audio that
 * [AndroidAudio] feeds from AudioRecord and plays through AudioTrack; cameras are opened with Camera2
 * ([AndroidCamera]). Needs the CAMERA and RECORD_AUDIO permissions.
 */
class AndroidMediaPlatform(context: Context) : MediaPlatform {
    private val context = context.applicationContext
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override val sharedSystemAudio: Boolean = false

    override fun createFactory(testAudio: Boolean): NativePeerConnectionFactory = NativePeerConnectionFactory(audioDevice = AudioDevice.SYNTHETIC)

    override fun startAudio(factory: NativePeerConnectionFactory, testAudio: Boolean): AutoCloseable? =
        if (testAudio) null else AndroidAudio(factory).apply { start() }

    override fun cameras(): List<CameraChoice.Device> = cameraManager.cameraIdList.mapIndexed { i, id ->
        val facing = when (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Фронтальная камера"
            CameraCharacteristics.LENS_FACING_BACK -> "Основная камера"
            else -> "Камера"
        }
        CameraChoice.Device(i, "$facing ($id)", id)
    }

    override fun openCamera(factory: NativePeerConnectionFactory, camera: CameraChoice.Device): OpenedCamera? {
        val source = factory.createVideoSource()
        return runCatching { AndroidCamera(context, source, camera.id.ifEmpty { null }).apply { start() } }
            .map { OpenedCamera(source, it) }
            .getOrElse { source.close(); null }
    }
}
