package krtc.native.demo

import krtc.client.RoomClient
import krtc.client.RoomState
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeTrack
import krtc.native.NativeVideoSource
import krtc.native.nativeCameras
import krtc.native.nativeScreenSources
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile

/**
 * Demo: a client built on krtc-client + krtc-core + libwebrtc joins a krtc-sfu room, publishes a test tone and
 * a video track, and reports what it sends and receives. Same code on every platform:
 *   krtc-native-demo.kexe ws://<sfu>:8092 test native-client 30 [codec|- [flags...]]
 * Flags: `push-video` publishes frames generated here (the push API) instead of the built-in test pattern,
 * `camera` publishes camera 0, `restart-ice` asks for an ICE restart after 5 s, `chat` sends a room message every second (data channels), `screen` shares the screen after joining (renegotiates).
 * Remote screen shares are rendered and summarized like cameras. Received video is rendered
 * through a frame listener and summarized (average colour).
 */
public suspend fun runDemo(
    args: Array<String>,
    /** How `camera` opens one (Android passes Camera2 capture into a pushed source). */
    openCamera: (NativePeerConnectionFactory) -> NativeVideoSource? = { it.createCameraSource(0, 1280, 720, 30) },
    /** How `screen` captures (Android passes MediaProjection capture into a screencast source). */
    openScreen: suspend (NativePeerConnectionFactory) -> NativeVideoSource? = { it.createScreenSource() },
): Unit = coroutineScope {
    val url = args.getOrElse(0) { "ws://127.0.0.1:8080" }
    val room = args.getOrElse(1) { "test" }
    val name = args.getOrElse(2) { "native-client" }
    val seconds = args.getOrElse(3) { "30" }.toInt()

    val preferred = args.getOrNull(4)?.takeIf { it != "-" }
    val flags = args.drop(5).toSet()
    val factory = NativePeerConnectionFactory(preferredVideoCodec = preferred)
    factory.setTestTone(true)
    println("video codecs: ${factory.videoCodecs}, cameras: ${nativeCameras()}, screens: ${nativeScreenSources()}")
    val mic = factory.createAudioTrack()
    var source: NativeVideoSource? = null
    val camera = when {
        "camera" in flags -> {
            source = openCamera(factory) ?: error("no camera")
            factory.createVideoTrack(source)
        }
        "push-video" in flags -> {
            val s = factory.createVideoSource().also { source = it }
            launch { pushTestFrames(s) }
            factory.createVideoTrack(s)
        }
        else -> factory.createTestVideoTrack(width = 640, height = 360, fps = 15)
    }
    val client = RoomClient(url, factory)
    client.join(room, name, microphone = mic, camera = camera)
    val connected = withTimeout(10_000) { client.state.first { it is RoomState.Connected } } as RoomState.Connected
    println("joined ${connected.room} as ${connected.selfId}")
    var screenSource: NativeVideoSource? = null
    var screenTrack: NativeTrack? = null
    if ("screen" in flags) {
        screenSource = openScreen(factory) ?: error("screen capture unavailable (no source, or not permitted)")
        screenTrack = factory.createVideoTrack(screenSource, "screen")
        client.setScreen(screenTrack)
        println("sharing the screen")
    }

    // Room messages: print what arrives; with the `chat` flag also send one every second.
    launch { client.data.collect { m -> println("     data from ${m.from ?: "server"} [${m.topic}${if (m.reliable) "" else ", lossy"}]: ${m.payload.decodeToString()}") } }
    val restartIceAt = if ("restart-ice" in flags) 5 else -1
    val rendered = RenderSummary()
    repeat(seconds) { second ->
        delay(1000)
        if (second + 1 == restartIceAt) {
            println("requesting an ICE restart")
            client.restartIce()
        }
        if ("chat" in flags && client.dataOpen.value) {
            client.sendData("hello #${second + 1} from $name".encodeToByteArray(), topic = "chat")
            client.sendData("${second + 1}".encodeToByteArray(), topic = "tick", reliable = false)
        }
        val pcState = client.connectionState?.value
        val peers = client.participants.value.joinToString { p ->
            val video = p.video.value as NativeTrack?
            if (video != null && rendered.track !== video) {
                rendered.track = video
                video.setFrameListener(rendered::onFrame)
            }
            val stats = video?.videoStats()
            val screen = (p.screen.value as NativeTrack?)?.videoStats()
            "${p.name}: audio=${p.audio.value != null} video=${stats?.let { "${it.frames} frames ${it.width}x${it.height}" } ?: "none"}" +
                (screen?.let { " screen=${it.frames} frames ${it.width}x${it.height}" } ?: "")
        }
        if ((second + 1) % 5 == 0) {
            val stats = client.peerConnection?.getStats().orEmpty().values
            val codecs = stats.filter { it.type == "codec" }.associateBy { it.id }
            val sent = stats.filter { it.type == "outbound-rtp" }.joinToString { s ->
                "${s.string("mid")?.let { "[$it] " } ?: ""}${s.string("kind")} ${codecs[s.string("codecId")]?.string("mimeType")} ${s.long("packetsSent")} pkts" +
                    (s.long("frameWidth")?.let { " ${it}x${s.long("frameHeight")}" } ?: "") +
                    (s.string("encoderImplementation")?.let { " enc=$it" } ?: "")
            }
            val got = stats.filter { it.type == "inbound-rtp" && it.string("kind") == "audio" }.sumOf { it.long("packetsReceived") ?: 0 }
            val source = stats.firstOrNull { it.type == "media-source" && it.string("kind") == "audio" }
            println("     mic source: level ${source?.double("audioLevel")} energy ${source?.double("totalAudioEnergy")}")
            println("     sent: $sent | audio received: $got pkts, output level ${(factory.outputLevel * 1000).toInt() / 1000.0}")
        }
        println("[${second + 1}s] pc=$pcState peers=[$peers]")
    }
    rendered.track?.setFrameListener(null)
    rendered.print()
    client.leave()
    source?.close()
    screenSource?.close()
    screenTrack?.release()
    coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
    mic.release()
    camera.release()
    factory.close()
    println("left")
}

/** 640×360 frames: a hue that cycles once every 4 s with a white bar sweeping across, 15 fps. */
private suspend fun pushTestFrames(source: NativeVideoSource) = coroutineScope {
    val w = 640
    val h = 360
    val frame = IntArray(w * h)
    var n = 0
    while (isActive) {
        val hue = (n % 60) / 60.0
        val (r, g, b) = hueToRgb(hue)
        frame.fill((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        val x0 = (n * 8) % w
        for (y in 0 until h) for (x in x0 until minOf(x0 + 24, w)) frame[y * w + x] = -1
        source.pushArgb(w, h, frame)
        n++
        delay(66)
    }
}

private fun hueToRgb(h: Double): Triple<Int, Int, Int> {
    val x = h * 6
    val c = 230
    val f = ((x - x.toInt()) * c).toInt()
    return when (x.toInt() % 6) {
        0 -> Triple(c, f, 30)
        1 -> Triple(c - f, c, 30)
        2 -> Triple(30, c, f)
        3 -> Triple(30, c - f, c)
        4 -> Triple(f, 30, c)
        else -> Triple(c, 30, c - f)
    }
}

/** Collects rendered frames of the remote video: count, size and the average colour of the last one. */
private class RenderSummary {
    @Volatile var track: NativeTrack? = null
    @Volatile private var frames = 0
    @Volatile private var last = ""

    fun onFrame(f: krtc.native.VideoFrame) {
        frames++
        if (frames % 30 != 1) return
        var r = 0L
        var g = 0L
        var b = 0L
        val step = maxOf(1, f.argb.size / 4096)
        var n = 0
        for (i in f.argb.indices step step) {
            val p = f.argb[i]
            r += (p ushr 16) and 0xFF
            g += (p ushr 8) and 0xFF
            b += p and 0xFF
            n++
        }
        last = "${f.width}x${f.height} avg rgb=(${r / n}, ${g / n}, ${b / n})"
        println("     rendered frame $frames: $last")
    }

    fun print() = println("rendered $frames frames, last $last")
}
