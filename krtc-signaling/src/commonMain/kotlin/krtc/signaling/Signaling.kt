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

/** Another participant: its id, name and whether its microphone and camera are muted (by itself or by the server). */
@Serializable
public data class PeerInfo(val id: String, val name: String, val audioMuted: Boolean = false, val videoMuted: Boolean = false)

/** Server → client. */
@Serializable
public sealed class ServerMessage {
    /**
     * [resume]: a secret the client presents (`resume` query parameter) to take this session over on a new
     * connection after its socket dropped, within the server's grace period; null if the server does not resume.
     */
    @Serializable @SerialName("welcome")
    public data class Welcome(
        val id: String,
        val room: String,
        val peers: List<PeerInfo>,
        val protocol: Int = SignalingProtocol.VERSION,
        val resume: String? = null,
    ) : ServerMessage()

    /**
     * `mids` maps each downlink m-line to the participant whose media it carries; `screens` lists the mids that
     * carry screen shares (the others are microphones and cameras). The client publishes its screen on m-line `s`.
     */
    @Serializable @SerialName("offer")
    public data class Offer(
        val version: Long,
        val sdp: String,
        val mids: Map<String, String> = emptyMap(),
        val screens: List<String> = emptyList(),
    ) : ServerMessage()

    @Serializable @SerialName("peer-joined")
    public data class PeerJoined(val id: String, val name: String) : ServerMessage()

    @Serializable @SerialName("peer-left")
    public data class PeerLeft(val id: String) : ServerMessage()

    /**
     * A participant muted or unmuted its microphone or camera ([ClientMessage.MediaState]), or the server did.
     * Sent to everyone in the room, the participant itself included.
     */
    @Serializable @SerialName("peer-state")
    public data class PeerState(val id: String, val audioMuted: Boolean, val videoMuted: Boolean) : ServerMessage()

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

    /**
     * Asks for an ICE restart (RFC 8445 §9) after the client's connectivity failed, e.g. on a network change.
     * The server answers with a new offer carrying fresh ICE credentials; DTLS and media state survive.
     */
    @Serializable @SerialName("restart-ice")
    public data object RestartIce : ClientMessage()

    /**
     * Asks for a new offer, e.g. to start or stop publishing a screen share or a camera: the client changes the
     * direction of its uplink m-line in the answer to that offer.
     */
    @Serializable @SerialName("renegotiate")
    public data object Renegotiate : ClientMessage()

    /** The client leaves for good: the server removes it at once instead of waiting for it to resume. */
    @Serializable @SerialName("leave")
    public data object Leave : ClientMessage()

    /** The client muted or unmuted its microphone or camera; the server tells the others ([ServerMessage.PeerState]). */
    @Serializable @SerialName("media-state")
    public data class MediaState(val audioMuted: Boolean, val videoMuted: Boolean) : ClientMessage()

    /**
     * Starts or stops receiving one participant's [feed] ([FEED_CAMERA] or [FEED_SCREEN]; the screen with its sound).
     * The m-lines stay negotiated: the server just stops forwarding, and resumes with a key frame. Everything is
     * received by default.
     */
    @Serializable @SerialName("subscribe")
    public data class Subscribe(val peer: String, val feed: String, val enabled: Boolean) : ClientMessage() {
        public companion object {
            public const val FEED_CAMERA: String = "camera"
            public const val FEED_SCREEN: String = "screen"
        }
    }
}
