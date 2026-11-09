package krtc.sfu

import krtc.sfu.platform.PeerAddress
import krtc.sfu.platform.UdpServer
import krtc.sfu.room.MediaPlan
import krtc.sfu.room.Participant
import krtc.sfu.room.Room
import krtc.sfu.room.Shard
import krtc.sfu.room.SignalSink
import krtc.sfu.srtp.SrtcpContext
import krtc.sfu.srtp.SrtpContext
import krtc.sfu.srtp.SrtpSessionKeys
import krtc.sfu.srtp.SrtpProfile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Forwarding throughput of the media hot path on the JVM: SRTP unprotect once, then rewrite +
 * protect + sendto per listener — exactly Room.forward, driven directly (no browsers, no DTLS).
 *
 * Opus at 20 ms frames is 50 packets/s per speaker, so "listener streams per core" =
 * output packets per second per core / 50.
 */
private object NoSignal : SignalSink {
    override fun send(message: String) {}
    override fun close() {}
}

private fun keysOf(profile: SrtpProfile, master: ByteArray, salt: ByteArray, rtpIn: SrtpContext? = null, rtpOut: SrtpContext? = null) =
    SrtpSessionKeys(
        rtpIn ?: SrtpContext.fromMaster(profile, master, salt),
        rtpOut ?: SrtpContext.fromMaster(profile, master, salt),
        SrtcpContext.fromMaster(profile, master, salt),
        SrtcpContext.fromMaster(profile, master, salt),
    )

private class Setup(val profile: SrtpProfile, listeners: Int, udp: UdpServer?, sink: PeerAddress, val shard: Shard) {
    val room = Room("bench", shard, udp, onSpeaking = { _, _ -> })
    val speaker = Participant("speaker", "speaker", room, NoSignal)
    private val masterKey = ByteArray(profile.keyLength) { it.toByte() }
    private val masterSalt = ByteArray(profile.saltLength) { (it + 1).toByte() }
    private val clientTx = SrtpContext.fromMaster(profile, masterKey, masterSalt)
    private var seq = 0

    /** Pre-encrypted packets are replayed in cycles; a fresh receive context per cycle keeps replay protection honest. */
    fun resetInbound() {
        speaker.keys = keysOf(profile, rtpIn = SrtpContext.fromMaster(profile, masterKey, masterSalt), master = masterKey, salt = masterSalt)
    }

    init {
        resetInbound()
        room.add(speaker)
        repeat(listeners) { i ->
            val l = Participant("l$i", "l$i", room, NoSignal)
            val k = ByteArray(profile.keyLength) { (i + it).toByte() }
            l.keys = keysOf(profile, rtpOut = SrtpContext.fromMaster(profile, k, masterSalt), master = k, salt = masterSalt)
            l.remote = sink
            l.receiving = setOf(speaker.id)
            room.add(l)
        }
    }

    // A typical Opus packet: 12-byte header + audio-level extension + 80 bytes of payload (~32 kbit/s).
    private val plain = ByteArray(12 + 8 + 80).also { p ->
        p[0] = 0x90.toByte(); p[1] = MediaPlan.OPUS_PT.toByte()
        p[8] = 0x12; p[9] = 0x34; p[10] = 0x56; p[11] = 0x78
        p[12] = 0xBE.toByte(); p[13] = 0xDE.toByte(); p[14] = 0; p[15] = 1
        p[16] = 0x10; p[17] = 0x85.toByte()
    }

    fun nextPacket(): ByteArray {
        seq = (seq + 1) and 0xFFFF
        plain[2] = (seq ushr 8).toByte(); plain[3] = seq.toByte()
        val ts = seq * 960L
        plain[4] = (ts ushr 24).toByte(); plain[5] = (ts ushr 16).toByte(); plain[6] = (ts ushr 8).toByte(); plain[7] = ts.toByte()
        val out = ByteArray(plain.size + 32)
        val n = clientTx.protect(plain, 0, plain.size, out, 0)
        return out.copyOf(n + 16)
    }

    /** Forwards `count` packets cycling over pre-encrypted ones; returns nothing, counters say what happened. */
    fun drive(packets: List<ByteArray>, until: Long) {
        val work = packets.map { it.copyOf() }
        var i = 0
        val n = packets[0].size - 16
        while (System.nanoTime() < until) {
            val idx = i++ % packets.size
            if (idx == 0) resetInbound()
            packets[idx].copyInto(work[idx]) // forward() decrypts in place
            room.forward(speaker, work[idx], n)
        }
    }
}

/** A loopback receiver that keeps draining, so the sender measures sendto, not a full socket buffer. */
private fun drainingSink(): Pair<DatagramChannel, PeerAddress> {
    val ch = DatagramChannel.open().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    thread(isDaemon = true, name = "sink") {
        val buf = ByteBuffer.allocateDirect(2048)
        try {
            while (ch.isOpen) { buf.clear(); ch.receive(buf) }
        } catch (_: Exception) {
        }
    }
    val a = ch.localAddress as InetSocketAddress
    return ch to PeerAddress(a.address.address, a.port)
}

private fun runSingleCore(profile: SrtpProfile, listeners: Int, withNetwork: Boolean, seconds: Double): Double {
    val shard = Shard(0)
    val (sink, sinkAddress) = drainingSink()
    val udp = if (withNetwork) UdpServer("127.0.0.1", 0, 1) { _, _, _ -> }.start() else null
    val s = Setup(profile, listeners, udp, sinkAddress, shard)
    val packets = List(4096) { s.nextPacket() }
    s.drive(packets, System.nanoTime() + 1_000_000_000L) // warm-up
    val before = shard.counters.rtpOut.load()
    val start = System.nanoTime()
    s.drive(packets, start + (seconds * 1e9).toLong())
    val elapsed = (System.nanoTime() - start) / 1e9
    check(shard.counters.srtpFailures.load() == 0L) { "benchmark produced SRTP failures" }
    val outPps = (shard.counters.rtpOut.load() - before) / elapsed
    shard.close(); udp?.close(); sink.close()
    return outPps
}

/** One room per shard, each driven by its own thread with its own send socket: checks scaling across cores. */
private fun runMultiCore(profile: SrtpProfile, shards: Int, listenersPerRoom: Int, seconds: Double): Double {
    val sinks = List(shards) { drainingSink() } // one receiver per shard so the sink is not the bottleneck
    val udp = UdpServer("127.0.0.1", 0, shards) { _, _, _ -> }.start()
    val shardList = List(shards) { Shard(it) }
    val ready = CountDownLatch(shards)
    val go = CountDownLatch(1)
    var deadline = 0L
    val threads = List(shards) { k ->
        thread(name = "bench-$k") {
            val s = Setup(profile, listenersPerRoom, udp, sinks[k].second, shardList[k])
            val packets = List(1024) { s.nextPacket() }
            ready.countDown()
            go.await()
            s.drive(packets, deadline)
        }
    }
    ready.await()
    val before = shardList.sumOf { it.counters.rtpOut.load() }
    val t0 = System.nanoTime()
    deadline = t0 + (seconds * 1e9).toLong()
    go.countDown()
    threads.forEach { it.join() }
    val elapsed = (System.nanoTime() - t0) / 1e9
    val total = shardList.sumOf { it.counters.rtpOut.load() } - before
    shardList.forEach { it.close() }; udp.close(); sinks.forEach { it.first.close() }
    return total / elapsed
}

public fun main() {
    val cores = Runtime.getRuntime().availableProcessors()
    println("krtc-sfu forwarding benchmark (JVM) — ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, $cores cores")
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
