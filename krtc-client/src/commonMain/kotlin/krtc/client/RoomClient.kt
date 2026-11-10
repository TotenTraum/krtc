package krtc.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import krtc.core.Krtc
import krtc.core.MediaStreamTrack
import krtc.core.PeerConnectionFactory
import krtc.core.RTCConfiguration
import krtc.core.RTCDataChannel
import krtc.core.RTCDataChannelInit
import krtc.core.RTCDataChannelMessage
import krtc.core.RTCDataChannelState
import krtc.core.RTCPeerConnection
import krtc.core.RTCPeerConnectionState
import krtc.core.RTCRtpTransceiverDirection
import krtc.core.RTCSdpType
import krtc.core.RTCSessionDescription
import krtc.signaling.ClientMessage
import krtc.signaling.DataEnvelope
import krtc.signaling.ServerMessage
import krtc.signaling.SignalingProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Video quality a subscriber asks the SFU for; maps to the simulcast layers `q`, `h`, `f`. */
public enum class VideoQuality(public val rid: String) { LOW("q"), MEDIUM("h"), HIGH("f") }

public sealed class RoomState {
    public data object Idle : RoomState()
    public data object Connecting : RoomState()
    public data class Connected(val room: String, val selfId: String) : RoomState()
    public data class Disconnected(val reason: String?) : RoomState()
}

/**
 * A message from the room, received over the SFU's data channels (ADR-0016).
 * [from] is the sender's participant id, or null when the server (the host application) sent it.
 */
public class RoomData(
    public val from: String?,
    public val topic: String,
    public val payload: ByteArray,
    /** Sent reliably and in order, or best-effort (see [RoomClient.sendData]). */
    public val reliable: Boolean,
) {
    override fun toString(): String = "RoomData(from=$from, topic=$topic, ${payload.size} bytes, reliable=$reliable)"
}

/** Another member of the room, as seen by this client. */
public class RemoteParticipant internal constructor(public val id: String, public val name: String) {
    internal val audioState = MutableStateFlow<MediaStreamTrack?>(null)
    internal val videoState = MutableStateFlow<MediaStreamTrack?>(null)
    internal val speakingState = MutableStateFlow(false)

    public val audio: StateFlow<MediaStreamTrack?> = audioState.asStateFlow()
    public val video: StateFlow<MediaStreamTrack?> = videoState.asStateFlow()
    public val speaking: StateFlow<Boolean> = speakingState.asStateFlow()

    override fun toString(): String = "RemoteParticipant($id, $name)"
}

/**
 * A client for one krtc-sfu room (ADR-0012), built on the krtc-core W3C API so it runs on every
 * platform that has a krtc-core backend.
 *
 * The SFU is the offerer: this client applies each offer, publishes its microphone and camera on
 * m-lines `0` and `1`, answers, and maps the other m-lines to participants using `mids`.
 * Offers are handled strictly one at a time.
 */
public class RoomClient(
    /** Base URL of the SFU, e.g. `wss://sfu.example.com` or `ws://localhost:8080`. */
    private val serverUrl: String,
    private val factory: PeerConnectionFactory = Krtc.factory,
    private val configuration: RTCConfiguration = RTCConfiguration(),
    private val http: HttpClient = HttpClient { install(WebSockets) },
    parentScope: CoroutineScope? = null,
) {
    private val scope = CoroutineScope((parentScope?.coroutineContext ?: Dispatchers.Default) + SupervisorJob())
    private val stateFlow = MutableStateFlow<RoomState>(RoomState.Idle)
    private val participantsFlow = MutableStateFlow<List<RemoteParticipant>>(emptyList())
    private val selfSpeakingFlow = MutableStateFlow(false)
    private val dataFlow = MutableSharedFlow<RoomData>(extraBufferCapacity = 256)
    private val dataOpenFlow = MutableStateFlow(false)

    public val state: StateFlow<RoomState> = stateFlow.asStateFlow()
    public val participants: StateFlow<List<RemoteParticipant>> = participantsFlow.asStateFlow()
    public val selfSpeaking: StateFlow<Boolean> = selfSpeakingFlow.asStateFlow()

    /** Messages from other participants and the server ([sendData] on their side, `Sfu.sendData` on the server). */
    public val data: SharedFlow<RoomData> = dataFlow.asSharedFlow()

    /** Whether [sendData] can send: the reliable data channel to the SFU is open. */
    public val dataOpen: StateFlow<Boolean> = dataOpenFlow.asStateFlow()

    private var session: WebSocketSession? = null
    private var pc: RTCPeerConnection? = null
    private var microphone: MediaStreamTrack? = null
    private var camera: MediaStreamTrack? = null
    private var selfId: String? = null
    private var midToPeer: Map<String, String> = emptyMap()
    private var reliableChannel: RTCDataChannel? = null
    private var lossyChannel: RTCDataChannel? = null
    private val offers = Channel<ServerMessage.Offer>(Channel.UNLIMITED)
    private var job: Job? = null

    /** The underlying peer connection once joined (for getStats and advanced use). */
    public val peerConnection: RTCPeerConnection? get() = pc

    /** The peer connection's state (ICE + DTLS), once joined. */
    public val connectionState: StateFlow<RTCPeerConnectionState>? get() = pc?.connectionState

    /**
     * Connects and joins [roomName]. [microphone] and [camera] are published if given (and can be
     * swapped later with [setMicrophone] / [setCamera]). Returns once the WebSocket is open.
     */
    public suspend fun join(roomName: String, displayName: String, microphone: MediaStreamTrack? = null, camera: MediaStreamTrack? = null) {
        check(job == null) { "already joined" }
        this.microphone = microphone
        this.camera = camera
        stateFlow.value = RoomState.Connecting
        val peerConnection = factory.createPeerConnection(configuration)
        pc = peerConnection
        val url = serverUrl.trimEnd('/') + "/ws?room=" + roomName.encodeURLParameter() + "&name=" + displayName.encodeURLParameter()
        val ws = http.webSocketSession(url)
        session = ws
        job = scope.launch {
            launch { peerConnection.ontrack.collect { ev -> onTrack(ev.transceiver.mid, ev.track) } }
            launch { for (offer in offers) answer(peerConnection, offer) }
            launch { restartIceWhenStuck(peerConnection) }
            try {
                for (frame in ws.incoming) {
                    if (frame is Frame.Text) SignalingProtocol.decodeServer(frame.readText())?.let(::onMessage)
                }
                if (stateFlow.value !is RoomState.Disconnected) stateFlow.value = RoomState.Disconnected("closed by server")
            } catch (t: Throwable) {
                if (stateFlow.value !is RoomState.Disconnected) stateFlow.value = RoomState.Disconnected(t.message)
            } finally {
                offers.close()
            }
        }
    }

    private fun onMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                selfId = message.id
                participantsFlow.value = message.peers.map { RemoteParticipant(it.id, it.name) }
                stateFlow.value = RoomState.Connected(message.room, message.id)
            }
            is ServerMessage.PeerJoined -> participantsFlow.update { list ->
                if (list.any { it.id == message.id }) list else list + RemoteParticipant(message.id, message.name)
            }
            is ServerMessage.PeerLeft -> participantsFlow.update { list -> list.filterNot { it.id == message.id } }
            is ServerMessage.Speaking ->
                if (message.id == selfId) selfSpeakingFlow.value = message.speaking
                else participant(message.id)?.speakingState?.value = message.speaking
            is ServerMessage.Offer -> offers.trySend(message)
            is ServerMessage.Bye -> stateFlow.value = RoomState.Disconnected(message.reason)
        }
    }

    /** Applies one SFU offer and answers it (JSEP answerer). */
    private suspend fun answer(peerConnection: RTCPeerConnection, offer: ServerMessage.Offer) {
        midToPeer = offer.mids
        peerConnection.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, offer.sdp))
        if (reliableChannel == null && "\nm=application " in offer.sdp) openDataChannels(peerConnection)
        for ((mid, track) in listOf(UPLINK_AUDIO_MID to microphone, UPLINK_VIDEO_MID to camera)) {
            val transceiver = peerConnection.getTransceivers().firstOrNull { it.mid == mid } ?: continue
            if (track != null) {
                transceiver.direction = RTCRtpTransceiverDirection.SENDONLY
                if (transceiver.sender.track !== track) transceiver.sender.replaceTrack(track)
            }
        }
        // m-lines the SFU marked inactive (someone left, or sends no video) lose their tracks.
        for (p in participantsFlow.value) {
            val mids = midToPeer.filterValues { it == p.id }.keys
            if (mids.isEmpty()) {
                p.audioState.value = null
                p.videoState.value = null
            }
        }
        val answer = peerConnection.createAnswer()
        peerConnection.setLocalDescription(answer)
        send(ClientMessage.Answer(offer.version, peerConnection.localDescription?.sdp ?: answer.sdp))
    }

    /**
     * The SFU's two channels (DataEnvelope): created once the offer carries the SCTP m-line, so the answer accepts
     * it; they open when DTLS and SCTP are up.
     */
    private fun openDataChannels(peerConnection: RTCPeerConnection) {
        val reliable = peerConnection.createDataChannel(DataEnvelope.RELIABLE_LABEL)
        val lossy = peerConnection.createDataChannel(DataEnvelope.LOSSY_LABEL, RTCDataChannelInit(ordered = false, maxRetransmits = 0))
        reliableChannel = reliable
        lossyChannel = lossy
        scope.launch { reliable.readyState.collect { dataOpenFlow.value = it == RTCDataChannelState.OPEN } }
        for (ch in listOf(reliable, lossy)) scope.launch {
            ch.onmessage.collect { m ->
                if (m !is RTCDataChannelMessage.Binary) return@collect
                val envelope = DataEnvelope.decode(m.data) ?: return@collect
                dataFlow.emit(RoomData(envelope.ids.firstOrNull(), envelope.topic, envelope.payload, reliable = ch === reliable))
            }
        }
    }

    /**
     * Sends [payload] to the room through the SFU: to everyone else, or only to the participants in [to].
     * [reliable] messages arrive complete and in order; otherwise delivery is best-effort (unordered, never
     * retransmitted), for frequent updates where only the latest matters (cursors, positions).
     * [topic] (up to 255 bytes) lets receivers tell message kinds apart. Payloads up to about 64 KB.
     * Returns false if the data channel is not open yet (see [dataOpen]).
     */
    public fun sendData(payload: ByteArray, topic: String = "", to: List<String> = emptyList(), reliable: Boolean = true): Boolean {
        val ch = (if (reliable) reliableChannel else lossyChannel) ?: return false
        if (ch.readyState.value != RTCDataChannelState.OPEN) return false
        val message = DataEnvelope.encode(to, topic, payload)
        require(message.size <= DataEnvelope.MAX_MESSAGE_SIZE) { "message of ${message.size} bytes exceeds ${DataEnvelope.MAX_MESSAGE_SIZE}" }
        return runCatching { ch.send(message) }.isSuccess
    }

    private fun onTrack(mid: String?, track: MediaStreamTrack) {
        val peer = mid?.let { midToPeer[it] } ?: return
        val p = participant(peer) ?: return
        if (track.kind == "video") p.videoState.value = track else p.audioState.value = track
    }

    private fun participant(id: String): RemoteParticipant? = participantsFlow.value.firstOrNull { it.id == id }

    /**
     * Recovers from lost connectivity (network change, NAT rebinding): when the peer connection fails, or stays
     * disconnected for [ICE_RESTART_GRACE_MILLIS], asks the SFU for an ICE restart, repeating every
     * [ICE_RESTART_RETRY_MILLIS] until it reconnects.
     */
    private suspend fun restartIceWhenStuck(peerConnection: RTCPeerConnection) {
        peerConnection.connectionState.collectLatest { s ->
            if (s != RTCPeerConnectionState.DISCONNECTED && s != RTCPeerConnectionState.FAILED) return@collectLatest
            if (s == RTCPeerConnectionState.DISCONNECTED) delay(ICE_RESTART_GRACE_MILLIS)
            while (stateFlow.value is RoomState.Connected) {
                restartIce()
                delay(ICE_RESTART_RETRY_MILLIS)
            }
        }
    }

    /** Asks the SFU for an ICE restart (fresh ICE credentials in a new offer); media and DTLS continue. */
    public suspend fun restartIce() {
        send(ClientMessage.RestartIce)
    }

    /** Replaces the published microphone track (null stops sending audio content). */
    public suspend fun setMicrophone(track: MediaStreamTrack?) {
        microphone = track
        pc?.getTransceivers()?.firstOrNull { it.mid == UPLINK_AUDIO_MID }?.sender?.replaceTrack(track)
    }

    /** Replaces the published camera track (null stops sending video content). */
    public suspend fun setCamera(track: MediaStreamTrack?) {
        camera = track
        pc?.getTransceivers()?.firstOrNull { it.mid == UPLINK_VIDEO_MID }?.sender?.replaceTrack(track)
    }

    /** Asks the SFU for a simulcast layer of [participantId]'s video. */
    public suspend fun setVideoQuality(participantId: String, quality: VideoQuality) {
        send(ClientMessage.Layer(participantId, quality.rid))
    }

    private suspend fun send(message: ClientMessage) {
        session?.send(Frame.Text(SignalingProtocol.encode(message)))
    }

    /** Leaves the room and releases the peer connection. Local tracks stay owned by the caller. */
    public fun leave() {
        val ws = session
        val peer = pc
        pc = null
        session = null
        reliableChannel = null
        lossyChannel = null
        dataOpenFlow.value = false
        // Release media and publish the final state before the socket closes, so observers of
        // `state` never see Disconnected while the peer connection is still open.
        peer?.close()
        participantsFlow.value = emptyList()
        stateFlow.value = RoomState.Disconnected("left")
        scope.launch { runCatching { ws?.close() } }.invokeOnCompletion { scope.cancel() }
    }

    private companion object {
        const val UPLINK_AUDIO_MID = "0"
        const val UPLINK_VIDEO_MID = "1"
        const val ICE_RESTART_GRACE_MILLIS = 2_000L
        const val ICE_RESTART_RETRY_MILLIS = 5_000L
    }
}
