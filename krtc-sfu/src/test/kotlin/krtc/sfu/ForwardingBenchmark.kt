package krtc.sfu

import krtc.sfu.room.MediaPlan
import krtc.sfu.room.Participant
import krtc.sfu.room.Room
import krtc.sfu.room.Shard
import krtc.sfu.room.SignalSink
import krtc.sfu.srtp.SrtpContext
import krtc.sfu.srtp.SrtpProfile
import krtc.sfu.transport.UdpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.LongAdder
import kotlin.concurrent.thread

/**
 * Forwarding throughput of the media hot path: SRTP unprotect once, then rewrite + protect + sendto
 * per listener — exactly Room.forward, driven directly (no browsers, no DTLS).
 *
 * Opus at 20 ms frames is 50 packets/s per speaker, so "listener streams per core" =
 * output packets per second per core / 50.
 */
private object NoSignal : SignalSink {
    override fun send(message: String) {}
    override fun close() {}
}

private class Setup(val profile: SrtpProfile, listeners: Int, val udp: UdpServer?, sink: InetSocketAddress, shard: Shard, metrics: Metrics) {
    val room = Room("bench", shard, udp, metrics) { _, _ -> }
    val speaker = Participant("speaker", "speaker", room, NoSignal)
    private val masterKey = ByteArray(profile.keyLength) { it.toByte() }
    private val masterSalt = ByteArray(profile.saltLength) { (it + 1).toByte() }
    private val clientTx = SrtpContext.fromMaster(profile, masterKey, masterSalt)
    private var seq = 0

    /** Pre-encrypted packets are replayed in cycles; a fresh receive context per cycle keeps replay protection honest. */
    fun resetInbound() {
        speaker.srtpIn = SrtpContext.fromMaster(profile, masterKey, masterSalt)
    }

    init {
        resetInbound()
        room.add(speaker)
        repeat(listeners) { i ->
            val l = Participant("l$i", "l$i", room, NoSignal)
            l.srtpOut = SrtpContext.fromMaster(profile, ByteArray(profile.keyLength) { (i + it).toByte() }, masterSalt)
            l.remote = sink
            l.receiving = setOf(speaker.id)
            room.add(l)
        }
    }

    // A typical Opus packet: 12-byte header + audio-level extension + ~80 bytes of payload (~32 kbit/s).
    private val plain = ByteArray(12 + 8 + 80).also { p ->
        p[0] = 0x90.toByte(); p[1] = MediaPlan.OPUS_PT.toByte()
        p[8] = 0x12; p[9] = 0x34; p[10] = 0x56; p[11] = 0x78
        p[12] = 0xBE.toByte(); p[13] = 0xDE.toByte(); p[14] = 0; p[15] = 1
        p[16] = 0x10; p[17] = 0x85.toByte()
    }

    fun nextPacket(): Pair<ByteArray, Int> {
        seq = (seq + 1) and 0xFFFF
        plain[2] = (seq ushr 8).toByte(); plain[3] = seq.toByte()
        val ts = seq * 960L
        plain[4] = (ts ushr 24).toByte(); plain[5] = (ts ushr 16).toByte(); plain[6] = (ts ushr 8).toByte(); plain[7] = ts.toByte()
        val out = ByteArray(plain.size + 32)
        val n = clientTx.protect(plain, 0, plain.size, out, 0)
        return out to n
    }
}

/** A loopback receiver that keeps draining, so the sender measures sendto, not a full socket buffer. */
private fun drainingSink(): DatagramChannel {
    val ch = DatagramChannel.open().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    thread(isDaemon = true, name = "sink") {
        val buf = java.nio.ByteBuffer.allocateDirect(2048)
        try {
            while (ch.isOpen) { buf.clear(); ch.receive(buf) }
        } catch (_: Exception) {
        }
    }
    return ch
}

private fun runSingleCore(profile: SrtpProfile, listeners: Int, withNetwork: Boolean, seconds: Double): Double {
    val metrics = Metrics()
    val shard = Shard(0)
    val sinkChannel = drainingSink()
    val udp = if (withNetwork) UdpServer(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) { _, _, _ -> }.start() else null
    val s = Setup(profile, listeners, udp, sinkChannel.localAddress as InetSocketAddress, shard, metrics)
    // Pre-encrypt so the measurement covers only the SFU's work.
    val packets = List(4096) { s.nextPacket() }
    var i = 0
    // warm-up
    val warm = System.nanoTime() + 1_000_000_000L
    while (System.nanoTime() < warm) {
        val idx = i++ and 4095
        if (idx == 0) s.resetInbound()
        val (p, n) = packets[idx]; s.room.forward(s.speaker, p.copyOf(), n)
    }
    val start = System.nanoTime()
    val before = metrics.rtpOut.sum()
    val end = start + (seconds * 1e9).toLong()
    var inCount = 0L
    val src = packets.map { it.first.copyOf() to it.second }
    while (System.nanoTime() < end) {
        val idx = (inCount and 4095).toInt()
        if (idx == 0) s.resetInbound()
        val (p, n) = src[idx]
        // forward() decrypts in place; refresh the buffer from the pristine copy
        packets[idx].first.copyInto(p)
        s.room.forward(s.speaker, p, n)
        inCount++
    }
    val elapsed = (System.nanoTime() - start) / 1e9
    check(metrics.srtpFailures.sum() == 0L) { "benchmark produced SRTP failures" }
    val outPps = (metrics.rtpOut.sum() - before) / elapsed
    shard.close(); udp?.close(); sinkChannel.close()
    return outPps
}

/** Many rooms spread over shards, each shard driven by its own thread: checks scaling across cores. */
private fun runMultiCore(profile: SrtpProfile, shards: Int, listenersPerRoom: Int, seconds: Double): Double {
    val metrics = Metrics()
    val sinks = List(shards) { drainingSink() } // one receiver per shard so the sink is not the bottleneck
    val udp = UdpServer(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), sockets = shards) { _, _, _ -> }.start()
    val out = LongAdder()
    val ready = CountDownLatch(shards)
    val go = CountDownLatch(1)
    val threads = List(shards) { k ->
        thread(name = "bench-$k") {
            val shard = Shard(k)
            val s = Setup(profile, listenersPerRoom, udp, sinks[k].localAddress as InetSocketAddress, shard, metrics)
            val packets = List(1024) { s.nextPacket() }
            val work = packets.map { it.first.copyOf() to it.second }
            ready.countDown(); go.await()
            val end = System.nanoTime() + (seconds * 1e9).toLong()
            var c = 0
            while (System.nanoTime() < end) {
                val idx = c++ and 1023
                if (idx == 0) s.resetInbound()
                packets[idx].first.copyInto(work[idx].first)
                s.room.forward(s.speaker, work[idx].first, work[idx].second)
            }
            shard.close()
        }
    }
    ready.await()
    val before = metrics.rtpOut.sum()
    val t0 = System.nanoTime()
    go.countDown()
    threads.forEach { it.join() }
    val elapsed = (System.nanoTime() - t0) / 1e9
    udp.close(); sinks.forEach { it.close() }
    out.add(metrics.rtpOut.sum() - before)
    return out.sum() / elapsed
}

public fun main() {
    val cores = Runtime.getRuntime().availableProcessors()
    println("krtc-sfu forwarding benchmark — ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, $cores cores")
    println()
    println("Single core, 1 speaker → 50 listeners")
    println("%-26s %14s %14s %16s".format("profile", "crypto only", "crypto+sendto", "listeners/core"))
    for (profile in SrtpProfile.entries) {
        val cryptoOnly = runSingleCore(profile, 50, withNetwork = false, seconds = 3.0)
        val full = runSingleCore(profile, 50, withNetwork = true, seconds = 3.0)
        println("%-26s %11.0f p/s %11.0f p/s %16.0f".format(profile.name, cryptoOnly, full, full / 50))
    }
    println()
    val shards = maxOf(1, cores / 2)
    println("Multi-core: $shards shards (one room each), 1 speaker → 50 listeners, AEAD_AES_128_GCM, with sendto")
    val total = runMultiCore(SrtpProfile.AEAD_AES_128_GCM, shards, 50, seconds = 5.0)
    println("  total %.0f packets/s out = %.0f concurrent listener streams (%.0f per shard)".format(total, total / 50, total / 50 / shards))
}
