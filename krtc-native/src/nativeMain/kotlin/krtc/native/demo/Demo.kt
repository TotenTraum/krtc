package krtc.native.demo

import krtc.client.RoomClient
import krtc.client.RoomState
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Demo: a native macOS client built on krtc-client + krtc-core + libwebrtc joins a krtc-sfu room, publishes a
 * test tone and a synthetic video track, and reports what it sends and receives.
 *   krtc-native-demo.kexe ws://127.0.0.1:8092 test native-client 30 [video/H264]
 */
public fun main(args: Array<String>): Unit = runBlocking {
    val url = args.getOrElse(0) { "ws://127.0.0.1:8080" }
    val room = args.getOrElse(1) { "test" }
    val name = args.getOrElse(2) { "native-client" }
    val seconds = args.getOrElse(3) { "30" }.toInt()

    val preferred = args.getOrNull(4)
    val factory = NativePeerConnectionFactory(preferredVideoCodec = preferred)
    factory.setTestTone(true)
    println("video codecs: ${factory.videoCodecs}")
    val mic = factory.createAudioTrack()
    val camera = factory.createTestVideoTrack(width = 640, height = 360, fps = 15)
    val client = RoomClient(url, factory)
    client.join(room, name, microphone = mic, camera = camera)
    val connected = withTimeout(10_000) { client.state.first { it is RoomState.Connected } } as RoomState.Connected
    println("joined ${connected.room} as ${connected.selfId}")

    repeat(seconds) { second ->
        delay(1000)
        val pcState = client.connectionState?.value
        val peers = client.participants.value.joinToString { p ->
            val video = p.video.value as NativeTrack?
            val stats = video?.videoStats()
            "${p.name}: audio=${p.audio.value != null} video=${stats?.let { "${it.frames} frames ${it.width}x${it.height}" } ?: "none"}"
        }
        if ((second + 1) % 5 == 0) {
            val stats = client.peerConnection?.getStats().orEmpty().values
            val codecs = stats.filter { it.type == "codec" }.associateBy { it.id }
            val sent = stats.filter { it.type == "outbound-rtp" }.joinToString { s ->
                "${s.string("kind")} ${codecs[s.string("codecId")]?.string("mimeType")} ${s.long("packetsSent")} pkts" +
                    (s.string("encoderImplementation")?.let { " enc=$it" } ?: "")
            }
            val got = stats.filter { it.type == "inbound-rtp" && it.string("kind") == "audio" }.sumOf { it.long("packetsReceived") ?: 0 }
            val source = stats.firstOrNull { it.type == "media-source" && it.string("kind") == "audio" }
            println("     mic source: level ${source?.double("audioLevel")} energy ${source?.double("totalAudioEnergy")}")
            println("     sent: $sent | audio received: $got pkts, output level ${(factory.outputLevel * 1000).toInt() / 1000.0}")
        }
        println("[${second + 1}s] pc=$pcState peers=[$peers]")
    }
    client.leave()
    mic.release()
    camera.release()
    factory.close()
    println("left")
}
