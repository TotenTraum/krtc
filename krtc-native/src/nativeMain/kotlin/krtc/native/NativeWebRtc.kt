package krtc.native

import kotlinx.cinterop.reinterpret

import kotlinx.cinterop.convert

import kotlinx.cinterop.UIntVar

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
import krtc.core.RTCStats
import krtc.core.RTCSessionDescription
import krtc.core.RTCTrackEvent
import cnames.structs.krtc_factory
import cnames.structs.krtc_pc
import cnames.structs.krtc_track
import cnames.structs.krtc_transceiver
import cnames.structs.krtc_video_source
import krtc.native.shim.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * krtc-core backend on libwebrtc via the krtc C shim. One factory owns libwebrtc's threads.
 * [platformVideoCodecs] enables hardware codecs (VideoToolbox H.264/H.265 on Apple); [preferredVideoCodec]
 * (e.g. "video/H264") is moved to the front of every video transceiver's codec list when answering.
 */
public actual class NativePeerConnectionFactory actual constructor(
    public actual val audioDevice: AudioDevice,
    platformVideoCodecs: Boolean,
    public actual val preferredVideoCodec: String?,
) : PeerConnectionFactory, krtc.core.SilentAudioTrackFactory, AutoCloseable {
    internal val handle: CPointer<krtc_factory> =
        krtc_factory_create_with(if (audioDevice == AudioDevice.PLATFORM) 1 else 0, if (platformVideoCodecs) 1 else 0)
            ?: error("libwebrtc factory creation failed")

    public actual val audioInputs: List<String> get() = devices(0)
    public actual val audioOutputs: List<String> get() = devices(1)

    private fun devices(output: Int): List<String> = List(krtc_factory_audio_device_count(handle, output).coerceAtLeast(0)) { i ->
        krtc_factory_audio_device_name(handle, output, i)?.let { p -> p.toKString().also { krtc_free(p) } } ?: "device $i"
    }

    public actual fun setAudioInput(index: Int): Boolean = krtc_factory_set_audio_device(handle, 0, index) == 0
    public actual fun setAudioOutput(index: Int): Boolean = krtc_factory_set_audio_device(handle, 1, index) == 0

    public actual fun setAudioProcessing(echoCancellation: Boolean, noiseSuppression: Boolean, autoGainControl: Boolean): Unit =
        krtc_factory_set_audio_processing(handle, if (echoCancellation) 1 else 0, if (noiseSuppression) 1 else 0, if (autoGainControl) 1 else 0)

    public actual fun setInputGate(gate: InputGate, threshold: Double, holdMillis: Int): Unit =
        krtc_factory_set_input_gate(handle, gate.ordinal, threshold, holdMillis)

    public actual val inputLevel: Double get() = krtc_factory_input_level(handle)
    public actual val inputGateOpen: Boolean get() = krtc_factory_input_gate_open(handle) != 0

    /** Video codecs this factory can send, e.g. [video/VP8, video/H264, ...]. */
    public actual val videoCodecs: List<String>
        get() = krtc_factory_video_codecs(handle)!!.let { p -> p.toKString().also { krtc_free(p) } }.split(' ').filter { it.isNotEmpty() }

    /** Synthetic audio: send a pulsed 440 Hz tone when the app pushes no PCM. */
    public actual fun setTestTone(enabled: Boolean) {
        krtc_factory_set_test_tone(handle, if (enabled) 1 else 0)
    }

    /** Synthetic audio: queues 48 kHz mono PCM as the microphone; returns how many samples were accepted. */
    public actual fun pushAudio(samples: ShortArray): Int = samples.usePinned { krtc_factory_push_audio(handle, it.addressOf(0), samples.size) }

    /** Synthetic audio: takes up to [out].size samples of the decoded remote mix (48 kHz mono). */
    public actual fun pullAudio(out: ShortArray): Int = out.usePinned { krtc_factory_pull_audio(handle, it.addressOf(0), out.size) }

    /** Synthetic audio: RMS level (0..1) of the remote mix over the last 10 ms. */
    public actual val outputLevel: Double get() = krtc_factory_output_level(handle)

    actual override fun createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection = NativePeerConnection(this)

    /** A microphone-less audio track (the factory uses a dummy audio device). */
    public actual fun createAudioTrack(id: String): NativeTrack =
        NativeTrack(krtc_factory_create_audio_track(handle, id) ?: error("audio track creation failed"))

    /** A synthetic video track (moving square), so no camera access is needed. */
    public actual fun createTestVideoTrack(id: String, width: Int, height: Int, fps: Int): NativeTrack =
        NativeTrack(krtc_factory_create_test_video_track(handle, id, width, height, fps) ?: error("video track creation failed"))

    public actual fun createVideoSource(): NativeVideoSource =
        NativeVideoSource(krtc_factory_create_video_source(handle) ?: error("video source creation failed"))

    public actual fun createCameraSource(index: Int, width: Int, height: Int, fps: Int): NativeVideoSource? =
        krtc_factory_create_camera_source(handle, index, width, height, fps)?.let(::NativeVideoSource)

    public actual fun createScreenSource(source: ScreenSource?, fps: Int, maxWidth: Int, maxHeight: Int): NativeVideoSource? =
        krtc_factory_create_screen_source(handle, source?.id ?: -1, if (source?.isWindow == true) 1 else 0, fps, maxWidth, maxHeight)?.let(::NativeVideoSource)

    public actual fun createAudioSource(): NativeAudioSource =
        NativeAudioSource(krtc_factory_create_audio_source(handle) ?: error("audio source creation failed"))

    /** A track on a pushed audio source nobody pushes to: silent, and attached as an external source from the start. */
    actual override fun createSilentAudioTrack(id: String): NativeTrack = createAudioSource().use { createAudioTrack(it, id) }

    public actual fun createSystemAudioSource(): NativeAudioSource? = krtc_factory_create_system_audio_source(handle)?.let(::NativeAudioSource)

    public actual fun createAudioTrack(source: NativeAudioSource, id: String): NativeTrack =
        NativeTrack(krtc_factory_create_audio_track_from(handle, id, source.handle) ?: error("audio track creation failed"))

    public actual fun createScreencastSource(): NativeVideoSource =
        NativeVideoSource(krtc_factory_create_screencast_source(handle) ?: error("video source creation failed"))

    public actual fun createVideoTrack(source: NativeVideoSource, id: String): NativeTrack =
        NativeTrack(krtc_factory_create_video_track(handle, id, source.handle) ?: error("video track creation failed"))

    actual override fun close() {
        krtc_factory_release(handle)
    }
}

public actual fun nativeScreenThumbnail(source: ScreenSource, maxWidth: Int, maxHeight: Int): VideoFrame? = memScoped {
    val out = IntArray(maxWidth * maxHeight)
    val w = alloc<IntVar>()
    val h = alloc<IntVar>()
    val ok = out.usePinned { pinned ->
        krtc_screen_thumbnail(source.id, if (source.isWindow) 1 else 0, maxWidth, maxHeight, pinned.addressOf(0).reinterpret(), w.ptr, h.ptr)
    }
    if (ok != 0) null else VideoFrame(w.value, h.value, out.copyOf(w.value * h.value), 0)
}

public actual fun nativeScreenSources(): List<ScreenSource> =
    parseScreenSources(krtc_screen_sources()?.let { p -> p.toKString().also { krtc_free(p) } }.orEmpty())

public actual fun nativeCameras(): List<String> =
    List(krtc_camera_count()) { i -> krtc_camera_name(i)?.let { p -> p.toKString().also { krtc_free(p) } } ?: "camera $i" }

/** Thread-safe: pushes after [close] are ignored. */
public actual class NativeAudioSource internal constructor(internal val handle: CPointer<cnames.structs.krtc_audio_source>) : AutoCloseable {
    private val lock = SynchronizedObject()
    private var closed = false

    public actual fun push(samples: ShortArray, channels: Int, sampleRate: Int, frames: Int) {
        require(samples.size >= frames * channels) { "${samples.size} samples, need ${frames * channels}" }
        synchronized(lock) {
            if (!closed && frames > 0) samples.usePinned { krtc_audio_source_push(handle, it.addressOf(0), frames, channels, sampleRate) }
        }
    }

    actual override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        krtc_audio_source_release(handle)
    }
}

/** Thread-safe: pushes after [close] are ignored, so a capture thread may outlive the source. */
public actual class NativeVideoSource internal constructor(internal val handle: CPointer<krtc_video_source>) : AutoCloseable {
    private val lock = SynchronizedObject()
    private var closed = false

    public actual fun pushI420(
        width: Int, height: Int,
        y: ByteArray, strideY: Int, u: ByteArray, strideU: Int, v: ByteArray, strideV: Int,
        rotation: Int, timestampMicros: Long,
    ): Unit = synchronized(lock) {
        if (closed) return
        y.usePinned { py ->
            u.usePinned { pu ->
                v.usePinned { pv ->
                    krtc_video_source_push_i420(
                        handle, width, height,
                        py.addressOf(0).reinterpret(), strideY, pu.addressOf(0).reinterpret(), strideU, pv.addressOf(0).reinterpret(), strideV,
                        rotation, timestampMicros,
                    )
                }
            }
        }
    }

    public actual fun pushArgb(width: Int, height: Int, argb: IntArray, rotation: Int, timestampMicros: Long) {
        require(argb.size >= width * height) { "argb holds ${argb.size} pixels, need ${width * height}" }
        synchronized(lock) {
            if (!closed) argb.usePinned { krtc_video_source_push_argb(handle, width, height, it.addressOf(0).reinterpret(), rotation, timestampMicros) }
        }
    }

    public actual fun pushRgba(width: Int, height: Int, rgba: ByteArray, stride: Int, rotation: Int, timestampMicros: Long) {
        require(rgba.size >= stride * (height - 1) + width * 4) { "rgba holds ${rgba.size} bytes, too few for $width x $height" }
        synchronized(lock) {
            if (!closed) rgba.usePinned { krtc_video_source_push_rgba(handle, width, height, it.addressOf(0).reinterpret(), stride, rotation, timestampMicros) }
        }
    }

    actual override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        krtc_video_source_release(handle)
    }
}

/** Receives a track's frames from the shim and hands them to the listener in a reused IntArray. */
private class FrameSink(val listener: (VideoFrame) -> Unit) {
    var pixels = IntArray(0)
}

private val frameCallback = staticCFunction { user: COpaquePointer?, width: Int, height: Int, argb: CPointer<UIntVar>?, timestampUs: Long ->
    val sink = user!!.asStableRef<FrameSink>().get()
    val n = width * height
    if (sink.pixels.size != n) sink.pixels = IntArray(n)
    sink.pixels.usePinned { platform.posix.memcpy(it.addressOf(0), argb, (n.toLong() * 4).convert()) }
    sink.listener(VideoFrame(width, height, sink.pixels, timestampUs))
}

/**
 * A native handle that one thread may release while others still use it (leaving a call while an offer is being
 * answered, a view detaching from a track): calls and [release] are mutually exclusive, so no call ever reaches freed
 * memory. After release, [use] throws and [useOr] returns its default.
 */
internal class Guarded<T : kotlinx.cinterop.CPointed>(private val value: CPointer<T>) {
    private val lock = SynchronizedObject()
    private var released = false

    fun <R> use(block: (CPointer<T>) -> R): R = synchronized(lock) {
        check(!released) { "already closed" }
        block(value)
    }

    fun <R> useOr(default: R, block: (CPointer<T>) -> R): R = synchronized(lock) { if (released) default else block(value) }

    fun release(block: (CPointer<T>) -> Unit): Boolean = synchronized(lock) {
        if (released) return false
        released = true
        block(value)
        true
    }
}

public actual class NativeTrack internal constructor(internal val handle: CPointer<krtc_track>) : MediaStreamTrack {
    private val guard = Guarded(handle)
    actual override val id: String = krtc_track_id(handle)!!.let { p -> p.toKString().also { krtc_free(p) } }
    actual override val kind: String = if (krtc_track_is_video(handle) != 0) "video" else "audio"

    // After release (e.g. the call ended while a view still shows the track) these do nothing.
    actual override var enabled: Boolean
        get() = guard.useOr(false) { krtc_track_enabled(it) != 0 }
        set(value) = guard.useOr(Unit) { krtc_track_set_enabled(it, if (value) 1 else 0) }

    public actual fun setVolume(volume: Double): Unit = guard.useOr(Unit) { krtc_track_set_volume(it, volume) }

    actual override fun stop() {
        enabled = false
    }

    /** For received video: frames delivered so far and the last frame size. */
    public actual fun videoStats(): NativeVideoStats = memScoped {
        val frames = alloc<LongVar>()
        val w = alloc<IntVar>()
        val h = alloc<IntVar>()
        guard.useOr(Unit) { krtc_track_video_stats(it, frames.ptr, w.ptr, h.ptr) }
        NativeVideoStats(frames.value, w.value, h.value)
    }

    private var frameSink: StableRef<FrameSink>? = null

    public actual fun setFrameListener(listener: ((VideoFrame) -> Unit)?) {
        guard.useOr(Unit) { h ->
            val old = frameSink
            frameSink = listener?.let { StableRef.create(FrameSink(it)) }
            // Replacing the sink is synchronous in libwebrtc: the old one gets no frames after this call.
            krtc_track_set_frame_sink(h, if (frameSink != null) frameCallback else null, frameSink?.asCPointer())
            old?.dispose()
        }
    }

    public actual fun release() {
        setFrameListener(null)
        guard.release { krtc_track_release(it) }
    }
}

private class NativeSender(private val transceiver: NativeTransceiver) : RTCRtpSender {
    override var track: MediaStreamTrack? = null

    override suspend fun replaceTrack(withTrack: MediaStreamTrack?) {
        val native = withTrack as NativeTrack?
        check(transceiver.guard.use { krtc_transceiver_set_sender_track(it, native?.handle) } == 0) { "replaceTrack failed" }
        track = withTrack
    }
}

private class NativeReceiver(override val track: MediaStreamTrack) : RTCRtpReceiver

internal class NativeTransceiver(handle: CPointer<krtc_transceiver>, receiverTrack: NativeTrack) : RTCRtpTransceiver {
    internal val guard = Guarded(handle)
    override val mid: String? get() = guard.useOr(null) { krtc_transceiver_mid(it) }?.let { p -> p.toKString().also { krtc_free(p) } }
    override var direction: RTCRtpTransceiverDirection
        get() = RTCRtpTransceiverDirection.entries[guard.use { krtc_transceiver_direction(it) }]
        set(value) {
            check(guard.use { krtc_transceiver_set_direction(it, value.ordinal) } == 0) { "setDirection($value) failed" }
        }
    override val sender: RTCRtpSender = NativeSender(this)
    override val receiver: RTCRtpReceiver = NativeReceiver(receiverTrack)

    fun release() {
        guard.release { krtc_transceiver_release(it) }
        (receiver.track as NativeTrack).release()
    }
}

/** Result slot for one asynchronous shim call. */
private class Pending(val continuation: Continuation<String?>)

private val doneCallback = staticCFunction { user: COpaquePointer?, sdp: kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>?, error: kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>? ->
    val ref = user!!.asStableRef<Pending>()
    val pending = ref.get()
    ref.dispose()
    if (error != null) pending.continuation.resumeWithException(IllegalStateException(error.toKString()))
    else pending.continuation.resume(sdp?.toKString())
}

private val remoteDataChannelCallback = staticCFunction { user: COpaquePointer?, channel: CPointer<cnames.structs.krtc_data_channel>? ->
    user!!.asStableRef<NativePeerConnection>().get().onDataChannel(channel!!)
}

private val eventCallback = staticCFunction { user: COpaquePointer?, event: Int, state: Int, transceiver: CPointer<krtc_transceiver>?, track: CPointer<krtc_track>? ->
    user!!.asStableRef<NativePeerConnection>().get().onEvent(event, state, transceiver, track)
}

internal class NativePeerConnection(private val factory: NativePeerConnectionFactory) : RTCPeerConnection {
    private val self = StableRef.create(this)
    private val handle: CPointer<krtc_pc> = krtc_pc_create(factory.handle, eventCallback, self.asCPointer()) ?: error("peer connection creation failed")
    private val guard = Guarded(handle)
    private val lock = SynchronizedObject()
    private val transceivers = mutableMapOf<String, NativeTransceiver>() // by mid; guarded by lock

    // Replaced by a newer OnTrack for the same mid. Released in close(), not on the signaling thread, which would wait
    // for a caller that holds the old one while waiting for the signaling thread. Guarded by lock.
    private val retired = mutableListOf<NativeTransceiver>()
    private val tracks = MutableSharedFlow<RTCTrackEvent>(replay = 64)
    private val state = MutableStateFlow(RTCPeerConnectionState.NEW)
    private var closed = false
    private val channels = mutableListOf<NativeDataChannel>() // guarded by lock
    private val remoteChannels = MutableSharedFlow<RTCDataChannel>(replay = 16)

    init {
        krtc_pc_set_data_channel_handler(handle, remoteDataChannelCallback, self.asCPointer())
    }

    override val ontrack: SharedFlow<RTCTrackEvent> get() = tracks
    override val ondatachannel: SharedFlow<RTCDataChannel> get() = remoteChannels

    override fun createDataChannel(label: String, init: RTCDataChannelInit): RTCDataChannel {
        require(init.maxRetransmits == null || init.maxPacketLifeTime == null) { "maxRetransmits and maxPacketLifeTime are exclusive" }
        val h = guard.use { handle -> krtc_pc_create_data_channel(
            handle, label, if (init.ordered) 1 else 0, init.maxRetransmits ?: -1, init.maxPacketLifeTime ?: -1,
            init.protocol, if (init.negotiated) 1 else 0, init.id ?: -1,
        ) } ?: throw IllegalArgumentException("createDataChannel('$label') failed")
        return NativeDataChannel(h).also { synchronized(lock) { channels += it } }
    }

    /** On libwebrtc's signaling thread. */
    fun onDataChannel(channel: CPointer<cnames.structs.krtc_data_channel>) {
        val dc = NativeDataChannel(channel)
        synchronized(lock) { channels += dc }
        remoteChannels.tryEmit(dc)
    }
    override val connectionState: StateFlow<RTCPeerConnectionState> = state.asStateFlow()

    /** On libwebrtc's signaling thread: hand off without blocking. */
    fun onEvent(event: Int, value: Int, transceiver: CPointer<krtc_transceiver>?, track: CPointer<krtc_track>?) {
        when (event) {
            KRTC_EVENT_CONNECTION_STATE.toInt() -> state.value = RTCPeerConnectionState.entries.getOrElse(value) { RTCPeerConnectionState.FAILED }
            KRTC_EVENT_TRACK.toInt() -> {
                val t = NativeTransceiver(transceiver!!, NativeTrack(track!!))
                val mid = t.mid
                if (mid != null) synchronized(lock) { transceivers.put(mid, t)?.let { retired += it } }
                tracks.tryEmit(RTCTrackEvent(t.receiver, t.receiver.track, t))
            }
        }
    }

    private suspend fun call(block: (COpaquePointer) -> Unit): String? = suspendCancellableCoroutine { c ->
        block(StableRef.create(Pending(c)).asCPointer())
    }

    override suspend fun setRemoteDescription(description: RTCSessionDescription) {
        call { done -> guard.use { krtc_pc_set_remote_description(it, description.type.value, description.sdp, doneCallback, done) } }
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video") (t as NativeTransceiver).guard.useOr(Unit) { krtc_transceiver_prefer_codec(factory.handle, it, mime) }
            }
        }
    }

    override suspend fun getStats(): Map<String, RTCStats> {
        val json = call { done -> guard.use { krtc_pc_get_stats(it, doneCallback, done) } } ?: return emptyMap()
        return parseStatsReport(json)
    }

    override suspend fun createAnswer(): RTCSessionDescription =
        RTCSessionDescription(RTCSdpType.ANSWER, call { done -> guard.use { krtc_pc_create_answer(it, doneCallback, done) } }!!)

    override suspend fun setLocalDescription(description: RTCSessionDescription) {
        call { done -> guard.use { krtc_pc_set_local_description(it, description.type.value, description.sdp, doneCallback, done) } }
        // As answerer libwebrtc sends with the offer's first codec; pick the preferred one explicitly
        // (RTCRtpEncodingParameters.codec). Not negotiated -> keep the default.
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video" && t.sender.track != null) (t as NativeTransceiver).guard.useOr(Unit) { krtc_transceiver_set_send_codec(it, mime) }
            }
        }
    }

    override val localDescription: RTCSessionDescription?
        get() = guard.useOr(null) { krtc_pc_local_description(it) }?.let { p -> RTCSessionDescription(RTCSdpType.ANSWER, p.toKString().also { krtc_free(p) }) }

    override val remoteDescription: RTCSessionDescription?
        get() = guard.useOr(null) { krtc_pc_remote_description(it) }?.let { p -> RTCSessionDescription(RTCSdpType.OFFER, p.toKString().also { krtc_free(p) }) }

    // The native calls run on libwebrtc's signaling thread, which also delivers OnTrack (onEvent takes `lock`): they
    // must not be made while holding `lock`, or a track event during another thread's call deadlocks both.
    override fun getTransceivers(): List<RTCRtpTransceiver> = guard.useOr(emptyList()) { handle ->
        val count = krtc_pc_transceiver_count(handle)
        (0 until count).mapNotNull { i ->
            val h = krtc_pc_transceiver_at(handle, i) ?: return@mapNotNull null
            val mid = krtc_transceiver_mid(h)?.let { p -> p.toKString().also { krtc_free(p) } }
            if (mid == null) {
                krtc_transceiver_release(h)
                return@mapNotNull null
            }
            synchronized(lock) { transceivers[mid] }?.let { known ->
                krtc_transceiver_release(h)
                return@mapNotNull known
            }
            val created = NativeTransceiver(h, NativeTrack(krtc_transceiver_receiver_track(h)!!))
            val kept = synchronized(lock) { transceivers[mid] ?: created.also { transceivers[mid] = it } }
            if (kept !== created) created.release() // an OnTrack for this mid came in between
            kept
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        krtc_pc_set_data_channel_handler(handle, null, null)
        synchronized(lock) { channels.toList().also { channels.clear() } }.forEach { it.release() }
        guard.release { krtc_pc_release(it) } // waits for calls in flight; later ones fail instead of touching freed memory
        synchronized(lock) { (transceivers.values + retired).toList().also { transceivers.clear(); retired.clear() } }.forEach { it.release() }
        self.dispose()
        state.value = RTCPeerConnectionState.CLOSED
    }
}
