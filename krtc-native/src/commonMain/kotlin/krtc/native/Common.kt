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

/** What the microphone's input gate lets through ([NativePeerConnectionFactory.setInputGate]). */
public enum class InputGate {
    /** Everything. */
    OPEN,

    /** Only sound at or above the threshold, and a moment after it (voice activation). */
    VOICE_ACTIVITY,

    /** Nothing: silence is sent (e.g. push-to-talk not held). */
    CLOSED,
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
) : PeerConnectionFactory, krtc.core.SilentAudioTrackFactory, AutoCloseable {
    public val audioDevice: AudioDevice
    public val preferredVideoCodec: String?

    /**
     * Microphones and speakers of [AudioDevice.PLATFORM] audio, by index (empty with synthetic audio). Pick one
     * with [setAudioInput] / [setAudioOutput], before or during a call.
     */
    public val audioInputs: List<String>
    public val audioOutputs: List<String>

    /** Switches the microphone ([audioInputs] index); a running capture restarts on it. False if it failed. */
    public fun setAudioInput(index: Int): Boolean

    /** Switches the speakers or headphones ([audioOutputs] index); playout restarts on them. False if it failed. */
    public fun setAudioOutput(index: Int): Boolean

    /**
     * Processing of the microphone, all on by default: echo cancellation, noise suppression and automatic gain
     * control (an adaptive digital gain that keeps quiet and loud voices at one level, plus the OS input volume on
     * desktop). Applies at once and to microphone tracks created afterwards (with [AudioDevice.SYNTHETIC], to the
     * pushed PCM).
     */
    public fun setAudioProcessing(echoCancellation: Boolean = true, noiseSuppression: Boolean = true, autoGainControl: Boolean = true)

    /**
     * Gates the microphone after its processing: with [InputGate.VOICE_ACTIVITY], audio passes only while its level
     * (0..1, peak) is at or above [threshold] and for [holdMillis] after, so pauses between words stay open. A closed
     * gate sends silence at once, without touching the track or renegotiating. Not for pushed [NativeAudioSource]s.
     */
    public fun setInputGate(gate: InputGate, threshold: Double = 0.05, holdMillis: Int = 400)

    /** The microphone's recent peak level (0..1), measured in every gate mode, also while the gate is closed. */
    public val inputLevel: Double

    /** Whether the input gate lets the microphone through right now. */
    public val inputGateOpen: Boolean

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

    /**
     * Shares a display or window (see [nativeScreenSources]; null: the whole desktop / main display) at up to [fps],
     * scaled down to fit [maxWidth] × [maxHeight]. Null where the platform has no built-in screen capture (Android:
     * capture with `AndroidScreenCapture` into [createScreencastSource]) or the user has not permitted it (macOS asks
     * for Screen Recording permission on first use; the permission applies from the next launch). Publish it with
     * `RoomClient.setScreen(createVideoTrack(source))`.
     */
    public fun createScreenSource(source: ScreenSource? = null, fps: Int = 15, maxWidth: Int = 1920, maxHeight: Int = 1080): NativeVideoSource?

    /**
     * A video source for screen content the application captures itself: like [createVideoSource], but tracks on it
     * are marked as screen content, so encoders keep resolution and sharpness and give up frame rate first.
     */
    public fun createScreencastSource(): NativeVideoSource

    /** A video track on [source]; several tracks may share one source. */
    public fun createVideoTrack(source: NativeVideoSource, id: String = "video"): NativeTrack

    /** An audio source the application feeds with PCM ([NativeAudioSource.push]), apart from the factory's audio device. */
    public fun createAudioSource(): NativeAudioSource

    /**
     * The system's sound, for a screen share (`RoomClient.setScreen(video, audio)`): macOS (ScreenCaptureKit, needs
     * Screen Recording permission, macOS 13+; the call's own sound is left out), Linux (the PulseAudio/PipeWire
     * monitor of the default output), Windows (WASAPI loopback; includes the call's own sound), iOS (this app's sound
     * while the screen capture runs). Null where unavailable (Android: `AndroidScreenCapture(audio = ...)`).
     */
    public fun createSystemAudioSource(): NativeAudioSource?

    /** An audio track on [source]. */
    public fun createAudioTrack(source: NativeAudioSource, id: String = "audio"): NativeTrack

    /** A silent track on a pushed source nobody pushes to (for an audio m-line that must send before its audio exists). */
    override fun createSilentAudioTrack(id: String): NativeTrack

    override fun close()
}

/** Cameras the built-in capture can open, by index (Apple: AVFoundation, Linux: V4L2; empty elsewhere). */
public expect fun nativeCameras(): List<String>

/** Something the built-in screen capture can share: a display, or a window ([isWindow]). */
public class ScreenSource(public val id: Long, public val isWindow: Boolean, public val title: String) {
    override fun toString(): String = "ScreenSource($id, ${if (isWindow) "window" else "display"}, $title)"
}

/**
 * Displays and windows the built-in screen capture can share: macOS (ScreenCaptureKit; empty until Screen Recording
 * is permitted), Linux (the X11 display), Windows (displays and windows), iOS (this app's screen, ReplayKit);
 * empty on Android.
 */
public expect fun nativeScreenSources(): List<ScreenSource>

/**
 * A still image of [source] for a picker, scaled to fit [maxWidth] × [maxHeight] (it captures briefly, up to about
 * two seconds: call it off the UI thread). Null if it cannot be captured (no permission, a minimized window) or the
 * platform lets the system pick (Android, iOS).
 */
public expect fun nativeScreenThumbnail(source: ScreenSource, maxWidth: Int = 320, maxHeight: Int = 180): VideoFrame?

internal fun parseScreenSources(text: String): List<ScreenSource> = text.lineSequence().mapNotNull { line ->
    val parts = line.split(' ', limit = 3)
    val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
    ScreenSource(id, parts.getOrNull(1) == "w", parts.getOrNull(2).orEmpty())
}.toList()

/** An audio source: PCM pushed by the application or a system sound capture. [close] stops a capture. */
public expect class NativeAudioSource : AutoCloseable {
    /** Pushes 16-bit interleaved PCM: [frames] samples per channel, any rate and channel count. Thread-safe. */
    public fun push(samples: ShortArray, channels: Int = 1, sampleRate: Int = 48_000, frames: Int = samples.size / channels)

    override fun close()
}

/** A video source: frames pushed by the application, a camera or a screen capture. [close] stops a capture. */
public expect class NativeVideoSource : AutoCloseable {
    /** Pushes one I420 frame. [rotation] (0, 90, 180, 270, clockwise) is applied by receivers; [timestampMicros] 0 means now. */
    public fun pushI420(
        width: Int, height: Int,
        y: ByteArray, strideY: Int, u: ByteArray, strideU: Int, v: ByteArray, strideV: Int,
        rotation: Int = 0, timestampMicros: Long = 0,
    )

    /** Pushes one frame of 0xAARRGGBB pixels, rows contiguous. */
    public fun pushArgb(width: Int, height: Int, argb: IntArray, rotation: Int = 0, timestampMicros: Long = 0)

    /** Pushes one frame of R,G,B,A bytes (e.g. Android's RGBA_8888) with [stride] bytes per row. */
    public fun pushRgba(width: Int, height: Int, rgba: ByteArray, stride: Int = width * 4, rotation: Int = 0, timestampMicros: Long = 0)

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

    /** Received audio: playout volume from 0 (silent) to 10; 1 plays it as received. Ignored for other tracks. */
    public fun setVolume(volume: Double)

    public fun release()
}

public data class NativeVideoStats(val frames: Long, val width: Int, val height: Int)
