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

    override fun close()
}

public expect class NativeTrack : MediaStreamTrack {
    override val id: String
    override val kind: String
    override var enabled: Boolean
    override fun stop()

    /** For received video: frames delivered so far and the last frame size. */
    public fun videoStats(): NativeVideoStats

    public fun release()
}

public data class NativeVideoStats(val frames: Long, val width: Int, val height: Int)
