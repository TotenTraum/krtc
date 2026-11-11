package krtc.sfu

import io.ktor.util.collections.ConcurrentMap
import krtc.protocol.PacketKind
import krtc.protocol.classifyPacket
import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.SessionDescription
import krtc.protocol.stun.StunConstants
import krtc.protocol.stun.StunMessage
import krtc.protocol.stun.StunMessageBuilder
import krtc.signaling.ClientMessage
import krtc.signaling.PeerInfo
import krtc.signaling.ServerMessage
import krtc.signaling.SignalingProtocol
import krtc.sfu.dtls.DtlsEndpoint
import krtc.sfu.dtls.DtlsIdentity
import krtc.sfu.platform.PeerAddress
import krtc.sfu.platform.TcpServer
import krtc.sfu.platform.Transports
import krtc.sfu.platform.UdpServer
import krtc.sfu.platform.logger
import krtc.sfu.platform.nowMicros
import krtc.sfu.platform.nowMillis
import krtc.sfu.platform.secureRandomLong
import krtc.sfu.room.MediaPlan
import krtc.sfu.room.OfferBuilder
import krtc.sfu.room.Participant
import krtc.sfu.room.Room
import krtc.sfu.room.Shard
import krtc.sfu.room.VideoKind
import krtc.sfu.srtp.SrtpSessionKeys
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A selective forwarding unit: rooms, participants, ICE-lite, DTLS-SRTP, RTP/RTCP forwarding with
 * simulcast. Embeddable in any Kotlin Multiplatform server (JVM or Kotlin/Native); see ADR-0013.
 *
 * The host owns signaling transport and identity:
 * 1. accept a client connection, call [join] with the host's [SignalSink] for that connection;
 * 2. pass every client message to [onMessage];
 * 3. call [leave] when the connection closes.
 *
 * Media uses one UDP port ([udpPort]) for all participants. Several instances may run in one process
 * on different ports. [close] stops everything; the instance cannot be reused afterwards.
 */
public class Sfu(public val config: SfuConfig = SfuConfig()) : AutoCloseable {
    private val log = logger("krtc.sfu.Sfu")

    private val metrics = Metrics()
    private val identity: DtlsIdentity = DtlsIdentity.generate()
    private val shards = List(config.shards) { Shard(it) }
    private val udp: UdpServer = UdpServer(config.bindAddress, config.udpPort, config.shards, ::onPacket)
    private val announced = config.announcedAddresses.ifEmpty { listOf(config.announcedAddress) }
    private val tcp: TcpServer? = if (config.iceTcp) TcpServer(udp.localAddresses, udp.localPort, ::onPacket) else null
    private val transports = Transports(udp, tcp)
    private val offers = OfferBuilder(announced, udp.localPort, identity.fingerprint, udp = config.iceUdp, tcp = tcp != null, dataChannels = config.dataChannels, screenShare = config.screenShare)

    /** Guards membership: [rooms], room member lists and the address table updates. Never held on the media path. */
    private val lock = SynchronizedObject()
    private val rooms = HashMap<String, Room>()
    private val sessions = ConcurrentMap<String, PeerSession>() // by participant id
    private val byUfrag = ConcurrentMap<String, PeerSession>()
    private val byAddress = ConcurrentMap<PeerAddress, PeerSession>()
    private val ids = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val eventFlow = MutableSharedFlow<SfuEvent>(extraBufferCapacity = config.eventBufferSize, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The UDP port media actually uses (useful with `udpPort = 0`). */
    public val udpPort: Int get() = udp.localPort

    /** Room and participant lifecycle, media and speaking events. Never blocks the SFU. */
    public val events: SharedFlow<SfuEvent> = eventFlow.asSharedFlow()

    init {
        for (s in shards) s.every(100) { housekeeping(s) }
        for (s in shards) s.every(MediaPlan.TWCC_FEEDBACK_INTERVAL_MILLIS) { transportFeedback(s) }
        if (config.dataChannels) for (s in shards) s.every(MediaPlan.SCTP_TICK_MILLIS) { sctpTimers(s) }
        udp.start()
        tcp?.start()
        if (config.simulatedLossPercent > 0) log.warn("Simulating ${config.simulatedLossPercent}% downlink video loss")
        if (config.simulatedDownlinkBps > 0) log.warn("Simulating a ${config.simulatedDownlinkBps / 1000} kbps downlink per subscriber")
        log.info("SFU UDP on ${udp.localAddresses.joinToString { PeerAddress(it, udp.localPort).toString() }} (${udp.socketCount} sockets${if (tcp != null) ", ICE-TCP" else ""}${if (!config.iceUdp) ", UDP candidates off" else ""}), announced ${announced.joinToString()}, ${shards.size} shards")
    }

    // ------------------------------------------------------------------ host API

    /**
     * Admits a client per [SfuConfig.joinPolicy] and sends it `welcome` and the first `offer` through [sink].
     * If the policy assigns an id that is already connected, the older session is replaced.
     * @throws JoinDeniedException if the policy denies the request
     */
    public suspend fun join(request: JoinRequest, sink: SignalSink): SfuParticipant {
        check(!closed.load()) { "Sfu is closed" }
        val decision = config.joinPolicy.decide(request)
        if (decision is JoinDecision.Deny) throw JoinDeniedException(decision.reason)
        val allow = decision as JoinDecision.Allow
        allow.participantId?.let { existing -> if (sessions.containsKey(existing)) kick(existing, "replaced by a newer session") }

        val roomName = request.room
        val name = (allow.displayName ?: request.displayName).take(64)
        var opened = false
        val (p, session, others) = synchronized(lock) {
            val room = rooms.getOrPut(roomName) {
                opened = true
                Room(roomName, shards[roomName.hashCode().mod(shards.size)], transports, ::announceSpeaking, config.simulatedLossPercent, config.simulatedUplinkLossPercent, config.simulatedDownlinkBps, config.dataPolicy)
            }
            val id = allow.participantId ?: ids.incrementAndFetch().toString(36)
            val p = Participant(id, name, room, sink)
            val session = PeerSession(p)
            sessions[p.id] = session
            byUfrag[p.iceUfrag] = session
            Triple(p, session, room.add(p))
        }
        if (opened) eventFlow.tryEmit(SfuEvent.RoomOpened(roomName))
        sink.send(ServerMessage.Welcome(p.id, roomName, others.map { PeerInfo(it.id, it.name) }))
        val joined = ServerMessage.PeerJoined(p.id, p.name)
        others.forEach { it.sink.send(joined) }
        session.negotiate()
        others.forEach { sessions[it.id]?.negotiate() }
        eventFlow.tryEmit(SfuEvent.ParticipantJoined(roomName, p.id, p.name))
        log.info("$p joined room $roomName (${others.size + 1} members)")
        return p
    }

    /** Handles one message from [participant]'s client. */
    public fun onMessage(participant: SfuParticipant, message: ClientMessage) {
        val p = participant as? Participant ?: return
        when (message) {
            is ClientMessage.Answer -> sessions[p.id]?.onAnswer(message.version, message.sdp)
            is ClientMessage.Layer -> {
                val layer = message.layer.toIntOrNull() ?: MediaPlan.LAYER_RIDS.indexOf(message.layer).takeIf { it >= 0 } ?: return
                p.room.shard.execute { p.room.setPreferredLayer(p, message.peer, layer) }
            }
            ClientMessage.RestartIce -> restartIce(p)
            ClientMessage.Renegotiate -> sessions[p.id]?.takeIf { it.participant === p }?.negotiate()
        }
    }

    /**
     * ICE restart for [p] (its client lost connectivity): fresh ICE credentials in a new offer. Checks with
     * either pair are answered until the client uses the new one; DTLS and SRTP continue unchanged.
     */
    private fun restartIce(p: Participant) {
        val session = synchronized(lock) {
            val session = sessions[p.id]?.takeIf { it.participant === p } ?: return
            p.previousIceUfrag?.let { if (byUfrag[it] === session) byUfrag.remove(it) } // only one restart in flight
            p.restartIce()
            byUfrag[p.iceUfrag] = session
            session
        }
        log.info("$p ICE restart")
        session.negotiate()
    }

    /** Decodes and handles a JSON message from [participant]'s client; unknown messages are ignored. */
    public fun onMessage(participant: SfuParticipant, text: String) {
        SignalingProtocol.decodeClient(text)?.let { onMessage(participant, it) }
    }

    /** Removes [participant] (its client disconnected). Idempotent. */
    public fun leave(participant: SfuParticipant, reason: String = "left") {
        val p = participant as? Participant ?: return
        var roomClosed = false
        val (session, remaining) = synchronized(lock) {
            val session = sessions[p.id]?.takeIf { it.participant === p } ?: return
            sessions.remove(p.id)
            byUfrag.remove(p.iceUfrag)
            p.previousIceUfrag?.let { byUfrag.remove(it) }
            p.remote?.let { if (byAddress[it] === session) byAddress.remove(it) }
            val remaining = p.room.remove(p)
            if (remaining.isEmpty()) {
                rooms.remove(p.room.name)
                roomClosed = true
            }
            session to remaining
        }
        session.dtls?.close()
        p.room.shard.execute { p.room.data.detach(p) }
        val left = ServerMessage.PeerLeft(p.id)
        remaining.forEach { it.sink.send(left); sessions[it.id]?.negotiate() }
        eventFlow.tryEmit(SfuEvent.ParticipantLeft(p.room.name, p.id, reason))
        if (roomClosed) eventFlow.tryEmit(SfuEvent.RoomClosed(p.room.name))
        log.info("$p left room ${p.room.name}: $reason")
    }

    /** Ends a participant's session: tells its client (`bye`), closes its sink and removes it. */
    public fun kick(participantId: String, reason: String = "kicked"): Boolean {
        val p = sessions[participantId]?.participant ?: return false
        p.sink.send(ServerMessage.Bye(reason))
        p.sink.close(reason)
        leave(p, reason)
        return true
    }

    /**
     * Sends a data message to participants of [room] over their data channels (ADR-0016), as the server (the
     * clients see no sender id). [to] empty means everyone in the room. Not subject to [SfuConfig.dataPolicy].
     * Returns false if the room does not exist or data channels are off. Delivery is asynchronous; participants
     * whose data channels are not open yet miss the message.
     */
    public fun sendData(room: String, payload: ByteArray, topic: String = "", to: List<String> = emptyList(), reliable: Boolean = true): Boolean {
        if (!config.dataChannels) return false
        val r = synchronized(lock) { rooms[room] } ?: return false
        val message = DataMessage(room, null, topic, payload, reliable, to)
        return r.shard.execute { r.data.sendFromHost(message) }
    }

    /** Server-side mute: stops (or resumes) forwarding a participant's audio, camera video and/or screen share. */
    public fun setMuted(participantId: String, audio: Boolean? = null, video: Boolean? = null, screen: Boolean? = null): Boolean {
        val p = sessions[participantId]?.participant ?: return false
        audio?.let { p.audioMuted = it }
        video?.let { p.videoMuted = it }
        screen?.let { p.screen.muted = it }
        return true
    }

    public fun participant(id: String): ParticipantInfo? = sessions[id]?.let(::info)

    public fun room(name: String): RoomInfo? = synchronized(lock) { rooms[name] }?.let { r -> RoomInfo(r.name, r.members.mapNotNull { sessions[it.id]?.let(::info) }) }

    public fun rooms(): List<RoomInfo> = synchronized(lock) { rooms.keys.toList() }.mapNotNull(::room)

    public fun metrics(): SfuMetrics = metrics.snapshot(sessions.size, synchronized(lock) { rooms.size }, shards)

    /** Bandwidth estimates and received simulcast layers of every participant. */
    public fun linkStats(): List<SfuLinkStats> = synchronized(lock) { rooms.values.toList() }.flatMap { room ->
        room.members.map { p ->
            SfuLinkStats(
                participantId = p.id,
                roomName = room.name,
                downlinkEstimateBps = if (p.bwe.active) p.bwe.estimateBps else 0,
                downlinkAckedBps = p.bwe.ackedBps,
                downlinkLossPercent = p.bwe.lossRatio * 100,
                videoLayers = runCatching { p.downlinks.toList().associate { (publisher, down) -> publisher to down.current } }.getOrDefault(emptyMap()), // owned by the shard thread
            )
        }
    }

    /** Ends all sessions (`bye` with "server shutdown"), releases the UDP port and worker threads. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.values.map { it.participant.id }.forEach { kick(it, "server shutdown") }
        udp.close()
        tcp?.close()
        shards.forEach { it.close() }
    }

    private fun info(s: PeerSession): ParticipantInfo {
        val p = s.participant
        return ParticipantInfo(p.id, p.name, p.room.name, s.mediaConnected, p.publishesVideo, p.audioMuted, p.videoMuted, p.speaking, p.screen.publishing, p.screen.muted)
    }

    private fun announceSpeaking(p: Participant, speaking: Boolean) {
        val msg = ServerMessage.Speaking(p.id, speaking)
        p.room.members.forEach { it.sink.send(msg) }
        eventFlow.tryEmit(SfuEvent.SpeakingChanged(p.room.name, p.id, speaking))
    }

    // ------------------------------------------------------------------ transport (UDP threads)

    private fun onPacket(data: ByteArray, len: Int, from: PeerAddress) {
        if (!from.tcp && !config.iceUdp) return
        when (classifyPacket(data, 0, len)) {
            PacketKind.STUN -> handleStun(data, len, from)
            PacketKind.DTLS -> {
                val s = byAddress[from]
                if (s == null) metrics.unknownSource.incrementAndFetch() else s.onDtls(data.copyOf(len))
            }
            PacketKind.RTP_OR_RTCP -> {
                val s = byAddress[from]
                if (s == null) {
                    metrics.unknownSource.incrementAndFetch()
                } else {
                    val p = s.participant
                    val arrival = nowMicros()
                    p.room.shard.execute { p.room.forward(p, data, len, arrival) }
                }
            }
            PacketKind.UNKNOWN -> metrics.unknownSource.incrementAndFetch()
        }
    }

    /** ICE-lite (RFC 8445 §2.5): answer connectivity checks and adopt the address the controlling agent uses. */
    private fun handleStun(data: ByteArray, len: Int, from: PeerAddress) {
        val msg = StunMessage.parse(data, 0, len) ?: return
        if (msg.type != StunConstants.BINDING_REQUEST) return
        metrics.stunRequests.incrementAndFetch()
        val ufrag = msg.username?.substringBefore(':')
        val session = ufrag?.let { byUfrag[it] }
        val password = session?.participant?.icePasswordFor(ufrag)
        if (session == null || password == null || !msg.verifyFingerprint() || !msg.verifyIntegrity(password)) {
            metrics.stunRejected.incrementAndFetch()
            return
        }
        val p = session.participant
        val response = StunMessageBuilder(StunConstants.BINDING_SUCCESS, msg.transactionId)
            .xorMappedAddress(from.ip, from.port)
            .build(integrityKey = password)
        transports.send(0, response, response.size, from)
        p.lastConsentMillis = nowMillis()
        if (ufrag == p.iceUfrag) p.previousIceUfrag?.let { old ->
            // The client switched to the restarted credentials: the old ones are done.
            synchronized(lock) { if (byUfrag[old] === session) byUfrag.remove(old) }
            p.forgetPreviousIce()
        }
        val current = p.remote
        if (current == null || (msg.useCandidate && current != from)) {
            synchronized(lock) {
                if (sessions[p.id] !== session) return
                current?.let { if (byAddress[it] === session) byAddress.remove(it) }
                p.remote = from
                byAddress[from] = session
            }
            session.flushPendingDtls()
        }
    }

    private fun transportFeedback(shard: Shard) {
        val mine = synchronized(lock) { rooms.values.filter { it.shard === shard } }
        for (room in mine) room.sendTransportFeedback()
    }

    private fun sctpTimers(shard: Shard) {
        val now = nowMillis()
        val mine = synchronized(lock) { rooms.values.filter { it.shard === shard } }
        for (room in mine) room.data.tick(now)
    }

    private fun housekeeping(shard: Shard) {
        val now = nowMillis()
        val mine = synchronized(lock) { rooms.values.filter { it.shard === shard } }
        for (room in mine) {
            room.tick(now)
            for (p in room.members) {
                if (p.remote != null && now - p.lastConsentMillis > MediaPlan.CONSENT_TIMEOUT_MILLIS) {
                    log.info("$p consent expired")
                    kick(p.id, "consent expired")
                }
            }
        }
    }

    // ------------------------------------------------------------------ per-participant negotiation

    private inner class PeerSession(val participant: Participant) {
        private val negotiation = SynchronizedObject()
        private val sessionId = secureRandomLong() and Long.MAX_VALUE
        private var version = 0L
        private var inFlight: Long = -1
        private var inFlightSlots: List<Participant?> = emptyList()
        private var inFlightVideo: Set<String> = emptySet()
        private var pending = false
        private val slots = ArrayList<Participant?>()

        @Volatile var dtls: DtlsEndpoint? = null
        @Volatile var mediaConnected: Boolean = false
        private val earlyDtls = ArrayList<ByteArray>()

        /** Sends a new offer reflecting current room membership, or queues one if an offer is outstanding. */
        fun negotiate() = synchronized(negotiation) {
            if (sessions[participant.id] !== this) return@synchronized
            if (inFlight >= 0) {
                pending = true
                return@synchronized
            }
            val members = participant.room.members.filter { it !== participant }
            for (i in slots.indices) if (slots[i] != null && slots[i] !in members) slots[i] = null
            for (m in members) if (m !in slots) {
                val free = slots.indexOf(null)
                if (free >= 0) slots[free] = m else slots += m
            }
            version++
            inFlight = version
            inFlightSlots = slots.toList()
            inFlightVideo = inFlightSlots.filterNotNull().flatMap { pub ->
                pub.feeds.filter { it.publishing && (it.kind == VideoKind.CAMERA || config.screenShare) }.map { it.key }
            }.toSet()
            val sdp = offers.build(participant, sessionId, version, inFlightSlots).toString()
            val screens = ArrayList<String>()
            val mids = buildMap {
                inFlightSlots.forEachIndexed { i, pub ->
                    if (pub != null) {
                        put("${2 + 2 * i}", pub.id)
                        if (pub.camera.key in inFlightVideo) put("${3 + 2 * i}", pub.id)
                        if (pub.screen.key in inFlightVideo) {
                            put(MediaPlan.screenMid(i), pub.id)
                            screens += MediaPlan.screenMid(i)
                        }
                    }
                }
            }
            participant.sink.send(ServerMessage.Offer(version, sdp, mids, screens))
        }

        fun onAnswer(answerVersion: Long, sdp: String) {
            val videoChanged = synchronized(negotiation) {
                if (answerVersion != inFlight) return
                val answer = SessionDescription.parse(sdp)
                participant.dataNegotiated = answer.media.any { it.mid == MediaPlan.DATA_MID && it.port != 0 }
                if (dtls == null && !startDtls(answer)) return
                participant.receiving = inFlightSlots.filterNotNull().map { it.id }.toSet()
                // New video subscriptions pick a layer and request a key frame on their first packet (Room.forwardVideo).
                participant.receivingVideo = inFlightVideo
                fun sends(mid: String) = answer.media.firstOrNull { it.mid == mid }?.let { m ->
                    m.port != 0 && (m.direction == Direction.SENDONLY || m.direction == Direction.SENDRECV)
                } ?: false
                val publishes = sends(MediaPlan.UPLINK_VIDEO_MID)
                val sharing = config.screenShare && sends(MediaPlan.UPLINK_SCREEN_MID)
                if (sharing) answer.media.firstOrNull { it.mid == MediaPlan.UPLINK_SCREEN_MID }?.let { participant.screenSsrcs = it.ssrcs.toSet() }
                val cameraChanged = publishes != participant.publishesVideo
                val screenChanged = sharing != participant.screen.publishing
                participant.publishesVideo = publishes
                participant.screen.publishing = sharing
                inFlight = -1
                if (pending) {
                    pending = false
                    negotiate()
                }
                cameraChanged to screenChanged
            }
            val (cameraChanged, screenChanged) = videoChanged
            if (cameraChanged || screenChanged) {
                // Its video m-lines in everyone else's offer change between sendonly and inactive.
                participant.room.members.filter { it !== participant }.forEach { sessions[it.id]?.negotiate() }
            }
            if (cameraChanged) eventFlow.tryEmit(SfuEvent.VideoPublishingChanged(participant.room.name, participant.id, participant.publishesVideo))
            if (screenChanged) eventFlow.tryEmit(SfuEvent.ScreenShareChanged(participant.room.name, participant.id, participant.screen.publishing))
        }

        private fun startDtls(answer: SessionDescription): Boolean {
            val (hash, fp) = answer.fingerprint() ?: (null to null)
            if (hash != "sha-256" || fp == null) {
                kick(participant.id, "answer without a sha-256 DTLS fingerprint")
                return false
            }
            val room = participant.room
            lateinit var endpoint: DtlsEndpoint
            endpoint = DtlsEndpoint(
                identity, fp,
                sender = { buf, off, len -> participant.remote?.let { to -> transports.send(0, buf.copyOfRange(off, off + len), len, to) } },
                onConnected = { keys ->
                    participant.installKeys(SrtpSessionKeys.forDtlsServer(keys.profile, keys.material))
                    // The SCTP endpoint is in place before any application record is read (same shard queue).
                    if (config.dataChannels && participant.dataNegotiated) room.shard.execute { room.data.attach(participant, endpoint::send) }
                    mediaConnected = true
                    metrics.dtlsConnected.incrementAndFetch()
                    eventFlow.tryEmit(SfuEvent.MediaConnected(participant.room.name, participant.id, keys.profile.name))
                    log.info("$participant DTLS connected, ${keys.profile}")
                },
                onFailed = { t ->
                    metrics.dtlsFailed.incrementAndFetch()
                    eventFlow.tryEmit(SfuEvent.MediaFailed(participant.room.name, participant.id, t.toString()))
                    log.info("$participant DTLS failed: $t")
                    kick(participant.id, "media connection failed")
                },
                onData = { packet -> room.shard.execute { room.data.onPacket(participant, packet) } },
            )
            dtls = endpoint
            endpoint.start()
            flushPendingDtls()
            return true
        }

        fun onDtls(record: ByteArray) {
            val d = dtls
            if (d != null) {
                d.deliver(record)
            } else {
                synchronized(negotiation) { if (earlyDtls.size < 16) earlyDtls += record }
            }
        }

        fun flushPendingDtls() {
            val d = dtls ?: return
            val pendingRecords = synchronized(negotiation) { earlyDtls.toList().also { earlyDtls.clear() } }
            pendingRecords.forEach(d::deliver)
        }
    }
}
