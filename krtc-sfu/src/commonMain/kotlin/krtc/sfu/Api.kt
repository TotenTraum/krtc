package krtc.sfu

import krtc.signaling.ServerMessage
import krtc.sfu.platform.availableProcessors

// Public API for embedding krtc-sfu in a host server (ADR-0013). Everything else in this module is internal.

/**
 * Where the SFU sends signaling messages for one participant: the host's transport (a WebSocket,
 * gRPC stream, message bus...). Implementations must not block: queue and return.
 */
public interface SignalSink {
    public fun send(message: ServerMessage)

    /**
     * The SFU ends this participant's session (kicked, replaced by a newer session with the same id,
     * media failed, consent expired). The host closes its transport; the SFU has already removed the
     * participant, so calling [Sfu.leave] afterwards is harmless.
     */
    public fun close(reason: String)
}

/** A participant as seen by the host. */
public interface SfuParticipant {
    public val id: String
    public val name: String
    public val roomName: String
}

public data class SfuConfig(
    /** UDP port for all media (0 picks a free port; see [Sfu.udpPort]). */
    val udpPort: Int = 50000,
    val bindAddress: String = "0.0.0.0",
    /** Address put into ICE candidates; must be reachable by clients (public IP behind NAT). */
    val announcedAddress: String = "127.0.0.1",
    /**
     * All addresses to offer as ICE host candidates, in order of preference (e.g. a public IPv4 and an IPv6
     * address). Empty means just [announcedAddress].
     */
    val announcedAddresses: List<String> = emptyList(),
    /** Also accept ICE over TCP (RFC 6544) on the same port, for clients whose UDP is blocked. */
    val iceTcp: Boolean = true,
    /** Offer and accept UDP candidates. Off only to force ICE-TCP (testing, or networks without UDP). */
    val iceUdp: Boolean = true,
    /** Worker threads (and UDP sockets); rooms are spread across them. */
    val shards: Int = availableProcessors(),
    /** Who may join which room, under which id. Defaults to allowing everyone with generated ids. */
    val joinPolicy: JoinPolicy = JoinPolicy.AllowAll,
    /** Capacity of [Sfu.events]; when a slow collector falls behind, the oldest events are dropped. */
    val eventBufferSize: Int = 1024,
    /**
     * Offer a data channel transport (SCTP over DTLS) so participants can exchange messages through the SFU
     * (ADR-0016); see [Sfu.sendData] and [dataPolicy].
     */
    val dataChannels: Boolean = true,
    /** Offer every participant a screen share uplink and the screen shares of the others (one m-line per slot). */
    val screenShare: Boolean = true,
    /** Decides what happens to each data message a participant sends. Defaults to relaying it unchanged. */
    val dataPolicy: DataPolicy = DataPolicy.Relay,
    /** Testing only: percentage of downlink video packets to drop, to exercise NACK/RTX recovery. */
    val simulatedLossPercent: Int = 0,
    /** Testing only: percentage of incoming video packets to drop, to exercise upstream NACK/RTX recovery. */
    val simulatedUplinkLossPercent: Int = 0,
    /** Testing only: cap every subscriber's downlink at this many bits per second (token bucket, drops the excess). */
    val simulatedDownlinkBps: Long = 0,
)

/**
 * A room message on the participants' data channels (ADR-0016), as seen by [DataPolicy] and sent by [Sfu.sendData].
 * The SFU does not look into [payload].
 */
public class DataMessage(
    public val room: String,
    /** The sending participant's id; null when the host sent it with [Sfu.sendData]. */
    public val from: String?,
    /** Application-defined label, up to 255 bytes of UTF-8 (e.g. "chat", "cursor"). */
    public val topic: String,
    public val payload: ByteArray,
    /** Reliable and ordered, or best-effort: unordered, never retransmitted (cursors, telemetry). */
    public val reliable: Boolean,
    /** Recipient participant ids; empty means everyone in the room except the sender. */
    public val to: List<String>,
) {
    public fun copy(
        topic: String = this.topic,
        payload: ByteArray = this.payload,
        reliable: Boolean = this.reliable,
        to: List<String> = this.to,
    ): DataMessage = DataMessage(room, from, topic, payload, reliable, to)

    override fun toString(): String = "DataMessage(room=$room, from=$from, topic=$topic, ${payload.size} bytes, reliable=$reliable, to=$to)"
}

/**
 * The host's control over data messages: called for every message a participant sends, before the SFU relays it.
 * Runs on the SFU's media thread: it must not block (hand slow work to another thread). Return the message to
 * relay, changed if needed (e.g. other recipients), or null to drop it, for example after consuming a message
 * meant for the server.
 */
public fun interface DataPolicy {
    public fun filter(message: DataMessage): DataMessage?

    public companion object {
        /** Relay every message as the sender addressed it. */
        public val Relay: DataPolicy = DataPolicy { it }

        /** Relay nothing: data channels stay available to [Sfu.sendData] only. */
        public val ServerOnly: DataPolicy = DataPolicy { null }
    }
}

/** What a client asks for when joining. [credentials] is opaque to the SFU (e.g. a token for the host's auth). */
public data class JoinRequest(
    val room: String,
    val displayName: String,
    val credentials: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

public sealed class JoinDecision {
    /**
     * Admit. [participantId] lets the host use its own user ids (a newer session with the same id
     * replaces the older one); null means the SFU generates an id. [displayName] overrides the requested one.
     */
    public data class Allow(val participantId: String? = null, val displayName: String? = null) : JoinDecision()

    public data class Deny(val reason: String) : JoinDecision()
}

/** The host's admission control. Called once per [Sfu.join]; may suspend (e.g. to check a token). */
public fun interface JoinPolicy {
    public suspend fun decide(request: JoinRequest): JoinDecision

    public companion object {
        public val AllowAll: JoinPolicy = JoinPolicy { JoinDecision.Allow() }
    }
}

public class JoinDeniedException(public val reason: String) : Exception("join denied: $reason")

/** What happens in the SFU, for the host's logic, analytics and monitoring. */
public sealed class SfuEvent {
    public abstract val room: String

    public data class RoomOpened(override val room: String) : SfuEvent()
    public data class RoomClosed(override val room: String) : SfuEvent()
    public data class ParticipantJoined(override val room: String, val participantId: String, val name: String) : SfuEvent()
    public data class ParticipantLeft(override val room: String, val participantId: String, val reason: String) : SfuEvent()

    /** ICE and DTLS completed: media can flow. */
    public data class MediaConnected(override val room: String, val participantId: String, val srtpProfile: String) : SfuEvent()
    public data class MediaFailed(override val room: String, val participantId: String, val error: String) : SfuEvent()
    public data class SpeakingChanged(override val room: String, val participantId: String, val speaking: Boolean) : SfuEvent()
    public data class VideoPublishingChanged(override val room: String, val participantId: String, val publishing: Boolean) : SfuEvent()
    public data class ScreenShareChanged(override val room: String, val participantId: String, val sharing: Boolean) : SfuEvent()
}

public data class ParticipantInfo(
    val id: String,
    val name: String,
    val room: String,
    val mediaConnected: Boolean,
    val publishesVideo: Boolean,
    val audioMuted: Boolean,
    val videoMuted: Boolean,
    val speaking: Boolean,
    val sharesScreen: Boolean = false,
    val screenMuted: Boolean = false,
)

public data class RoomInfo(val name: String, val participants: List<ParticipantInfo>)

/**
 * Congestion-control view of one participant's transport (a snapshot; values come from the media thread).
 * [downlinkEstimateBps] is the SFU's estimate of what it can send to this participant (transport-cc), 0 when
 * the participant sends no transport-cc feedback; [videoLayers] maps each publisher it watches to the
 * simulcast layer it currently gets (0 = lowest, -1 = none yet).
 */
public data class SfuLinkStats(
    val participantId: String,
    val roomName: String,
    val downlinkEstimateBps: Long,
    val downlinkAckedBps: Long,
    val downlinkLossPercent: Double,
    val videoLayers: Map<String, Int>,
)

/** A snapshot of the SFU's counters (cumulative since start, except the gauges). */
public data class SfuMetrics(
    val participants: Int,
    val rooms: Int,
    val rtpIn: Long,
    val rtpOut: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    val rtcpIn: Long,
    val rtcpOut: Long,
    val srtpFailures: Long,
    val droppedOverload: Long,
    val keyframeRequests: Long,
    /** Retransmissions the SFU served to subscribers from its cache. */
    val nacksAnswered: Long,
    /** NACKs the SFU sent to publishers for packets lost on their uplink. */
    val nacksSent: Long,
    /** Packets restored from publishers' RTX retransmissions. */
    val rtxRecovered: Long,
    /** Data channel messages received from participants. */
    val dataMessagesIn: Long,
    /** Data channel messages delivered to participants (one per recipient). */
    val dataMessagesOut: Long,
    /** Data messages not delivered: a recipient's queue was full or its channel missing, or the message was malformed. */
    val dataMessagesDropped: Long,
    val stunRequests: Long,
    val stunRejected: Long,
    val dtlsConnected: Long,
    val dtlsFailed: Long,
    val unknownSource: Long,
    val shardQueueDepth: List<Int>,
)
