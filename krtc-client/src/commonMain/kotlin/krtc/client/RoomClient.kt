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
import krtc.core.RTCPeerConnection
import krtc.core.RTCPeerConnectionState
import krtc.core.RTCRtpTransceiverDirection
import krtc.core.RTCSdpType
import krtc.core.RTCSessionDescription
import krtc.signaling.ClientMessage
import krtc.signaling.ServerMessage
import krtc.signaling.SignalingProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    public val state: StateFlow<RoomState> = stateFlow.asStateFlow()
    public val participants: StateFlow<List<RemoteParticipant>> = participantsFlow.asStateFlow()
    public val selfSpeaking: StateFlow<Boolean> = selfSpeakingFlow.asStateFlow()

    private var session: WebSocketSession? = null
    private var pc: RTCPeerConnection? = null
    private var microphone: MediaStreamTrack? = null
    private var camera: MediaStreamTrack? = null
    private var selfId: String? = null
    private var midToPeer: Map<String, String> = emptyMap()
    private val offers = Channel<ServerMessage.Offer>(Channel.UNLIMITED)
    private var job: Job? = null

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

    private fun onTrack(mid: String?, track: MediaStreamTrack) {
        val peer = mid?.let { midToPeer[it] } ?: return
        val p = participant(peer) ?: return
        if (track.kind == "video") p.videoState.value = track else p.audioState.value = track
    }

    private fun participant(id: String): RemoteParticipant? = participantsFlow.value.firstOrNull { it.id == id }

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
    }
}
