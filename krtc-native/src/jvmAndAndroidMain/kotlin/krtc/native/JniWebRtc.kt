package krtc.native

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
) : PeerConnectionFactory, AutoCloseable {
    internal val handle: Long = Jni.factoryCreate(if (audioDevice == AudioDevice.PLATFORM) 1 else 0, if (platformVideoCodecs) 1 else 0)
        .also { check(it != 0L) { "libwebrtc factory creation failed" } }

    /** Video codecs this factory can send, e.g. [video/VP8, video/H264, ...]. */
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

    actual override fun close(): Unit = Jni.factoryRelease(handle)
}

public actual class NativeTrack internal constructor(internal val handle: Long) : MediaStreamTrack {
    actual override val id: String = Jni.trackId(handle)
    actual override val kind: String = if (Jni.trackIsVideo(handle)) "video" else "audio"
    actual override var enabled: Boolean
        get() = Jni.trackEnabled(handle)
        set(value) = Jni.trackSetEnabled(handle, value)

    actual override fun stop() {
        enabled = false
    }

    /** For received video: frames delivered so far and the last frame size. */
    public actual fun videoStats(): NativeVideoStats {
        val out = LongArray(3)
        Jni.trackVideoStats(handle, out)
        return NativeVideoStats(out[0], out[1].toInt(), out[2].toInt())
    }

    public actual fun release(): Unit = Jni.trackRelease(handle)
}

private class NativeSender(private val transceiver: NativeTransceiver) : RTCRtpSender {
    @Volatile override var track: MediaStreamTrack? = null

    override suspend fun replaceTrack(withTrack: MediaStreamTrack?) {
        check(Jni.transceiverSetSenderTrack(transceiver.handle, (withTrack as NativeTrack?)?.handle ?: 0L) == 0) { "replaceTrack failed" }
        track = withTrack
    }
}

private class NativeReceiver(override val track: MediaStreamTrack) : RTCRtpReceiver

internal class NativeTransceiver(internal val handle: Long, receiverTrack: NativeTrack) : RTCRtpTransceiver {
    override val mid: String? get() = Jni.transceiverMid(handle)
    override var direction: RTCRtpTransceiverDirection
        get() = RTCRtpTransceiverDirection.entries[Jni.transceiverDirection(handle)]
        set(value) {
            check(Jni.transceiverSetDirection(handle, value.ordinal) == 0) { "setDirection($value) failed" }
        }
    override val sender: RTCRtpSender = NativeSender(this)
    override val receiver: RTCRtpReceiver = NativeReceiver(receiverTrack)

    fun release() {
        Jni.transceiverRelease(handle)
        (receiver.track as NativeTrack).release()
    }
}

internal class NativePeerConnection(private val factory: NativePeerConnectionFactory) : RTCPeerConnection {
    private val lock = Any()
    private val transceivers = mutableMapOf<String, NativeTransceiver>() // by mid; guarded by lock
    private val tracks = MutableSharedFlow<RTCTrackEvent>(replay = 64)
    private val state = MutableStateFlow(RTCPeerConnectionState.NEW)
    private var closed = false
    private val handle: Long = Jni.pcCreate(factory.handle, ::onEvent).also { check(it != 0L) { "peer connection creation failed" } }

    override val ontrack: SharedFlow<RTCTrackEvent> get() = tracks
    override val connectionState: StateFlow<RTCPeerConnectionState> = state.asStateFlow()

    /** On libwebrtc's signaling thread: hand off without blocking. */
    private fun onEvent(event: Int, value: Int, transceiver: Long, track: Long) {
        when (event) {
            EVENT_CONNECTION_STATE -> state.value = RTCPeerConnectionState.entries.getOrElse(value) { RTCPeerConnectionState.FAILED }
            EVENT_TRACK -> {
                val t = NativeTransceiver(transceiver, NativeTrack(track))
                val mid = t.mid
                if (mid != null) synchronized(lock) { transceivers[mid]?.release(); transceivers[mid] = t }
                tracks.tryEmit(RTCTrackEvent(t.receiver, t.receiver.track, t))
            }
        }
    }

    private suspend fun call(block: (Jni.Done) -> Unit): String? = suspendCancellableCoroutine { c ->
        block(Jni.Done { result, error -> if (error != null) c.resumeWithException(IllegalStateException(error)) else c.resume(result) })
    }

    override suspend fun setRemoteDescription(description: RTCSessionDescription) {
        call { Jni.pcSetRemoteDescription(handle, description.type.value, description.sdp, it) }
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video") Jni.transceiverPreferCodec(factory.handle, (t as NativeTransceiver).handle, mime)
            }
        }
    }

    override suspend fun getStats(): Map<String, RTCStats> = call { Jni.pcGetStats(handle, it) }?.let(::parseStatsReport).orEmpty()

    override suspend fun createAnswer(): RTCSessionDescription = RTCSessionDescription(RTCSdpType.ANSWER, call { Jni.pcCreateAnswer(handle, it) }!!)

    override suspend fun setLocalDescription(description: RTCSessionDescription) {
        call { Jni.pcSetLocalDescription(handle, description.type.value, description.sdp, it) }
        // As answerer libwebrtc sends with the offer's first codec; pick the preferred one explicitly.
        factory.preferredVideoCodec?.let { mime ->
            for (t in getTransceivers()) {
                if (t.receiver.track.kind == "video" && t.sender.track != null) Jni.transceiverSetSendCodec((t as NativeTransceiver).handle, mime)
            }
        }
    }

    override val localDescription: RTCSessionDescription? get() = Jni.pcLocalDescription(handle)?.let { RTCSessionDescription(RTCSdpType.ANSWER, it) }
    override val remoteDescription: RTCSessionDescription? get() = Jni.pcRemoteDescription(handle)?.let { RTCSessionDescription(RTCSdpType.OFFER, it) }

    override fun getTransceivers(): List<RTCRtpTransceiver> = synchronized(lock) {
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
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        Jni.pcRelease(handle)
        synchronized(lock) { transceivers.values.forEach { it.release() }; transceivers.clear() }
        state.value = RTCPeerConnectionState.CLOSED
    }
}
