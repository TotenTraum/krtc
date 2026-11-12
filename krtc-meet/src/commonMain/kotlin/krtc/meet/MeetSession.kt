package krtc.meet

import krtc.client.RemoteParticipant
import krtc.client.RoomClient
import krtc.client.RoomState
import krtc.client.VideoQuality
import krtc.core.MediaStreamTrack
import krtc.native.AudioDevice
import krtc.native.InputGate
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeTrack
import krtc.native.NativeVideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

/** Processing of the microphone (krtc-native `setAudioProcessing`); all on by default. */
data class AudioProcessing(
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    /** Adaptive input: keeps quiet and loud voices at one level. */
    val autoGainControl: Boolean = true,
)

/** How the call's connection is doing, from `getStats` every few seconds. */
enum class LinkQuality { GOOD, FAIR, POOR }

/**
 * [rttMillis]: round trip to the SFU; [lossPercent]: media lost on the way here over the last interval;
 * [peers]: the quality of what each participant's media looks like on arrival (their uplink plus ours).
 */
data class ConnectionInfo(val quality: LinkQuality, val rttMillis: Int?, val lossPercent: Double, val peers: Map<String, LinkQuality>)

enum class Layout { GRID, SPEAKER }

/** When the microphone transmits (while it is not muted). */
enum class InputMode {
    /** Always. */
    OPEN,

    /** While the input level is above [InputSettings.threshold], and a moment after. */
    VOICE_ACTIVITY,

    /** While the push-to-talk key or button is held. */
    PUSH_TO_TALK,
}

/** [threshold]: the input level (0..1, as in `getStats` audioLevel) that opens the microphone with [InputMode.VOICE_ACTIVITY]. */
data class InputSettings(val mode: InputMode = InputMode.OPEN, val threshold: Float = 0.05f)

/** Short sounds for what happens in the call. */
enum class Cue { JOIN, LEAVE, MUTE, UNMUTE, DEAFEN, UNDEAFEN }

/** Plays [cue] on the device's speakers, without blocking. */
expect fun playCue(cue: Cue)

/** A chat line; [from] is null for the server, [mine] for this user's own lines. */
data class ChatMessage(val from: String?, val text: String, val mine: Boolean = false)

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

    private val screenFlow = MutableStateFlow<MediaStreamTrack?>(null)
    private val chatFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val unreadFlow = MutableStateFlow(0)
    private val chatOpenFlow = MutableStateFlow(false)
    private val noticeFlow = MutableStateFlow<String?>(null)
    private val volumesFlow = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val processingFlow = MutableStateFlow(AudioProcessing())

    private val screenFpsFlow = MutableStateFlow(DEFAULT_SCREEN_FPS)

    /**
     * Frame rate of the next screen share ([SCREEN_FPS_CHOICES]). Higher is smoother for video and scrolling but
     * costs bandwidth and CPU; the encoder still lowers it first when the link cannot carry it, to keep text sharp.
     */
    val screenFps: StateFlow<Int> = screenFpsFlow.asStateFlow()

    fun setScreenFps(fps: Int) {
        screenFpsFlow.value = fps.coerceIn(1, 60)
    }

    private val screenHeightFlow = MutableStateFlow(DEFAULT_SCREEN_HEIGHT)

    /**
     * Resolution of the next screen share, as a height ([SCREEN_HEIGHT_CHOICES]; [SOURCE_HEIGHT]: as captured, up to
     * 4K). Lower is lighter for everyone watching.
     */
    val screenHeight: StateFlow<Int> = screenHeightFlow.asStateFlow()

    fun setScreenHeight(height: Int) {
        screenHeightFlow.value = height
    }

    private val expandedFlow = MutableStateFlow<String?>(null)

    /** The tile ([tileKey]) that fills the whole window, e.g. a stream being watched; null: the normal layout. */
    val expanded: StateFlow<String?> = expandedFlow.asStateFlow()

    fun setExpanded(key: String?) {
        expandedFlow.value = key
    }

    /** This user's screen share, while it runs. */
    val screen: StateFlow<MediaStreamTrack?> = screenFlow.asStateFlow()
    val chat: StateFlow<List<ChatMessage>> = chatFlow.asStateFlow()

    /** Chat lines that arrived while the chat was closed. */
    val unread: StateFlow<Int> = unreadFlow.asStateFlow()
    val chatOpen: StateFlow<Boolean> = chatOpenFlow.asStateFlow()

    /** A short message for the user (e.g. a screen share that could not start); cleared by [dismissNotice]. */
    val notice: StateFlow<String?> = noticeFlow.asStateFlow()

    private val inputFlowSettings = MutableStateFlow(InputSettings())
    private val inputLevelFlow = MutableStateFlow(0f)
    private val transmittingFlow = MutableStateFlow(false)
    private val deafenedFlow = MutableStateFlow(false)
    private val cuesFlow = MutableStateFlow(true)

    /** When the microphone transmits: always, by voice or by push-to-talk. */
    val inputSettings: StateFlow<InputSettings> = inputFlowSettings.asStateFlow()

    /** The microphone's level (0..1) a few times a second, also while it does not transmit. */
    val inputLevel: StateFlow<Float> = inputLevelFlow.asStateFlow()

    /** Whether the microphone is sending right now: not muted, and its [InputMode] lets it. */
    val transmitting: StateFlow<Boolean> = transmittingFlow.asStateFlow()

    /** The room's sound is off for this user, and so is the microphone; the others see it. */
    val deafened: StateFlow<Boolean> = deafenedFlow.asStateFlow()

    /** Whether join, leave and mute sounds play. */
    val cues: StateFlow<Boolean> = cuesFlow.asStateFlow()

    private val reconnectingFlow = MutableStateFlow<Int?>(null)
    private val connectionFlow = MutableStateFlow<ConnectionInfo?>(null)
    private val layoutFlow = MutableStateFlow(Layout.GRID)
    private val pinnedFlow = MutableStateFlow<String?>(null)
    private val activeSpeakerFlow = MutableStateFlow<String?>(null)
    private val sharingFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Participants whose screen share is on. */
    val sharing: StateFlow<Set<String>> = sharingFlow.asStateFlow()

    /** Reconnect attempt while the signaling connection is down (media may still flow), null otherwise. */
    val reconnecting: StateFlow<Int?> = reconnectingFlow.asStateFlow()
    val connection: StateFlow<ConnectionInfo?> = connectionFlow.asStateFlow()
    val layout: StateFlow<Layout> = layoutFlow.asStateFlow()

    /** The tile shown large in [Layout.SPEAKER] whatever happens ([tileKey]); null: follow screens and speakers. */
    val pinned: StateFlow<String?> = pinnedFlow.asStateFlow()

    /** The participant who spoke last. */
    val activeSpeaker: StateFlow<String?> = activeSpeakerFlow.asStateFlow()

    /** Playout volume per participant ([volumeKey]): 1 as received, 0 silent, up to [MAX_VOLUME]. */
    val volumes: StateFlow<Map<String, Float>> = volumesFlow.asStateFlow()
    val audioProcessing: StateFlow<AudioProcessing> = processingFlow.asStateFlow()

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

    /** The room client of the current call (tests and diagnostics). */
    internal val roomClient: RoomClient? get() = client
    private var mic: NativeTrack? = null
    private var camera: NativeTrack? = null
    private var openedCamera: OpenedCamera? = null
    private var audioPlumbing: AutoCloseable? = null
    private var watch: Job? = null
    private var joining: Job? = null
    private var openedScreen: OpenedScreen? = null
    private var screenTrack: NativeTrack? = null
    private var screenAudioTrack: NativeTrack? = null

    fun join(settings: JoinSettings) {
        if (phaseFlow.value is CallPhase.Joining || phaseFlow.value is CallPhase.InCall) return
        this.settings = settings
        phaseFlow.value = CallPhase.Joining
        joining = scope.launch {
            try {
                val shared = !settings.testAudio && platform.sharedSystemAudio
                val f = if (shared) platform() else platform.createFactory(settings.testAudio).also { callFactory = it }
                factory = f
                if (settings.testAudio) f.setTestTone(true)
                applyProcessing(f, processingFlow.value)
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
                deafenedFlow.value = false
                pttHeld = false
                applyGate()
                cameraOnFlow.value = camera != null
                val c = RoomClient(settings.server, f)
                client = c
                c.setMediaState(audioMuted = false, videoMuted = camera == null)
                c.join(settings.room, settings.name, microphone = mic, camera = camera)
                watch = scope.launch {
                    val watched = HashSet<String>()
                    launch {
                        c.participants.collect { list ->
                            val before = participantsFlow.value.map { it.id }.toSet()
                            val now = list.map { it.id }.toSet()
                            // Not for the people already there on joining, nor while reconnecting.
                            if (phaseFlow.value is CallPhase.InCall && reconnectingFlow.value == null) {
                                if ((now - before).isNotEmpty()) cue(Cue.JOIN) else if ((before - now).isNotEmpty()) cue(Cue.LEAVE)
                            }
                            participantsFlow.value = list
                            for (p in list) if (watched.add(p.id)) {
                                launch { applyVolumes(p) }
                                launch { p.speaking.collect { if (it) activeSpeakerFlow.value = p.id } }
                                launch { p.screen.collect { t -> sharingFlow.update { if (t != null) it + p.id else it - p.id } } }
                            }
                            val keys = list.flatMap { listOf(tileKey(it.id, false), tileKey(it.id, true)) } + listOf(SELF, SELF_SCREEN)
                            if (pinnedFlow.value?.let { it !in keys } == true) pinnedFlow.value = null
                        }
                    }
                    launch { watchConnection(c) }
                    launch { watchInput() }
                    launch { c.selfSpeaking.collect { selfSpeakingFlow.value = it } }
                    launch {
                        c.data.collect { d ->
                            if (d.topic != CHAT_TOPIC || !d.reliable) return@collect
                            val name = d.from?.let { id -> c.participants.value.firstOrNull { it.id == id }?.name ?: id }
                            chatFlow.update { it + ChatMessage(name, d.payload.decodeToString()) }
                            if (!chatOpenFlow.value) unreadFlow.update { it + 1 }
                        }
                    }
                    c.state.collectLatest { s ->
                        when (s) {
                            is RoomState.Connected -> {
                                reconnectingFlow.value = null
                                phaseFlow.value = CallPhase.InCall(s.room)
                            }
                            is RoomState.Reconnecting -> reconnectingFlow.value = s.attempt
                            is RoomState.Disconnected -> if (phaseFlow.value !is CallPhase.Ended) end(s.reason)
                            else -> {}
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c // the call ended while joining: end() released everything
            } catch (t: Throwable) {
                end(t.message ?: t.toString())
            }
        }
    }

    fun toggleMic() {
        if (mic == null) return
        if (deafenedFlow.value) deafenedFlow.value = false // unmuting also ends deafen: you want to be heard and to hear
        else micOnFlow.value = !micOnFlow.value
        cue(if (micOnFlow.value) Cue.UNMUTE else Cue.MUTE)
        applyGate()
        announceMediaState()
    }

    /** Turns the room's sound off (and the microphone with it) or back on; the microphone returns to how it was. */
    fun toggleDeafen() {
        deafenedFlow.value = !deafenedFlow.value
        cue(if (deafenedFlow.value) Cue.DEAFEN else Cue.UNDEAFEN)
        applyGate()
        announceMediaState()
    }

    fun setInputSettings(settings: InputSettings) {
        inputFlowSettings.value = settings.copy(threshold = settings.threshold.coerceIn(0f, 1f))
        applyGate()
    }

    /** Push-to-talk: the key or button went down or up. */
    fun setPushToTalk(held: Boolean) {
        if (pttHeld == held) return
        pttHeld = held
        if (!held) pttReleasedAt = clock.markNow()
        applyGate()
        if (!held && inputFlowSettings.value.mode == InputMode.PUSH_TO_TALK) {
            scope.launch { kotlinx.coroutines.delay(PTT_RELEASE_MILLIS + 20); applyGate() } // close after the tail
        }
    }

    fun setCues(enabled: Boolean) {
        cuesFlow.value = enabled
    }

    private fun cue(cue: Cue) {
        // Not with test audio: that mode is for running without sound hardware.
        if (cuesFlow.value && settings?.testAudio == false) runCatching { playCue(cue) }
    }

    private val clock = kotlin.time.TimeSource.Monotonic
    private var pttHeld = false
    private var pttReleasedAt: kotlin.time.TimeMark? = null

    /**
     * Applies mute, deafen and the input mode: the track is disabled only for mute and deafen; the input mode is
     * the factory's gate, which silences the microphone natively, frame by frame.
     */
    private fun applyGate() {
        val track = mic ?: return
        val f = factory ?: return
        val allowed = micOnFlow.value && !deafenedFlow.value
        if (track.enabled != allowed) track.enabled = allowed
        val input = inputFlowSettings.value
        when (input.mode) {
            InputMode.OPEN -> f.setInputGate(InputGate.OPEN)
            InputMode.VOICE_ACTIVITY -> f.setInputGate(InputGate.VOICE_ACTIVITY, input.threshold.toDouble(), VOICE_HOLD_MILLIS.toInt())
            // A short tail, so the end of the last word is not cut off.
            InputMode.PUSH_TO_TALK -> {
                val open = pttHeld || pttReleasedAt?.let { it.elapsedNow().inWholeMilliseconds < PTT_RELEASE_MILLIS } == true
                f.setInputGate(if (open) InputGate.OPEN else InputGate.CLOSED)
            }
        }
        transmittingFlow.value = allowed && f.inputGateOpen
    }

    /** Follows the microphone's level and the gate, for the meter and the "transmitting" indicator. */
    private suspend fun watchInput() {
        while (true) {
            kotlinx.coroutines.delay(LEVEL_POLL_MILLIS)
            val f = factory ?: continue
            inputLevelFlow.value = f.inputLevel.toFloat()
            transmittingFlow.value = micOnFlow.value && !deafenedFlow.value && f.inputGateOpen
        }
    }

    fun toggleCamera() {
        val track = camera ?: return
        track.enabled = !track.enabled // a disabled track sends black frames; the others show the camera as off
        cameraOnFlow.value = track.enabled
        announceMediaState()
    }

    private fun announceMediaState() {
        val c = client ?: return
        val deaf = deafenedFlow.value
        scope.launch { runCatching { c.setMediaState(audioMuted = !micOnFlow.value || deaf, videoMuted = camera == null || !cameraOnFlow.value, deafened = deaf) } }
    }

    // ---------------------------------------------------------------- screen share

    /** Displays and windows to share. */
    fun screens(): List<ScreenChoice> = platform.screens()

    /** Shares [choice] (with the system's sound if [withAudio]); replaces a running share. */
    fun startScreen(choice: ScreenChoice, withAudio: Boolean) {
        val f = factory ?: return
        val c = client ?: return
        scope.launch {
            val height = screenHeightFlow.value
            val opened = runCatching { platform.openScreen(f, choice, withAudio, screenFpsFlow.value, height * 16 / 9, height) }.getOrNull()
            if (opened == null) {
                noticeFlow.value = "Не удалось начать показ экрана: нет разрешения или источник недоступен"
                return@launch
            }
            if (client !== c) return@launch opened.close() // the call ended meanwhile
            val video = f.createVideoTrack(opened.video, "screen")
            val audio = opened.audio?.let { f.createAudioTrack(it, "screen-audio") }
            if (withAudio && audio == null) noticeFlow.value = "Звук экрана на этой платформе недоступен, показ без звука"
            runCatching { c.setScreen(video, audio) }.onFailure {
                video.release(); audio?.release(); opened.close()
                noticeFlow.value = "Не удалось начать показ экрана: ${it.message}"
                return@launch
            }
            releaseScreen()
            openedScreen = opened
            screenTrack = video
            screenAudioTrack = audio
            screenFlow.value = video
        }
    }

    fun stopScreen() {
        val c = client
        scope.launch {
            runCatching { c?.setScreen(null) }
            releaseScreen()
        }
    }

    private fun releaseScreen() {
        screenFlow.value = null
        screenTrack?.release()
        screenTrack = null
        screenAudioTrack?.release()
        screenAudioTrack = null
        openedScreen?.close()
        openedScreen = null
    }

    /** Starts or stops receiving [participant]'s screen share; while off it costs no bandwidth. */
    fun setScreenSubscribed(participant: RemoteParticipant, enabled: Boolean) {
        val c = client ?: return
        scope.launch { runCatching { c.setSubscribed(participant.id, enabled) } }
    }

    // ---------------------------------------------------------------- chat

    fun sendChat(text: String): Boolean {
        val line = text.trim()
        if (line.isEmpty()) return false
        val c = client ?: return false
        if (!c.sendData(line.encodeToByteArray(), CHAT_TOPIC)) {
            noticeFlow.value = "Чат ещё не подключён"
            return false
        }
        chatFlow.update { it + ChatMessage(settings?.name, line, mine = true) }
        return true
    }

    fun setChatOpen(open: Boolean) {
        chatOpenFlow.value = open
        if (open) unreadFlow.value = 0
    }

    fun dismissNotice() {
        noticeFlow.value = null
    }

    // ---------------------------------------------------------------- layout, simulcast, connection quality

    fun setLayout(layout: Layout) {
        layoutFlow.value = layout
    }

    /** Pins a tile ([tileKey]) as the large one, or unpins it if it already is. */
    fun togglePin(key: String) {
        pinnedFlow.value = if (pinnedFlow.value == key) null else key
        if (pinnedFlow.value != null) layoutFlow.value = Layout.SPEAKER
    }

    private val requestedQuality = HashMap<String, VideoQuality>()

    /**
     * How tall [participant]'s camera tile is on screen, in pixels (0: not shown). The SFU then sends the simulcast
     * layer that fills it: small tiles get the low one, which saves the bandwidth and the decoding and copying of
     * frames nobody sees at full size.
     */
    fun setTileHeight(participant: RemoteParticipant, heightPx: Int) {
        val quality = when {
            heightPx <= LOW_LAYER_MAX_PX -> VideoQuality.LOW
            heightPx <= MEDIUM_LAYER_MAX_PX -> VideoQuality.MEDIUM
            else -> VideoQuality.HIGH
        }
        if (requestedQuality[participant.id] == quality) return
        requestedQuality[participant.id] = quality
        val c = client ?: return
        scope.launch { runCatching { c.setVideoQuality(participant.id, quality) } }
    }

    /** Samples `getStats` every [STATS_INTERVAL_MILLIS] into [connection]. */
    private suspend fun watchConnection(c: RoomClient) {
        var previous: Map<String, Pair<Long, Long>> = emptyMap() // inbound-rtp id -> (received, lost)
        while (true) {
            kotlinx.coroutines.delay(STATS_INTERVAL_MILLIS)
            val pc = c.peerConnection ?: continue
            val report = runCatching { pc.getStats() }.getOrNull() ?: continue
            val rtt = report.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                ?.members?.get("currentRoundTripTime")?.let { (it as? Number)?.toDouble() }?.let { (it * 1000).toInt() }
            val now = HashMap<String, Pair<Long, Long>>()
            var received = 0L
            var lost = 0L
            val perPeer = HashMap<String, Pair<Long, Long>>()
            for (s in report.values) {
                if (s.type != "inbound-rtp") continue
                val r = (s.members["packetsReceived"] as? Number)?.toLong() ?: continue
                val l = ((s.members["packetsLost"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0)
                now[s.id] = r to l
                val (r0, l0) = previous[s.id] ?: (0L to 0L)
                val dr = (r - r0).coerceAtLeast(0)
                val dl = (l - l0).coerceAtLeast(0)
                received += dr
                lost += dl
                val peer = (s.members["mid"] as? String)?.let(c::participantOfMid) ?: continue
                val (pr, pl) = perPeer[peer] ?: (0L to 0L)
                perPeer[peer] = (pr + dr) to (pl + dl)
            }
            val hadMedia = previous.isNotEmpty()
            previous = now
            fun loss(r: Long, l: Long) = if (r + l == 0L) 0.0 else l * 100.0 / (r + l)
            val total = loss(received, lost)
            // Nothing arriving at all (the network is down) is no loss to measure, but the worst quality there is.
            val stalled = hadMedia && now.isNotEmpty() && received == 0L
            connectionFlow.value = ConnectionInfo(
                quality = if (stalled) LinkQuality.POOR else grade(rtt, total),
                rttMillis = rtt,
                lossPercent = total,
                peers = perPeer.mapValues { (_, v) -> if (stalled) LinkQuality.POOR else grade(rtt, loss(v.first, v.second)) },
            )
        }
    }

    private fun grade(rttMillis: Int?, lossPercent: Double): LinkQuality = when {
        lossPercent >= 8 || (rttMillis ?: 0) >= 600 -> LinkQuality.POOR
        lossPercent >= 2 || (rttMillis ?: 0) >= 300 -> LinkQuality.FAIR
        else -> LinkQuality.GOOD
    }

    // ---------------------------------------------------------------- volume and processing

    /** Sets the playout volume of a participant's voice, or of its screen's sound with [screen]. */
    fun setVolume(participant: RemoteParticipant, volume: Float, screen: Boolean = false) {
        volumesFlow.update { it + (volumeKey(participant.id, screen) to volume.coerceIn(0f, MAX_VOLUME)) }
    }

    /** Applies the chosen volumes to the participant's tracks, now and whenever they change. */
    private suspend fun applyVolumes(p: RemoteParticipant) {
        kotlinx.coroutines.coroutineScope {
            for (screen in listOf(false, true)) launch {
                val key = volumeKey(p.id, screen)
                combine(if (screen) p.screenAudio else p.audio, volumesFlow.map { it[key] ?: 1f }, deafenedFlow) { t, v, deaf -> t to (if (deaf) 0f else v) }
                    .collect { (t, v) -> (t as? NativeTrack)?.setVolume(v.toDouble()) }
            }
        }
    }

    /**
     * Changes the microphone processing. Applies at once; during a call the microphone track is also recreated, so
     * libwebrtc takes the new options for good.
     */
    fun setAudioProcessing(processing: AudioProcessing) {
        processingFlow.value = processing
        platformFactory?.let { applyProcessing(it, processing) }
        val f = factory ?: return
        applyProcessing(f, processing)
        val c = client ?: return
        val old = mic ?: return
        val fresh = f.createAudioTrack("mic").also { it.enabled = old.enabled }
        mic = fresh
        applyGate()
        scope.launch {
            runCatching { c.setMicrophone(fresh) }
            old.release()
        }
    }

    private fun applyProcessing(f: NativePeerConnectionFactory, p: AudioProcessing) =
        f.setAudioProcessing(p.echoCancellation, p.noiseSuppression, p.autoGainControl)

    fun leave() = end("left")

    /** Back to the join screen after a call ended. */
    fun reset() {
        if (phaseFlow.value is CallPhase.Ended) phaseFlow.value = CallPhase.Idle
    }

    private fun end(reason: String?) {
        phaseFlow.value = CallPhase.Ended(reason)
        joining?.cancel() // stops at its next suspension, before it uses what is released below
        joining = null
        watch?.cancel()
        watch = null
        client?.leave()
        client = null
        releaseScreen()
        participantsFlow.value = emptyList()
        chatFlow.value = emptyList()
        unreadFlow.value = 0
        volumesFlow.value = emptyMap()
        noticeFlow.value = null
        reconnectingFlow.value = null
        transmittingFlow.value = false
        inputLevelFlow.value = 0f
        connectionFlow.value = null
        pinnedFlow.value = null
        expandedFlow.value = null
        activeSpeakerFlow.value = null
        sharingFlow.value = emptySet()
        requestedQuality.clear()
        localVideoFlow.value = null
        openedCamera?.close()
        openedCamera = null
        audioPlumbing?.close()
        audioPlumbing = null
        camera?.release()
        camera = null
        mic?.release()
        mic = null
        runCatching { factory?.setInputGate(InputGate.OPEN) } // the shared factory outlives the call
        factory = null
        callFactory?.close()
        callFactory = null
    }

    companion object {
        /** Data channel topic of chat lines: UTF-8 text, sent reliably. */
        const val CHAT_TOPIC: String = "chat"
        const val MAX_VOLUME: Float = 3f
        val SCREEN_FPS_CHOICES: List<Int> = listOf(15, 30, 60)
        const val DEFAULT_SCREEN_FPS: Int = 15

        /** Captured as it is, limited to 4K. */
        const val SOURCE_HEIGHT: Int = 2160
        val SCREEN_HEIGHT_CHOICES: List<Int> = listOf(720, 1080, SOURCE_HEIGHT)
        const val DEFAULT_SCREEN_HEIGHT: Int = 1080
        const val STATS_INTERVAL_MILLIS: Long = 2_000

        /** How often the input level and the gate are read for the meter. */
        const val LEVEL_POLL_MILLIS: Long = 50

        /** The voice gate stays open this long after the level fell below the threshold. */
        const val VOICE_HOLD_MILLIS: Long = 400
        const val PTT_RELEASE_MILLIS: Long = 200

        /** Tiles up to this many pixels tall get the low simulcast layer (180p), up to the next the middle one (360p). */
        const val LOW_LAYER_MAX_PX: Int = 240
        const val MEDIUM_LAYER_MAX_PX: Int = 480

        /** [tileKey]s of this user's own tiles. */
        const val SELF: String = "self"
        const val SELF_SCREEN: String = "self/screen"

        /** Identifies a tile: a participant's camera, or its screen share with [screen]. */
        fun tileKey(participantId: String, screen: Boolean): String = if (screen) "$participantId/screen" else participantId

        fun volumeKey(participantId: String, screen: Boolean): String = if (screen) "$participantId/screen" else participantId
    }
}
