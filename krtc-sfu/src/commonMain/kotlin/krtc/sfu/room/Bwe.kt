package krtc.sfu.room

import krtc.protocol.rtp.Rtp
import krtc.protocol.rtp.TransportCc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Extends 16-bit sequence numbers to a monotonic Int (wraps after ~2^31 packets, far beyond a session). */
internal class SeqUnwrapper {
    private var last = -1
    internal fun unwrap(seq16: Int): Int {
        if (last < 0) {
            last = seq16
            return seq16
        }
        val u = last + Rtp.seqDelta(last and 0xFFFF, seq16)
        if (u > last) last = u
        return u
    }
}

/**
 * Uplink (publisher → SFU): remembers when each transport-wide sequence number arrived and turns that into
 * transport-cc feedback, so the publisher's own bandwidth estimator (GCC in libwebrtc) adapts its send rate.
 * Shard thread only.
 */
internal class TwccReceiver {
    private val arrivals = LongArray(SIZE)
    private val seqs = IntArray(SIZE) { -1 }
    private val unwrapper = SeqUnwrapper()
    private var nextBase = -1 // first sequence number (unwrapped) not reported yet
    private var highest = -1
    private var feedbackCount = 0

    /** A media SSRC of the publisher, for the feedback's media-source field. */
    internal var mediaSsrc: Long = 0

    internal fun record(seq16: Int, arrivalMicros: Long) {
        val seq = unwrapper.unwrap(seq16)
        if (nextBase < 0) nextBase = seq
        if (seq < nextBase) return // reported (as lost) already
        if (seq - nextBase >= SIZE / 2) nextBase = seq // a long silence: start over
        if (seq > highest) highest = seq
        val i = seq and (SIZE - 1)
        seqs[i] = seq
        arrivals[i] = arrivalMicros
    }

    /** Feedback for everything since the last call, or null if nothing arrived. */
    internal fun feedback(senderSsrc: Long): ByteArray? {
        if (nextBase < 0 || highest < nextBase) return null
        val count = highest - nextBase + 1
        val times = LongArray(count) { k ->
            val s = nextBase + k
            val i = s and (SIZE - 1)
            if (seqs[i] == s) arrivals[i] else -1
        }
        val built = TransportCc.feedback(senderSsrc, mediaSsrc, nextBase and 0xFFFF, times, count, feedbackCount)
        feedbackCount = (feedbackCount + 1) and 0xFF
        nextBase += built.count
        return built.packet
    }

    private companion object {
        const val SIZE = 4096
    }
}

/**
 * Downlink (SFU → subscriber) send-side bandwidth estimation from the subscriber's transport-cc feedback,
 * a compact take on Google Congestion Control (draft-ietf-rmcat-gcc):
 * - delay-based: inter-group delay variation → trendline slope → over/under-use against an adaptive
 *   threshold, then AIMD on the rate;
 * - loss-based: more than 10 % loss cuts the rate, less than 2 % lets it grow.
 * The estimate is what the SFU may send to this subscriber; it chooses simulcast layers to fit it.
 * Shard thread only.
 */
internal class SendSideBwe(startBps: Long = START_BPS) {
    // Send history by transport-wide sequence number.
    private val histSeq = IntArray(HISTORY) { -1 }
    private val histSendMicros = LongArray(HISTORY)
    private val histSize = IntArray(HISTORY)
    private var nextSeq = 0

    /** Current estimate of the available downlink bandwidth, bits per second. */
    internal var estimateBps: Long = startBps
        private set

    /** Whether the subscriber sends transport-cc feedback at all (older clients may not). */
    internal var active: Boolean = false
        private set

    /** Bitrate the subscriber acknowledged receiving recently, bits per second. */
    internal var ackedBps: Long = 0
        private set

    internal var state: Usage = Usage.NORMAL
        private set

    internal var lossRatio: Double = 0.0
        private set

    internal enum class Usage { UNDER, NORMAL, OVER }

    /** Takes the next transport-wide sequence number for a packet of [bytes] sent now. */
    internal fun onSend(nowMicros: Long, bytes: Int): Int {
        val s = nextSeq
        nextSeq = (nextSeq + 1) and 0xFFFF
        val i = s and (HISTORY - 1)
        histSeq[i] = s
        histSendMicros[i] = nowMicros
        histSize[i] = bytes
        return s
    }

    // Acked bitrate: (arrival µs, bytes) over a sliding window.
    private val ackTimes = LongArray(ACK_SLOTS)
    private val ackBytes = IntArray(ACK_SLOTS)
    private var ackHead = 0
    private var ackCount = 0

    // Delay-based detector.
    private var groupFirstSend = -1L
    private var groupLastSend = 0L
    private var groupLastArrival = 0L
    private var prevGroupLastSend = -1L
    private var prevGroupLastArrival = 0L
    private var accumulatedDelayMs = 0.0
    private var smoothedDelayMs = 0.0
    private val trendX = DoubleArray(TREND_WINDOW)
    private val trendY = DoubleArray(TREND_WINDOW)
    private var trendCount = 0
    private var trendHead = 0
    private var deltas = 0
    private var firstArrivalMs = -1.0
    private var threshold = 12.5
    private var lastThresholdUpdateMs = -1L
    private var overuseStartMs = -1L
    private var previousTrend = 0.0

    // Rate control.
    private var lastUpdateMs = -1L
    private var lastDecreaseMs = -1L

    /** Applies one feedback packet received at [nowMs]. */
    internal fun onFeedback(fb: TransportCc.Feedback, nowMs: Long) {
        active = true
        var total = 0
        var lost = 0
        for (k in 0 until fb.count) {
            val seq = fb.seq(k)
            val i = seq and (HISTORY - 1)
            if (histSeq[i] != seq) continue // too old, or never sent by us
            total++
            val arrival = fb.arrivalMicros[k]
            if (arrival == TransportCc.LOST) {
                lost++
                continue
            }
            addAcked(arrival, histSize[i])
            onPacketDelay(histSendMicros[i], arrival, nowMs)
        }
        if (total > 0) lossRatio = 0.8 * lossRatio + 0.2 * (lost.toDouble() / total)
        updateRate(nowMs)
    }

    private fun addAcked(arrivalMicros: Long, bytes: Int) {
        ackTimes[ackHead] = arrivalMicros
        ackBytes[ackHead] = bytes
        ackHead = (ackHead + 1) % ACK_SLOTS
        if (ackCount < ACK_SLOTS) ackCount++
        var sum = 0L
        var oldest = arrivalMicros
        for (n in 0 until ackCount) {
            val j = (ackHead - 1 - n + ACK_SLOTS) % ACK_SLOTS
            if (arrivalMicros - ackTimes[j] > ACK_WINDOW_MICROS) break
            sum += ackBytes[j]
            oldest = ackTimes[j]
        }
        val span = max(arrivalMicros - oldest, ACK_WINDOW_MICROS / 4)
        ackedBps = sum * 8 * 1_000_000 / span
    }

    /** Groups packets sent within 5 ms (a burst) and feeds inter-group delay variation to the trendline. */
    private fun onPacketDelay(sendMicros: Long, arrivalMicros: Long, nowMs: Long) {
        if (groupFirstSend < 0) {
            groupFirstSend = sendMicros
            groupLastSend = sendMicros
            groupLastArrival = arrivalMicros
            return
        }
        if (sendMicros < groupFirstSend) return // reordered from an earlier group
        if (sendMicros - groupFirstSend <= BURST_MICROS) {
            groupLastSend = max(groupLastSend, sendMicros)
            groupLastArrival = max(groupLastArrival, arrivalMicros)
            return
        }
        // The current group is complete.
        if (prevGroupLastSend >= 0) {
            val sendDelta = (groupLastSend - prevGroupLastSend) / 1000.0
            val arrivalDelta = (groupLastArrival - prevGroupLastArrival) / 1000.0
            onDelayVariation(arrivalDelta - sendDelta, groupLastArrival / 1000.0, nowMs)
        }
        prevGroupLastSend = groupLastSend
        prevGroupLastArrival = groupLastArrival
        groupFirstSend = sendMicros
        groupLastSend = sendMicros
        groupLastArrival = arrivalMicros
    }

    private fun onDelayVariation(variationMs: Double, arrivalMs: Double, nowMs: Long) {
        if (firstArrivalMs < 0) firstArrivalMs = arrivalMs
        deltas = min(deltas + 1, MAX_DELTAS)
        accumulatedDelayMs += variationMs
        smoothedDelayMs = SMOOTHING * smoothedDelayMs + (1 - SMOOTHING) * accumulatedDelayMs
        trendX[trendHead] = arrivalMs - firstArrivalMs
        trendY[trendHead] = smoothedDelayMs
        trendHead = (trendHead + 1) % TREND_WINDOW
        if (trendCount < TREND_WINDOW) trendCount++
        if (trendCount < TREND_WINDOW) return
        val trend = slope() * deltas * TREND_GAIN
        detect(trend, nowMs)
    }

    /** Least-squares slope of delay over arrival time in the window. */
    private fun slope(): Double {
        var mx = 0.0
        var my = 0.0
        for (i in 0 until trendCount) {
            mx += trendX[i]
            my += trendY[i]
        }
        mx /= trendCount
        my /= trendCount
        var num = 0.0
        var den = 0.0
        for (i in 0 until trendCount) {
            num += (trendX[i] - mx) * (trendY[i] - my)
            den += (trendX[i] - mx) * (trendX[i] - mx)
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private fun detect(trend: Double, nowMs: Long) {
        state = when {
            trend > threshold -> {
                if (overuseStartMs < 0) overuseStartMs = nowMs
                if (nowMs - overuseStartMs >= OVERUSE_TIME_MS && trend >= previousTrend) Usage.OVER else state
            }
            trend < -threshold -> {
                overuseStartMs = -1
                Usage.UNDER
            }
            else -> {
                overuseStartMs = -1
                Usage.NORMAL
            }
        }
        previousTrend = trend
        // Adaptive threshold: follows |trend| slowly upwards, faster downwards (GCC §5.4).
        if (lastThresholdUpdateMs < 0) lastThresholdUpdateMs = nowMs
        val m = abs(trend)
        if (m <= threshold + 15) {
            val k = if (m < threshold) 0.039 else 0.0087
            val dt = min(nowMs - lastThresholdUpdateMs, 100L)
            threshold = (threshold + k * (m - threshold) * dt).coerceIn(6.0, 600.0)
        }
        lastThresholdUpdateMs = nowMs
    }

    private fun updateRate(nowMs: Long) {
        val dtMs = if (lastUpdateMs < 0) 0L else min(nowMs - lastUpdateMs, 1000L)
        lastUpdateMs = nowMs
        val canDecrease = lastDecreaseMs < 0 || nowMs - lastDecreaseMs >= DECREASE_INTERVAL_MS
        when {
            state == Usage.OVER && canDecrease -> {
                val base = if (ackedBps > 0) ackedBps else estimateBps
                estimateBps = (BETA * base).toLong()
                lastDecreaseMs = nowMs
            }
            lossRatio > HIGH_LOSS && canDecrease -> {
                estimateBps = (estimateBps * (1 - 0.5 * lossRatio)).toLong()
                lastDecreaseMs = nowMs
            }
            state == Usage.NORMAL && lossRatio < LOW_LOSS -> {
                val grown = (estimateBps * INCREASE_PER_SECOND.pow(dtMs / 1000.0)).toLong()
                // Without probing, never run far ahead of what actually got through.
                val cap = if (ackedBps > 0) (ackedBps * 3 / 2 + HEADROOM_BPS) else MAX_BPS
                estimateBps = max(estimateBps, min(grown, cap))
            }
        }
        estimateBps = estimateBps.coerceIn(MIN_BPS, MAX_BPS)
    }

    internal companion object {
        const val START_BPS = 1_000_000L
        const val MIN_BPS = 100_000L
        const val MAX_BPS = 20_000_000L
        const val HEADROOM_BPS = 1_000_000L
        const val HISTORY = 8192
        const val ACK_SLOTS = 2048
        const val ACK_WINDOW_MICROS = 500_000L
        const val BURST_MICROS = 5_000L
        const val TREND_WINDOW = 20
        const val MAX_DELTAS = 60
        const val TREND_GAIN = 4.0
        const val SMOOTHING = 0.9
        const val OVERUSE_TIME_MS = 10L
        const val DECREASE_INTERVAL_MS = 300L
        const val BETA = 0.85
        const val INCREASE_PER_SECOND = 1.08
        const val HIGH_LOSS = 0.10
        const val LOW_LOSS = 0.02
    }
}
