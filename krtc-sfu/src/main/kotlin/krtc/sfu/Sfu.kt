package krtc.sfu

import krtc.protocol.PacketKind
import krtc.protocol.classifyPacket
import krtc.protocol.sdp.SessionDescription
import krtc.protocol.stun.StunConstants
import krtc.protocol.stun.StunMessage
import krtc.protocol.stun.StunMessageBuilder
import krtc.sfu.dtls.DtlsEndpoint
import krtc.sfu.dtls.DtlsIdentity
import krtc.sfu.room.MediaPlan
import krtc.sfu.room.OfferBuilder
import krtc.sfu.room.Participant
import krtc.sfu.room.Room
import krtc.sfu.room.Shard
import krtc.sfu.room.SignalSink
import krtc.sfu.srtp.SrtpContext
import krtc.sfu.transport.UdpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

public class SfuConfig(
    public val udpPort: Int = 50000,
    public val httpPort: Int = 8080,
    public val bindAddress: String = "0.0.0.0",
    /** Address put into ICE candidates; must be reachable by clients (public IP behind NAT). */
    public val announcedAddress: String = "127.0.0.1",
    public val shards: Int = Runtime.getRuntime().availableProcessors(),
)

/**
 * The SFU core: rooms, participants, ICE-lite, DTLS-SRTP and offer/answer.
 * Transport-level entry point is [onPacket]; signaling entry points are [join], [onAnswer], [leave].
 */
public class Sfu(public val config: SfuConfig) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Sfu::class.java)

    public val metrics: Metrics = Metrics()
    public val identity: DtlsIdentity = DtlsIdentity.generate()
    private val shards = List(config.shards) { Shard(it) }
    public val udp: UdpServer = UdpServer(InetSocketAddress(config.bindAddress, config.udpPort), sockets = config.shards, onPacket = ::onPacket)
    private val offers = OfferBuilder(config.announcedAddress, udp.localPort, identity.fingerprint)

    private val rooms = ConcurrentHashMap<String, Room>()
    private val sessions = ConcurrentHashMap<String, PeerSession>() // by participant id
    private val byUfrag = ConcurrentHashMap<String, PeerSession>()
    private val byAddress = ConcurrentHashMap<InetSocketAddress, PeerSession>()
    private val ids = AtomicLong()
    private val stunSendBuffer = ByteBuffer.allocateDirect(1500) // UDP thread only
    private val dtlsSendBuffer = ByteBuffer.allocateDirect(1500) // guarded by itself

    init {
        for (s in shards) s.every(100) { housekeeping(s) }
        udp.start()
        log.info("SFU UDP on {} ({} sockets, announced {}), {} shards", udp.localPort, udp.socketCount, config.announcedAddress, shards.size)
    }

    public val participantCount: Int get() = sessions.size
    public val roomCount: Int get() = rooms.size

    public fun metricsJson(): JsonObject = metrics.toJson(participantCount, roomCount, shards.map { it.queueDepth })

    // ------------------------------------------------------------------ signaling

    /** Adds a participant to [roomName]; the SFU immediately sends it `welcome` and an `offer`. */
    public fun join(roomName: String, displayName: String, sink: SignalSink): Participant {
        val room = rooms.computeIfAbsent(roomName) { name ->
            Room(name, shards[Math.floorMod(name.hashCode(), shards.size)], udp, metrics, ::announceSpeaking)
        }
        val p = Participant(ids.incrementAndGet().toString(36), displayName.take(64), room, sink)
        val session = PeerSession(p)
        sessions[p.id] = session
        byUfrag[p.iceUfrag] = session
        val others: List<Participant>
        synchronized(room) {
            others = room.members
            room.add(p)
        }
        sink.send(buildJsonObject {
            put("type", "welcome")
            put("id", p.id)
            put("room", room.name)
            put("peers", buildJsonArray { others.forEach { add(peerJson(it)) } })
        }.toString())
        val joined = buildJsonObject { put("type", "peer-joined"); put("id", p.id); put("name", p.name) }.toString()
        others.forEach { it.sink.send(joined) }
        session.negotiate()
        others.forEach { sessions[it.id]?.negotiate() }
        log.info("{} joined room {} ({} members)", p, room.name, others.size + 1)
        return p
    }

    public fun onAnswer(p: Participant, version: Long, sdp: String) {
        sessions[p.id]?.onAnswer(version, sdp)
    }

    public fun leave(p: Participant) {
        val session = sessions.remove(p.id) ?: return
        byUfrag.remove(p.iceUfrag)
        p.remote?.let { byAddress.remove(it, session) }
        session.dtls?.close()
        val room = p.room
        val remaining: List<Participant>
        synchronized(room) {
            room.remove(p)
            remaining = room.members
            if (remaining.isEmpty()) rooms.remove(room.name, room)
        }
        val left = buildJsonObject { put("type", "peer-left"); put("id", p.id) }.toString()
        remaining.forEach { it.sink.send(left); sessions[it.id]?.negotiate() }
        log.info("{} left room {}", p, room.name)
    }

    private fun peerJson(p: Participant) = buildJsonObject { put("id", p.id); put("name", p.name) }

    private fun announceSpeaking(p: Participant, speaking: Boolean) {
        val msg = buildJsonObject { put("type", "speaking"); put("id", p.id); put("speaking", speaking) }.toString()
        p.room.members.forEach { it.sink.send(msg) }
    }

    // ------------------------------------------------------------------ transport (UDP thread)

    private fun onPacket(data: ByteArray, len: Int, from: InetSocketAddress) {
        when (classifyPacket(data, 0, len)) {
            PacketKind.STUN -> handleStun(data, len, from)
            PacketKind.DTLS -> byAddress[from]?.onDtls(data.copyOf(len)) ?: metrics.unknownSource.increment()
            PacketKind.RTP_OR_RTCP -> {
                val s = byAddress[from]
                if (s == null) {
                    metrics.unknownSource.increment()
                } else {
                    val p = s.participant
                    p.room.shard.execute { p.room.forward(p, data, len) }
                }
            }
            PacketKind.UNKNOWN -> metrics.unknownSource.increment()
        }
    }

    /** ICE-lite (RFC 8445 §2.5): answer connectivity checks and adopt the address the controlling agent uses. */
    private fun handleStun(data: ByteArray, len: Int, from: InetSocketAddress) {
        val msg = StunMessage.parse(data, 0, len) ?: return
        if (msg.type != StunConstants.BINDING_REQUEST) return
        metrics.stunRequests.increment()
        val session = msg.username?.substringBefore(':')?.let { byUfrag[it] }
        if (session == null || !msg.verifyFingerprint() || !msg.verifyIntegrity(session.participant.icePwdBytes)) {
            metrics.stunRejected.increment()
            return
        }
        val p = session.participant
        val response = StunMessageBuilder(StunConstants.BINDING_SUCCESS, msg.transactionId)
            .xorMappedAddress(from.address.address, from.port)
            .build(integrityKey = p.icePwdBytes)
        udp.send(0, stunSendBuffer, response, response.size, from)
        p.lastConsentMillis = System.currentTimeMillis()
        val current = p.remote
        if (current == null || (msg.useCandidate && current != from)) {
            current?.let { byAddress.remove(it, session) }
            p.remote = from
            byAddress[from] = session
            session.flushPendingDtls()
        }
    }

    private fun housekeeping(shard: Shard) {
        val now = System.currentTimeMillis()
        for (room in rooms.values) {
            if (room.shard !== shard) continue
            room.tick(now)
            for (p in room.members) {
                if (p.remote != null && now - p.lastConsentMillis > MediaPlan.CONSENT_TIMEOUT_MILLIS) {
                    log.info("{} consent expired", p)
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
        private val sessionId = SecureRandom().nextLong() and Long.MAX_VALUE
        private var version = 0L
        private var inFlight: Long = -1
        private var inFlightSlots: List<Participant?> = emptyList()
        private var pending = false
        private val slots = ArrayList<Participant?>()

        @Volatile var dtls: DtlsEndpoint? = null
        private val earlyDtls = ArrayList<ByteArray>()

        /** Sends a new offer reflecting current room membership, or queues one if an offer is outstanding. */
        @Synchronized
        fun negotiate() {
            if (!sessions.containsKey(participant.id)) return
            if (inFlight >= 0) {
                pending = true
                return
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
            val sdp = offers.build(participant, sessionId, version, inFlightSlots).toString()
            participant.sink.send(buildJsonObject {
                put("type", "offer")
                put("version", version)
                put("sdp", sdp)
                putJsonObject("mids") { inFlightSlots.forEachIndexed { i, pub -> if (pub != null) put("${i + 1}", pub.id) } }
            }.toString())
        }

        @Synchronized
        fun onAnswer(answerVersion: Long, sdp: String) {
            if (answerVersion != inFlight) return
            val answer = SessionDescription.parse(sdp)
            if (dtls == null) startDtls(answer)
            participant.receiving = inFlightSlots.filterNotNull().map { it.id }.toSet()
            inFlight = -1
            if (pending) {
                pending = false
                negotiate()
            }
        }

        private fun startDtls(answer: SessionDescription) {
            val (hash, fp) = answer.fingerprint() ?: return participant.sink.close()
            if (hash != "sha-256") return participant.sink.close()
            val endpoint = DtlsEndpoint(
                identity, fp,
                sender = { buf, off, len ->
                    participant.remote?.let { to ->
                        synchronized(dtlsSendBuffer) { udp.send(0, dtlsSendBuffer, buf.copyOfRange(off, off + len), len, to) }
                    }
                },
                onConnected = { keys ->
                    val (clientCtx, serverCtx) = SrtpContext.fromDtlsExport(keys.profile, keys.material)
                    participant.installSrtp(inbound = clientCtx, outbound = serverCtx)
                    metrics.dtlsConnected.increment()
                    log.info("{} DTLS connected, {}", participant, keys.profile)
                },
                onFailed = { metrics.dtlsFailed.increment(); participant.sink.close() },
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
                synchronized(earlyDtls) { if (earlyDtls.size < 16) earlyDtls += record }
            }
        }

        fun flushPendingDtls() {
            val d = dtls ?: return
            val pendingRecords = synchronized(earlyDtls) { earlyDtls.toList().also { earlyDtls.clear() } }
            pendingRecords.forEach(d::deliver)
        }
    }

}
