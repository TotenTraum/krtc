package krtc.native

import krtc.core.MediaStreamTrack
import krtc.core.PeerConnectionFactory
import krtc.core.RTCConfiguration
import krtc.core.RTCPeerConnection
import krtc.core.RTCStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Where a factory's audio comes from and goes to. */
public enum class AudioDevice {
    /** No sound hardware: the app pushes PCM (or a test tone is sent) and can pull the decoded mix. */
    SYNTHETIC,

    /** The system microphone and speaker (asks for microphone permission). */
    PLATFORM,
}

/**
 * The W3C stats report from libwebrtc's JSON. `RTCStatsReport::ToJson()` is an array of stats objects;
 * an object keyed by id is accepted too.
 */
internal fun parseStatsReport(json: String): Map<String, RTCStats> {
    val objects = when (val root = Json.parseToJsonElement(json)) {
        is JsonArray -> root.map { it.jsonObject }
        is JsonObject -> root.map { (id, v) -> JsonObject(v.jsonObject + ("id" to JsonPrimitive(id))) }
        else -> emptyList()
    }
    return objects.associate { o ->
        val id = o["id"]?.jsonPrimitive?.content ?: ""
        id to RTCStats(
            id = id,
            type = o["type"]?.jsonPrimitive?.content ?: "",
            timestamp = o["timestamp"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            members = o.filterKeys { it != "id" && it != "type" && it != "timestamp" }.mapValues { (_, v) -> v.toKotlin() },
        )
    }
}

private fun JsonElement.toKotlin(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonArray -> map { it.toKotlin() }
    is JsonObject -> mapValues { it.value.toKotlin() }
}

/**
 * krtc-core backend on libwebrtc via the krtc C shim (Kotlin/Native: cinterop; JVM and Android: JNI).
 * One factory owns libwebrtc's threads. [platformVideoCodecs] enables the platform's codecs (VideoToolbox on
 * Apple); [preferredVideoCodec] (e.g. "video/H264") is negotiated first and used for sending when offered.
 */
public expect class NativePeerConnectionFactory(
    audioDevice: AudioDevice = AudioDevice.SYNTHETIC,
    platformVideoCodecs: Boolean = true,
    preferredVideoCodec: String? = null,
) : PeerConnectionFactory, AutoCloseable {
    public val audioDevice: AudioDevice
    public val preferredVideoCodec: String?

    /** Video codecs this factory can send, e.g. [video/VP8, video/H264, ...]. */
    public val videoCodecs: List<String>

    /** Synthetic audio: send a pulsed 440 Hz tone when the app pushes no PCM. */
    public fun setTestTone(enabled: Boolean)

    /** Synthetic audio: queues 48 kHz mono PCM as the microphone; returns how many samples were accepted. */
    public fun pushAudio(samples: ShortArray): Int

    /** Synthetic audio: takes up to [out].size samples of the decoded remote mix (48 kHz mono). */
    public fun pullAudio(out: ShortArray): Int

    /** Synthetic audio: RMS level (0..1) of the remote mix over the last 10 ms. */
    public val outputLevel: Double

    override fun createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection

    /** An audio track fed by the factory's audio device. */
    public fun createAudioTrack(id: String = "audio"): NativeTrack

    /** A synthetic video track (moving square), so no camera access is needed. */
    public fun createTestVideoTrack(id: String = "video", width: Int = 640, height: Int = 360, fps: Int = 15): NativeTrack

    /** A video source the application feeds with [NativeVideoSource.pushI420] / [NativeVideoSource.pushArgb]. */
    public fun createVideoSource(): NativeVideoSource

    /**
     * Captures camera [index] (see [nativeCameras]) at about [width] × [height] and [fps]; null if there is no such
     * camera or the platform has no built-in capture (Android: capture with Camera2 into [createVideoSource]).
     * The OS may ask the user for camera permission.
     */
    public fun createCameraSource(index: Int = 0, width: Int = 1280, height: Int = 720, fps: Int = 30): NativeVideoSource?

    /** A video track on [source]; several tracks may share one source. */
    public fun createVideoTrack(source: NativeVideoSource, id: String = "video"): NativeTrack

    override fun close()
}

/** Cameras the built-in capture can open, by index (Apple: AVFoundation, Linux: V4L2; empty elsewhere). */
public expect fun nativeCameras(): List<String>

/** A video source: frames pushed by the application, or a camera. [close] stops a camera. */
public expect class NativeVideoSource : AutoCloseable {
    /** Pushes one I420 frame. [rotation] (0, 90, 180, 270, clockwise) is applied by receivers; [timestampMicros] 0 means now. */
    public fun pushI420(
        width: Int, height: Int,
        y: ByteArray, strideY: Int, u: ByteArray, strideU: Int, v: ByteArray, strideV: Int,
        rotation: Int = 0, timestampMicros: Long = 0,
    )

    /** Pushes one frame of 0xAARRGGBB pixels, rows contiguous. */
    public fun pushArgb(width: Int, height: Int, argb: IntArray, rotation: Int = 0, timestampMicros: Long = 0)

    override fun close()
}

/**
 * One decoded (or captured) video frame, upright, as 0xAARRGGBB pixels with rows contiguous.
 * [argb] is reused for the next frame of the same size: copy it to keep it beyond the listener call.
 */
public class VideoFrame(public val width: Int, public val height: Int, public val argb: IntArray, public val timestampMicros: Long)

public expect class NativeTrack : MediaStreamTrack {
    override val id: String
    override val kind: String
    override var enabled: Boolean
    override fun stop()

    /** For received video: frames delivered so far and the last frame size. */
    public fun videoStats(): NativeVideoStats

    /**
     * Video tracks: calls [listener] with every frame (on a libwebrtc thread; keep it short), e.g. to render it.
     * Null stops the frames.
     */
    public fun setFrameListener(listener: ((VideoFrame) -> Unit)?)

    public fun release()
}

public data class NativeVideoStats(val frames: Long, val width: Int, val height: Int)
