package krtc.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The krtc-sfu signaling protocol. The server is always the SDP offerer.
 *
 * Transport-agnostic: the messages travel over whatever the host uses (WebSocket in krtc-sfu-ktor,
 * or the host's own channel). Unknown message types and fields are ignored on decode, so either side
 * can add messages without breaking the other.
 */
public object SignalingProtocol {
    /** Bumped on incompatible changes; sent in [ServerMessage.Welcome]. */
    public const val VERSION: Int = 1

    public val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(message: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), message)
    public fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    /** Null for malformed or unknown messages. */
    public fun decodeServer(text: String): ServerMessage? = runCatching { json.decodeFromString(ServerMessage.serializer(), text) }.getOrNull()

    /** Null for malformed or unknown messages. */
    public fun decodeClient(text: String): ClientMessage? = runCatching { json.decodeFromString(ClientMessage.serializer(), text) }.getOrNull()
}

@Serializable
public data class PeerInfo(val id: String, val name: String)

/** Server → client. */
@Serializable
public sealed class ServerMessage {
    @Serializable @SerialName("welcome")
    public data class Welcome(
        val id: String,
        val room: String,
        val peers: List<PeerInfo>,
        val protocol: Int = SignalingProtocol.VERSION,
    ) : ServerMessage()

    /** `mids` maps each downlink m-line to the participant whose media it carries. */
    @Serializable @SerialName("offer")
    public data class Offer(val version: Long, val sdp: String, val mids: Map<String, String> = emptyMap()) : ServerMessage()

    @Serializable @SerialName("peer-joined")
    public data class PeerJoined(val id: String, val name: String) : ServerMessage()

    @Serializable @SerialName("peer-left")
    public data class PeerLeft(val id: String) : ServerMessage()

    @Serializable @SerialName("speaking")
    public data class Speaking(val id: String, val speaking: Boolean) : ServerMessage()

    /** The server is about to close this participant's session (kicked, replaced, consent lost...). */
    @Serializable @SerialName("bye")
    public data class Bye(val reason: String) : ServerMessage()
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
