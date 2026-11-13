package krtc.meet

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import krtc.native.AndroidAudio
import krtc.native.AndroidCamera
import krtc.native.AndroidScreenCapture
import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory

/**
 * Android: libwebrtc has no audio device here, so every call gets a factory with synthetic audio that
 * [AndroidAudio] feeds from AudioRecord and plays through AudioTrack; cameras are opened with Camera2
 * ([AndroidCamera]). Needs the CAMERA and RECORD_AUDIO permissions. A screen share needs the user's consent:
 * [requestScreenConsent] shows the system dialog ([AndroidScreenCapture.consentIntent]) and returns its result,
 * or null if declined.
 */
class AndroidMediaPlatform(
    context: Context,
    private val requestScreenConsent: suspend () -> Pair<Int, Intent>?,
) : MediaPlatform {
    private val context = context.applicationContext
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override val sharedSystemAudio: Boolean = false

    override val settings: SettingsStore = PreferencesSettings(context)

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

    override fun screens(): List<ScreenChoice> = listOf(ScreenChoice(
        "Экран или приложение", note = "Что показывать — весь экран или одно приложение, — Android спросит в своём окне.",
    ))

    override suspend fun openScreen(
        factory: NativePeerConnectionFactory, choice: ScreenChoice, withAudio: Boolean, fps: Int, maxWidth: Int, maxHeight: Int,
    ): OpenedScreen? {
        val (code, data) = requestScreenConsent() ?: return null
        val video = factory.createScreencastSource()
        val audio = if (withAudio) factory.createAudioSource() else null
        return runCatching { AndroidScreenCapture(context, code, data, video, maxWidth = maxWidth, maxHeight = maxHeight, fps = fps, audio = audio).apply { start() } }
            .map { OpenedScreen(video, audio, it) }
            .getOrElse { video.close(); audio?.close(); null }
    }

    companion object {
        /** The system's screen capture dialog; its result goes to [requestScreenConsent]. */
        fun screenConsentIntent(activity: android.app.Activity): Intent = AndroidScreenCapture.consentIntent(activity)
    }
}
