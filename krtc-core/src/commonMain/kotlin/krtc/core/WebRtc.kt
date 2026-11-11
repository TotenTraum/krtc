package krtc.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Public WebRTC API of krtc — W3C names and semantics (ADR-0004): Promise → suspend, events → Flow,
// state attributes → StateFlow. This is the minimal subset krtc-client needs (ADR-0012); the full
// surface follows krtc-idl. Implementations are pluggable backends behind PeerConnectionFactory.

/** [W3C RTCSdpType](https://w3c.github.io/webrtc-pc/#dom-rtcsdptype) */
public enum class RTCSdpType(public val value: String) { OFFER("offer"), PRANSWER("pranswer"), ANSWER("answer"), ROLLBACK("rollback") }

/** [W3C RTCSessionDescriptionInit](https://w3c.github.io/webrtc-pc/#dom-rtcsessiondescriptioninit) */
public data class RTCSessionDescription(val type: RTCSdpType, val sdp: String)

/** [W3C RTCRtpTransceiverDirection](https://w3c.github.io/webrtc-pc/#dom-rtcrtptransceiverdirection) */
public enum class RTCRtpTransceiverDirection(public val value: String) {
    SENDRECV("sendrecv"), SENDONLY("sendonly"), RECVONLY("recvonly"), INACTIVE("inactive"), STOPPED("stopped"),
}

/** [W3C RTCPeerConnectionState](https://w3c.github.io/webrtc-pc/#dom-rtcpeerconnectionstate) */
public enum class RTCPeerConnectionState(public val value: String) {
    NEW("new"), CONNECTING("connecting"), CONNECTED("connected"), DISCONNECTED("disconnected"), FAILED("failed"), CLOSED("closed"),
}

/** [W3C RTCIceServer](https://w3c.github.io/webrtc-pc/#dom-rtciceserver) */
public data class RTCIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** [W3C RTCConfiguration](https://w3c.github.io/webrtc-pc/#dom-rtcconfiguration) (subset) */
public data class RTCConfiguration(val iceServers: List<RTCIceServer> = emptyList())

/** [W3C MediaStreamTrack](https://w3c.github.io/mediacapture-main/#dom-mediastreamtrack) (subset) */
public interface MediaStreamTrack {
    public val id: String

    /** `"audio"` or `"video"`. */
    public val kind: String
    public var enabled: Boolean
    public fun stop()
}

/** [W3C RTCRtpSender](https://w3c.github.io/webrtc-pc/#dom-rtcrtpsender) (subset) */
public interface RTCRtpSender {
    public val track: MediaStreamTrack?
    public suspend fun replaceTrack(withTrack: MediaStreamTrack?)
}

/** [W3C RTCRtpReceiver](https://w3c.github.io/webrtc-pc/#dom-rtcrtpreceiver) (subset) */
public interface RTCRtpReceiver {
    public val track: MediaStreamTrack
}

/** [W3C RTCRtpTransceiver](https://w3c.github.io/webrtc-pc/#dom-rtcrtptransceiver) (subset) */
public interface RTCRtpTransceiver {
    public val mid: String?
    public var direction: RTCRtpTransceiverDirection
    public val sender: RTCRtpSender
    public val receiver: RTCRtpReceiver
}

/** [W3C RTCTrackEvent](https://w3c.github.io/webrtc-pc/#dom-rtctrackevent) */
public class RTCTrackEvent(
    public val receiver: RTCRtpReceiver,
    public val track: MediaStreamTrack,
    public val transceiver: RTCRtpTransceiver,
)

/**
 * One [W3C RTCStats](https://w3c.github.io/webrtc-pc/#dom-rtcstats) dictionary (`inbound-rtp`, `outbound-rtp`,
 * `codec`, `candidate-pair`, ...; webrtc-stats). [members] holds every other field as String, Double, Long,
 * Boolean, List or Map.
 */
public class RTCStats(public val id: String, public val type: String, public val timestamp: Double, public val members: Map<String, Any?>) {
    public fun string(name: String): String? = members[name] as? String
    public fun long(name: String): Long? = (members[name] as? Number)?.toLong()
    public fun double(name: String): Double? = (members[name] as? Number)?.toDouble()
    override fun toString(): String = "RTCStats($type $id $members)"
}

/** [W3C RTCDataChannelState](https://w3c.github.io/webrtc-pc/#dom-rtcdatachannelstate) */
public enum class RTCDataChannelState(public val value: String) { CONNECTING("connecting"), OPEN("open"), CLOSING("closing"), CLOSED("closed") }

/** [W3C RTCDataChannelInit](https://w3c.github.io/webrtc-pc/#dom-rtcdatachannelinit) */
public data class RTCDataChannelInit(
    val ordered: Boolean = true,
    /** Milliseconds a message may take to be delivered; at most one of this and [maxRetransmits]. */
    val maxPacketLifeTime: Int? = null,
    val maxRetransmits: Int? = null,
    val protocol: String = "",
    /** Negotiated out of band: both sides create the channel with the same [id], no in-band open message. */
    val negotiated: Boolean = false,
    val id: Int? = null,
)

/** The `data` of a data channel's [W3C MessageEvent](https://html.spec.whatwg.org/multipage/comms.html#messageevent): a string or binary. */
public sealed class RTCDataChannelMessage {
    public data class Text(val text: String) : RTCDataChannelMessage()

    public class Binary(public val data: ByteArray) : RTCDataChannelMessage() {
        override fun toString(): String = "Binary(${data.size} bytes)"
    }
}

/** [W3C RTCDataChannel](https://w3c.github.io/webrtc-pc/#dom-rtcdatachannel) */
public interface RTCDataChannel {
    public val label: String
    public val ordered: Boolean
    public val maxPacketLifeTime: Int?
    public val maxRetransmits: Int?
    public val protocol: String
    public val negotiated: Boolean

    /** The SCTP stream id; null until the transport's DTLS role is known. */
    public val id: Int?

    /** `readyState` together with the `open`, `closing` and `close` events. */
    public val readyState: StateFlow<RTCDataChannelState>

    /** Bytes queued by [send] and not yet handed to the network. */
    public val bufferedAmount: Long

    /** `message` events. */
    public val onmessage: Flow<RTCDataChannelMessage>

    /** Sends a text message. @throws IllegalStateException unless [readyState] is OPEN (W3C InvalidStateError). */
    public fun send(data: String)

    /** Sends a binary message. @throws IllegalStateException unless [readyState] is OPEN (W3C InvalidStateError). */
    public fun send(data: ByteArray)

    public fun close()
}

/** [W3C RTCPeerConnection](https://w3c.github.io/webrtc-pc/#dom-rtcpeerconnection) (subset) */
public interface RTCPeerConnection : AutoCloseable {
    public suspend fun setRemoteDescription(description: RTCSessionDescription)
    public suspend fun createAnswer(): RTCSessionDescription
    public suspend fun setLocalDescription(description: RTCSessionDescription)
    public val localDescription: RTCSessionDescription?
    public val remoteDescription: RTCSessionDescription?
    public fun getTransceivers(): List<RTCRtpTransceiver>

    /** [W3C getStats()](https://w3c.github.io/webrtc-pc/#dom-rtcpeerconnection-getstats): the RTCStatsReport, keyed by stats id. */
    public suspend fun getStats(): Map<String, RTCStats>

    /** `track` events. */
    public val ontrack: Flow<RTCTrackEvent>

    /** [W3C createDataChannel()](https://w3c.github.io/webrtc-pc/#dom-peerconnection-createdatachannel) */
    public fun createDataChannel(label: String, init: RTCDataChannelInit = RTCDataChannelInit()): RTCDataChannel

    /** `datachannel` events: channels the remote side opened. */
    public val ondatachannel: Flow<RTCDataChannel>

    /** `connectionState` together with its `connectionstatechange` event. */
    public val connectionState: StateFlow<RTCPeerConnectionState>

    /** [W3C close()](https://w3c.github.io/webrtc-pc/#dom-rtcpeerconnection-close) */
    override fun close()
}

/** Creates peer connections for one backend (browser, libwebrtc, or a test fake). */
public fun interface PeerConnectionFactory {
    public fun createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection
}

/**
 * Optional backend capability: a silent audio track to send on an audio m-line before its real audio exists, then
 * swapped with `replaceTrack`. Backends whose send streams must have their audio source attached when they start
 * (krtc-native: libwebrtc otherwise also feeds them from the audio device) implement it on their factory;
 * `RoomClient` uses it for the screen-audio uplink (ADR-0017).
 */
public interface SilentAudioTrackFactory {
    public fun createSilentAudioTrack(id: String): MediaStreamTrack
}

/** Entry point: the platform backend installs its factory here (ADR-0012). */
public object Krtc {
    private var installed: PeerConnectionFactory? = null

    public var factory: PeerConnectionFactory
        get() = installed ?: error("No krtc backend installed: set Krtc.factory (browser, libwebrtc or a test fake)")
        set(value) {
            installed = value
        }
}
