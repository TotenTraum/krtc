package krtc.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The krtc-sfu signaling protocol (krtc-sfu/README.md), as typed messages.

@Serializable
public data class PeerInfo(val id: String, val name: String)

/** Server → client. Unknown types are ignored, so the server can add messages without breaking clients. */
@Serializable
public sealed class ServerMessage {
    @Serializable @SerialName("welcome")
    public data class Welcome(val id: String, val room: String, val peers: List<PeerInfo>) : ServerMessage()

    /** `mids` maps each downlink m-line to the participant whose media it carries. */
    @Serializable @SerialName("offer")
    public data class Offer(val version: Long, val sdp: String, val mids: Map<String, String> = emptyMap()) : ServerMessage()

    @Serializable @SerialName("peer-joined")
    public data class PeerJoined(val id: String, val name: String) : ServerMessage()

    @Serializable @SerialName("peer-left")
    public data class PeerLeft(val id: String) : ServerMessage()

    @Serializable @SerialName("speaking")
    public data class Speaking(val id: String, val speaking: Boolean) : ServerMessage()
}

/** Client → server. */
@Serializable
public sealed class ClientMessage {
    @Serializable @SerialName("answer")
    public data class Answer(val version: Long, val sdp: String) : ClientMessage()

    /** Simulcast preference for one participant's video: `q`, `h` or `f`. */
    @Serializable @SerialName("layer")
    public data class Layer(val peer: String, val layer: String) : ClientMessage()
}

internal val SignalingJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun decodeServerMessage(text: String): ServerMessage? =
    runCatching { SignalingJson.decodeFromString(ServerMessage.serializer(), text) }.getOrNull()

internal fun encodeClientMessage(message: ClientMessage): String =
    SignalingJson.encodeToString(ClientMessage.serializer(), message)
