package krtc.sfu.room

import kotlin.concurrent.atomics.incrementAndFetch
import krtc.protocol.rtp.AudioLevel
import krtc.protocol.rtp.H264
import krtc.protocol.rtp.Rtcp
import krtc.protocol.rtp.Rtp
import krtc.protocol.rtp.RtpHeader
import krtc.protocol.rtp.RtpStreamId
import krtc.protocol.rtp.Vp8
import krtc.sfu.platform.PeerAddress
import krtc.sfu.platform.UdpServer
import krtc.sfu.platform.nowMillis
import krtc.sfu.srtp.SrtpSessionKeys
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * A room. Membership changes take a lock and publish an immutable snapshot; the media path reads the
 * snapshot on the room's shard without locking.
 */
public class Room(
    public val name: String,
    public val shard: Shard,
    private val udp: UdpServer?,
    private val onSpeaking: (Participant, Boolean) -> Unit,
    /** Testing only: drop this percentage of downlink video packets (first transmissions, not RTX). */
    private val simulatedLossPercent: Int = 0,
) {
    private val lock = SynchronizedObject()
    private val lossRandom = kotlin.random.Random(42)

    @Volatile public var members: List<Participant> = emptyList()
        private set

    // Shard-thread scratch state.
    private val header = RtpHeader()
    private val vp8 = Vp8.Descriptor()
    private val scratch = ByteArray(2048)
    private val counters get() = shard.counters
    private var lastRemb = 0L

    public fun add(p: Participant): List<Participant> = synchronized(lock) {
        val before = members
        members = before + p
        before
    }

    public fun remove(p: Participant): List<Participant> = synchronized(lock) {
        members = members - p
        members
    }

    /**
     * Media hot path, on the shard thread: authenticate and decrypt once, then rewrite and re-encrypt
     * for every subscriber. Cost is O(subscribers) per packet — the classic SFU fan-out.
     */
    public fun forward(from: Participant, data: ByteArray, length: Int) {
        val keys = from.keys ?: return
        if (Rtp.isRtcp(data, 0, length)) {
            onRtcp(from, keys, data, length)
            return
        }
        val plainLen = keys.rtpIn.unprotect(data, 0, length)
        if (plainLen < 0) {
            counters.srtpFailures.incrementAndFetch()
            return
        }
        val h = header
        if (!h.parse(data, 0, plainLen)) return
        counters.rtpIn.incrementAndFetch()
        counters.bytesIn.addAndFetch(length.toLong())
        when {
            h.payloadType == MediaPlan.OPUS_PT -> forwardAudio(from, data, plainLen, h)
            MediaPlan.isVideo(h.payloadType) -> forwardVideo(from, data, plainLen, h)
            // Upstream RTX only answers NACKs the SFU sends; it sends none, so drop it.
            else -> Unit
        }
    }

    private fun forwardAudio(from: Participant, data: ByteArray, plainLen: Int, h: RtpHeader) {
        from.uplinkAudioSsrc = h.ssrc
        trackSpeaking(from, data, h)
        from.audioOut.rewrite(data, h)
        val out = shard.out
        for (sub in members) {
            if (sub === from || from.id !in sub.receiving) continue
            val k = sub.keys ?: continue
            val to = sub.remote ?: continue
            send(out, k.rtpOut.protect(data, 0, plainLen, out, 0), to)
        }
    }

    private fun forwardVideo(from: Participant, data: ByteArray, plainLen: Int, h: RtpHeader) {
        val now = nowMillis()
        val layer = from.layerOf(h.ssrc, RtpStreamId.read(data, h, MediaPlan.RID_EXT_ID))
        layer.ssrc = h.ssrc
        layer.lastPacketMillis = now
        // Subscribers negotiated their own MID mapping: hide the publisher's MID/RID from them.
        RtpStreamId.rename(data, h, UPLINK_ONLY_EXTENSIONS, MediaPlan.IGNORED_EXT_ID)
        layer.cache.put(h.sequenceNumber, data, plainLen)

        val payload = h.headerLength
        val payloadLen = h.payloadLength(data, 0, plainLen)
        val d = if (h.payloadType == MediaPlan.VP8_PT && Vp8.parse(data, payload, payloadLen, vp8)) vp8 else null
        val keyFrame = if (d != null) Vp8.isKeyFrame(data, payload, payloadLen, d) else H264.isKeyFrame(data, payload, payloadLen)

        val out = shard.out
        for (sub in members) {
            if (sub === from || from.id !in sub.receivingVideo) continue
            val k = sub.keys ?: continue
            val to = sub.remote ?: continue
            val down = sub.downlinkFrom(from.id)
            if (down.target < 0) chooseLayer(from, down, now)
            // Switch layers only at a key frame of the target layer, so the decoder never sees a gap.
            if (down.target != down.current && layer.index == down.target && keyFrame) down.switchTo(layer.index, h, d)
            if (layer.index != down.current) continue
            down.rewrite(data, h, d, payload, from.videoSsrc)
            val n = k.rtpOut.protect(data, 0, plainLen, out, 0)
            if (simulatedLossPercent > 0 && lossRandom.nextInt(100) < simulatedLossPercent) continue
            send(out, n, to)
        }
    }

    /** Picks the best active layer at or below the subscriber's preference and asks for a key frame on it. */
    private fun chooseLayer(publisher: Participant, down: DownlinkVideo, now: Long) {
        val active = publisher.layers.filter { it.isActive(now) }
        val chosen = active.lastOrNull { it.index <= down.preferred } ?: active.firstOrNull() ?: return
        down.target = chosen.index
        if (down.target != down.current) requestKeyframe(publisher, chosen)
    }

    /** Sets which simulcast layer [subscriber] wants from [publisherId] (0 = lowest). */
    public fun setPreferredLayer(subscriber: Participant, publisherId: String, layer: Int) {
        val down = subscriber.downlinkFrom(publisherId)
        down.preferred = layer.coerceIn(0, MediaPlan.TOP_LAYER)
        down.target = -1 // re-evaluated on the next packet
    }

    private fun send(buf: ByteArray, n: Int, to: PeerAddress) {
        udp?.send(shard.index, buf, n, to)
        counters.rtpOut.incrementAndFetch()
        counters.bytesOut.addAndFetch(n.toLong())
    }

    // ------------------------------------------------------------------ RTCP

    private fun onRtcp(from: Participant, keys: SrtpSessionKeys, data: ByteArray, length: Int) {
        val n = keys.rtcpIn.unprotect(data, 0, length)
        if (n < 0) {
            counters.srtpFailures.incrementAndFetch()
            return
        }
        counters.rtcpIn.incrementAndFetch()
        Rtcp.forEach(data, 0, n) { type, fmt, at, size ->
            when {
                type == Rtcp.SR -> forwardSenderReport(from, data, at)
                type == Rtcp.RTPFB && fmt == Rtcp.FMT_NACK ->
                    publisherOfVideo(Rtcp.mediaSsrc(data, at))?.let { retransmit(it, from, Rtcp.nackSequences(data, at, size)) }
                type == Rtcp.PSFB && fmt == Rtcp.FMT_PLI ->
                    publisherOfVideo(Rtcp.mediaSsrc(data, at))?.let { keyframeForSubscriber(it, from) }
                type == Rtcp.PSFB && fmt == Rtcp.FMT_FIR ->
                    Rtcp.firTargets(data, at, size).forEach { ssrc -> publisherOfVideo(ssrc)?.let { keyframeForSubscriber(it, from) } }
                // RR, SDES, BYE and subscriber REMB are terminated here.
            }
        }
    }

    private fun publisherOfVideo(downlinkSsrc: Long): Participant? = members.firstOrNull { it.videoSsrc == downlinkSsrc }

    private fun keyframeForSubscriber(publisher: Participant, subscriber: Participant) {
        val current = subscriber.downlinks[publisher.id]?.current ?: return
        if (current >= 0) requestKeyframe(publisher, publisher.layers[current])
    }

    /** Forwards a publisher's SR under the downlink SSRC so subscribers can synchronize audio and video. */
    private fun forwardSenderReport(from: Participant, data: ByteArray, at: Int) {
        val ssrc = Rtcp.senderSsrc(data, at)
        val srTs = Rtcp.senderReportRtpTimestamp(data, at)
        if (ssrc == from.uplinkAudioSsrc) {
            val sr = Rtcp.rewriteSenderReport(data, at, from.audioOut.ssrc, from.audioOut.mapTimestamp(srTs))
            for (sub in members) if (sub !== from && from.id in sub.receiving) sendRtcp(sub, sr)
            return
        }
        val layer = from.layerBySsrc(ssrc) ?: return
        for (sub in members) {
            if (sub === from || from.id !in sub.receivingVideo) continue
            val down = sub.downlinks[from.id] ?: continue
            if (down.current != layer.index) continue // each subscriber's timeline follows its own layer
            sendRtcp(sub, Rtcp.rewriteSenderReport(data, at, from.videoSsrc, (srTs + down.tsOffset) and 0xFFFFFFFFL))
        }
    }

    /** Answers a subscriber's NACK with RTX packets (RFC 4588) rebuilt from the publisher's layer cache. */
    private fun retransmit(publisher: Participant, subscriber: Participant, seqs: List<Int>) {
        val k = subscriber.keys ?: return
        val to = subscriber.remote ?: return
        val down = subscriber.downlinks[publisher.id] ?: return
        val rtx = scratch
        for (outSeq in seqs) {
            val source = down.source(outSeq) ?: continue
            val layer = publisher.layers[source ushr 16]
            val srcSeq = source and 0xFFFF
            val pkt = layer.cache.packet(srcSeq) ?: continue
            val len = layer.cache.length(srcSeq)
            if (!header.parse(pkt, 0, len)) continue
            val hl = header.headerLength
            pkt.copyInto(rtx, 0, 0, hl)
            rtx[1] = ((pkt[1].toInt() and 0x80) or MediaPlan.rtxFor(header.payloadType)).toByte()
            publisher.rtxSeq = (publisher.rtxSeq + 1) and 0xFFFF
            Rtp.setSequenceNumber(rtx, 0, publisher.rtxSeq)
            Rtp.setSsrc(rtx, 0, publisher.videoRtxSsrc)
            Rtp.setTimestamp(rtx, 0, (header.timestamp + down.sentTsOffset(outSeq)) and 0xFFFFFFFFL)
            rtx[hl] = (outSeq ushr 8).toByte() // original sequence number, as the subscriber saw it
            rtx[hl + 1] = outSeq.toByte()
            pkt.copyInto(rtx, hl + 2, hl, len)
            if (header.payloadType == MediaPlan.VP8_PT && Vp8.parse(rtx, hl + 2, len - hl, vp8)) {
                if (vp8.pictureId >= 0) {
                    val mask = if (vp8.pictureIdBits == 15) 0x7FFF else 0x7F
                    Vp8.setPictureId(rtx, hl + 2, vp8, (vp8.pictureId + down.sentPicIdOffset(outSeq)) and mask)
                }
                if (vp8.tl0PicIdx >= 0) Vp8.setTl0PicIdx(rtx, hl + 2, vp8, (vp8.tl0PicIdx + down.sentTl0Offset(outSeq)) and 0xFF)
            }
            send(shard.out, k.rtpOut.protect(rtx, 0, len + 2, shard.out, 0), to)
            counters.nacksAnswered.incrementAndFetch()
        }
    }

    /** Asks [publisher] for a key frame on [layer] (PLI), at most once per KEYFRAME_MIN_INTERVAL per layer. */
    private fun requestKeyframe(publisher: Participant, layer: VideoLayer) {
        if (layer.ssrc < 0) return
        val now = nowMillis()
        if (layer.lastKeyframeRequest >= 0 && now - layer.lastKeyframeRequest < MediaPlan.KEYFRAME_MIN_INTERVAL_MILLIS) return
        layer.lastKeyframeRequest = now
        sendRtcp(publisher, Rtcp.concat(Rtcp.receiverReport(MediaPlan.SFU_SSRC), Rtcp.pli(MediaPlan.SFU_SSRC, layer.ssrc)))
        counters.keyframeRequests.incrementAndFetch()
    }

    private fun sendRtcp(to: Participant, rtcp: ByteArray) {
        val k = to.keys ?: return
        val addr = to.remote ?: return
        val n = k.rtcpOut.protect(rtcp, 0, rtcp.size, scratch, 0)
        udp?.send(shard.index, scratch, n, addr)
        counters.rtcpOut.incrementAndFetch()
    }

    private fun trackSpeaking(p: Participant, data: ByteArray, h: RtpHeader) {
        val at = h.findExtension(data, 0, MediaPlan.AUDIO_LEVEL_EXT_ID)
        if (at < 0) return
        val level = AudioLevel.read(data, at)
        if (level.level <= MediaPlan.SPEAKING_LEVEL) {
            p.lastLoudMillis = nowMillis()
            if (!p.speaking) {
                p.speaking = true
                onSpeaking(p, true)
            }
        }
    }

    /**
     * Periodic housekeeping on the shard: speaking timeouts, bandwidth hints to video publishers, and
     * simulcast layer re-selection when a layer stops (or a better one resumes).
     */
    internal fun tick(now: Long) {
        val remb = now - lastRemb >= MediaPlan.REMB_INTERVAL_MILLIS
        if (remb) lastRemb = now
        val snapshot = members
        for (p in snapshot) {
            if (p.speaking && now - p.lastLoudMillis > MediaPlan.SPEAKING_HOLD_MILLIS) {
                p.speaking = false
                onSpeaking(p, false)
            }
            val activeSsrcs = p.layers.filter { it.isActive(now) }.map { it.ssrc }
            if (remb && activeSsrcs.isNotEmpty()) {
                sendRtcp(p, Rtcp.concat(Rtcp.receiverReport(MediaPlan.SFU_SSRC), Rtcp.remb(MediaPlan.SFU_SSRC, MediaPlan.VIDEO_BITRATE, activeSsrcs)))
            }
            // Downlinks: drop state for publishers who left; re-choose layers that went quiet or can improve.
            val it = p.downlinks.entries.iterator()
            while (it.hasNext()) {
                val (publisherId, down) = it.next()
                val publisher = snapshot.firstOrNull { m -> m.id == publisherId }
                if (publisher == null) {
                    it.remove()
                    continue
                }
                val best = publisher.layers.filter { l -> l.isActive(now) }.lastOrNull { l -> l.index <= down.preferred }?.index ?: -1
                val currentActive = down.current >= 0 && publisher.layers[down.current].isActive(now)
                if (!currentActive || (best > down.current && down.target != best)) down.target = -1
            }
        }
    }

    private companion object {
        val UPLINK_ONLY_EXTENSIONS = intArrayOf(MediaPlan.MID_EXT_ID, MediaPlan.RID_EXT_ID, MediaPlan.REPAIRED_RID_EXT_ID)
    }
}
