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
    data class Device(val index: Int, val name: String) : CameraChoice()
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
class MeetSession(private val scope: CoroutineScope) {
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

    private var factory: NativePeerConnectionFactory? = null
    private var client: RoomClient? = null
    private var mic: NativeTrack? = null
    private var camera: NativeTrack? = null
    private var cameraSource: NativeVideoSource? = null
    private var watch: Job? = null

    fun join(settings: JoinSettings) {
        if (phaseFlow.value is CallPhase.Joining || phaseFlow.value is CallPhase.InCall) return
        this.settings = settings
        phaseFlow.value = CallPhase.Joining
        scope.launch {
            try {
                val f = NativePeerConnectionFactory(audioDevice = if (settings.testAudio) AudioDevice.SYNTHETIC else AudioDevice.PLATFORM)
                factory = f
                if (settings.testAudio) f.setTestTone(true)
                mic = f.createAudioTrack("mic")
                camera = when (val c = settings.camera) {
                    CameraChoice.None -> null
                    CameraChoice.TestPattern -> f.createTestVideoTrack("camera", 1280, 720, 30)
                    is CameraChoice.Device -> f.createCameraSource(c.index, 1280, 720, 30)?.let { s ->
                        cameraSource = s
                        f.createVideoTrack(s, "camera")
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
        cameraSource?.close()
        cameraSource = null
        camera?.release()
        camera = null
        mic?.release()
        mic = null
        factory?.close()
        factory = null
    }
}
