package krtc.meet

import krtc.client.RemoteParticipant
import krtc.client.RoomClient
import krtc.client.RoomState
import krtc.client.VideoQuality
import krtc.core.MediaStreamTrack
import krtc.native.AudioDevice
import krtc.native.InputGate
import krtc.native.VideoFrame
import krtc.meet.log.ClientLog
import krtc.meet.log.LogUploader
import krtc.meet.log.Traced
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable

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
class MeetSession(uiScope: CoroutineScope, private val platform: MediaPlatform = NativeMediaPlatform) {
    private val store: SettingsStore = platform.settings

    /**
     * The session's coroutines: on the UI's dispatcher, ended with it, but a failure in one of them (a native call on
     * a connection that just closed, a device that went away) is logged and shown, and does not take the window down.
     */
    private val scope: CoroutineScope = CoroutineScope(
        uiScope.coroutineContext + SupervisorJob(uiScope.coroutineContext[Job]) + CoroutineExceptionHandler { _, e ->
            ClientLog.e("call", "unexpected: ${e.stackTraceToString()}")
            noticeFlow.value = "Что-то пошло не так: ${e.message ?: e::class.simpleName}. Подробности в журнале."
        },
    )
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

    @Traced
    fun setScreenFps(fps: Int) {
        screenFpsFlow.value = fps.coerceIn(1, 60)
    }

    private val screenHeightFlow = MutableStateFlow(DEFAULT_SCREEN_HEIGHT)

    /**
     * Resolution of the next screen share, as a height ([SCREEN_HEIGHT_CHOICES]; [SOURCE_HEIGHT]: as captured, up to
     * 4K). Lower is lighter for everyone watching.
     */
    val screenHeight: StateFlow<Int> = screenHeightFlow.asStateFlow()

    @Traced
    fun setScreenHeight(height: Int) {
        screenHeightFlow.value = height
    }

    private val expandedFlow = MutableStateFlow<String?>(null)

    /** The tile ([tileKey]) that fills the whole window, e.g. a stream being watched; null: the normal layout. */
    val expanded: StateFlow<String?> = expandedFlow.asStateFlow()

    @Traced
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

    /** Creates the shared factory on first use; native work, so only on [native]. */
    private fun platform(): NativePeerConnectionFactory =
        platformFactory ?: platform.createFactory(testAudio = false).also { platformFactory = it }

    /**
     * Native work (libwebrtc, devices, capture) runs here, one call at a time, not on the UI thread: a device or a
     * libwebrtc thread that takes long (or hangs) must not freeze the window.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val native = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /**
     * Runs [block] on [native]. It is not interrupted when the caller is cancelled (native calls cannot be); if the
     * caller was cancelled meanwhile (the call ended), [release] frees what it made, and the cancellation goes on.
     */
    /**
     * Releasing native objects runs on [native] too, in order, in a scope of its own: it must still run when the UI's
     * scope is gone (the window closed), and after whatever native work is under way.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + native)

    private fun teardown(block: () -> Unit) {
        teardownScope.launch { runCatching(block).onFailure { ClientLog.w("call", "releasing failed: ${it.stackTraceToString()}") } }
    }

    private suspend fun <T> offUi(release: (T) -> Unit = {}, block: () -> T): T {
        val value = withContext(NonCancellable + native) { block() }
        if (!currentCoroutineContext().isActive) {
            runCatching { release(value) }
            throw kotlinx.coroutines.CancellationException("the call ended")
        }
        return value
    }

    /** Cameras to choose from on the join screen. */
    fun cameras(): List<CameraChoice.Device> = platform.cameras()

    /** Whether microphones and speakers can be chosen (desktop, iOS). */
    val audioDeviceChoice: Boolean get() = platform.sharedSystemAudio

    /** Reads the device lists again (e.g. after plugging in a headset). */
    fun refreshAudioDevices() {
        if (!platform.sharedSystemAudio) return
        scope.launch {
            val restore = inputFlow.value == null && outputFlow.value == null
            val (lists, chosen) = offUi {
                val f = platform()
                (f.audioInputs to f.audioOutputs) to (if (restore) restoreDevices(f) else null to null)
            }
            inputsFlow.value = lists.first
            outputsFlow.value = lists.second
            chosen.first?.let { inputFlow.value = it }
            chosen.second?.let { outputFlow.value = it }
        }
    }

    /** Switches the microphone; works during a call too. */
    @Traced
    fun selectAudioInput(index: Int) {
        if (!platform.sharedSystemAudio) return
        scope.launch {
            if (offUi { platform().setAudioInput(index) }) {
                inputFlow.value = index
                store["audio.input"] = inputsFlow.value.getOrNull(index)
            } else noticeFlow.value = "Не удалось переключить микрофон"
        }
    }

    /** Switches the speakers or headphones; works during a call too. */
    @Traced
    fun selectAudioOutput(index: Int) {
        if (!platform.sharedSystemAudio) return
        scope.launch {
            if (offUi { platform().setAudioOutput(index) }) {
                outputFlow.value = index
                store["audio.output"] = outputsFlow.value.getOrNull(index)
            } else noticeFlow.value = "Не удалось переключить динамики"
        }
    }

    /** Releases platform audio (when the app closes). */
    @Traced
    fun close() {
        end(null)
        val shared = platformFactory
        platformFactory = null
        teardown { shared?.close() } // after the call's own teardown: same thread, in order
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

    @Traced
    fun join(settings: JoinSettings) {
        if (phaseFlow.value is CallPhase.Joining || phaseFlow.value is CallPhase.InCall) return
        @Suppress("NAME_SHADOWING")
        val settings = settings.copy(server = normalizeServer(settings.server))
        this.settings = settings
        phaseFlow.value = CallPhase.Joining
        val attempt = ++joinAttempt
        rememberJoin(settings)
        startUploader(settings)
        log("join ${settings.server} room=${settings.room} name=${settings.name} camera=${settings.camera} testAudio=${settings.testAudio}")
        // If joining hangs (a blocked thread, a server that never answers), say so instead of "Подключение…" forever,
        // and record where every thread was, to find the cause on a machine nobody here can look at.
        watchdog(JOIN_TIMEOUT_MILLIS, stillStuck = { phaseFlow.value is CallPhase.Joining && joinAttempt == attempt }) {
            ClientLog.e("call", "join still not done after ${JOIN_TIMEOUT_MILLIS / 1000} s; threads:\n${threadDump() ?: "(no thread dump on this platform)"}")
            scope.launch {
                if (phaseFlow.value is CallPhase.Joining && joinAttempt == attempt) {
                    end("сервер не ответил за ${JOIN_TIMEOUT_MILLIS / 1000} с" + (logLocation()?.let { ". Подробности в журнале: $it" } ?: ""))
                }
            }
        }
        joining = scope.launch {
            try {
                val shared = !settings.testAudio && platform.sharedSystemAudio
                val processing = processingFlow.value
                // The shared factory outlives calls: nothing to release if this one is cancelled meanwhile.
                val f = if (shared) offUi { platform() } else offUi({ it.close() }) { platform.createFactory(settings.testAudio) }.also { callFactory = it }
                factory = f
                log("factory ready (${if (shared) "system audio, shared" else "per call"})")
                offUi {
                    if (settings.testAudio) f.setTestTone(true)
                    applyProcessing(f, processing)
                }
                audioPlumbing = offUi({ it?.close() }) { platform.startAudio(f, settings.testAudio) }
                mic = offUi({ it.release() }) { f.createAudioTrack("mic") }
                camera = when (val c = settings.camera) {
                    CameraChoice.None -> null
                    CameraChoice.TestPattern -> offUi({ it.release() }) { f.createTestVideoTrack("camera", 1280, 720, 30) }
                    is CameraChoice.Device -> {
                        val opened = offUi({ it?.close() }) { platform.openCamera(f, c) }
                        if (opened == null) noticeFlow.value = "Камера «${c.name}» недоступна: звонок без видео"
                        opened?.let {
                            openedCamera = it
                            offUi({ t -> t.release() }) { f.createVideoTrack(it.source, "camera") }
                        }
                    }
                }
                log("microphone ready, camera ${if (camera != null) "ready" else "none"}")
                localVideoFlow.value = camera
                micOnFlow.value = true
                deafenedFlow.value = false
                pttHeld = false
                applyGate()
                cameraOnFlow.value = camera != null
                val c = RoomClient(settings.server, f)
                client = c
                c.setMediaState(audioMuted = false, videoMuted = camera == null)
                log("connecting")
                withContext(native) { c.join(settings.room, settings.name, microphone = mic, camera = camera) }
                log("signaling socket open")
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
                            for (p in list) if (p.id !in before) log("participant ${p.id} '${p.name}' here")
                            for (id in before - now) log("participant $id left")
                            participantsFlow.value = list
                            for (p in list) if (watched.add(p.id)) {
                                // Volumes chosen for this person (by name) in an earlier call.
                                for (screen in listOf(false, true)) store[savedVolumeKey(p.name, screen)]?.toFloatOrNull()?.let { v ->
                                    volumesFlow.update { it + (volumeKey(p.id, screen) to v.coerceIn(0f, MAX_VOLUME)) }
                                }
                                launch { applyVolumes(p) }
                                launch { p.speaking.collect { if (it) activeSpeakerFlow.value = p.id } }
                                launch {
                                    p.screen.collect { t ->
                                        log("participant ${p.id} screen ${if (t != null) "track ${t.id}" else "gone"}")
                                        sharingFlow.update { if (t != null) it + p.id else it - p.id }
                                    }
                                }
                                launch { p.video.collect { t -> log("participant ${p.id} camera ${if (t != null) "track ${t.id}" else "gone"}") } }
                                launch { p.audio.collect { t -> log("participant ${p.id} audio ${if (t != null) "track ${t.id}" else "gone"}") } }
                                launch { p.screenSubscribed.collect { log("participant ${p.id} screen subscribed=$it") } }
                            }
                            val keys = list.flatMap { listOf(tileKey(it.id, false), tileKey(it.id, true)) } + listOf(SELF, SELF_SCREEN)
                            if (pinnedFlow.value?.let { it !in keys } == true) pinnedFlow.value = null
                        }
                    }
                    launch { watchConnection(c) }
                    launch { logFrames() }
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
                        log("room state $s")
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
                ClientLog.e("call", "join failed: ${t.stackTraceToString()}")
                end("не удалось подключиться к ${settings.server}: ${t.message ?: t::class.simpleName}")
            }
        }
    }

    private var joinAttempt = 0
    private val logClientId = kotlin.random.Random.nextInt(0x1000000).toString(16).padStart(6, '0')
    private var uploader: LogUploader? = null
    private val logUploadFlow = MutableStateFlow(true)

    /** Whether the log goes to the call's server too (krtc-sfu-server `--client-logs`), not only to the local file. */
    val logUpload: StateFlow<Boolean> = logUploadFlow.asStateFlow()

    @Traced
    fun setLogUpload(enabled: Boolean) {
        logUploadFlow.value = enabled
        if (!enabled) {
            uploader?.stop()
            uploader = null
        } else if (phaseFlow.value is CallPhase.Joining || phaseFlow.value is CallPhase.InCall) {
            settings?.let(::startUploader)
        }
    }

    private fun startUploader(settings: JoinSettings) {
        if (!logUploadFlow.value || uploader != null) return
        // One file per app run on the server: the name (letters and digits only) and a random part.
        val name = settings.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifEmpty { "user" }
        uploader = LogUploader(LogUploader.endpointFor(settings.server), "$name-$logClientId", "krtc-meet", platformName()).also { it.start(scope) }
    }

    private fun log(line: String) = ClientLog.i("call", line)

    /** Every few seconds: what each tile received (frames, size) and the link, so a stalled picture shows in the log. */
    private suspend fun logFrames() {
        val last = HashMap<String, Long>()
        while (true) {
            kotlinx.coroutines.delay(FRAME_LOG_MILLIS)
            val parts = ArrayList<String>()
            for (p in participantsFlow.value) {
                for ((kind, track) in listOf("camera" to p.video.value, "screen" to p.screen.value)) {
                    val stats = (track as? NativeTrack)?.videoStats() ?: continue
                    val key = "${p.id}/$kind"
                    val new = stats.frames - (last[key] ?: 0)
                    last[key] = stats.frames
                    parts += "$key +$new ${stats.width}x${stats.height}"
                }
            }
            connectionFlow.value?.let { parts += "link ${it.quality} rtt=${it.rttMillis} loss=${it.lossPercent.toInt()}%" }
            if (inputFlowSettings.value.mode != InputMode.OPEN || !transmittingFlow.value) parts += "mic transmitting=${transmittingFlow.value} level=${inputLevelFlow.value}"
            if (parts.isNotEmpty()) log("frames: " + parts.joinToString(", "))
        }
    }

    @Traced
    fun toggleMic() {
        if (mic == null) return
        if (deafenedFlow.value) deafenedFlow.value = false // unmuting also ends deafen: you want to be heard and to hear
        else micOnFlow.value = !micOnFlow.value
        cue(if (micOnFlow.value) Cue.UNMUTE else Cue.MUTE)
        applyGate()
        announceMediaState()
    }

    /** Turns the room's sound off (and the microphone with it) or back on; the microphone returns to how it was. */
    @Traced
    fun toggleDeafen() {
        deafenedFlow.value = !deafenedFlow.value
        cue(if (deafenedFlow.value) Cue.DEAFEN else Cue.UNDEAFEN)
        applyGate()
        announceMediaState()
    }

    fun setInputSettings(settings: InputSettings) {
        if (settings.mode != inputFlowSettings.value.mode) log("input mode ${settings.mode}")
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

    @Traced
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

    @Traced
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

    private val pickerFlow = MutableStateFlow(false)

    /** Whether the screen share picker is open. */
    val screenPicker: StateFlow<Boolean> = pickerFlow.asStateFlow()

    @Traced
    fun setScreenPicker(open: Boolean) {
        pickerFlow.value = open
    }

    /** A picker's still image of [choice]: captures briefly, so call it off the UI thread. */
    fun screenThumbnail(choice: ScreenChoice): VideoFrame? = runCatching { platform.screenThumbnail(choice, THUMB_WIDTH, THUMB_HEIGHT) }.getOrNull()

    /** Shares [choice] (with the system's sound if [withAudio]); replaces a running share. */
    @Traced
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
            if (client !== c) return@launch teardown { opened.close() } // the call ended meanwhile
            val (video, audio) = offUi({ (v, a) -> v.release(); a?.release(); opened.close() }) {
                f.createVideoTrack(opened.video, "screen") to opened.audio?.let { f.createAudioTrack(it, "screen-audio") }
            }
            if (withAudio && audio == null) noticeFlow.value = "Звук экрана на этой платформе недоступен, показ без звука"
            runCatching { withContext(native) { c.setScreen(video, audio) } }.onFailure {
                teardown { video.release(); audio?.release(); opened.close() }
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

    @Traced
    fun stopScreen() {
        val c = client
        scope.launch {
            runCatching { withContext(native) { c?.setScreen(null) } }
            releaseScreen()
        }
    }

    private fun releaseScreen() {
        screenFlow.value = null
        val video = screenTrack
        val audio = screenAudioTrack
        val opened = openedScreen
        screenTrack = null
        screenAudioTrack = null
        openedScreen = null
        teardown {
            video?.release()
            audio?.release()
            opened?.close()
        }
    }

    /** Starts or stops receiving [participant]'s screen share; while off it costs no bandwidth. */
    @Traced
    fun setScreenSubscribed(participant: RemoteParticipant, enabled: Boolean) {
        val c = client ?: return
        scope.launch { runCatching { c.setSubscribed(participant.id, enabled) } }
    }

    // ---------------------------------------------------------------- chat

    @Traced(args = false) // the chat text stays out of the log
    fun sendChat(text: String): Boolean {
        val line = text.trim()
        if (line.isEmpty()) return false
        if (line.length > MAX_CHAT_CHARS) {
            noticeFlow.value = "Сообщение длиннее $MAX_CHAT_CHARS символов"
            return false
        }
        val c = client ?: return false
        if (!runCatching { c.sendData(line.encodeToByteArray(), CHAT_TOPIC) }.getOrDefault(false)) {
            noticeFlow.value = "Чат ещё не подключён"
            return false
        }
        chatFlow.update { it + ChatMessage(settings?.name, line, mine = true) }
        return true
    }

    @Traced
    fun setChatOpen(open: Boolean) {
        chatOpenFlow.value = open
        if (open) unreadFlow.value = 0
    }

    fun dismissNotice() {
        noticeFlow.value = null
    }

    // ---------------------------------------------------------------- layout, simulcast, connection quality

    @Traced
    fun setLayout(layout: Layout) {
        layoutFlow.value = layout
    }

    /** Pins a tile ([tileKey]) as the large one, or unpins it if it already is. */
    @Traced
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
        val v = volume.coerceIn(0f, MAX_VOLUME)
        volumesFlow.update { it + (volumeKey(participant.id, screen) to v) }
        // Remembered by name, so it holds the next time this person is in a call.
        store[savedVolumeKey(participant.name, screen)] = if (v == 1f) null else v.toString()
    }

    private fun savedVolumeKey(name: String, screen: Boolean) = "volume.${if (screen) "screen." else ""}$name"

    // ---------------------------------------------------------------- saved settings

    /**
     * The join form's values: those [given] explicitly (environment, intent extras: tests and automation) as they are,
     * the others as last used, or [defaults] the first time. [given] holds field names: server, room, name, camera,
     * testAudio.
     */
    fun rememberedJoin(defaults: JoinSettings, given: Set<String> = emptySet()): JoinSettings {
        fun saved(field: String) = store["join.$field"]?.takeIf { field !in given }
        val camera = saved("camera")?.let { c ->
            when {
                c == "none" -> CameraChoice.None
                c == "test" -> CameraChoice.TestPattern
                c.startsWith("device:") -> runCatching { cameras() }.getOrDefault(emptyList()).firstOrNull { it.name == c.removePrefix("device:") }
                else -> null
            }
        } ?: defaults.camera
        return JoinSettings(
            server = saved("server") ?: defaults.server,
            room = saved("room") ?: defaults.room,
            name = saved("name") ?: defaults.name,
            camera = camera,
            testAudio = saved("testAudio")?.toBooleanStrictOrNull() ?: defaults.testAudio,
        )
    }

    private fun rememberJoin(settings: JoinSettings) {
        store["join.server"] = settings.server
        store["join.room"] = settings.room
        store["join.name"] = settings.name
        store["join.testAudio"] = settings.testAudio.toString()
        store["join.camera"] = when (val c = settings.camera) {
            CameraChoice.None -> "none"
            CameraChoice.TestPattern -> "test"
            is CameraChoice.Device -> "device:${c.name}"
        }
    }

    private val updateFlow = MutableStateFlow<Update?>(null)
    private val updateCheckFlow = MutableStateFlow(true)

    /** A newer build to download, if there is one (checked once at start, see [checkUpdates]). */
    val update: StateFlow<Update?> = updateFlow.asStateFlow()
    val updateCheck: StateFlow<Boolean> = updateCheckFlow.asStateFlow()

    @Traced
    fun setUpdateCheck(enabled: Boolean) {
        updateCheckFlow.value = enabled
        if (!enabled) updateFlow.value = null else checkUpdates()
    }

    /** Asks GitHub for a newer release of this app (one request; nothing is sent about the user). */
    fun checkUpdates() {
        if (!platform.updatable || !updateCheckFlow.value) return
        scope.launch {
            val found = withContext(kotlinx.coroutines.Dispatchers.Default) { checkForUpdate() }
            if (found != null) ClientLog.i("app", "update available: ${found.version}")
            if (updateCheckFlow.value) updateFlow.value = found
        }
    }

    /** Applies the saved settings, then saves every change of them. */
    private fun restoreSettings() {
        fun bool(key: String) = store[key]?.toBooleanStrictOrNull()
        fun int(key: String) = store[key]?.toIntOrNull()
        processingFlow.value = AudioProcessing(
            echoCancellation = bool("processing.echo") ?: true,
            noiseSuppression = bool("processing.noise") ?: true,
            autoGainControl = bool("processing.agc") ?: true,
        )
        inputFlowSettings.value = InputSettings(
            mode = store["input.mode"]?.let { m -> InputMode.entries.firstOrNull { it.name == m } } ?: InputMode.OPEN,
            threshold = store["input.threshold"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: InputSettings().threshold,
        )
        bool("cues")?.let { cuesFlow.value = it }
        bool("log.upload")?.let { logUploadFlow.value = it }
        bool("updates.check")?.let { updateCheckFlow.value = it }
        int("screen.fps")?.takeIf { it in SCREEN_FPS_CHOICES }?.let { screenFpsFlow.value = it }
        int("screen.height")?.takeIf { it in SCREEN_HEIGHT_CHOICES }?.let { screenHeightFlow.value = it }
        store["layout"]?.let { l -> Layout.entries.firstOrNull { it.name == l } }?.let { layoutFlow.value = it }
        scope.launch {
            launch { processingFlow.collect { store["processing.echo"] = "${it.echoCancellation}"; store["processing.noise"] = "${it.noiseSuppression}"; store["processing.agc"] = "${it.autoGainControl}" } }
            launch { inputFlowSettings.collect { store["input.mode"] = it.mode.name; store["input.threshold"] = it.threshold.toString() } }
            launch { cuesFlow.collect { store["cues"] = it.toString() } }
            launch { logUploadFlow.collect { store["log.upload"] = it.toString() } }
            launch { updateCheckFlow.collect { store["updates.check"] = it.toString() } }
            launch { screenFpsFlow.collect { store["screen.fps"] = it.toString() } }
            launch { screenHeightFlow.collect { store["screen.height"] = it.toString() } }
            launch { layoutFlow.collect { store["layout"] = it.name } }
        }
    }

    /** Selects the microphone and speakers used last time, by name (indexes change as devices come and go). */
    private fun restoreDevices(f: NativePeerConnectionFactory): Pair<Int?, Int?> {
        val inputs = f.audioInputs
        val outputs = f.audioOutputs
        val input = store["audio.input"]?.let { inputs.indexOf(it) }?.takeIf { it >= 0 && f.setAudioInput(it) }
        val output = store["audio.output"]?.let { outputs.indexOf(it) }?.takeIf { it >= 0 && f.setAudioOutput(it) }
        return input to output
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
    @Traced
    fun setAudioProcessing(processing: AudioProcessing) {
        processingFlow.value = processing
        val shared = platformFactory
        val f = factory
        val c = client
        scope.launch {
            offUi {
                shared?.let { applyProcessing(it, processing) }
                f?.let { applyProcessing(it, processing) }
            }
            if (f == null || c == null || client !== c) return@launch
            val old = mic ?: return@launch
            val fresh = offUi({ it.release() }) { f.createAudioTrack("mic").also { it.enabled = old.enabled } }
            if (client !== c || mic !== old) return@launch teardown { fresh.release() }
            mic = fresh
            applyGate()
            runCatching { withContext(native) { c.setMicrophone(fresh) } }
            teardown { old.release() }
        }
    }

    private fun applyProcessing(f: NativePeerConnectionFactory, p: AudioProcessing) =
        f.setAudioProcessing(p.echoCancellation, p.noiseSuppression, p.autoGainControl)

    @Traced
    fun leave() = end("left")

    /** Back to the join screen after a call ended. */
    fun reset() {
        if (phaseFlow.value is CallPhase.Ended) phaseFlow.value = CallPhase.Idle
    }

    private fun end(reason: String?) {
        log("call ended: $reason")
        // What happened up to the end, including why it ended, still goes out; then the uploader stops.
        uploader?.let { u ->
            uploader = null
            scope.launch {
                u.flush()
                u.stop()
            }
        }
        phaseFlow.value = CallPhase.Ended(reason)
        joining?.cancel() // stops at its next suspension, before it uses what is released below
        joining = null
        watch?.cancel()
        watch = null
        val leaving = client
        client = null
        teardown { leaving?.leave() } // closes the peer connection: native, so not on the UI thread
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
        val cam = openedCamera
        val plumbing = audioPlumbing
        val cameraTrack = camera
        val micTrack = mic
        val f = factory
        val own = callFactory
        openedCamera = null
        audioPlumbing = null
        camera = null
        mic = null
        factory = null
        callFactory = null
        teardown {
            cam?.close()
            plumbing?.close()
            cameraTrack?.release()
            micTrack?.release()
            f?.setInputGate(InputGate.OPEN) // the shared factory outlives the call
            own?.close()
        }
    }

    init {
        restoreSettings()
    }

    companion object {
        /** Data channel topic of chat lines: UTF-8 text, sent reliably. */
        const val CHAT_TOPIC: String = "chat"
        const val MAX_VOLUME: Float = 3f

        /** Well under the data channel's 64 KB per message, even in 4-byte characters. */
        const val MAX_CHAT_CHARS: Int = 4_000

        /**
         * The signaling URL as the SFU expects it, from what people type: `host:port` and `http(s)://` become
         * `ws(s)://`, a trailing `/` or `/ws` goes (the client adds the path).
         */
        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            s = when {
                s.startsWith("https://", ignoreCase = true) -> "wss://" + s.substring(8)
                s.startsWith("http://", ignoreCase = true) -> "ws://" + s.substring(7)
                s.startsWith("ws://", ignoreCase = true) || s.startsWith("wss://", ignoreCase = true) -> s
                else -> "ws://$s"
            }
            return s.removeSuffix("/ws").trimEnd('/')
        }
        val SCREEN_FPS_CHOICES: List<Int> = listOf(15, 30, 60)
        const val DEFAULT_SCREEN_FPS: Int = 15
        const val THUMB_WIDTH: Int = 384
        const val THUMB_HEIGHT: Int = 216

        /** Captured as it is, limited to 4K. */
        const val SOURCE_HEIGHT: Int = 2160
        val SCREEN_HEIGHT_CHOICES: List<Int> = listOf(720, 1080, SOURCE_HEIGHT)
        const val DEFAULT_SCREEN_HEIGHT: Int = 1080
        const val STATS_INTERVAL_MILLIS: Long = 2_000

        /** Joining that takes longer than this is reported as failed (and logged with a thread dump). */
        const val JOIN_TIMEOUT_MILLIS: Long = 20_000
        const val FRAME_LOG_MILLIS: Long = 5_000

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
