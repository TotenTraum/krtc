package krtc.sfu

import kotlin.concurrent.atomics.incrementAndFetch
import io.ktor.util.collections.ConcurrentMap
import krtc.protocol.PacketKind
import krtc.protocol.classifyPacket
import krtc.protocol.sdp.SessionDescription
import krtc.protocol.stun.StunConstants
import krtc.protocol.stun.StunMessage
import krtc.protocol.stun.StunMessageBuilder
import krtc.sfu.dtls.DtlsEndpoint
import krtc.sfu.dtls.DtlsIdentity
import krtc.sfu.platform.PeerAddress
import krtc.sfu.platform.UdpServer
import krtc.sfu.platform.availableProcessors
import krtc.sfu.platform.logger
import krtc.sfu.platform.nowMillis
import krtc.sfu.platform.secureRandomLong
import krtc.sfu.room.MediaPlan
import krtc.sfu.room.OfferBuilder
import krtc.sfu.room.Participant
import krtc.sfu.room.Room
import krtc.sfu.room.Shard
import krtc.sfu.room.SignalSink
import krtc.sfu.srtp.SrtpSessionKeys
import krtc.protocol.sdp.Direction
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

public class SfuConfig(
    public val udpPort: Int = 50000,
    public val httpPort: Int = 8080,
    public val bindAddress: String = "0.0.0.0",
    /** Address put into ICE candidates; must be reachable by clients (public IP behind NAT). */
    public val announcedAddress: String = "127.0.0.1",
    public val shards: Int = availableProcessors(),
    /** Testing only: percentage of downlink video packets to drop, to exercise NACK/RTX recovery. */
    public val simulatedLossPercent: Int = 0,
)

/**
 * The SFU core: rooms, participants, ICE-lite, DTLS-SRTP and offer/answer. Common code for JVM and
 * Kotlin/Native. Transport entry point is [onPacket]; signaling entry points are [join], [onAnswer], [leave].
 */
public class Sfu(public val config: SfuConfig) : AutoCloseable {
    private val log = logger("krtc.sfu.Sfu")

    public val metrics: Metrics = Metrics()
    public val identity: DtlsIdentity = DtlsIdentity.generate()
    private val shards = List(config.shards) { Shard(it) }
    public val udp: UdpServer = UdpServer(config.bindAddress, config.udpPort, config.shards, ::onPacket)
    private val offers = OfferBuilder(config.announcedAddress, udp.localPort, identity.fingerprint)

    /** Guards membership: [rooms], room member lists and the address table updates. Never held on the media path. */
    private val lock = SynchronizedObject()
    private val rooms = HashMap<String, Room>()
    private val sessions = ConcurrentMap<String, PeerSession>() // by participant id
    private val byUfrag = ConcurrentMap<String, PeerSession>()
    private val byAddress = ConcurrentMap<PeerAddress, PeerSession>()
    private val ids = AtomicLong(0)

    init {
        for (s in shards) s.every(100) { housekeeping(s) }
        udp.start()
        if (config.simulatedLossPercent > 0) log.warn("Simulating ${config.simulatedLossPercent}% downlink video loss")
        log.info("SFU UDP on ${udp.localPort} (${udp.socketCount} sockets, announced ${config.announcedAddress}), ${shards.size} shards")
    }

    public val participantCount: Int get() = sessions.size
    public val roomCount: Int get() = synchronized(lock) { rooms.size }

    public fun metricsJson(): JsonObject = metrics.toJson(participantCount, roomCount, shards)

    // ------------------------------------------------------------------ signaling

    /** Adds a participant to [roomName]; the SFU immediately sends it `welcome` and an `offer`. */
    public fun join(roomName: String, displayName: String, sink: SignalSink): Participant {
        val (p, session, others) = synchronized(lock) {
            val room = rooms.getOrPut(roomName) {
                Room(roomName, shards[roomName.hashCode().mod(shards.size)], udp, ::announceSpeaking, config.simulatedLossPercent)
            }
            val p = Participant(ids.incrementAndFetch().toString(36), displayName.take(64), room, sink)
            val session = PeerSession(p)
            sessions[p.id] = session
            byUfrag[p.iceUfrag] = session
            Triple(p, session, room.add(p))
        }
        sink.send(buildJsonObject {
            put("type", "welcome")
            put("id", p.id)
            put("room", roomName)
            put("peers", buildJsonArray { others.forEach { add(peerJson(it)) } })
        }.toString())
        val joined = buildJsonObject { put("type", "peer-joined"); put("id", p.id); put("name", p.name) }.toString()
        others.forEach { it.sink.send(joined) }
        session.negotiate()
        others.forEach { sessions[it.id]?.negotiate() }
        log.info("$p joined room $roomName (${others.size + 1} members)")
        return p
    }

    public fun onAnswer(p: Participant, version: Long, sdp: String) {
        sessions[p.id]?.onAnswer(version, sdp)
    }

    public fun leave(p: Participant) {
        val (session, remaining) = synchronized(lock) {
            val session = sessions.remove(p.id) ?: return
            byUfrag.remove(p.iceUfrag)
            p.remote?.let { if (byAddress[it] === session) byAddress.remove(it) }
            val remaining = p.room.remove(p)
            if (remaining.isEmpty()) rooms.remove(p.room.name)
            session to remaining
        }
        session.dtls?.close()
        val left = buildJsonObject { put("type", "peer-left"); put("id", p.id) }.toString()
        remaining.forEach { it.sink.send(left); sessions[it.id]?.negotiate() }
        log.info("$p left room ${p.room.name}")
    }

    private fun peerJson(p: Participant) = buildJsonObject { put("id", p.id); put("name", p.name) }

    private fun announceSpeaking(p: Participant, speaking: Boolean) {
        val msg = buildJsonObject { put("type", "speaking"); put("id", p.id); put("speaking", speaking) }.toString()
        p.room.members.forEach { it.sink.send(msg) }
    }

    // ------------------------------------------------------------------ transport (UDP threads)

    private fun onPacket(data: ByteArray, len: Int, from: PeerAddress) {
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
                    p.room.shard.execute { p.room.forward(p, data, len) }
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
        val session = msg.username?.substringBefore(':')?.let { byUfrag[it] }
        if (session == null || !msg.verifyFingerprint() || !msg.verifyIntegrity(session.participant.icePwdBytes)) {
            metrics.stunRejected.incrementAndFetch()
            return
        }
        val p = session.participant
        val response = StunMessageBuilder(StunConstants.BINDING_SUCCESS, msg.transactionId)
            .xorMappedAddress(from.ip, from.port)
            .build(integrityKey = p.icePwdBytes)
        udp.send(0, response, response.size, from)
        p.lastConsentMillis = nowMillis()
        val current = p.remote
        if (current == null || (msg.useCandidate && current != from)) {
            synchronized(lock) {
                if (!sessions.containsKey(p.id)) return
                current?.let { if (byAddress[it] === session) byAddress.remove(it) }
                p.remote = from
                byAddress[from] = session
            }
            session.flushPendingDtls()
        }
    }

    private fun housekeeping(shard: Shard) {
        val now = nowMillis()
        val mine = synchronized(lock) { rooms.values.filter { it.shard === shard } }
        for (room in mine) {
            room.tick(now)
            for (p in room.members) {
                if (p.remote != null && now - p.lastConsentMillis > MediaPlan.CONSENT_TIMEOUT_MILLIS) {
                    log.info("$p consent expired")
                    p.sink.close()
                }
            }
        }
    }

    override fun close() {
        udp.close()
        shards.forEach { it.close() }
        sessions.values.forEach { it.dtls?.close() }
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
        private val earlyDtls = ArrayList<ByteArray>()

        /** Sends a new offer reflecting current room membership, or queues one if an offer is outstanding. */
        fun negotiate() = synchronized(negotiation) {
            if (!sessions.containsKey(participant.id)) return@synchronized
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
            inFlightVideo = inFlightSlots.filter { it != null && it.publishesVideo }.map { it!!.id }.toSet()
            val sdp = offers.build(participant, sessionId, version, inFlightSlots).toString()
            participant.sink.send(buildJsonObject {
                put("type", "offer")
                put("version", version)
                put("sdp", sdp)
                putJsonObject("mids") {
                    inFlightSlots.forEachIndexed { i, pub ->
                        if (pub != null) {
                            put("${2 + 2 * i}", pub.id)
                            if (pub.id in inFlightVideo) put("${3 + 2 * i}", pub.id)
                        }
                    }
                }
            }.toString())
        }

        fun onAnswer(answerVersion: Long, sdp: String) {
            val videoChanged = synchronized(negotiation) {
                if (answerVersion != inFlight) return
                val answer = SessionDescription.parse(sdp)
                if (dtls == null) startDtls(answer)
                participant.receiving = inFlightSlots.filterNotNull().map { it.id }.toSet()
                val newlyWatched = inFlightVideo - participant.receivingVideo
                participant.receivingVideo = inFlightVideo
                // Someone starts watching a publisher: ask that publisher for a key frame right away.
                val room = participant.room
                newlyWatched.forEach { id -> room.members.firstOrNull { it.id == id }?.let { pub -> room.shard.execute { room.requestKeyframe(pub) } } }
                val uplinkVideo = answer.media.firstOrNull { it.mid == "1" }?.direction
                val publishes = uplinkVideo == Direction.SENDONLY || uplinkVideo == Direction.SENDRECV
                val changed = publishes != participant.publishesVideo
                participant.publishesVideo = publishes
                inFlight = -1
                if (pending) {
                    pending = false
                    negotiate()
                }
                changed
            }
            // Its video m-line in everyone else's offer changes between sendonly and inactive.
            if (videoChanged) participant.room.members.filter { it !== participant }.forEach { sessions[it.id]?.negotiate() }
        }

        private fun startDtls(answer: SessionDescription) {
            val (hash, fp) = answer.fingerprint() ?: return participant.sink.close()
            if (hash != "sha-256") return participant.sink.close()
            val endpoint = DtlsEndpoint(
                identity, fp,
                sender = { buf, off, len -> participant.remote?.let { to -> udp.send(0, buf.copyOfRange(off, off + len), len, to) } },
                onConnected = { keys ->
                    participant.installKeys(SrtpSessionKeys.forDtlsServer(keys.profile, keys.material))
                    metrics.dtlsConnected.incrementAndFetch()
                    log.info("$participant DTLS connected, ${keys.profile}")
                },
                onFailed = { t ->
                    metrics.dtlsFailed.incrementAndFetch()
                    log.info("$participant DTLS failed: $t")
                    participant.sink.close()
                },
            )
            dtls = endpoint
            endpoint.start()
            flushPendingDtls()
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
