package krtc.client

import krtc.core.MediaStreamTrack
import krtc.core.PeerConnectionFactory
import krtc.core.RTCPeerConnection
import krtc.core.RTCPeerConnectionState
import krtc.core.RTCRtpReceiver
import krtc.core.RTCRtpSender
import krtc.core.RTCRtpTransceiver
import krtc.core.RTCRtpTransceiverDirection
import krtc.core.RTCSdpType
import krtc.core.RTCSessionDescription
import krtc.core.RTCTrackEvent
import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.SessionDescription
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A media-less krtc-core backend that follows JSEP answerer rules closely enough to exercise krtc-client. */
class FakeTrack(override val kind: String, override val id: String = "$kind-${counter++}") : MediaStreamTrack {
    override var enabled: Boolean = true
    var stopped = false
    override fun stop() { stopped = true }
    override fun toString() = "FakeTrack($id)"

    private companion object { var counter = 0 }
}

private class FakeSender : RTCRtpSender {
    override var track: MediaStreamTrack? = null
    override suspend fun replaceTrack(withTrack: MediaStreamTrack?) { track = withTrack }
}

private class FakeReceiver(override val track: MediaStreamTrack) : RTCRtpReceiver

private class FakeTransceiver(override val mid: String, kind: String) : RTCRtpTransceiver {
    override var direction: RTCRtpTransceiverDirection = RTCRtpTransceiverDirection.RECVONLY
    override val sender: RTCRtpSender = FakeSender()
    override val receiver: RTCRtpReceiver = FakeReceiver(FakeTrack(kind))
    var remoteDirection: Direction = Direction.INACTIVE
}

class FakePeerConnection : RTCPeerConnection {
    private val transceivers = mutableListOf<FakeTransceiver>()
    private val tracks = MutableSharedFlow<RTCTrackEvent>(replay = 64)
    private val state = MutableStateFlow(RTCPeerConnectionState.NEW)
    override var localDescription: RTCSessionDescription? = null; private set
    override var remoteDescription: RTCSessionDescription? = null; private set
    var closed = false; private set

    override val ontrack: SharedFlow<RTCTrackEvent> get() = tracks
    override val connectionState: StateFlow<RTCPeerConnectionState> get() = state

    override suspend fun setRemoteDescription(description: RTCSessionDescription) {
        require(description.type == RTCSdpType.OFFER)
        remoteDescription = description
        for (m in SessionDescription.parse(description.sdp).media) {
            val mid = m.mid ?: continue
            val t = transceivers.firstOrNull { it.mid == mid } ?: FakeTransceiver(mid, m.type).also { transceivers += it }
            val wasSending = t.remoteDirection == Direction.SENDONLY || t.remoteDirection == Direction.SENDRECV
            t.remoteDirection = m.direction
            val sending = m.direction == Direction.SENDONLY || m.direction == Direction.SENDRECV
            // JSEP: a track event fires when the remote side starts sending on a transceiver.
            if (sending && !wasSending) tracks.emit(RTCTrackEvent(t.receiver, t.receiver.track, t))
        }
    }

    override suspend fun createAnswer(): RTCSessionDescription {
        val offer = SessionDescription.parse(requireNotNull(remoteDescription).sdp)
        val lines = mutableListOf("v=0", "o=- 1 1 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE " + offer.media.joinToString(" ") { it.mid!! })
        for (m in offer.media) {
            val t = transceivers.first { it.mid == m.mid }
            val wantsSend = t.direction == RTCRtpTransceiverDirection.SENDONLY || t.direction == RTCRtpTransceiverDirection.SENDRECV
            val wantsRecv = t.direction == RTCRtpTransceiverDirection.RECVONLY || t.direction == RTCRtpTransceiverDirection.SENDRECV
            val remoteSends = m.direction == Direction.SENDONLY || m.direction == Direction.SENDRECV
            val remoteRecvs = m.direction == Direction.RECVONLY || m.direction == Direction.SENDRECV
            val send = wantsSend && remoteRecvs
            val recv = wantsRecv && remoteSends
            val dir = when {
                send && recv -> "sendrecv"
                send -> "sendonly"
                recv -> "recvonly"
                else -> "inactive"
            }
            lines += "m=${m.type} 9 UDP/TLS/RTP/SAVPF ${m.formats.joinToString(" ")}"
            lines += "a=mid:${m.mid}"
            lines += "a=ice-ufrag:fake"
            lines += "a=ice-pwd:fakefakefakefakefakefake"
            lines += "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":")
            lines += "a=setup:active"
            lines += "a=$dir"
        }
        return RTCSessionDescription(RTCSdpType.ANSWER, lines.joinToString("\r\n") + "\r\n")
    }

    override suspend fun setLocalDescription(description: RTCSessionDescription) {
        localDescription = description
    }

    override fun getTransceivers(): List<RTCRtpTransceiver> = transceivers.toList()

    override fun close() {
        closed = true
        state.value = RTCPeerConnectionState.CLOSED
    }
}

class FakeFactory : PeerConnectionFactory {
    val created = mutableListOf<FakePeerConnection>()
    override fun createPeerConnection(configuration: krtc.core.RTCConfiguration): RTCPeerConnection = FakePeerConnection().also { created += it }
}
