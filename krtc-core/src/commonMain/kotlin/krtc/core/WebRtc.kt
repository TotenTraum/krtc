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

/** [W3C RTCPeerConnection](https://w3c.github.io/webrtc-pc/#dom-rtcpeerconnection) (subset) */
public interface RTCPeerConnection : AutoCloseable {
    public suspend fun setRemoteDescription(description: RTCSessionDescription)
    public suspend fun createAnswer(): RTCSessionDescription
    public suspend fun setLocalDescription(description: RTCSessionDescription)
    public val localDescription: RTCSessionDescription?
    public val remoteDescription: RTCSessionDescription?
    public fun getTransceivers(): List<RTCRtpTransceiver>

    /** `track` events. */
    public val ontrack: Flow<RTCTrackEvent>

    /** `connectionState` together with its `connectionstatechange` event. */
    public val connectionState: StateFlow<RTCPeerConnectionState>

    /** [W3C close()](https://w3c.github.io/webrtc-pc/#dom-rtcpeerconnection-close) */
    override fun close()
}

/** Creates peer connections for one backend (browser, libwebrtc, or a test fake). */
public fun interface PeerConnectionFactory {
    public fun createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection
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
