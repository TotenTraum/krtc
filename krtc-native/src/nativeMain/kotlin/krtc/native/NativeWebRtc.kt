package krtc.native

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
import krtc.native.shim.KRTC_EVENT_CONNECTION_STATE
import krtc.native.shim.KRTC_EVENT_TRACK
import cnames.structs.krtc_factory
import krtc.native.shim.krtc_factory_create_with
import krtc.native.shim.krtc_factory_output_level
import krtc.native.shim.krtc_factory_pull_audio
import krtc.native.shim.krtc_factory_push_audio
import krtc.native.shim.krtc_factory_set_test_tone
import krtc.native.shim.krtc_factory_video_codecs
import krtc.native.shim.krtc_pc_get_stats
import krtc.native.shim.krtc_transceiver_prefer_codec
import krtc.native.shim.krtc_factory_create_audio_track
import krtc.native.shim.krtc_factory_create_test_video_track
import krtc.native.shim.krtc_factory_release
import krtc.native.shim.krtc_free
import cnames.structs.krtc_pc
import krtc.native.shim.krtc_pc_create
import krtc.native.shim.krtc_pc_create_answer
import krtc.native.shim.krtc_pc_local_description
import krtc.native.shim.krtc_pc_release
import krtc.native.shim.krtc_pc_remote_description
import krtc.native.shim.krtc_pc_set_local_description
import krtc.native.shim.krtc_pc_set_remote_description
import krtc.native.shim.krtc_pc_transceiver_at
import krtc.native.shim.krtc_pc_transceiver_count
import cnames.structs.krtc_track
import krtc.native.shim.krtc_track_enabled
import krtc.native.shim.krtc_track_id
import krtc.native.shim.krtc_track_is_video
import krtc.native.shim.krtc_track_release
import krtc.native.shim.krtc_track_set_enabled
import krtc.native.shim.krtc_track_video_stats
import cnames.structs.krtc_transceiver
import krtc.native.shim.krtc_transceiver_direction
import krtc.native.shim.krtc_transceiver_mid
import krtc.native.shim.krtc_transceiver_receiver_track
import krtc.native.shim.krtc_transceiver_release
import krtc.native.shim.krtc_transceiver_set_send_codec
import krtc.native.shim.krtc_transceiver_set_direction
import krtc.native.shim.krtc_transceiver_set_sender_track
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
) : PeerConnectionFactory, AutoCloseable {
    internal val handle: CPointer<krtc_factory> =
        krtc_factory_create_with(if (audioDevice == AudioDevice.PLATFORM) 1 else 0, if (platformVideoCodecs) 1 else 0)
            ?: error("libwebrtc factory creation failed")

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

    actual override fun close() {
        krtc_factory_release(handle)
    }
}

public actual class NativeTrack internal constructor(internal val handle: CPointer<krtc_track>) : MediaStreamTrack {
    actual override val id: String = krtc_track_id(handle)!!.let { p -> p.toKString().also { krtc_free(p) } }
    actual override val kind: String = if (krtc_track_is_video(handle) != 0) "video" else "audio"
    actual override var enabled: Boolean
        get() = krtc_track_enabled(handle) != 0
        set(value) = krtc_track_set_enabled(handle, if (value) 1 else 0)

    actual override fun stop() {
        enabled = false
    }

    /** For received video: frames delivered so far and the last frame size. */
    public actual fun videoStats(): NativeVideoStats = memScoped {
        val frames = alloc<LongVar>()
        val w = alloc<IntVar>()
        val h = alloc<IntVar>()
        krtc_track_video_stats(handle, frames.ptr, w.ptr, h.ptr)
        NativeVideoStats(frames.value, w.value, h.value)
    }

    public actual fun release() {
        krtc_track_release(handle)
    }
}

private class NativeSender(private val transceiver: NativeTransceiver) : RTCRtpSender {
    override var track: MediaStreamTrack? = null

    override suspend fun replaceTrack(withTrack: MediaStreamTrack?) {
        val native = withTrack as NativeTrack?
        check(krtc_transceiver_set_sender_track(transceiver.handle, native?.handle) == 0) { "replaceTrack failed" }
        track = withTrack
    }
}

private class NativeReceiver(override val track: MediaStreamTrack) : RTCRtpReceiver

internal class NativeTransceiver(internal val handle: CPointer<krtc_transceiver>, receiverTrack: NativeTrack) : RTCRtpTransceiver {
    override val mid: String? get() = krtc_transceiver_mid(handle)?.let { p -> p.toKString().also { krtc_free(p) } }
    override var direction: RTCRtpTransceiverDirection
        get() = RTCRtpTransceiverDirection.entries[krtc_transceiver_direction(handle)]
        set(value) {
            check(krtc_transceiver_set_direction(handle, value.ordinal) == 0) { "setDirection($value) failed" }
        }
    override val sender: RTCRtpSender = NativeSender(this)
    override val receiver: RTCRtpReceiver = NativeReceiver(receiverTrack)

    fun release() {
        krtc_transceiver_release(handle)
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

private val eventCallback = staticCFunction { user: COpaquePointer?, event: Int, state: Int, transceiver: CPointer<krtc_transceiver>?, track: CPointer<krtc_track>? ->
    user!!.asStableRef<NativePeerConnection>().get().onEvent(event, state, transceiver, track)
}

internal class NativePeerConnection(private val factory: NativePeerConnectionFactory) : RTCPeerConnection {
    private val self = StableRef.create(this)
    private val handle: CPointer<krtc_pc> = krtc_pc_create(factory.handle, eventCallback, self.asCPointer()) ?: error("peer connection creation failed")
    private val lock = SynchronizedObject()
    private val transceivers = mutableMapOf<String, NativeTransceiver>() // by mid; guarded by lock
    private val tracks = MutableSharedFlow<RTCTrackEvent>(replay = 64)
    private val state = MutableStateFlow(RTCPeerConnectionState.NEW)
    private var closed = false

    override val ontrack: SharedFlow<RTCTrackEvent> get() = tracks
    override val connectionState: StateFlow<RTCPeerConnectionState> = state.asStateFlow()

    /** On libwebrtc's signaling thread: hand off without blocking. */
    fun onEvent(event: Int, value: Int, transceiver: CPointer<krtc_transceiver>?, track: CPointer<krtc_track>?) {
        when (event) {
            KRTC_EVENT_CONNECTION_STATE.toInt() -> state.value = RTCPeerConnectionState.entries.getOrElse(value) { RTCPeerConnectionState.FAILED }
            KRTC_EVENT_TRACK.toInt() -> {
                val t = NativeTransceiver(transceiver!!, NativeTrack(track!!))
                val mid = t.mid
                if (mid != null) synchronized(lock) { transceivers[mid]?.release(); transceivers[mid] = t }
                tracks.tryEmit(RTCTrackEvent(t.receiver, t.receiver.track, t))
            }
        }
    }

    private suspend fun call(block: (COpaquePointer) -> Unit): String? = suspendCancellableCoroutine { c ->
        block(StableRef.create(Pending(c)).asCPointer())
    }

    override suspend fun setRemoteDescription(description: RTCSessionDescription) {
        call { krtc_pc_set_remote_description(handle, description.type.value, description.sdp, doneCallback, it) }
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video") krtc_transceiver_prefer_codec(factory.handle, (t as NativeTransceiver).handle, mime)
            }
        }
    }

    override suspend fun getStats(): Map<String, RTCStats> {
        val json = call { krtc_pc_get_stats(handle, doneCallback, it) } ?: return emptyMap()
        return parseStatsReport(json)
    }

    override suspend fun createAnswer(): RTCSessionDescription =
        RTCSessionDescription(RTCSdpType.ANSWER, call { krtc_pc_create_answer(handle, doneCallback, it) }!!)

    override suspend fun setLocalDescription(description: RTCSessionDescription) {
        call { krtc_pc_set_local_description(handle, description.type.value, description.sdp, doneCallback, it) }
        // As answerer libwebrtc sends with the offer's first codec; pick the preferred one explicitly
        // (RTCRtpEncodingParameters.codec). Not negotiated -> keep the default.
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video" && t.sender.track != null) krtc_transceiver_set_send_codec((t as NativeTransceiver).handle, mime)
            }
        }
    }

    override val localDescription: RTCSessionDescription?
        get() = krtc_pc_local_description(handle)?.let { p -> RTCSessionDescription(RTCSdpType.ANSWER, p.toKString().also { krtc_free(p) }) }

    override val remoteDescription: RTCSessionDescription?
        get() = krtc_pc_remote_description(handle)?.let { p -> RTCSessionDescription(RTCSdpType.OFFER, p.toKString().also { krtc_free(p) }) }

    override fun getTransceivers(): List<RTCRtpTransceiver> = synchronized(lock) {
        val count = krtc_pc_transceiver_count(handle)
        (0 until count).mapNotNull { i ->
            val h = krtc_pc_transceiver_at(handle, i) ?: return@mapNotNull null
            val mid = krtc_transceiver_mid(h)?.let { p -> p.toKString().also { krtc_free(p) } }
            if (mid == null) {
                krtc_transceiver_release(h)
                return@mapNotNull null
            }
            transceivers[mid]?.also { krtc_transceiver_release(h) }
                ?: NativeTransceiver(h, NativeTrack(krtc_transceiver_receiver_track(h)!!)).also { transceivers[mid] = it }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        krtc_pc_release(handle)
        synchronized(lock) { transceivers.values.forEach { it.release() }; transceivers.clear() }
        self.dispose()
        state.value = RTCPeerConnectionState.CLOSED
    }
}
