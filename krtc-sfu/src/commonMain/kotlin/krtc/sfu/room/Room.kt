package krtc.sfu.room

import kotlin.concurrent.atomics.incrementAndFetch
import krtc.protocol.rtp.AudioLevel
import krtc.protocol.rtp.H264
import krtc.protocol.rtp.Rtcp
import krtc.protocol.rtp.Rtp
import krtc.protocol.rtp.RtpHeader
import krtc.protocol.rtp.RtpStreamId
import krtc.protocol.rtp.TransportCc
import krtc.protocol.rtp.Vp8
import krtc.sfu.platform.PeerAddress
import krtc.sfu.platform.Transports
import krtc.sfu.platform.nowMicros
import krtc.sfu.platform.nowMillis
import krtc.sfu.srtp.SrtpSessionKeys
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * A room. Membership changes take a lock and publish an immutable snapshot; the media path reads the
 * snapshot on the room's shard without locking.
 */
internal class Room(
    internal val name: String,
    internal val shard: Shard,
    private val udp: Transports?,
    private val onSpeaking: (Participant, Boolean) -> Unit,
    /** Testing only: drop this percentage of downlink video packets (first transmissions, not RTX). */
    private val simulatedLossPercent: Int = 0,
    /** Testing only: drop this percentage of incoming (publisher → SFU) video packets, not RTX. */
    private val simulatedUplinkLossPercent: Int = 0,
    /** Testing only: each subscriber's downlink carries at most this many bits per second; the rest is dropped. */
    private val simulatedDownlinkBps: Long = 0,
    dataPolicy: krtc.sfu.DataPolicy = krtc.sfu.DataPolicy.Relay,
) {
    /** Data channel messaging between members (shard thread). */
    internal val data: DataRelay = DataRelay(this, dataPolicy)

    private val lock = SynchronizedObject()
    private val lossRandom = kotlin.random.Random(42)

    @Volatile public var members: List<Participant> = emptyList()
        private set

    // Shard-thread scratch state.
    private val header = RtpHeader()
    private val vp8 = Vp8.Descriptor()
    private val scratch = ByteArray(2048)
    private val restored = ByteArray(2048) // RTX payloads rebuilt into original packets
    private val counters get() = shard.counters
    private var lastRemb = 0L
    private var lastTick = -1L

    internal fun add(p: Participant): List<Participant> = synchronized(lock) {
        val before = members
        members = before + p
        before
    }

    internal fun remove(p: Participant): List<Participant> = synchronized(lock) {
        members = members - p
        members
    }

    /**
     * Media hot path, on the shard thread: authenticate and decrypt once, then rewrite and re-encrypt
     * for every subscriber. Cost is O(subscribers) per packet — the classic SFU fan-out.
     * [arrivalMicros] is when the UDP thread received the packet (for transport-cc feedback).
     */
    internal fun forward(from: Participant, data: ByteArray, length: Int, arrivalMicros: Long = nowMicros()) {
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
        val tcc = h.findExtension(data, 0, MediaPlan.TRANSPORT_CC_EXT_ID)
        if (tcc >= 0 && h.lastElementLength >= 2) {
            from.twccIn.record(TransportCc.read(data, tcc), arrivalMicros)
            from.twccIn.mediaSsrc = h.ssrc
        }
        when {
            h.payloadType == MediaPlan.OPUS_PT -> {
                // Microphone or screen audio: by MID while the sender includes it, then by SSRC (answer or learned).
                val mid = RtpStreamId.read(data, h, MediaPlan.MID_EXT_ID)
                val screen = when (mid) {
                    MediaPlan.UPLINK_SCREEN_AUDIO_MID -> true
                    MediaPlan.UPLINK_AUDIO_MID -> false
                    else -> h.ssrc == from.uplinkScreenAudioSsrc || h.ssrc in from.screenAudioSsrcs
                }
                RtpStreamId.rename(data, h, UPLINK_ONLY_EXTENSIONS, MediaPlan.IGNORED_EXT_ID)
                if (screen) forwardScreenAudio(from, data, plainLen, h) else forwardAudio(from, data, plainLen, h)
            }
            MediaPlan.isVideo(h.payloadType) -> {
                if (simulatedUplinkLossPercent > 0 && lossRandom.nextInt(100) < simulatedUplinkLossPercent) return
                forwardVideo(from.feedOf(h.ssrc, RtpStreamId.read(data, h, MediaPlan.MID_EXT_ID)), data, plainLen, h)
            }
            MediaPlan.isRtx(h.payloadType) -> onUpstreamRtx(from, data, plainLen, h)
            else -> Unit
        }
    }

    private fun forwardAudio(from: Participant, data: ByteArray, plainLen: Int, h: RtpHeader) {
        from.uplinkAudioSsrc = h.ssrc
        if (from.audioMuted) return
        trackSpeaking(from, data, h)
        from.audioOut.rewrite(data, h)
        val tcc = h.findExtension(data, 0, MediaPlan.TRANSPORT_CC_EXT_ID)
        val sendMicros = nowMicros()
        val out = shard.out
        for (sub in members) {
            if (sub === from || from.id !in sub.receiving) continue
            val k = sub.keys ?: continue
            val to = sub.remote ?: continue
            stampTransportSeq(sub, data, tcc, plainLen, sendMicros)
            deliver(sub, out, k.rtpOut.protect(data, 0, plainLen, out, 0), to, sendMicros)
        }
    }

    private fun forwardScreenAudio(from: Participant, data: ByteArray, plainLen: Int, h: RtpHeader) {
        from.uplinkScreenAudioSsrc = h.ssrc
        if (from.screenAudioMuted || from.screen.muted) return
        from.screenAudioOut.rewrite(data, h)
        val tcc = h.findExtension(data, 0, MediaPlan.TRANSPORT_CC_EXT_ID)
        val sendMicros = nowMicros()
        val out = shard.out
        for (sub in members) {
            if (sub === from || from.id !in sub.receivingScreenAudio || from.screen.key in sub.paused) continue
            val k = sub.keys ?: continue
            val to = sub.remote ?: continue
            stampTransportSeq(sub, data, tcc, plainLen, sendMicros)
            deliver(sub, out, k.rtpOut.protect(data, 0, plainLen, out, 0), to, sendMicros)
        }
    }

    private fun forwardVideo(feed: VideoFeed, data: ByteArray, plainLen: Int, h: RtpHeader) {
        val from = feed.publisher
        val now = nowMillis()
        val layer = feed.layerOf(h.ssrc, RtpStreamId.read(data, h, MediaPlan.RID_EXT_ID))
        layer.ssrc = h.ssrc
        layer.lastPacketMillis = now
        layer.countBytes(plainLen)
        if (layer.trackArrival(h.sequenceNumber, now)) sendNack(from, layer)
        // Subscribers negotiated their own MID mapping: hide the publisher's MID/RID from them.
        RtpStreamId.rename(data, h, UPLINK_ONLY_EXTENSIONS, MediaPlan.IGNORED_EXT_ID)
        layer.cache.put(h.sequenceNumber, data, plainLen)
        if (feed.muted) return

        val payload = h.headerLength
        val payloadLen = h.payloadLength(data, 0, plainLen)
        val d = if (h.payloadType == MediaPlan.VP8_PT && Vp8.parse(data, payload, payloadLen, vp8)) vp8 else null
        val keyFrame = if (d != null) Vp8.isKeyFrame(data, payload, payloadLen, d) else H264.isKeyFrame(data, payload, payloadLen)
        val tcc = h.findExtension(data, 0, MediaPlan.TRANSPORT_CC_EXT_ID)
        val sendMicros = nowMicros()

        val out = shard.out
        for (sub in members) {
            if (sub === from || feed.key !in sub.receivingVideo || feed.key in sub.paused) continue
            val k = sub.keys ?: continue
            val to = sub.remote ?: continue
            val down = sub.downlinkFrom(feed.key)
            if (down.target < 0) chooseLayer(feed, down, now)
            // Switch layers only at a key frame of the target layer, so the decoder never sees a gap.
            if (down.target != down.current && layer.index == down.target && keyFrame) down.switchTo(layer.index, h, d)
            if (layer.index != down.current) continue
            down.rewrite(data, h, d, payload, feed.ssrc)
            stampTransportSeq(sub, data, tcc, plainLen, sendMicros)
            val n = k.rtpOut.protect(data, 0, plainLen, out, 0)
            if (simulatedLossPercent > 0 && lossRandom.nextInt(100) < simulatedLossPercent) continue
            deliver(sub, out, n, to, sendMicros)
        }
    }

    /** Picks the best active layer within the subscriber's cap (preference and bandwidth) and asks for a key frame on it. */
    private fun chooseLayer(feed: VideoFeed, down: DownlinkVideo, now: Long) {
        val active = feed.layers.filter { it.isActive(now) }
        val chosen = active.lastOrNull { it.index <= down.cap } ?: active.firstOrNull() ?: return
        down.target = chosen.index
        if (down.target != down.current) requestKeyframe(feed.publisher, chosen)
    }

    /** Sets which simulcast layer [subscriber] wants from a feed ([VideoFeed.key]: publisher id = camera) (0 = lowest). */
    internal fun setPreferredLayer(subscriber: Participant, feedKey: String, layer: Int) {
        val down = subscriber.downlinkFrom(feedKey)
        down.preferred = layer.coerceIn(0, MediaPlan.TOP_LAYER)
        down.target = -1 // re-evaluated on the next packet
    }

    /** Stops or resumes forwarding a feed ([VideoFeed.key]) to [subscriber]; it resumes at a key frame. */
    internal fun setPaused(subscriber: Participant, feedKey: String, paused: Boolean) {
        if (paused) {
            if (subscriber.paused.add(feedKey)) subscriber.downlinks[feedKey]?.restart()
        } else {
            subscriber.paused.remove(feedKey)
        }
    }

    /** Gives a packet to [sub] its own transport-wide sequence number (and remembers when it went out). */
    private fun stampTransportSeq(sub: Participant, buf: ByteArray, extAt: Int, length: Int, sendMicros: Long) {
        if (extAt >= 0) TransportCc.write(buf, extAt, sub.bwe.onSend(sendMicros, length))
    }

    /** Sends media to a subscriber, through the simulated downlink when testing with one. */
    private fun deliver(sub: Participant, buf: ByteArray, n: Int, to: PeerAddress, nowMicros: Long) {
        if (simulatedDownlinkBps > 0) {
            val bytesPerMicro = simulatedDownlinkBps / 8e6
            val burst = simulatedDownlinkBps / 8 * 0.3 // 300 ms worth of buffer
            if (sub.shapingLastMicros >= 0) sub.shapingTokens = minOf(burst, sub.shapingTokens + (nowMicros - sub.shapingLastMicros) * bytesPerMicro)
            else sub.shapingTokens = burst
            sub.shapingLastMicros = nowMicros
            if (sub.shapingTokens < n) return
            sub.shapingTokens -= n
        }
        send(buf, n, to)
    }

    private fun send(buf: ByteArray, n: Int, to: PeerAddress) {
        udp?.send(shard.index, buf, n, to)
        counters.rtpOut.incrementAndFetch()
        counters.bytesOut.addAndFetch(n.toLong())
    }

    // ------------------------------------------------------------------ upstream loss recovery

    /** Asks the publisher to retransmit what is missing on [layer] (RFC 4585 generic NACK; it answers with RTX). */
    private fun sendNack(publisher: Participant, layer: VideoLayer) {
        val seqs = layer.missingToRequest(nowMillis())
        if (seqs.isEmpty() || layer.ssrc < 0) return
        sendRtcp(publisher, Rtcp.concat(Rtcp.receiverReport(MediaPlan.SFU_SSRC), Rtcp.nack(MediaPlan.SFU_SSRC, layer.ssrc, seqs)))
        counters.nacksSent.incrementAndFetch()
    }

    /**
     * A publisher's retransmission (RFC 4588): rebuilds the original packet (payload type from `apt`,
     * sequence number from the OSN, SSRC of the layer named by repaired-RID) and forwards it as if it
     * had arrived in time. Padding-only RTX (bandwidth probing) is dropped.
     */
    private fun onUpstreamRtx(from: Participant, data: ByteArray, plainLen: Int, h: RtpHeader) {
        val payload = h.headerLength
        val payloadLen = h.payloadLength(data, 0, plainLen)
        if (payloadLen < 2) return
        val feed = from.feedOf(h.ssrc, RtpStreamId.read(data, h, MediaPlan.MID_EXT_ID))
        val layer = feed.layerOfRtx(h.ssrc, RtpStreamId.read(data, h, MediaPlan.REPAIRED_RID_EXT_ID)) ?: return
        if (layer.ssrc < 0) return
        val osn = ((data[payload].toInt() and 0xFF) shl 8) or (data[payload + 1].toInt() and 0xFF)
        if (!layer.isMissing(osn)) return // already recovered (or never lost): don't forward twice
        val out = restored
        data.copyInto(out, 0, 0, payload)
        out[0] = (out[0].toInt() and 0xDF).toByte() // the restored packet carries no RTX padding
        out[1] = ((data[1].toInt() and 0x80) or MediaPlan.aptFor(h.payloadType)).toByte()
        Rtp.setSequenceNumber(out, 0, osn)
        Rtp.setSsrc(out, 0, layer.ssrc)
        data.copyInto(out, payload, payload + 2, payload + payloadLen)
        val len = payload + payloadLen - 2
        if (!header.parse(out, 0, len)) return
        counters.rtxRecovered.incrementAndFetch()
        forwardVideo(feed, out, len, header)
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
                type == Rtcp.RTPFB && fmt == TransportCc.FMT ->
                    TransportCc.parse(data, at, size)?.let { from.bwe.onFeedback(it, nowMillis()) }
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

    private fun publisherOfVideo(downlinkSsrc: Long): VideoFeed? {
        for (m in members) for (f in m.feeds) if (f.ssrc == downlinkSsrc) return f
        return null
    }

    private fun feedByKey(snapshot: List<Participant>, key: String): VideoFeed? {
        for (m in snapshot) for (f in m.feeds) if (f.key == key) return f
        return null
    }

    private fun keyframeForSubscriber(feed: VideoFeed, subscriber: Participant) {
        val current = subscriber.downlinks[feed.key]?.current ?: return
        if (current >= 0) requestKeyframe(feed.publisher, feed.layers[current])
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
        if (ssrc == from.uplinkScreenAudioSsrc) {
            val sr = Rtcp.rewriteSenderReport(data, at, from.screenAudioOut.ssrc, from.screenAudioOut.mapTimestamp(srTs))
            for (sub in members) if (sub !== from && from.id in sub.receivingScreenAudio) sendRtcp(sub, sr)
            return
        }
        val (feed, layer) = from.layerBySsrc(ssrc) ?: return
        for (sub in members) {
            if (sub === from || feed.key !in sub.receivingVideo) continue
            val down = sub.downlinks[feed.key] ?: continue
            if (down.current != layer.index) continue // each subscriber's timeline follows its own layer
            sendRtcp(sub, Rtcp.rewriteSenderReport(data, at, feed.ssrc, (srTs + down.tsOffset) and 0xFFFFFFFFL))
        }
    }

    /** Answers a subscriber's NACK with RTX packets (RFC 4588) rebuilt from the publisher's layer cache. */
    private fun retransmit(feed: VideoFeed, subscriber: Participant, seqs: List<Int>) {
        val k = subscriber.keys ?: return
        val to = subscriber.remote ?: return
        val down = subscriber.downlinks[feed.key] ?: return
        val rtx = scratch
        for (outSeq in seqs) {
            val source = down.source(outSeq) ?: continue
            val layer = feed.layers[source ushr 16]
            val srcSeq = source and 0xFFFF
            val pkt = layer.cache.packet(srcSeq) ?: continue
            val len = layer.cache.length(srcSeq)
            if (!header.parse(pkt, 0, len)) continue
            val hl = header.headerLength
            pkt.copyInto(rtx, 0, 0, hl)
            rtx[1] = ((pkt[1].toInt() and 0x80) or MediaPlan.rtxFor(header.payloadType)).toByte()
            feed.rtxSeq = (feed.rtxSeq + 1) and 0xFFFF
            Rtp.setSequenceNumber(rtx, 0, feed.rtxSeq)
            Rtp.setSsrc(rtx, 0, feed.rtxSsrc)
            Rtp.setTimestamp(rtx, 0, (header.timestamp + down.sentTsOffset(outSeq)) and 0xFFFFFFFFL)
            rtx[hl] = (outSeq ushr 8).toByte() // original sequence number, as the subscriber saw it
            rtx[hl + 1] = outSeq.toByte()
            pkt.copyInto(rtx, hl + 2, hl, len)
            val tcc = header.findExtension(pkt, 0, MediaPlan.TRANSPORT_CC_EXT_ID)
            if (header.payloadType == MediaPlan.VP8_PT && Vp8.parse(rtx, hl + 2, len - hl, vp8)) {
                if (vp8.pictureId >= 0) {
                    val mask = if (vp8.pictureIdBits == 15) 0x7FFF else 0x7F
                    Vp8.setPictureId(rtx, hl + 2, vp8, (vp8.pictureId + down.sentPicIdOffset(outSeq)) and mask)
                }
                if (vp8.tl0PicIdx >= 0) Vp8.setTl0PicIdx(rtx, hl + 2, vp8, (vp8.tl0PicIdx + down.sentTl0Offset(outSeq)) and 0xFF)
            }
            val sendMicros = nowMicros()
            stampTransportSeq(subscriber, rtx, tcc, len + 2, sendMicros)
            deliver(subscriber, shard.out, k.rtpOut.protect(rtx, 0, len + 2, shard.out, 0), to, sendMicros)
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
        val elapsed = if (lastTick < 0) 0L else now - lastTick
        lastTick = now
        val snapshot = members
        for (p in snapshot) for (f in p.feeds) for (layer in f.layers) layer.updateBitrate(elapsed)
        for (p in snapshot) {
            if (p.speaking && now - p.lastLoudMillis > MediaPlan.SPEAKING_HOLD_MILLIS) {
                p.speaking = false
                onSpeaking(p, false)
            }
            // Re-ask for packets still missing (each sequence number at most a few times).
            for (f in p.feeds) for (layer in f.layers) if (layer.hasMissing()) sendNack(p, layer)
            val activeSsrcs = p.feeds.flatMap { f -> f.layers.filter { it.isActive(now) }.map { it.ssrc } }
            if (remb && activeSsrcs.isNotEmpty()) {
                sendRtcp(p, Rtcp.concat(Rtcp.receiverReport(MediaPlan.SFU_SSRC), Rtcp.remb(MediaPlan.SFU_SSRC, MediaPlan.VIDEO_BITRATE, activeSsrcs)))
            }
            // Downlinks: drop state for publishers who left; re-choose layers that went quiet or can improve.
            val it = p.downlinks.entries.iterator()
            while (it.hasNext()) {
                val (feedKey, down) = it.next()
                val feed = feedByKey(snapshot, feedKey)
                if (feed == null) {
                    it.remove()
                    continue
                }
                val best = feed.layers.filter { l -> l.isActive(now) }.lastOrNull { l -> l.index <= down.cap }?.index ?: -1
                val currentActive = down.current >= 0 && feed.layers[down.current].isActive(now)
                if (!currentActive || (best > down.current && down.target != best)) down.target = -1
            }
            allocate(p, snapshot, now)
        }
    }

    /** Sends transport-cc feedback to every publisher for the packets that arrived since the last call. */
    internal fun sendTransportFeedback() {
        for (p in members) p.twccIn.feedback(MediaPlan.SFU_SSRC)?.let { sendRtcp(p, it) }
    }

    /**
     * Fits [sub]'s video downlinks into its estimated bandwidth: drops the most expensive stream a layer
     * while over budget, then upgrades streams while everything still fits with headroom. Changes take
     * effect at the next key frame of the new layer. Subscribers without transport-cc feedback are not limited.
     */
    private fun allocate(sub: Participant, snapshot: List<Participant>, now: Long) {
        if (!sub.bwe.active) return
        val pubs = ArrayList<VideoFeed>()
        val downs = ArrayList<DownlinkVideo>()
        for ((feedKey, down) in sub.downlinks) {
            if (feedKey !in sub.receivingVideo || feedKey in sub.paused) continue
            pubs += feedByKey(snapshot, feedKey) ?: continue
            downs += down
        }
        if (downs.isEmpty()) return
        val audioStreams = snapshot.count { it !== sub && it.id in sub.receiving } + snapshot.count { it !== sub && it.id in sub.receivingScreenAudio }
        val budget = sub.bwe.estimateBps - audioStreams * MediaPlan.AUDIO_RESERVE_BPS
        val active = Array(downs.size) { i -> pubs[i].layers.filter { it.isActive(now) && it.index <= downs[i].preferred }.map { it.index } }
        val choice = IntArray(downs.size) { i ->
            val a = active[i]
            if (a.isEmpty()) -1 else a.lastOrNull { it <= downs[i].allowed } ?: a.first()
        }
        fun cost(i: Int, layer: Int): Long = if (layer < 0) 0 else pubs[i].layers[layer].bitrateBps
        fun total(): Long = choice.indices.sumOf { cost(it, choice[it]) }

        while (total() > budget) {
            var worst = -1
            for (i in choice.indices) {
                if (choice[i] < 0 || active[i].first() >= choice[i]) continue
                if (worst < 0 || cost(i, choice[i]) > cost(worst, choice[worst])) worst = i
            }
            if (worst < 0) break
            choice[worst] = active[worst].last { it < choice[worst] }
        }
        var upgraded = true
        while (upgraded) {
            upgraded = false
            for (i in choice.indices) {
                if (choice[i] < 0) continue
                val next = active[i].firstOrNull { it > choice[i] } ?: continue
                if (total() - cost(i, choice[i]) + cost(i, next) <= budget * MediaPlan.UPGRADE_HEADROOM) {
                    choice[i] = next
                    upgraded = true
                }
            }
        }
        for (i in downs.indices) {
            if (choice[i] < 0) continue
            val allowed = if (choice[i] == active[i].last()) MediaPlan.TOP_LAYER else choice[i]
            if (downs[i].allowed != allowed) {
                downs[i].allowed = allowed
                downs[i].target = -1 // re-chosen on the next packet, switching at a key frame
            }
        }
    }

    private companion object {
        val UPLINK_ONLY_EXTENSIONS = intArrayOf(MediaPlan.MID_EXT_ID, MediaPlan.RID_EXT_ID, MediaPlan.REPAIRED_RID_EXT_ID)
    }
}
