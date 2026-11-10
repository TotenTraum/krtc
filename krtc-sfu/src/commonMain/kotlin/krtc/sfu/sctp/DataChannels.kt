package krtc.sfu.sctp

import krtc.protocol.u16
import krtc.protocol.u32
import krtc.protocol.u8

/** One WebRTC data channel (RFC 8831) on an SCTP stream. */
internal class DataChannel(
    val id: Int,
    val label: String,
    val protocol: String,
    val ordered: Boolean,
    /** Partial reliability: retransmissions allowed, or -1. */
    val maxRetransmits: Int,
    /** Partial reliability: milliseconds a message may wait for delivery, or -1. */
    val maxLifetimeMillis: Long,
) {
    /** Until the peer acknowledges our DATA_CHANNEL_OPEN, messages go ordered (RFC 8832 §6). */
    internal var acknowledged = true
    var open = false
        internal set
    internal var closing = false

    val reliable: Boolean get() = maxRetransmits < 0 && maxLifetimeMillis < 0

    override fun toString(): String = "DataChannel($id '$label')"
}

/**
 * Data channels over one SCTP association: the Data Channel Establishment Protocol (RFC 8832) and message
 * framing by PPID (RFC 8831 §8). The DTLS server uses odd stream ids for the channels it opens, the client even.
 * Single-threaded, like [SctpAssociation].
 */
internal class DataChannels(
    private val dtlsServer: Boolean,
    output: (ByteArray) -> Unit,
    private val listener: Listener,
    random: () -> Long,
) {
    interface Listener {
        /** The association is up (local channels opened before this are open now too). */
        fun onConnected() {}

        /** A channel opened: by the peer (DCEP), or one of ours once the association is up. */
        fun onOpen(channel: DataChannel)
        fun onMessage(channel: DataChannel, data: ByteArray, binary: Boolean)
        fun onClose(channel: DataChannel)
        fun onDisconnected(reason: String) {}
    }

    private val channels = HashMap<Int, DataChannel>()
    private var nextLocalId = if (dtlsServer) 1 else 0
    private var now = 0L

    private val association: SctpAssociation = SctpAssociation(output, object : SctpAssociation.Listener {
        override fun onEstablished() {
            listener.onConnected()
            for (ch in channels.values.toList()) {
                if (!ch.acknowledged) sendOpen(ch)
                if (!ch.open) {
                    ch.open = true
                    listener.onOpen(ch)
                }
            }
        }

        override fun onMessage(stream: Int, ppid: Int, data: ByteArray) = onSctpMessage(stream, ppid, data)

        override fun onIncomingStreamsReset(streams: IntArray) {
            for (id in streams) {
                val ch = channels[id] ?: continue
                if (!ch.closing) {
                    // The peer closed it: reset our side too (RFC 8831 §6.7), then it is gone.
                    ch.closing = true
                    association.resetStreams(intArrayOf(id), now)
                }
                channels.remove(id)
                ch.open = false
                listener.onClose(ch)
            }
        }

        override fun onOutgoingStreamsReset(streams: IntArray) {}

        override fun onClosed(reason: String) {
            val all = channels.values.toList()
            channels.clear()
            for (ch in all) {
                ch.open = false
                listener.onClose(ch)
            }
            listener.onDisconnected(reason)
        }
    }, random)

    val connected: Boolean get() = association.state == SctpAssociation.State.ESTABLISHED

    /** Bytes accepted by [send] and not yet acknowledged, over all channels. */
    val bufferedAmount: Long get() = association.bufferedAmount

    val all: Collection<DataChannel> get() = channels.values

    /** The client side starts the association; the SFU (DTLS server) waits for the INIT. */
    fun connect(now: Long) {
        this.now = now
        association.connect(now)
    }

    fun handle(packet: ByteArray, len: Int, now: Long) {
        this.now = now
        association.handle(packet, len, now)
    }

    fun tick(now: Long) {
        this.now = now
        association.tick(now)
    }

    /**
     * Opens a channel through DCEP, or with [negotiatedId] as an out-of-band negotiated channel (no DCEP; the
     * peer must create the same one). Null if no stream id is free.
     */
    fun open(
        label: String,
        ordered: Boolean = true,
        maxRetransmits: Int = -1,
        maxLifetimeMillis: Long = -1,
        protocol: String = "",
        negotiatedId: Int? = null,
        now: Long,
    ): DataChannel? {
        this.now = now
        val id = negotiatedId ?: run {
            var candidate = nextLocalId
            while (candidate in channels) candidate += 2
            if (candidate > MAX_STREAM_ID) return null
            nextLocalId = candidate + 2
            candidate
        }
        val ch = DataChannel(id, label, protocol, ordered, maxRetransmits, maxLifetimeMillis)
        ch.acknowledged = negotiatedId != null
        channels[id] = ch
        if (connected) {
            if (!ch.acknowledged) sendOpen(ch)
            ch.open = true
            listener.onOpen(ch)
        }
        return ch
    }

    /** Sends one message. False if the channel is not open or the association refused it (closed, too large). */
    fun send(ch: DataChannel, data: ByteArray, binary: Boolean, now: Long): Boolean {
        this.now = now
        if (!ch.open || ch.closing) return false
        val empty = data.isEmpty()
        val ppid = when {
            binary && empty -> PPID_BINARY_EMPTY
            binary -> PPID_BINARY
            empty -> PPID_STRING_EMPTY
            else -> PPID_STRING
        }
        val ordered = ch.ordered || !ch.acknowledged
        return association.send(ch.id, ppid, if (empty) byteArrayOf(0) else data, ordered, ch.maxRetransmits, ch.maxLifetimeMillis, now)
    }

    /** Closes a channel (stream reset); [Listener.onClose] follows when the peer resets its side. */
    fun close(ch: DataChannel, now: Long) {
        this.now = now
        if (ch.closing || channels[ch.id] !== ch) return
        ch.closing = true
        if (connected) {
            association.resetStreams(intArrayOf(ch.id), now)
        } else {
            channels.remove(ch.id)
            ch.open = false
            listener.onClose(ch)
        }
    }

    /** Ends the association (ABORT) without notifying the listener. */
    fun abort() {
        channels.clear()
        association.abort()
    }

    private fun sendOpen(ch: DataChannel) {
        val label = ch.label.encodeToByteArray()
        val protocol = ch.protocol.encodeToByteArray()
        val type = when {
            ch.maxRetransmits >= 0 -> 0x01
            ch.maxLifetimeMillis >= 0 -> 0x02
            else -> 0x00
        } or (if (ch.ordered) 0 else 0x80)
        val reliability = when {
            ch.maxRetransmits >= 0 -> ch.maxRetransmits.toLong()
            ch.maxLifetimeMillis >= 0 -> ch.maxLifetimeMillis
            else -> 0
        }
        val msg = Writer(12 + label.size + protocol.size)
            .u8(DCEP_OPEN).u8(type).u16(0).u32(reliability).u16(label.size).u16(protocol.size).put(label).put(protocol).bytes
        association.send(ch.id, PPID_DCEP, msg, ordered = true, maxRetransmits = -1, lifetimeMillis = -1, now = now)
    }

    private fun onSctpMessage(stream: Int, ppid: Int, data: ByteArray) {
        if (ppid == PPID_DCEP) return onDcep(stream, data)
        val ch = channels[stream] ?: return
        if (!ch.open) return
        ch.acknowledged = true // RFC 8832 §6: any message from the peer implies the ACK
        when (ppid) {
            PPID_STRING, PPID_STRING_PARTIAL -> listener.onMessage(ch, data, binary = false)
            PPID_BINARY, PPID_BINARY_PARTIAL -> listener.onMessage(ch, data, binary = true)
            PPID_STRING_EMPTY -> listener.onMessage(ch, ByteArray(0), binary = false)
            PPID_BINARY_EMPTY -> listener.onMessage(ch, ByteArray(0), binary = true)
        }
    }

    private fun onDcep(stream: Int, data: ByteArray) {
        if (data.isEmpty()) return
        when (data.u8(0)) {
            DCEP_ACK -> channels[stream]?.acknowledged = true
            DCEP_OPEN -> {
                if (data.size < 12) return
                // The opener's stream id must have the peer's parity (RFC 8832 §6).
                if ((stream % 2 == 1) != !dtlsServer) return
                val type = data.u8(1)
                val reliability = data.u32(4)
                val labelLen = data.u16(8)
                val protocolLen = data.u16(10)
                if (12 + labelLen + protocolLen > data.size) return
                val label = data.decodeToString(12, 12 + labelLen)
                val protocol = data.decodeToString(12 + labelLen, 12 + labelLen + protocolLen)
                val ch = DataChannel(
                    stream, label, protocol,
                    ordered = type and 0x80 == 0,
                    maxRetransmits = if (type and 0x7F == 0x01) reliability.toInt() else -1,
                    maxLifetimeMillis = if (type and 0x7F == 0x02) reliability else -1,
                )
                channels[stream]?.let { old -> if (old.open) listener.onClose(old) }
                channels[stream] = ch
                association.send(stream, PPID_DCEP, byteArrayOf(DCEP_ACK.toByte()), ordered = true, maxRetransmits = -1, lifetimeMillis = -1, now = now)
                ch.open = true
                listener.onOpen(ch)
            }
        }
    }

    internal companion object {
        const val PPID_DCEP = 50
        const val PPID_STRING = 51
        const val PPID_BINARY_PARTIAL = 52
        const val PPID_BINARY = 53
        const val PPID_STRING_PARTIAL = 54
        const val PPID_STRING_EMPTY = 56
        const val PPID_BINARY_EMPTY = 57
        const val DCEP_ACK = 0x02
        const val DCEP_OPEN = 0x03
        const val MAX_STREAM_ID = 65534
    }
}
