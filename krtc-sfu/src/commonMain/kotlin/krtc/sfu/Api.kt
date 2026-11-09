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
    /** Worker threads (and UDP sockets); rooms are spread across them. */
    val shards: Int = availableProcessors(),
    /** Who may join which room, under which id. Defaults to allowing everyone with generated ids. */
    val joinPolicy: JoinPolicy = JoinPolicy.AllowAll,
    /** Capacity of [Sfu.events]; when a slow collector falls behind, the oldest events are dropped. */
    val eventBufferSize: Int = 1024,
    /** Testing only: percentage of downlink video packets to drop, to exercise NACK/RTX recovery. */
    val simulatedLossPercent: Int = 0,
)

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
)

public data class RoomInfo(val name: String, val participants: List<ParticipantInfo>)

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
    val nacksAnswered: Long,
    val stunRequests: Long,
    val stunRejected: Long,
    val dtlsConnected: Long,
    val dtlsFailed: Long,
    val unknownSource: Long,
    val shardQueueDepth: List<Int>,
)
