package krtc.native

import krtc.core.MediaStreamTrack
import krtc.core.PeerConnectionFactory
import krtc.core.RTCConfiguration
import krtc.core.RTCDataChannel
import krtc.core.RTCDataChannelInit
import krtc.core.RTCPeerConnection
import krtc.core.RTCPeerConnectionState
import krtc.core.RTCRtpReceiver
import krtc.core.RTCRtpSender
import krtc.core.RTCRtpTransceiver
import krtc.core.RTCRtpTransceiverDirection
import krtc.core.RTCSdpType
import krtc.core.RTCSessionDescription
import krtc.core.RTCStats
import krtc.core.RTCTrackEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The JVM / Android side of krtc-native: the same classes as nativeMain, over JNI (ADR-0002).

private const val EVENT_CONNECTION_STATE = 1
private const val EVENT_TRACK = 2

/**
 * krtc-core backend on libwebrtc via the krtc C shim. One factory owns libwebrtc's threads.
 * [platformVideoCodecs] enables the platform's codec set; [preferredVideoCodec] (e.g. "video/H264") is
 * negotiated first and used for sending when the SFU offers it.
 */
public actual class NativePeerConnectionFactory actual constructor(
    public actual val audioDevice: AudioDevice,
    platformVideoCodecs: Boolean,
    public actual val preferredVideoCodec: String?,
) : PeerConnectionFactory, krtc.core.SilentAudioTrackFactory, AutoCloseable {
    internal val handle: Long = Jni.factoryCreate(if (audioDevice == AudioDevice.PLATFORM) 1 else 0, if (platformVideoCodecs) 1 else 0)
        .also { check(it != 0L) { "libwebrtc factory creation failed" } }

    /** Video codecs this factory can send, e.g. [video/VP8, video/H264, ...]. */
    public actual val audioInputs: List<String> get() = devices(false)
    public actual val audioOutputs: List<String> get() = devices(true)

    private fun devices(output: Boolean): List<String> =
        List(Jni.factoryAudioDeviceCount(handle, output).coerceAtLeast(0)) { i -> Jni.factoryAudioDeviceName(handle, output, i) ?: "device $i" }

    public actual fun setAudioInput(index: Int): Boolean = Jni.factorySetAudioDevice(handle, false, index) == 0
    public actual fun setAudioOutput(index: Int): Boolean = Jni.factorySetAudioDevice(handle, true, index) == 0

    public actual fun setAudioProcessing(echoCancellation: Boolean, noiseSuppression: Boolean, autoGainControl: Boolean): Unit =
        Jni.factorySetAudioProcessing(handle, echoCancellation, noiseSuppression, autoGainControl)

    public actual fun setInputGate(gate: InputGate, threshold: Double, holdMillis: Int): Unit =
        Jni.factorySetInputGate(handle, gate.ordinal, threshold, holdMillis)

    public actual val inputLevel: Double get() = Jni.factoryInputLevel(handle)
    public actual val inputGateOpen: Boolean get() = Jni.factoryInputGateOpen(handle)

    public actual val videoCodecs: List<String> get() = Jni.factoryVideoCodecs(handle).split(' ').filter { it.isNotEmpty() }

    /** Synthetic audio: send a pulsed 440 Hz tone when the app pushes no PCM. */
    public actual fun setTestTone(enabled: Boolean): Unit = Jni.factorySetTestTone(handle, enabled)

    /** Synthetic audio: queues 48 kHz mono PCM as the microphone; returns how many samples were accepted. */
    public actual fun pushAudio(samples: ShortArray): Int = Jni.factoryPushAudio(handle, samples, samples.size)

    /** Synthetic audio: takes up to [out].size samples of the decoded remote mix (48 kHz mono). */
    public actual fun pullAudio(out: ShortArray): Int = Jni.factoryPullAudio(handle, out)

    /** Synthetic audio: RMS level (0..1) of the remote mix over the last 10 ms. */
    public actual val outputLevel: Double get() = Jni.factoryOutputLevel(handle)

    actual override fun createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection = NativePeerConnection(this)

    public actual fun createAudioTrack(id: String): NativeTrack =
        NativeTrack(Jni.factoryCreateAudioTrack(handle, id).also { check(it != 0L) { "audio track creation failed" } })

    /** A synthetic video track (moving square), so no camera access is needed. */
    public actual fun createTestVideoTrack(id: String, width: Int, height: Int, fps: Int): NativeTrack =
        NativeTrack(Jni.factoryCreateTestVideoTrack(handle, id, width, height, fps).also { check(it != 0L) { "video track creation failed" } })

    public actual fun createVideoSource(): NativeVideoSource =
        NativeVideoSource(Jni.factoryCreateVideoSource(handle).also { check(it != 0L) { "video source creation failed" } })

    public actual fun createCameraSource(index: Int, width: Int, height: Int, fps: Int): NativeVideoSource? =
        Jni.factoryCreateCameraSource(handle, index, width, height, fps).takeIf { it != 0L }?.let(::NativeVideoSource)

    public actual fun createScreenSource(source: ScreenSource?, fps: Int, maxWidth: Int, maxHeight: Int): NativeVideoSource? =
        Jni.factoryCreateScreenSource(handle, source?.id ?: -1, source?.isWindow == true, fps, maxWidth, maxHeight).takeIf { it != 0L }?.let(::NativeVideoSource)

    public actual fun createAudioSource(): NativeAudioSource =
        NativeAudioSource(Jni.factoryCreateAudioSource(handle).also { check(it != 0L) { "audio source creation failed" } })

    /** A track on a pushed audio source nobody pushes to: silent, and attached as an external source from the start. */
    actual override fun createSilentAudioTrack(id: String): NativeTrack = createAudioSource().use { createAudioTrack(it, id) }

    public actual fun createSystemAudioSource(): NativeAudioSource? =
        Jni.factoryCreateSystemAudioSource(handle).takeIf { it != 0L }?.let(::NativeAudioSource)

    public actual fun createAudioTrack(source: NativeAudioSource, id: String): NativeTrack =
        NativeTrack(Jni.factoryCreateAudioTrackFrom(handle, id, source.handle).also { check(it != 0L) { "audio track creation failed" } })

    public actual fun createScreencastSource(): NativeVideoSource =
        NativeVideoSource(Jni.factoryCreateScreencastSource(handle).also { check(it != 0L) { "video source creation failed" } })

    public actual fun createVideoTrack(source: NativeVideoSource, id: String): NativeTrack =
        NativeTrack(Jni.factoryCreateVideoTrack(handle, id, source.handle).also { check(it != 0L) { "video track creation failed" } })

    actual override fun close(): Unit = Jni.factoryRelease(handle)
}

public actual fun nativeScreenSources(): List<ScreenSource> = parseScreenSources(Jni.screenSources().orEmpty())

public actual fun nativeCameras(): List<String> = List(Jni.cameraCount()) { i -> Jni.cameraName(i) ?: "camera $i" }

/** Thread-safe: pushes after [close] are ignored. */
public actual class NativeAudioSource internal constructor(internal val handle: Long) : AutoCloseable {
    private var closed = false

    @Synchronized
    public actual fun push(samples: ShortArray, channels: Int, sampleRate: Int, frames: Int) {
        require(samples.size >= frames * channels) { "${samples.size} samples, need ${frames * channels}" }
        if (!closed && frames > 0) Jni.audioSourcePush(handle, samples, frames, channels, sampleRate)
    }

    @Synchronized
    actual override fun close() {
        if (closed) return
        closed = true
        Jni.audioSourceRelease(handle)
    }
}

/** Thread-safe: pushes after [close] are ignored, so a capture thread may outlive the source. */
public actual class NativeVideoSource internal constructor(internal val handle: Long) : AutoCloseable {
    private var closed = false

    @Synchronized
    public actual fun pushI420(
        width: Int, height: Int,
        y: ByteArray, strideY: Int, u: ByteArray, strideU: Int, v: ByteArray, strideV: Int,
        rotation: Int, timestampMicros: Long,
    ) {
        if (!closed) Jni.videoSourcePushI420(handle, width, height, y, strideY, u, strideU, v, strideV, rotation, timestampMicros)
    }

    @Synchronized
    public actual fun pushArgb(width: Int, height: Int, argb: IntArray, rotation: Int, timestampMicros: Long) {
        require(argb.size >= width * height) { "argb holds ${argb.size} pixels, need ${width * height}" }
        if (!closed) Jni.videoSourcePushArgb(handle, width, height, argb, rotation, timestampMicros)
    }

    @Synchronized
    public actual fun pushRgba(width: Int, height: Int, rgba: ByteArray, stride: Int, rotation: Int, timestampMicros: Long) {
        require(rgba.size >= stride * (height - 1) + width * 4) { "rgba holds ${rgba.size} bytes, too few for $width x $height" }
        if (!closed) Jni.videoSourcePushRgba(handle, width, height, rgba, stride, rotation, timestampMicros)
    }

    /** Pushes RGBA from a direct buffer without copying it into the Java heap (Android screen capture). */
    @Synchronized
    internal fun pushRgba(width: Int, height: Int, rgba: java.nio.ByteBuffer, stride: Int, rotation: Int, timestampMicros: Long) {
        require(rgba.isDirect) { "a direct buffer is needed" }
        if (!closed) Jni.videoSourcePushRgbaDirect(handle, width, height, rgba, stride, rotation, timestampMicros)
    }

    @Synchronized
    actual override fun close() {
        if (closed) return
        closed = true
        Jni.videoSourceRelease(handle)
    }
}

public actual class NativeTrack internal constructor(internal val handle: Long) : MediaStreamTrack {
    private val guard = Guarded(handle)
    actual override val id: String = Jni.trackId(handle)
    actual override val kind: String = if (Jni.trackIsVideo(handle)) "video" else "audio"

    // After release (e.g. the call ended while a view still shows the track) these do nothing.
    actual override var enabled: Boolean
        get() = guard.useOr(false) { Jni.trackEnabled(it) }
        set(value) = guard.useOr(Unit) { Jni.trackSetEnabled(it, value) }

    public actual fun setVolume(volume: Double): Unit = guard.useOr(Unit) { Jni.trackSetVolume(it, volume) }

    actual override fun stop() {
        enabled = false
    }

    /** For received video: frames delivered so far and the last frame size. */
    public actual fun videoStats(): NativeVideoStats {
        val out = LongArray(3)
        guard.useOr(Unit) { Jni.trackVideoStats(it, out) }
        return NativeVideoStats(out[0], out[1].toInt(), out[2].toInt())
    }

    private var frameSink = 0L

    @Synchronized
    public actual fun setFrameListener(listener: ((VideoFrame) -> Unit)?) {
        guard.useOr(Unit) { h ->
            val old = frameSink
            // Replacing the sink is synchronous in libwebrtc: the old one gets no frames after this call.
            frameSink = Jni.trackSetFrameSink(h, listener?.let { l -> Jni.FrameListener { w, hh, argb, ts -> l(VideoFrame(w, hh, argb, ts)) } })
            if (old != 0L) Jni.frameSinkRelease(old)
        }
    }

    public actual fun release() {
        setFrameListener(null)
        guard.release { Jni.trackRelease(it) }
    }
}

private class NativeSender(private val transceiver: NativeTransceiver) : RTCRtpSender {
    @Volatile override var track: MediaStreamTrack? = null

    override suspend fun replaceTrack(withTrack: MediaStreamTrack?) {
        check(transceiver.guard.use { Jni.transceiverSetSenderTrack(it, (withTrack as NativeTrack?)?.handle ?: 0L) } == 0) { "replaceTrack failed" }
        track = withTrack
    }
}

private class NativeReceiver(override val track: MediaStreamTrack) : RTCRtpReceiver

internal class NativeTransceiver(internal val handle: Long, receiverTrack: NativeTrack) : RTCRtpTransceiver {
    internal val guard = Guarded(handle)
    override val mid: String? get() = guard.useOr(null) { Jni.transceiverMid(it) }
    override var direction: RTCRtpTransceiverDirection
        get() = RTCRtpTransceiverDirection.entries[guard.use { Jni.transceiverDirection(it) }]
        set(value) {
            check(guard.use { Jni.transceiverSetDirection(it, value.ordinal) } == 0) { "setDirection($value) failed" }
        }
    override val sender: RTCRtpSender = NativeSender(this)
    override val receiver: RTCRtpReceiver = NativeReceiver(receiverTrack)

    fun release() {
        guard.release { Jni.transceiverRelease(it) }
        (receiver.track as NativeTrack).release()
    }
}

internal class NativePeerConnection(private val factory: NativePeerConnectionFactory) : RTCPeerConnection {
    private val lock = Any()
    private val transceivers = mutableMapOf<String, NativeTransceiver>() // by mid; guarded by lock

    // Replaced by a newer OnTrack for the same mid. Released in close(), not on the signaling thread, which would wait
    // for a caller that holds the old one while waiting for the signaling thread. Guarded by lock.
    private val retired = mutableListOf<NativeTransceiver>()
    private val tracks = MutableSharedFlow<RTCTrackEvent>(replay = 64)
    private val state = MutableStateFlow(RTCPeerConnectionState.NEW)
    private var closed = false
    private val guard = Guarded(Jni.pcCreate(factory.handle, ::onEvent).also { check(it != 0L) { "peer connection creation failed" } })

    private val channels = mutableListOf<NativeDataChannel>() // guarded by lock
    private val remoteChannels = MutableSharedFlow<RTCDataChannel>(replay = 16)

    override val ontrack: SharedFlow<RTCTrackEvent> get() = tracks
    override val ondatachannel: SharedFlow<RTCDataChannel> get() = remoteChannels
    override val connectionState: StateFlow<RTCPeerConnectionState> = state.asStateFlow()

    override fun createDataChannel(label: String, init: RTCDataChannelInit): RTCDataChannel {
        require(init.maxRetransmits == null || init.maxPacketLifeTime == null) { "maxRetransmits and maxPacketLifeTime are exclusive" }
        val h = guard.use { handle -> Jni.pcCreateDataChannel(
            handle, label, init.ordered, init.maxRetransmits ?: -1, init.maxPacketLifeTime ?: -1, init.protocol, init.negotiated, init.id ?: -1,
        ) }
        require(h != 0L) { "createDataChannel('$label') failed" }
        return NativeDataChannel(h).also { synchronized(lock) { channels += it } }
    }

    /** On libwebrtc's signaling thread: hand off without blocking. */
    private fun onEvent(event: Int, value: Int, transceiver: Long, track: Long) {
        when (event) {
            Jni.EVENT_DATA_CHANNEL -> {
                val dc = NativeDataChannel(transceiver)
                synchronized(lock) { channels += dc }
                remoteChannels.tryEmit(dc)
            }
            EVENT_CONNECTION_STATE -> state.value = RTCPeerConnectionState.entries.getOrElse(value) { RTCPeerConnectionState.FAILED }
            EVENT_TRACK -> {
                val t = NativeTransceiver(transceiver, NativeTrack(track))
                val mid = t.mid
                if (mid != null) synchronized(lock) { transceivers.put(mid, t)?.let { retired += it } }
                tracks.tryEmit(RTCTrackEvent(t.receiver, t.receiver.track, t))
            }
        }
    }

    private suspend fun call(block: (Jni.Done) -> Unit): String? = suspendCancellableCoroutine { c ->
        block(Jni.Done { result, error -> if (error != null) c.resumeWithException(IllegalStateException(error)) else c.resume(result) })
    }

    override suspend fun setRemoteDescription(description: RTCSessionDescription) {
        call { done -> guard.use { Jni.pcSetRemoteDescription(it, description.type.value, description.sdp, done) } }
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video") (t as NativeTransceiver).guard.useOr(Unit) { Jni.transceiverPreferCodec(factory.handle, it, mime) }
            }
        }
    }

    override suspend fun getStats(): Map<String, RTCStats> = call { done -> guard.use { Jni.pcGetStats(it, done) } }?.let(::parseStatsReport).orEmpty()

    override suspend fun createAnswer(): RTCSessionDescription = RTCSessionDescription(RTCSdpType.ANSWER, call { done -> guard.use { Jni.pcCreateAnswer(it, done) } }!!)

    override suspend fun setLocalDescription(description: RTCSessionDescription) {
        call { done -> guard.use { Jni.pcSetLocalDescription(it, description.type.value, description.sdp, done) } }
        // As answerer libwebrtc sends with the offer's first codec; pick the preferred one explicitly.
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video" && t.sender.track != null) (t as NativeTransceiver).guard.useOr(Unit) { Jni.transceiverSetSendCodec(it, mime) }
            }
        }
    }

    override val localDescription: RTCSessionDescription? get() = guard.useOr(null) { Jni.pcLocalDescription(it) }?.let { RTCSessionDescription(RTCSdpType.ANSWER, it) }
    override val remoteDescription: RTCSessionDescription? get() = guard.useOr(null) { Jni.pcRemoteDescription(it) }?.let { RTCSessionDescription(RTCSdpType.OFFER, it) }

    override fun getTransceivers(): List<RTCRtpTransceiver> = guard.useOr(emptyList()) { handle -> synchronized(lock) {
        (0 until Jni.pcTransceiverCount(handle)).mapNotNull { i ->
            val h = Jni.pcTransceiverAt(handle, i).takeIf { it != 0L } ?: return@mapNotNull null
            val mid = Jni.transceiverMid(h)
            if (mid == null) {
                Jni.transceiverRelease(h)
                return@mapNotNull null
            }
            transceivers[mid]?.also { Jni.transceiverRelease(h) }
                ?: NativeTransceiver(h, NativeTrack(Jni.transceiverReceiverTrack(h))).also { transceivers[mid] = it }
        }
    } }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        synchronized(lock) { channels.toList().also { channels.clear() } }.forEach { it.release() }
        guard.release { Jni.pcRelease(it) } // waits for calls in flight; later ones fail instead of touching freed memory
        synchronized(lock) { (transceivers.values + retired).toList().also { transceivers.clear(); retired.clear() } }.forEach { it.release() }
        state.value = RTCPeerConnectionState.CLOSED
    }
}
