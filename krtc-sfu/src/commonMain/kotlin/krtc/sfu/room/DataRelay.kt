package krtc.sfu.room

import krtc.signaling.DataEnvelope
import krtc.sfu.DataMessage
import krtc.sfu.DataPolicy
import krtc.sfu.platform.logger
import krtc.sfu.platform.nowMillis
import krtc.sfu.platform.secureRandomLong
import krtc.sfu.sctp.DataChannel
import krtc.sfu.sctp.DataChannels
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Room messaging over data channels (ADR-0016), on the room's shard thread. Each participant has one SCTP
 * association with the SFU and opens the [DataEnvelope.RELIABLE_LABEL] and [DataEnvelope.LOSSY_LABEL] channels;
 * a message from one participant goes through the [DataPolicy] and then to its recipients' channels of the same
 * kind, re-addressed with the sender's id. Channels with other labels are refused (closed).
 */
internal class DataRelay(private val room: Room, private val policy: DataPolicy) {
    private val log = logger("krtc.sfu.data")
    private val counters get() = room.shard.counters

    /** Starts the SCTP endpoint for [p] after its DTLS handshake; [send] encrypts and sends one DTLS record. */
    fun attach(p: Participant, send: (ByteArray) -> Unit) {
        if (p.data != null) return
        p.data = DataChannels(dtlsServer = true, output = send, listener = listener(p), random = ::secureRandomLong)
    }

    fun detach(p: Participant) {
        p.data?.abort()
        p.data = null
        p.reliableChannel = null
        p.lossyChannel = null
    }

    fun onPacket(p: Participant, packet: ByteArray) {
        p.data?.handle(packet, packet.size, nowMillis())
    }

    fun tick(now: Long) {
        for (p in room.members) p.data?.tick(now)
    }

    /** A message from the host ([krtc.sfu.Sfu.sendData]): no policy, straight to the recipients. */
    fun sendFromHost(message: DataMessage) = deliver(message)

    private fun listener(p: Participant) = object : DataChannels.Listener {
        override fun onOpen(channel: DataChannel) {
            when (channel.label) {
                DataEnvelope.RELIABLE_LABEL -> p.reliableChannel = channel
                DataEnvelope.LOSSY_LABEL -> p.lossyChannel = channel
                else -> {
                    log.debug("$p: refusing data channel '${channel.label}'")
                    p.data?.close(channel, nowMillis())
                }
            }
        }

        override fun onMessage(channel: DataChannel, data: ByteArray, binary: Boolean) {
            val reliable = when (channel) {
                p.reliableChannel -> true
                p.lossyChannel -> false
                else -> return
            }
            counters.dataIn.incrementAndFetch()
            val envelope = if (binary) DataEnvelope.decode(data) else null
            if (envelope == null) {
                counters.dataDropped.incrementAndFetch()
                return
            }
            val message = DataMessage(room.name, p.id, envelope.topic, envelope.payload, reliable, envelope.ids)
            val decided = try {
                policy.filter(message)
            } catch (t: Throwable) {
                log.error("data policy failed", t)
                null
            } ?: return
            deliver(decided)
        }

        override fun onClose(channel: DataChannel) {
            if (p.reliableChannel === channel) p.reliableChannel = null
            if (p.lossyChannel === channel) p.lossyChannel = null
        }

        override fun onDisconnected(reason: String) {
            log.debug("$p: SCTP closed: $reason")
            p.reliableChannel = null
            p.lossyChannel = null
        }
    }

    private fun deliver(message: DataMessage) {
        val bytes = DataEnvelope.encode(listOfNotNull(message.from), message.topic, message.payload)
        val now = nowMillis()
        val recipients = if (message.to.isEmpty()) room.members.filter { it.id != message.from } else room.members.filter { it.id in message.to }
        for (r in recipients) {
            val dc = r.data
            val channel = if (message.reliable) r.reliableChannel else r.lossyChannel ?: r.reliableChannel
            // A recipient that cannot keep up loses messages instead of growing the SFU's memory.
            val limit = if (message.reliable) RELIABLE_QUEUE_LIMIT else LOSSY_QUEUE_LIMIT
            if (dc == null || channel == null || dc.bufferedAmount > limit || !dc.send(channel, bytes, binary = true, now = now)) {
                counters.dataDropped.incrementAndFetch()
                continue
            }
            counters.dataOut.incrementAndFetch()
        }
    }

    private companion object {
        const val RELIABLE_QUEUE_LIMIT = 4L shl 20
        const val LOSSY_QUEUE_LIMIT = 256L shl 10
    }
}
