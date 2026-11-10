package krtc.sfu.sctp

import krtc.protocol.u16
import krtc.protocol.u32
import krtc.protocol.u8

/**
 * An SCTP association over DTLS (RFC 8261), as WebRTC data channels use it (RFC 8831): one association per
 * peer connection, port 5000 on both sides, no multihoming (DTLS is the path).
 *
 * Implemented: the four-way handshake (as responder, and as initiator for tests), DATA with fragmentation and
 * reassembly, ordered and unordered delivery, SACK with gap blocks, retransmission (T3 timer, fast retransmit),
 * congestion control (RFC 9260 §7), partial reliability (RFC 3758: FORWARD-TSN, limited retransmissions and
 * lifetime), stream reset (RFC 6525) for closing data channels, HEARTBEAT replies, ABORT and SHUTDOWN.
 * Not implemented: I-DATA (RFC 8260; never advertised), stream schedulers, a sender-side heartbeat (ICE consent
 * covers liveness), restart preserving state (a new INIT starts a fresh association).
 *
 * Not thread-safe: the owner calls everything from one thread. Time is passed in, in milliseconds.
 */
internal class SctpAssociation(
    private val output: (ByteArray) -> Unit,
    private val listener: Listener,
    private val random: () -> Long,
    private val port: Int = 5000,
) {
    interface Listener {
        fun onEstablished()
        fun onMessage(stream: Int, ppid: Int, data: ByteArray)

        /** The peer reset these incoming streams (closed the channels on them); their SSNs start over. */
        fun onIncomingStreamsReset(streams: IntArray)

        /** Our reset of these outgoing streams completed. */
        fun onOutgoingStreamsReset(streams: IntArray)
        fun onClosed(reason: String)
    }

    enum class State { CLOSED, COOKIE_WAIT, COOKIE_ECHOED, ESTABLISHED, SHUTDOWN }

    var state: State = State.CLOSED
        private set

    // ------------------------------------------------------------------ handshake state

    private var localTag = 0L
    private var peerTag = 0L
    private var cookie: ByteArray? = null
    private var peerSupportsForwardTsn = false
    private var peerSupportsReconfig = false
    private var t1Deadline = Long.MAX_VALUE
    private var t1Attempts = 0
    private var handshakeChunk: ByteArray? = null // INIT or COOKIE ECHO, resent on T1

    // ------------------------------------------------------------------ receive state

    /** Highest TSN up to which everything arrived (unwrapped). */
    private var peerCumTsn = 0L
    private val received = HashSet<Long>() // TSNs above peerCumTsn
    private val fragments = HashMap<Long, Fragment>() // by TSN, until assembled
    private val inStreams = HashMap<Int, InStream>()
    private var receiveBuffered = 0
    private var sackNeeded = false
    private val duplicates = ArrayList<Long>()

    private class Fragment(val tsn: Long, val stream: Int, val ssn: Int, val ppid: Int, val flags: Int, val data: ByteArray) {
        val begin get() = flags and DataFlags.BEGIN != 0
        val end get() = flags and DataFlags.END != 0
        val unordered get() = flags and DataFlags.UNORDERED != 0
    }

    private class InStream {
        var nextSsn = 0
        val waiting = HashMap<Int, Pair<Int, ByteArray>>() // ssn -> (ppid, message)
    }

    // ------------------------------------------------------------------ send state

    private class OutMessage(val stream: Int, val ordered: Boolean, val ssn: Int, val maxRetransmits: Int, val expiresAt: Long) {
        val chunks = ArrayList<OutChunk>(1)
        var abandoned = false
    }

    private class OutChunk(val tsn: Long, val message: OutMessage, val ppid: Int, val flags: Int, val payload: ByteArray) {
        var transmits = 0
        var sentAt = 0L
        var inFlight = false
        var acked = false
        var abandoned = false
        var retransmit = false
        var missingReports = 0
    }

    private var nextTsn = 0L
    private var cumAckTsn = 0L // our TSNs acknowledged cumulatively by the peer
    private val outstanding = ArrayDeque<OutChunk>() // TSN order, all not cumulatively acked: first is cumAckTsn + 1
    private var unsentIndex = 0 // outstanding[unsentIndex..] were never sent
    private val outSsn = HashMap<Int, Int>()
    private var flightBytes = 0
    private var queuedBytes = 0L
    private var cwnd = 4 * MTU
    private var ssthresh = Int.MAX_VALUE
    private var partialBytesAcked = 0
    private var peerRwnd = 0L
    private var fastRecoveryExit = -1L
    private var srtt = -1.0
    private var rttvar = 0.0
    private var rto = RTO_INITIAL
    private var t3Deadline = Long.MAX_VALUE
    private var errorCount = 0
    private var forwardTsnNeeded = false
    private val control = ArrayList<ByteArray>() // chunks to send in the next packet, before DATA

    // ------------------------------------------------------------------ stream reset (RFC 6525)

    private var nextRequestSeq = 0L // ours
    private var peerNextRequestSeq = 0L // the peer's next expected request
    private var lastPeerResult = RESULT_SUCCESS_PERFORMED
    private val resetQueue = LinkedHashSet<Int>() // outgoing streams waiting for a request
    private var resetInFlight: IntArray? = null
    private var resetInFlightSeq = 0L
    private var resetDeadline = Long.MAX_VALUE

    /** Bytes accepted by [send] and not yet acknowledged (nor abandoned). */
    val bufferedAmount: Long get() = queuedBytes

    // ------------------------------------------------------------------ API

    /** Initiates the association (the side that does not wait for INIT). */
    fun connect(now: Long) {
        check(state == State.CLOSED)
        reset()
        state = State.COOKIE_WAIT
        handshakeChunk = initChunk(ChunkType.INIT, null)
        sendHandshake(now)
    }

    /**
     * Queues one message. [maxRetransmits] < 0 and [lifetimeMillis] < 0 mean fully reliable.
     * Returns false if the association is not established or the message is too large.
     */
    fun send(stream: Int, ppid: Int, data: ByteArray, ordered: Boolean, maxRetransmits: Int, lifetimeMillis: Long, now: Long): Boolean {
        if (state != State.ESTABLISHED || data.size > MAX_MESSAGE) return false
        val ssn = if (ordered) outSsn.getOrElse(stream) { 0 }.also { outSsn[stream] = (it + 1) and 0xFFFF } else 0
        val partial = peerSupportsForwardTsn
        val message = OutMessage(
            stream, ordered, ssn,
            if (partial) maxRetransmits else -1,
            if (partial && lifetimeMillis >= 0) now + lifetimeMillis else Long.MAX_VALUE,
        )
        val pieces = maxOf(1, (data.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD)
        for (i in 0 until pieces) {
            var flags = if (ordered) 0 else DataFlags.UNORDERED
            if (i == 0) flags = flags or DataFlags.BEGIN
            if (i == pieces - 1) flags = flags or DataFlags.END
            val piece = data.copyOfRange(i * MAX_PAYLOAD, minOf(data.size, (i + 1) * MAX_PAYLOAD))
            val c = OutChunk(nextTsn++, message, ppid, flags, piece)
            message.chunks += c
            outstanding.addLast(c)
            queuedBytes += piece.size
        }
        flush(now)
        return true
    }

    /** Resets our outgoing [streams] (closing data channels): the peer resets its incoming side. */
    fun resetStreams(streams: IntArray, now: Long) {
        if (state != State.ESTABLISHED || !peerSupportsReconfig) return
        streams.forEach { resetQueue += it }
        sendResetRequest(now)
        flush(now)
    }

    /** Handles one packet received from the peer (the plaintext of a DTLS record). */
    fun handle(buf: ByteArray, len: Int, now: Long) {
        val packet = SctpPacket.parse(buf, len) ?: return
        if (packet.destinationPort != port) return
        for ((i, c) in packet.chunks.withIndex()) {
            if (!tagAccepted(packet, c, i)) return
            when (c.type) {
                ChunkType.INIT -> onInit(c)
                ChunkType.INIT_ACK -> onInitAck(c, now)
                ChunkType.COOKIE_ECHO -> onCookieEcho(c)
                ChunkType.COOKIE_ACK -> onCookieAck()
                ChunkType.DATA -> onData(c)
                ChunkType.SACK -> onSack(c, now)
                ChunkType.HEARTBEAT -> control += chunk(ChunkType.HEARTBEAT_ACK, 0, c.bytes(0))
                ChunkType.HEARTBEAT_ACK, ChunkType.ERROR -> {}
                ChunkType.FORWARD_TSN -> onForwardTsn(c)
                ChunkType.RECONFIG -> onReconfig(c, now)
                ChunkType.ABORT -> { closed("aborted by peer"); return }
                ChunkType.SHUTDOWN -> { control += chunk(ChunkType.SHUTDOWN_ACK, 0, ByteArray(0)); flush(now); closed("shut down by peer"); return }
                ChunkType.SHUTDOWN_ACK -> { control += chunk(ChunkType.SHUTDOWN_COMPLETE, 0, ByteArray(0)); flush(now); closed("shut down"); return }
                ChunkType.SHUTDOWN_COMPLETE -> { closed("shut down"); return }
                else -> if (c.type and 0x80 == 0) return // RFC 9260 §3.2: unknown, stop processing the packet
            }
        }
        flush(now)
    }

    /** Runs timers; call every few tens of milliseconds. */
    fun tick(now: Long) {
        if (now >= t1Deadline) {
            if (++t1Attempts > MAX_INIT_RETRANSMITS) return closed("handshake timed out")
            sendHandshake(now)
        }
        if (state != State.ESTABLISHED) return
        expireMessages(now)
        if (now >= t3Deadline) onT3Timeout(now)
        if (now >= resetDeadline) {
            resetInFlight = null // no response: ask again
            sendResetRequest(now)
        }
        flush(now)
    }

    /** Ends the association at once (ABORT). */
    fun abort() {
        if (state == State.CLOSED) return
        if (state != State.COOKIE_WAIT) output(SctpPacket.build(port, port, peerTag, listOf(chunk(ChunkType.ABORT, 0, ByteArray(0)))))
        state = State.CLOSED
    }

    // ------------------------------------------------------------------ handshake

    private fun reset() {
        localTag = nonZero32()
        nextTsn = random() and 0xFFFFFFFFL
        cumAckTsn = nextTsn - 1
        nextRequestSeq = nextTsn
        outstanding.clear()
        unsentIndex = 0
        outSsn.clear()
        flightBytes = 0
        queuedBytes = 0
        cwnd = 4 * MTU
        ssthresh = Int.MAX_VALUE
        partialBytesAcked = 0
        srtt = -1.0
        rto = RTO_INITIAL
        t3Deadline = Long.MAX_VALUE
        t1Deadline = Long.MAX_VALUE
        t1Attempts = 0
        errorCount = 0
        received.clear()
        fragments.clear()
        inStreams.clear()
        receiveBuffered = 0
        control.clear()
        resetQueue.clear()
        resetInFlight = null
        resetDeadline = Long.MAX_VALUE
        fastRecoveryExit = -1
    }

    private fun initChunk(type: Int, stateCookie: ByteArray?): ByteArray {
        val params = ArrayList<ByteArray>()
        stateCookie?.let { params += param(ParamType.STATE_COOKIE, it) }
        params += param(ParamType.SUPPORTED_EXTENSIONS, byteArrayOf(ChunkType.RECONFIG.toByte(), ChunkType.FORWARD_TSN.toByte()))
        params += param(ParamType.FORWARD_TSN_SUPPORTED, ByteArray(0))
        val w = Writer(16 + params.sumOf { it.size })
            .u32(localTag).u32(RWND.toLong()).u16(MAX_STREAMS).u16(MAX_STREAMS).u32(nextTsn)
        params.forEach { w.put(it) }
        return chunk(type, 0, w.bytes)
    }

    /** Peer parameters from an INIT or INIT ACK. */
    private fun adoptPeer(c: Chunk): Boolean {
        if (c.len < 16) return false
        peerTag = c.u32(0)
        if (peerTag == 0L) return false
        peerRwnd = c.u32(4)
        ssthresh = peerRwnd.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val initialTsn = c.u32(12)
        peerCumTsn = initialTsn - 1
        peerNextRequestSeq = initialTsn
        peerSupportsForwardTsn = false
        peerSupportsReconfig = false
        forEachParam(c.buf, c.off + 16, c.off + c.len) { type, off, len ->
            when (type) {
                ParamType.FORWARD_TSN_SUPPORTED -> peerSupportsForwardTsn = true
                ParamType.SUPPORTED_EXTENSIONS -> for (i in 0 until len) when (c.buf.u8(off + i)) {
                    ChunkType.FORWARD_TSN -> peerSupportsForwardTsn = true
                    ChunkType.RECONFIG -> peerSupportsReconfig = true
                }
                ParamType.STATE_COOKIE -> cookie = c.buf.copyOfRange(off, off + len)
            }
        }
        return true
    }

    private fun onInit(c: Chunk) {
        val repeated = initAck
        if (state == State.COOKIE_WAIT && handshakeChunk == null && repeated != null && c.len >= 4 && c.u32(0) == peerTag) {
            output(repeated) // our INIT ACK was lost: the same one again, so the cookie the peer echoes stays valid
            return
        }
        if (state == State.ESTABLISHED) listener.onClosed("restarted by peer")
        reset()
        if (!adoptPeer(c)) return
        val stateCookie = ByteArray(16) { (random() and 0xFF).toByte() }
        cookie = stateCookie
        state = State.COOKIE_WAIT // waiting for COOKIE ECHO, as responder
        // The INIT ACK goes out alone, tagged with the peer's tag.
        val packet = SctpPacket.build(port, port, peerTag, listOf(initChunk(ChunkType.INIT_ACK, stateCookie)))
        initAck = packet
        output(packet)
    }

    private var initAck: ByteArray? = null

    private fun onInitAck(c: Chunk, now: Long) {
        if (state != State.COOKIE_WAIT || handshakeChunk == null) return
        cookie = null
        if (!adoptPeer(c)) return
        val echoed = cookie ?: return closed("INIT ACK without a state cookie")
        state = State.COOKIE_ECHOED
        handshakeChunk = chunk(ChunkType.COOKIE_ECHO, 0, echoed)
        t1Attempts = 0
        sendHandshake(now)
    }

    private fun onCookieEcho(c: Chunk) {
        val expected = cookie ?: return
        if (!c.bytes(0).contentEquals(expected)) return
        control += chunk(ChunkType.COOKIE_ACK, 0, ByteArray(0))
        if (state != State.ESTABLISHED) established()
    }

    private fun onCookieAck() {
        if (state == State.COOKIE_ECHOED) established()
    }

    private fun established() {
        state = State.ESTABLISHED
        handshakeChunk = null
        t1Deadline = Long.MAX_VALUE
        listener.onEstablished()
    }

    private fun sendHandshake(now: Long) {
        val c = handshakeChunk ?: return
        output(SctpPacket.build(port, port, if (c[0].toInt() == ChunkType.INIT) 0 else peerTag, listOf(c)))
        t1Deadline = now + RTO_INITIAL * (1L shl minOf(t1Attempts, 6))
    }

    private fun tagAccepted(packet: SctpPacket, c: Chunk, index: Int): Boolean = when (c.type) {
        ChunkType.INIT -> index == 0 && packet.verificationTag == 0L && packet.chunks.size == 1
        ChunkType.ABORT, ChunkType.SHUTDOWN_COMPLETE ->
            packet.verificationTag == if (c.flags and 1 != 0) peerTag else localTag
        else -> packet.verificationTag == localTag
    }

    private fun closed(reason: String) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        t1Deadline = Long.MAX_VALUE
        t3Deadline = Long.MAX_VALUE
        listener.onClosed(reason)
    }

    // ------------------------------------------------------------------ receiving

    private fun unwrapPeer(tsn: Long): Long = peerCumTsn + ((tsn - peerCumTsn).toInt()).toLong()

    private fun onData(c: Chunk) {
        if (state != State.ESTABLISHED || c.len < 12) return
        sackNeeded = true
        val tsn = unwrapPeer(c.u32(0))
        if (tsn <= peerCumTsn || tsn in received) {
            if (duplicates.size < 16) duplicates += tsn
            return
        }
        val data = c.bytes(12)
        // Out of buffer: drop without acknowledging; the sender retransmits.
        if (receiveBuffered + data.size > RWND) return
        received += tsn
        while (received.remove(peerCumTsn + 1)) peerCumTsn++
        val f = Fragment(tsn, c.u16(4), c.u16(6), c.u32(8).toInt(), c.flags, data)
        if (f.begin && f.end) return deliver(f.stream, f.ssn, f.ppid, f.unordered, f.data)
        fragments[tsn] = f
        receiveBuffered += data.size
        assemble(f)
    }

    /** Completes the message [f] belongs to, if all of its fragments (consecutive TSNs) are here. */
    private fun assemble(f: Fragment) {
        var first = f
        while (!first.begin) first = fragments[first.tsn - 1]?.takeIf { it.stream == f.stream && it.unordered == f.unordered } ?: return
        var last = f
        while (!last.end) last = fragments[last.tsn + 1]?.takeIf { it.stream == f.stream && it.unordered == f.unordered } ?: return
        val parts = (first.tsn..last.tsn).map { fragments.remove(it)!! }
        val size = parts.sumOf { it.data.size }
        receiveBuffered -= size
        val message = ByteArray(size)
        var at = 0
        for (p in parts) {
            p.data.copyInto(message, at)
            at += p.data.size
        }
        deliver(first.stream, first.ssn, first.ppid, first.unordered, message)
    }

    private fun deliver(stream: Int, ssn: Int, ppid: Int, unordered: Boolean, message: ByteArray) {
        if (unordered) return listener.onMessage(stream, ppid, message)
        val s = inStreams.getOrPut(stream) { InStream() }
        if (ssnBefore(ssn, s.nextSsn)) return // already skipped by FORWARD-TSN
        s.waiting[ssn] = ppid to message
        receiveBuffered += message.size
        drain(stream, s)
    }

    private fun drain(stream: Int, s: InStream) {
        while (true) {
            val (ppid, message) = s.waiting.remove(s.nextSsn) ?: return
            receiveBuffered -= message.size
            s.nextSsn = (s.nextSsn + 1) and 0xFFFF
            listener.onMessage(stream, ppid, message)
        }
    }

    private fun onForwardTsn(c: Chunk) {
        if (state != State.ESTABLISHED || c.len < 4) return
        sackNeeded = true
        val newCum = unwrapPeer(c.u32(0))
        if (newCum <= peerCumTsn) return
        // Drop fragments of the abandoned messages and anything else at or below the new cumulative TSN.
        fragments.keys.filter { it <= newCum }.forEach { receiveBuffered -= fragments.remove(it)!!.data.size }
        received.removeAll { it <= newCum }
        peerCumTsn = newCum
        while (received.remove(peerCumTsn + 1)) peerCumTsn++
        var at = 4
        while (at + 4 <= c.len) {
            val stream = c.u16(at)
            val ssn = c.u16(at + 2)
            at += 4
            val s = inStreams.getOrPut(stream) { InStream() }
            if (!ssnBefore(ssn, s.nextSsn)) {
                // Messages up to ssn are abandoned; anything waiting at or below it is gone too.
                s.waiting.keys.filter { !ssnBefore(ssn, it) }.forEach { receiveBuffered -= s.waiting.remove(it)!!.second.size }
                s.nextSsn = (ssn + 1) and 0xFFFF
                drain(stream, s)
            }
        }
        performDeferredReset()
    }

    private fun sackChunk(): ByteArray {
        val above = received.sorted()
        val gaps = ArrayList<Pair<Int, Int>>()
        for (t in above) {
            val offset = (t - peerCumTsn).toInt()
            val last = gaps.lastOrNull()
            if (last != null && last.second + 1 == offset) gaps[gaps.size - 1] = last.first to offset
            else if (gaps.size < MAX_GAP_BLOCKS) gaps += offset to offset
        }
        val w = Writer(12 + 4 * gaps.size + 4 * duplicates.size)
            .u32(peerCumTsn).u32(maxOf(0, RWND - receiveBuffered).toLong()).u16(gaps.size).u16(duplicates.size)
        gaps.forEach { (start, end) -> w.u16(start).u16(end) }
        duplicates.forEach { w.u32(it) }
        duplicates.clear()
        return chunk(ChunkType.SACK, 0, w.bytes)
    }

    // ------------------------------------------------------------------ sending

    private fun unwrapOwn(tsn: Long): Long = cumAckTsn + ((tsn - cumAckTsn).toInt()).toLong()

    private fun onSack(c: Chunk, now: Long) {
        if (state != State.ESTABLISHED || c.len < 12) return
        val cum = unwrapOwn(c.u32(0))
        if (cum < cumAckTsn || cum >= nextTsn) return // stale or bogus
        val rwnd = c.u32(4)
        val gapCount = c.u16(8)
        var bytesAcked = 0
        var rttSample = -1L
        val advanced = cum > cumAckTsn
        while (outstanding.isNotEmpty() && outstanding.first().tsn <= cum) {
            val k = outstanding.removeFirst()
            if (unsentIndex > 0) unsentIndex-- // (a chunk acked before ever being sent cannot happen; keeps the index aligned)
            if (k.inFlight) {
                flightBytes -= k.payload.size
                bytesAcked += k.payload.size
                if (k.transmits == 1) rttSample = now - k.sentAt
            }
            if (!k.acked && !k.abandoned) queuedBytes -= k.payload.size
        }
        cumAckTsn = cum
        // Gap blocks: chunks received above the cumulative point.
        var highestGapAcked = cum
        for (i in 0 until gapCount) {
            if (16 + 4 * i > c.len) break
            val start = c.u16(12 + 4 * i)
            val end = c.u16(14 + 4 * i)
            for (off in start..end) {
                val k = chunkAt(cum + off) ?: continue
                if (!k.acked) {
                    k.acked = true
                    k.retransmit = false
                    if (k.inFlight) {
                        k.inFlight = false
                        flightBytes -= k.payload.size
                        bytesAcked += k.payload.size
                        if (k.transmits == 1) rttSample = now - k.sentAt
                    }
                    if (!k.abandoned) queuedBytes -= k.payload.size
                }
                highestGapAcked = maxOf(highestGapAcked, cum + off)
            }
        }
        if (rttSample >= 0) updateRto(rttSample)
        if (advanced) errorCount = 0
        peerRwnd = maxOf(0, rwnd - flightBytes)

        // Fast retransmit (RFC 9260 §7.2.4): three reports of a chunk missing below a gap-acked one.
        var fastRetransmit = false
        if (highestGapAcked > cum) {
            for (k in outstanding) {
                if (k.tsn >= highestGapAcked) break
                if (k.acked || k.abandoned || !k.inFlight) continue
                if (++k.missingReports == 3) {
                    markForRetransmit(k)
                    fastRetransmit = true
                }
            }
        }
        if (fastRecoveryExit >= 0 && cum >= fastRecoveryExit) fastRecoveryExit = -1
        if (fastRetransmit && fastRecoveryExit < 0) {
            ssthresh = maxOf(cwnd / 2, 4 * MTU)
            cwnd = ssthresh
            partialBytesAcked = 0
            fastRecoveryExit = nextTsn - 1
        } else if (advanced && fastRecoveryExit < 0) {
            if (cwnd <= ssthresh) {
                cwnd += minOf(bytesAcked, MTU)
            } else {
                partialBytesAcked += bytesAcked
                if (partialBytesAcked >= cwnd) {
                    partialBytesAcked -= cwnd
                    cwnd += MTU
                }
            }
        }
        if (flightBytes == 0) {
            t3Deadline = Long.MAX_VALUE
            partialBytesAcked = 0
        } else if (advanced) {
            t3Deadline = now + rto
        }
        updateAdvancedAckPoint()
        performDeferredReset()
        if (resetInFlight == null && resetQueue.isNotEmpty()) sendResetRequest(now)
    }

    private fun chunkAt(tsn: Long): OutChunk? {
        if (outstanding.isEmpty()) return null
        val i = tsn - outstanding.first().tsn
        return if (i in 0 until outstanding.size) outstanding[i.toInt()] else null
    }

    private fun markForRetransmit(k: OutChunk) {
        if (k.inFlight) {
            k.inFlight = false
            flightBytes -= k.payload.size
        }
        val m = k.message
        if (m.maxRetransmits >= 0 && k.transmits > m.maxRetransmits) abandon(m) else k.retransmit = true
    }

    private fun abandon(m: OutMessage) {
        if (m.abandoned) return
        m.abandoned = true
        for (k in m.chunks) {
            if (k.inFlight) {
                k.inFlight = false
                flightBytes -= k.payload.size
            }
            if (!k.acked && !k.abandoned) queuedBytes -= k.payload.size
            k.abandoned = true
            k.retransmit = false
        }
        updateAdvancedAckPoint()
    }

    private fun expireMessages(now: Long) {
        for (k in outstanding) {
            val m = k.message
            if (!m.abandoned && now >= m.expiresAt) abandon(m)
        }
    }

    /** RFC 3758 §3.5: move past abandoned chunks at the front and tell the peer with FORWARD-TSN. */
    private fun updateAdvancedAckPoint() {
        if (!peerSupportsForwardTsn) return
        val first = outstanding.firstOrNull() ?: return
        if (first.abandoned) forwardTsnNeeded = true
    }

    private fun forwardTsnChunk(): ByteArray? {
        var point = cumAckTsn
        val ssns = HashMap<Int, Int>()
        for (k in outstanding) {
            if (!(k.abandoned || k.acked)) break
            point = k.tsn
            if (k.abandoned && k.message.ordered) ssns[k.message.stream] = k.message.ssn
        }
        if (point == cumAckTsn) return null
        val w = Writer(4 + 4 * ssns.size).u32(point)
        ssns.forEach { (stream, ssn) -> w.u16(stream).u16(ssn) }
        // Never-sent chunks below the point are skipped for good.
        while (unsentIndex < outstanding.size && outstanding[unsentIndex].tsn <= point) unsentIndex++
        return chunk(ChunkType.FORWARD_TSN, 0, w.bytes)
    }

    private fun onT3Timeout(now: Long) {
        t3Deadline = Long.MAX_VALUE
        val forwardPending = peerSupportsForwardTsn && outstanding.firstOrNull()?.abandoned == true
        if (flightBytes == 0 && !forwardPending) return
        if (++errorCount > MAX_RETRANSMITS) return closed("peer stopped acknowledging")
        rto = minOf(rto * 2, RTO_MAX)
        ssthresh = maxOf(cwnd / 2, 4 * MTU)
        cwnd = MTU
        partialBytesAcked = 0
        fastRecoveryExit = -1
        for (k in outstanding) if (k.inFlight && !k.acked) markForRetransmit(k)
        if (peerSupportsForwardTsn && outstanding.firstOrNull()?.abandoned == true) forwardTsnNeeded = true
        t3Deadline = Long.MAX_VALUE
    }

    private fun updateRto(r: Long) {
        if (srtt < 0) {
            srtt = r.toDouble()
            rttvar = r / 2.0
        } else {
            rttvar = 0.75 * rttvar + 0.25 * kotlin.math.abs(srtt - r)
            srtt = 0.875 * srtt + 0.125 * r
        }
        rto = (srtt + 4 * rttvar).toLong().coerceIn(RTO_MIN, RTO_MAX)
    }

    /** Sends control chunks, a SACK if due, retransmissions and new DATA as the windows allow. */
    private fun flush(now: Long) {
        if (state != State.ESTABLISHED && control.isEmpty()) return
        val chunks = ArrayList<ByteArray>()
        chunks += control
        control.clear()
        if (sackNeeded && state == State.ESTABLISHED) {
            chunks += sackChunk()
            sackNeeded = false
        }
        var forwardSent = false
        if (forwardTsnNeeded) {
            forwardTsnChunk()?.let { chunks += it; forwardSent = true }
            forwardTsnNeeded = false
        }
        if (state == State.ESTABLISHED) {
            // Retransmissions first (they are below everything new), then never-sent chunks.
            for (i in 0 until unsentIndex) {
                val k = outstanding[i]
                if (!k.retransmit) continue
                if (!windowAllows(k)) break
                transmit(k, now, chunks)
            }
            while (unsentIndex < outstanding.size) {
                val k = outstanding[unsentIndex]
                if (k.abandoned || k.acked) { unsentIndex++; continue }
                if (!windowAllows(k)) break
                unsentIndex++
                transmit(k, now, chunks)
            }
        }
        if (chunks.isEmpty()) return
        // Packetize: as many chunks per packet as fit.
        var batch = ArrayList<ByteArray>()
        var size = SctpPacket.HEADER
        for (c in chunks) {
            if (batch.isNotEmpty() && size + c.size > MTU) {
                output(SctpPacket.build(port, port, peerTag, batch))
                batch = ArrayList()
                size = SctpPacket.HEADER
            }
            batch += c
            size += c.size
        }
        output(SctpPacket.build(port, port, peerTag, batch))
        // The timer also covers a FORWARD-TSN: if it is lost, T3 sends it again.
        if ((flightBytes > 0 || forwardSent) && t3Deadline == Long.MAX_VALUE) t3Deadline = now + rto
    }

    private fun windowAllows(k: OutChunk): Boolean =
        flightBytes == 0 || (flightBytes + k.payload.size <= cwnd && k.payload.size <= peerRwnd)

    private fun transmit(k: OutChunk, now: Long, out: MutableList<ByteArray>) {
        k.retransmit = false
        k.transmits++
        k.sentAt = now
        k.inFlight = true
        k.missingReports = 0
        flightBytes += k.payload.size
        peerRwnd = maxOf(0, peerRwnd - k.payload.size)
        val m = k.message
        out += chunk(
            ChunkType.DATA, k.flags,
            Writer(12 + k.payload.size).u32(k.tsn).u16(m.stream).u16(m.ssn).u32(k.ppid.toLong()).put(k.payload).bytes,
        )
    }

    // ------------------------------------------------------------------ stream reset (RFC 6525)

    private var deferredReset: Triple<Long, Long, IntArray>? = null // (request seq, last TSN, streams)

    private fun onReconfig(c: Chunk, now: Long) {
        if (state != State.ESTABLISHED) return
        val responses = ArrayList<ByteArray>()
        forEachParam(c.buf, c.off, c.off + c.len) { type, off, len ->
            when (type) {
                ParamType.OUTGOING_RESET_REQUEST -> if (len >= 12) {
                    val seq = c.buf.u32(off)
                    val lastTsn = unwrapPeer(c.buf.u32(off + 8))
                    val streams = IntArray((len - 12) / 2) { c.buf.u16(off + 12 + 2 * it) }
                    responses += param(ParamType.RECONFIG_RESPONSE, Writer(8).u32(seq).u32(onResetRequest(seq, lastTsn, streams)).bytes)
                }
                ParamType.INCOMING_RESET_REQUEST -> if (len >= 4) {
                    // Asking us to reset our outgoing streams: not used by WebRTC; decline.
                    responses += param(ParamType.RECONFIG_RESPONSE, Writer(8).u32(c.buf.u32(off)).u32(RESULT_DENIED).bytes)
                }
                ParamType.RECONFIG_RESPONSE -> if (len >= 8) onResetResponse(c.buf.u32(off), c.buf.u32(off + 4), now)
            }
        }
        if (responses.isNotEmpty()) {
            val w = Writer(responses.sumOf { it.size })
            responses.forEach { w.put(it) }
            control += chunk(ChunkType.RECONFIG, 0, w.bytes)
        }
    }

    private fun onResetRequest(seq: Long, lastTsn: Long, streams: IntArray): Long {
        val expected = peerNextRequestSeq and 0xFFFFFFFFL
        if (seq == ((expected - 1) and 0xFFFFFFFFL)) return lastPeerResult // a retransmission
        if (seq != expected) return RESULT_BAD_SEQUENCE
        if (lastTsn > peerCumTsn) {
            // Data sent before the reset is still missing: reset once it arrives, report progress meanwhile.
            deferredReset = Triple(seq, lastTsn, streams)
            return RESULT_IN_PROGRESS
        }
        performReset(streams)
        return RESULT_SUCCESS_PERFORMED
    }

    private fun performDeferredReset() {
        val (_, lastTsn, streams) = deferredReset ?: return
        if (lastTsn > peerCumTsn) return
        deferredReset = null
        performReset(streams)
    }

    private fun performReset(streams: IntArray) {
        deferredReset = null
        peerNextRequestSeq++
        lastPeerResult = RESULT_SUCCESS_PERFORMED
        for (s in streams) inStreams.remove(s)?.let { st -> st.waiting.values.forEach { receiveBuffered -= it.second.size } }
        listener.onIncomingStreamsReset(streams)
    }

    private fun sendResetRequest(now: Long) {
        if (resetInFlight != null || resetQueue.isEmpty() || !peerSupportsReconfig) return
        val streams = resetQueue.toIntArray()
        resetQueue.clear()
        resetInFlight = streams
        resetInFlightSeq = nextRequestSeq and 0xFFFFFFFFL
        val w = Writer(12 + 2 * streams.size)
            .u32(resetInFlightSeq).u32((peerNextRequestSeq - 1) and 0xFFFFFFFFL).u32(nextTsn - 1)
        streams.forEach { w.u16(it) }
        control += chunk(ChunkType.RECONFIG, 0, param(ParamType.OUTGOING_RESET_REQUEST, w.bytes))
        resetDeadline = now + rto
    }

    private fun onResetResponse(seq: Long, result: Long, now: Long) {
        val streams = resetInFlight ?: return
        if (seq != resetInFlightSeq) return
        when (result) {
            RESULT_SUCCESS_PERFORMED, RESULT_SUCCESS_NOTHING -> {
                nextRequestSeq++
                resetInFlight = null
                resetDeadline = Long.MAX_VALUE
                streams.forEach { outSsn.remove(it) }
                listener.onOutgoingStreamsReset(streams)
                sendResetRequest(now)
            }
            RESULT_IN_PROGRESS -> resetDeadline = now + rto // ask again later with the same sequence number
            else -> { // denied or an error: give up on these streams
                nextRequestSeq++
                resetInFlight = null
                resetDeadline = Long.MAX_VALUE
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun nonZero32(): Long {
        while (true) {
            val v = random() and 0xFFFFFFFFL
            if (v != 0L) return v
        }
    }

    /** RFC 1982 serial comparison of 16-bit stream sequence numbers: a < b. */
    private fun ssnBefore(a: Int, b: Int): Boolean = a != b && ((b - a) and 0xFFFF) < 0x8000

    internal companion object {
        /** Largest SCTP packet: fits a DTLS record in a 1280-byte IPv6 path with UDP or TCP framing. */
        const val MTU = 1200
        const val MAX_PAYLOAD = MTU - SctpPacket.HEADER - 16
        const val RWND = 1 shl 20
        const val MAX_MESSAGE = 256 * 1024
        const val MAX_STREAMS = 65535
        const val MAX_GAP_BLOCKS = 32
        const val RTO_INITIAL = 1000L
        const val RTO_MIN = 200L
        const val RTO_MAX = 10_000L
        const val MAX_RETRANSMITS = 10
        const val MAX_INIT_RETRANSMITS = 8

        const val RESULT_SUCCESS_NOTHING = 0L
        const val RESULT_SUCCESS_PERFORMED = 1L
        const val RESULT_DENIED = 2L
        const val RESULT_BAD_SEQUENCE = 5L
        const val RESULT_IN_PROGRESS = 6L
    }
}
