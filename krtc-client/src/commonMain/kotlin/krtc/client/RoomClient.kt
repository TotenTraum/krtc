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
import krtc.core.SilentAudioTrackFactory
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Video quality a subscriber asks the SFU for; maps to the simulcast layers `q`, `h`, `f`. */
public enum class VideoQuality(public val rid: String) { LOW("q"), MEDIUM("h"), HIGH("f") }

public sealed class RoomState {
    public data object Idle : RoomState()
    public data object Connecting : RoomState()
    public data class Connected(val room: String, val selfId: String) : RoomState()

    /**
     * The signaling connection dropped ([reason]); trying again ([attempt] so far). Media keeps flowing meanwhile if
     * its path still works; the session is resumed (same id) if the server still has it, otherwise joined anew.
     */
    public data class Reconnecting(val attempt: Int, val reason: String?) : RoomState()
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
public class RemoteParticipant internal constructor(public val id: String, public val name: String, audioMuted: Boolean = false, videoMuted: Boolean = false) {
    internal val audioState = MutableStateFlow<MediaStreamTrack?>(null)
    internal val videoState = MutableStateFlow<MediaStreamTrack?>(null)
    internal val screenState = MutableStateFlow<MediaStreamTrack?>(null)
    internal val screenAudioState = MutableStateFlow<MediaStreamTrack?>(null)
    internal val speakingState = MutableStateFlow(false)
    internal val audioMutedState = MutableStateFlow(audioMuted)
    internal val videoMutedState = MutableStateFlow(videoMuted)
    internal val screenSubscribedState = MutableStateFlow(true)

    public val audio: StateFlow<MediaStreamTrack?> = audioState.asStateFlow()
    public val video: StateFlow<MediaStreamTrack?> = videoState.asStateFlow()

    /** Its screen share, while it shares one. */
    public val screen: StateFlow<MediaStreamTrack?> = screenState.asStateFlow()

    /** The sound of its screen share (system or tab audio), while it shares one with sound. */
    public val screenAudio: StateFlow<MediaStreamTrack?> = screenAudioState.asStateFlow()
    public val speaking: StateFlow<Boolean> = speakingState.asStateFlow()

    /** It muted its microphone or camera (or the server did): show it as muted rather than silent or frozen. */
    public val audioMuted: StateFlow<Boolean> = audioMutedState.asStateFlow()
    public val videoMuted: StateFlow<Boolean> = videoMutedState.asStateFlow()

    /** Whether this client receives its screen share ([RoomClient.setSubscribed]); true by default. */
    public val screenSubscribed: StateFlow<Boolean> = screenSubscribedState.asStateFlow()

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
    /** How long to keep reconnecting after the signaling connection drops before giving up; 0 never reconnects. */
    private val reconnectTimeoutMillis: Long = 60_000,
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

    private val selfAudioMutedFlow = MutableStateFlow(false)
    private val selfVideoMutedFlow = MutableStateFlow(false)

    /** How the others see this client's microphone and camera: muted by [setMediaState] or by the server. */
    public val selfAudioMuted: StateFlow<Boolean> = selfAudioMutedFlow.asStateFlow()
    public val selfVideoMuted: StateFlow<Boolean> = selfVideoMutedFlow.asStateFlow()

    /** Messages from other participants and the server ([sendData] on their side, `Sfu.sendData` on the server). */
    public val data: SharedFlow<RoomData> = dataFlow.asSharedFlow()

    /** Whether [sendData] can send: the reliable data channel to the SFU is open. */
    public val dataOpen: StateFlow<Boolean> = dataOpenFlow.asStateFlow()

    private var session: WebSocketSession? = null
    @kotlin.concurrent.Volatile private var left = false
    private var pc: RTCPeerConnection? = null
    private var microphone: MediaStreamTrack? = null
    private var camera: MediaStreamTrack? = null
    private var screen: MediaStreamTrack? = null
    private var screenAudio: MediaStreamTrack? = null

    /**
     * On backends that need it ([SilentAudioTrackFactory], krtc-native), the screen-audio uplink sends this silent track
     * from the first answer, before media starts, and screen sound only swaps the track: libwebrtc keeps device audio
     * out of a send stream only if its source is attached when the stream starts (ADR-0017).
     */
    private var placeholder: MediaStreamTrack? = null
    private val screenAudioPlaceholder: MediaStreamTrack?
        get() = placeholder ?: (factory as? SilentAudioTrackFactory)?.createSilentAudioTrack("screen-audio")?.also { placeholder = it }
    private var screenMids: Set<String> = emptySet()
    private var selfId: String? = null
    private var midToPeer: Map<String, String> = emptyMap()
    private var reliableChannel: RTCDataChannel? = null
    private var lossyChannel: RTCDataChannel? = null
    private var job: Job? = null
    private var roomName = ""
    private var displayName = ""
    private var resumeToken: String? = null
    private var link: Link? = null

    /** Screen shares this client stopped receiving ([setSubscribed]); sent again to a new session. */
    private val unsubscribedScreens = HashSet<String>()

    /** One peer connection and the coroutines that serve it; replaced when the server lost the session. */
    private class Link(val pc: RTCPeerConnection, val offers: Channel<ServerMessage.Offer>, val job: Job)

    /** The underlying peer connection once joined (for getStats and advanced use). */
    public val peerConnection: RTCPeerConnection? get() = pc

    /** The peer connection's state (ICE + DTLS), once joined. */
    public val connectionState: StateFlow<RTCPeerConnectionState>? get() = pc?.connectionState

    /**
     * Connects and joins [roomName]. [microphone] and [camera] are published if given (and can be
     * swapped later with [setMicrophone] / [setCamera]). Returns once the WebSocket is open.
     */
    public suspend fun join(
        roomName: String,
        displayName: String,
        microphone: MediaStreamTrack? = null,
        camera: MediaStreamTrack? = null,
        screen: MediaStreamTrack? = null,
    ) {
        check(job == null) { "already joined" }
        this.microphone = microphone
        this.camera = camera
        this.screen = screen
        this.roomName = roomName
        this.displayName = displayName
        stateFlow.value = RoomState.Connecting
        startLink()
        val ws = openSocket() ?: return
        session = ws
        job = scope.launch { signaling(ws) }
    }

    /** A new peer connection with its track, offer and ICE-restart handling. */
    private fun startLink() {
        val peerConnection = factory.createPeerConnection(configuration)
        val offers = Channel<ServerMessage.Offer>(Channel.UNLIMITED)
        val linkJob = scope.launch {
            launch { peerConnection.ontrack.collect { ev -> onTrack(ev.transceiver.mid, ev.track) } }
            launch { for (offer in offers) answer(peerConnection, offer) }
            launch { restartIceWhenStuck(peerConnection) }
        }
        pc = peerConnection
        link = Link(peerConnection, offers, linkJob)
    }

    /**
     * The server lost this client's session (it rejoined under a new id): drop the old peer connection and everything
     * received on it; the new session's offers bring the media back.
     */
    private fun restartLink() {
        link?.let { old ->
            old.job.cancel()
            old.offers.close()
            old.pc.close()
        }
        reliableChannel = null
        lossyChannel = null
        dataOpenFlow.value = false
        midToPeer = emptyMap()
        screenMids = emptySet()
        for (p in participantsFlow.value) {
            p.audioState.value = null
            p.videoState.value = null
            p.screenState.value = null
            p.screenAudioState.value = null
        }
        startLink()
    }

    /**
     * Opens the signaling socket, resuming the session if there is a token. Null if the client left meanwhile.
     * Not cancellable: a handshake that completes as the caller gives up would leave an open socket nobody closes,
     * and the SFU would keep a ghost participant. Closed here instead if the caller left or was cancelled.
     */
    private suspend fun openSocket(): WebSocketSession? {
        val url = serverUrl.trimEnd('/') + "/ws?room=" + roomName.encodeURLParameter() + "&name=" + displayName.encodeURLParameter() +
            (resumeToken?.let { "&resume=" + it.encodeURLParameter() } ?: "")
        val ws = withContext(NonCancellable) { http.webSocketSession(url) }
        if (left || !currentCoroutineContext().isActive) {
            withContext(NonCancellable) { runCatching { ws.close() } }
            currentCoroutineContext().ensureActive()
            return null
        }
        return ws
    }

    /** Reads signaling until the client leaves, the server ends the session, or reconnecting gives up. */
    private suspend fun signaling(first: WebSocketSession) {
        var ws = first
        while (true) {
            val reason = try {
                for (frame in ws.incoming) {
                    if (frame is Frame.Text) SignalingProtocol.decodeServer(frame.readText())?.let(::onMessage)
                }
                "closed by server"
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
            if (left || stateFlow.value is RoomState.Disconnected) return // left, or the server said bye
            ws = reconnect(reason) ?: return
            session = ws
        }
    }

    /** Opens a new socket, retrying with backoff for [reconnectTimeoutMillis]; null (and Disconnected) if it never does. */
    private suspend fun reconnect(reason: String): WebSocketSession? {
        val giveUpAt = kotlin.time.TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("${reconnectTimeoutMillis}ms")
        var attempt = 0
        while (!left && reconnectTimeoutMillis > 0 && giveUpAt.hasNotPassedNow()) {
            attempt++
            stateFlow.value = RoomState.Reconnecting(attempt, reason)
            val ws = runCatching { openSocket() }.getOrElse { null }
            if (ws != null) return ws
            if (left) return null
            delay(minOf(RECONNECT_MAX_DELAY_MILLIS, RECONNECT_FIRST_DELAY_MILLIS shl minOf(attempt - 1, 4)))
        }
        if (!left) stateFlow.value = RoomState.Disconnected(reason)
        return null
    }

    private fun onMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                val previous = selfId
                val resumed = previous == message.id
                if (previous != null && !resumed) restartLink()
                selfId = message.id
                resumeToken = message.resume
                // Participants that are still there keep their objects (and, when resumed, their tracks).
                val known = participantsFlow.value.associateBy { it.id }
                participantsFlow.value = message.peers.map { info ->
                    (known[info.id] ?: RemoteParticipant(info.id, info.name)).also {
                        it.audioMutedState.value = info.audioMuted
                        it.videoMutedState.value = info.videoMuted
                        it.speakingState.value = false
                    }
                }
                selfSpeakingFlow.value = false
                stateFlow.value = RoomState.Connected(message.room, message.id)
                val newSession = !resumed
                val stillThere = message.peers.map { it.id }.toSet()
                unsubscribedScreens.retainAll(stillThere)
                scope.launch {
                    if (mediaState.audioMuted || mediaState.videoMuted) send(mediaState)
                    if (newSession && previous != null) for (id in unsubscribedScreens) send(ClientMessage.Subscribe(id, ClientMessage.Subscribe.FEED_SCREEN, false))
                }
            }
            is ServerMessage.PeerJoined -> participantsFlow.update { list ->
                if (list.any { it.id == message.id }) list else list + RemoteParticipant(message.id, message.name)
            }
            is ServerMessage.PeerLeft -> participantsFlow.update { list -> list.filterNot { it.id == message.id } }
            is ServerMessage.PeerState ->
                if (message.id == selfId) {
                    selfAudioMutedFlow.value = message.audioMuted
                    selfVideoMutedFlow.value = message.videoMuted
                } else participant(message.id)?.let {
                    it.audioMutedState.value = message.audioMuted
                    it.videoMutedState.value = message.videoMuted
                }
            is ServerMessage.Speaking ->
                if (message.id == selfId) selfSpeakingFlow.value = message.speaking
                else participant(message.id)?.speakingState?.value = message.speaking
            is ServerMessage.Offer -> link?.offers?.trySend(message)
            is ServerMessage.Bye -> stateFlow.value = RoomState.Disconnected(message.reason)
        }
    }

    /** Applies one SFU offer and answers it (JSEP answerer). */
    private suspend fun answer(peerConnection: RTCPeerConnection, offer: ServerMessage.Offer) {
        midToPeer = offer.mids
        screenMids = offer.screens.toSet()
        peerConnection.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, offer.sdp))
        if (reliableChannel == null && "\nm=application " in offer.sdp) openDataChannels(peerConnection)
        for ((mid, track) in listOf(UPLINK_AUDIO_MID to microphone, UPLINK_VIDEO_MID to camera)) {
            val transceiver = peerConnection.getTransceivers().firstOrNull { it.mid == mid } ?: continue
            if (track != null) {
                transceiver.direction = RTCRtpTransceiverDirection.SENDONLY
                if (transceiver.sender.track !== track) transceiver.sender.replaceTrack(track)
            }
        }
        // The screen uplinks send only while there is a track (the screen audio: or its silent placeholder); the SFU
        // forwards screen audio only while the screen is shared.
        for ((mid, track) in listOf(UPLINK_SCREEN_MID to screen, UPLINK_SCREEN_AUDIO_MID to (screenAudio ?: screenAudioPlaceholder))) {
            val t = peerConnection.getTransceivers().firstOrNull { it.mid == mid } ?: continue
            if (track != null) {
                t.direction = RTCRtpTransceiverDirection.SENDONLY
                if (t.sender.track !== track) t.sender.replaceTrack(track)
            } else if (t.direction != RTCRtpTransceiverDirection.INACTIVE) {
                t.sender.replaceTrack(null)
                t.direction = RTCRtpTransceiverDirection.INACTIVE
            }
        }
        // m-lines the SFU marked inactive (someone left, or sends no video) lose their tracks.
        for (p in participantsFlow.value) {
            val mids = midToPeer.filterValues { it == p.id }.keys
            if (mids.isEmpty()) {
                p.audioState.value = null
                p.videoState.value = null
            }
            if (mids.none { it in screenMids }) {
                p.screenState.value = null
                p.screenAudioState.value = null
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
        when {
            mid in screenMids && track.kind == "audio" -> p.screenAudioState.value = track
            mid in screenMids -> p.screenState.value = track
            track.kind == "video" -> p.videoState.value = track
            else -> p.audioState.value = track
        }
    }

    private fun participant(id: String): RemoteParticipant? = participantsFlow.value.firstOrNull { it.id == id }

    /**
     * Recovers from lost connectivity (network change, NAT rebinding): when the peer connection fails, or stays
     * disconnected for [ICE_RESTART_GRACE_MILLIS], asks the SFU for an ICE restart, repeating every
     * [ICE_RESTART_RETRY_MILLIS] until it reconnects.
     */
    private suspend fun restartIceWhenStuck(peerConnection: RTCPeerConnection) {
        // Also re-evaluated when signaling comes back: a network change usually breaks both, and the restart can only
        // be asked for once the socket is up again.
        combine(peerConnection.connectionState, stateFlow) { s, room -> s to (room is RoomState.Connected) }.collectLatest { (s, signaling) ->
            if (!signaling) return@collectLatest
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

    /**
     * Replaces the published camera track (null stops sending video content). Publishing a camera for the first
     * time (joined without one) renegotiates, so the others get a video m-line for it.
     */
    public suspend fun setCamera(track: MediaStreamTrack?) {
        camera = track
        val t = pc?.getTransceivers()?.firstOrNull { it.mid == UPLINK_VIDEO_MID } ?: return
        t.sender.replaceTrack(track)
        if (track != null && t.direction != RTCRtpTransceiverDirection.SENDONLY) send(ClientMessage.Renegotiate)
    }

    /**
     * Starts sharing [track] as this participant's screen (a capture of the display, a window or app content), with
     * its sound in [audio] (system or tab audio) if there is any, or stops with null. Starting and stopping either
     * renegotiate with the SFU, which then offers them to everyone else as [RemoteParticipant.screen] and
     * [RemoteParticipant.screenAudio]; replacing tracks with other ones does not.
     */
    public suspend fun setScreen(track: MediaStreamTrack?, audio: MediaStreamTrack? = null) {
        screen = track
        screenAudio = audio?.takeIf { track != null }
        var renegotiate = false
        for ((mid, t) in listOf(UPLINK_SCREEN_MID to screen, UPLINK_SCREEN_AUDIO_MID to (screenAudio ?: screenAudioPlaceholder))) {
            val transceiver = pc?.getTransceivers()?.firstOrNull { it.mid == mid } ?: continue
            val sending = transceiver.direction == RTCRtpTransceiverDirection.SENDONLY
            when {
                t != null && sending -> transceiver.sender.replaceTrack(t)
                t != null -> renegotiate = true
                sending -> {
                    transceiver.sender.replaceTrack(null) // stop sending now; the m-line goes inactive with the next answer
                    renegotiate = true
                }
            }
        }
        if (renegotiate) send(ClientMessage.Renegotiate)
    }

    private var mediaState = ClientMessage.MediaState(audioMuted = false, videoMuted = false)

    /**
     * Tells the others that this client muted (or unmuted) its microphone or camera, so they show it as muted
     * ([RemoteParticipant.audioMuted]). Only the state: stop the media itself too, e.g. by disabling the track.
     * May be called before [join]; the state is sent once joined.
     */
    public suspend fun setMediaState(audioMuted: Boolean, videoMuted: Boolean) {
        mediaState = ClientMessage.MediaState(audioMuted, videoMuted)
        if (stateFlow.value is RoomState.Connected) send(mediaState)
    }

    /**
     * Starts or stops receiving [participantId]'s screen share (with its sound), or its camera with [screen] false.
     * The SFU stops forwarding while unsubscribed, so it costs no bandwidth; the track stays and resumes at a key
     * frame. Everything is received by default.
     */
    public suspend fun setSubscribed(participantId: String, enabled: Boolean, screen: Boolean = true) {
        if (screen) {
            participant(participantId)?.screenSubscribedState?.value = enabled
            if (enabled) unsubscribedScreens -= participantId else unsubscribedScreens += participantId
        }
        val feed = if (screen) ClientMessage.Subscribe.FEED_SCREEN else ClientMessage.Subscribe.FEED_CAMERA
        send(ClientMessage.Subscribe(participantId, feed, enabled))
    }

    /**
     * The participant whose media a downlink m-line carries (per the latest offer), e.g. to attribute `inbound-rtp`
     * stats (their `mid`) to participants. Null for this client's own uplinks and unused m-lines.
     */
    public fun participantOfMid(mid: String): String? = midToPeer[mid]

    /** Asks the SFU for a simulcast layer of [participantId]'s video. */
    public suspend fun setVideoQuality(participantId: String, quality: VideoQuality) {
        send(ClientMessage.Layer(participantId, quality.rid))
    }

    private suspend fun send(message: ClientMessage) {
        session?.send(Frame.Text(SignalingProtocol.encode(message)))
    }

    /** Leaves the room and releases the peer connection. Local tracks stay owned by the caller. */
    public fun leave() {
        left = true
        val ws = session
        val peer = pc
        pc = null
        link?.job?.cancel()
        link = null
        session = null
        reliableChannel = null
        lossyChannel = null
        dataOpenFlow.value = false
        // Release media and publish the final state before the socket closes, so observers of
        // `state` never see Disconnected while the peer connection is still open.
        peer?.close()
        placeholder?.stop()
        placeholder = null
        participantsFlow.value = emptyList()
        stateFlow.value = RoomState.Disconnected("left")
        // Saying so lets the server remove this client at once; a socket that merely closes could be a dropped
        // connection, which the server keeps for a while in case the client resumes.
        scope.launch {
            runCatching { ws?.send(Frame.Text(SignalingProtocol.encode(ClientMessage.Leave))) }
            runCatching { ws?.close() }
        }.invokeOnCompletion { scope.cancel() }
    }

    private companion object {
        const val UPLINK_AUDIO_MID = "0"
        const val UPLINK_VIDEO_MID = "1"
        const val UPLINK_SCREEN_MID = "s"
        const val UPLINK_SCREEN_AUDIO_MID = "sa"
        const val ICE_RESTART_GRACE_MILLIS = 2_000L
        const val ICE_RESTART_RETRY_MILLIS = 5_000L
        const val RECONNECT_FIRST_DELAY_MILLIS = 500L
        const val RECONNECT_MAX_DELAY_MILLIS = 5_000L
    }
}
