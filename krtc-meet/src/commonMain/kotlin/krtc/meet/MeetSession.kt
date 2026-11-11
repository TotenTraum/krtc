package krtc.meet

import krtc.client.RemoteParticipant
import krtc.client.RoomClient
import krtc.client.RoomState
import krtc.core.MediaStreamTrack
import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeTrack
import krtc.native.NativeVideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the user picked on the join screen. */
data class JoinSettings(
    val server: String,
    val room: String,
    val name: String,
    val camera: CameraChoice,
    /** No sound hardware: a test tone instead of the microphone, remote audio not played (testing, several instances). */
    val testAudio: Boolean,
)

sealed class CameraChoice {
    data object None : CameraChoice()
    data object TestPattern : CameraChoice()
    /** A camera by index in [MediaPlatform.cameras]; [id] is the platform's own id where it has one (Camera2). */
    data class Device(val index: Int, val name: String, val id: String = "") : CameraChoice()
}

sealed class CallPhase {
    data object Idle : CallPhase()
    data object Joining : CallPhase()
    data class InCall(val room: String) : CallPhase()
    data class Ended(val reason: String?) : CallPhase()
}

/**
 * One call: the factory, local tracks and the [RoomClient], with the state the screens show. Main thread only
 * (the flows may be collected anywhere).
 */
class MeetSession(private val scope: CoroutineScope, private val platform: MediaPlatform = NativeMediaPlatform) {
    private val phaseFlow = MutableStateFlow<CallPhase>(CallPhase.Idle)
    private val participantsFlow = MutableStateFlow<List<RemoteParticipant>>(emptyList())
    private val localVideoFlow = MutableStateFlow<MediaStreamTrack?>(null)
    private val micOnFlow = MutableStateFlow(true)
    private val cameraOnFlow = MutableStateFlow(true)
    private val selfSpeakingFlow = MutableStateFlow(false)

    val phase: StateFlow<CallPhase> = phaseFlow.asStateFlow()
    val participants: StateFlow<List<RemoteParticipant>> = participantsFlow.asStateFlow()
    val localVideo: StateFlow<MediaStreamTrack?> = localVideoFlow.asStateFlow()
    val micOn: StateFlow<Boolean> = micOnFlow.asStateFlow()
    val cameraOn: StateFlow<Boolean> = cameraOnFlow.asStateFlow()
    val selfSpeaking: StateFlow<Boolean> = selfSpeakingFlow.asStateFlow()
    var settings: JoinSettings? = null
        private set

    // Where the platform allows it, system audio lives as long as the app: it lists the devices before a call, and
    // the chosen microphone and speakers stay chosen across calls. Otherwise (Android, test audio) every call gets a
    // factory of its own.
    private var platformFactory: NativePeerConnectionFactory? = null
    private val inputsFlow = MutableStateFlow<List<String>>(emptyList())
    private val outputsFlow = MutableStateFlow<List<String>>(emptyList())
    private val inputFlow = MutableStateFlow<Int?>(null)
    private val outputFlow = MutableStateFlow<Int?>(null)

    /** Microphones and speakers (platform audio), and the chosen ones (null: the system's default). */
    val audioInputs: StateFlow<List<String>> = inputsFlow.asStateFlow()
    val audioOutputs: StateFlow<List<String>> = outputsFlow.asStateFlow()
    val audioInput: StateFlow<Int?> = inputFlow.asStateFlow()
    val audioOutput: StateFlow<Int?> = outputFlow.asStateFlow()

    private fun platform(): NativePeerConnectionFactory =
        platformFactory ?: platform.createFactory(testAudio = false).also { platformFactory = it }

    /** Cameras to choose from on the join screen. */
    fun cameras(): List<CameraChoice.Device> = platform.cameras()

    /** Whether microphones and speakers can be chosen (desktop, iOS). */
    val audioDeviceChoice: Boolean get() = platform.sharedSystemAudio

    /** Reads the device lists again (e.g. after plugging in a headset). */
    fun refreshAudioDevices() {
        if (!platform.sharedSystemAudio) return
        val f = platform()
        inputsFlow.value = f.audioInputs
        outputsFlow.value = f.audioOutputs
    }

    /** Switches the microphone; works during a call too. */
    fun selectAudioInput(index: Int) {
        if (platform.sharedSystemAudio && platform().setAudioInput(index)) inputFlow.value = index
    }

    /** Switches the speakers or headphones; works during a call too. */
    fun selectAudioOutput(index: Int) {
        if (platform.sharedSystemAudio && platform().setAudioOutput(index)) outputFlow.value = index
    }

    /** Releases platform audio (when the app closes). */
    fun close() {
        end(null)
        platformFactory?.close()
        platformFactory = null
    }

    private var factory: NativePeerConnectionFactory? = null
    private var callFactory: NativePeerConnectionFactory? = null // test audio only; closed when the call ends
    private var client: RoomClient? = null
    private var mic: NativeTrack? = null
    private var camera: NativeTrack? = null
    private var openedCamera: OpenedCamera? = null
    private var audioPlumbing: AutoCloseable? = null
    private var watch: Job? = null

    fun join(settings: JoinSettings) {
        if (phaseFlow.value is CallPhase.Joining || phaseFlow.value is CallPhase.InCall) return
        this.settings = settings
        phaseFlow.value = CallPhase.Joining
        scope.launch {
            try {
                val shared = !settings.testAudio && platform.sharedSystemAudio
                val f = if (shared) platform() else platform.createFactory(settings.testAudio).also { callFactory = it }
                factory = f
                if (settings.testAudio) f.setTestTone(true)
                audioPlumbing = platform.startAudio(f, settings.testAudio)
                mic = f.createAudioTrack("mic")
                camera = when (val c = settings.camera) {
                    CameraChoice.None -> null
                    CameraChoice.TestPattern -> f.createTestVideoTrack("camera", 1280, 720, 30)
                    is CameraChoice.Device -> platform.openCamera(f, c)?.let { opened ->
                        openedCamera = opened
                        f.createVideoTrack(opened.source, "camera")
                    }
                }
                localVideoFlow.value = camera
                micOnFlow.value = true
                cameraOnFlow.value = camera != null
                val c = RoomClient(settings.server, f)
                client = c
                c.join(settings.room, settings.name, microphone = mic, camera = camera)
                watch = scope.launch {
                    launch { c.participants.collect { participantsFlow.value = it } }
                    launch { c.selfSpeaking.collect { selfSpeakingFlow.value = it } }
                    c.state.collectLatest { s ->
                        when (s) {
                            is RoomState.Connected -> phaseFlow.value = CallPhase.InCall(s.room)
                            is RoomState.Disconnected -> if (phaseFlow.value !is CallPhase.Ended) end(s.reason)
                            else -> {}
                        }
                    }
                }
            } catch (t: Throwable) {
                end(t.message ?: t.toString())
            }
        }
    }

    fun toggleMic() {
        val track = mic ?: return
        track.enabled = !track.enabled
        micOnFlow.value = track.enabled
    }

    fun toggleCamera() {
        val track = camera ?: return
        track.enabled = !track.enabled // a disabled track sends black frames
        cameraOnFlow.value = track.enabled
    }

    fun leave() = end("left")

    /** Back to the join screen after a call ended. */
    fun reset() {
        if (phaseFlow.value is CallPhase.Ended) phaseFlow.value = CallPhase.Idle
    }

    private fun end(reason: String?) {
        phaseFlow.value = CallPhase.Ended(reason)
        watch?.cancel()
        watch = null
        client?.leave()
        client = null
        participantsFlow.value = emptyList()
        localVideoFlow.value = null
        openedCamera?.close()
        openedCamera = null
        audioPlumbing?.close()
        audioPlumbing = null
        camera?.release()
        camera = null
        mic?.release()
        mic = null
        factory = null
        callFactory?.close()
        callFactory = null
    }
}
