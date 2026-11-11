package krtc.meet

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import krtc.client.RemoteParticipant
import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeTrack
import krtc.native.NativeVideoSource
import krtc.sfu.Sfu
import krtc.sfu.SfuConfig
import krtc.sfu.ktor.krtcSfu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two [MeetSession]s with real libwebrtc (synthetic audio, test pattern) in a room of an in-process krtc-sfu:
 * mute state, chat, screen share subscription and playout volume, as the app uses them.
 */
class MeetSessionTest {
    private lateinit var sfu: Sfu
    private lateinit var server: EmbeddedServer<*, *>
    private var url = ""
    // MeetSession is confined to one thread (the UI thread in the app): so are the tests.
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    /** Synthetic audio, and a "screen" of pushed frames: no devices or permissions needed. */
    private class TestPlatform : MediaPlatform {
        val factories = mutableListOf<NativePeerConnectionFactory>()
        override val sharedSystemAudio = false
        override fun createFactory(testAudio: Boolean) = NativePeerConnectionFactory(AudioDevice.SYNTHETIC).also { factories += it }
        override fun cameras() = emptyList<CameraChoice.Device>()
        override fun openCamera(factory: NativePeerConnectionFactory, camera: CameraChoice.Device) = null
        override fun screens() = listOf(ScreenChoice("test"))
        override suspend fun openScreen(factory: NativePeerConnectionFactory, choice: ScreenChoice, withAudio: Boolean): OpenedScreen {
            val source = factory.createScreencastSource()
            return OpenedScreen(source, null, Pusher(source))
        }
    }

    private class Pusher(source: NativeVideoSource) : AutoCloseable {
        @Volatile private var running = true
        private val t = thread {
            val pixels = IntArray(640 * 360)
            var n = 0
            while (running) {
                pixels.fill(0xFF000000.toInt() or (n++ * 7 and 0xFF))
                source.pushArgb(640, 360, pixels)
                Thread.sleep(66)
            }
        }
        override fun close() {
            running = false
            t.join()
        }
    }

    @BeforeTest
    fun start() {
        sfu = Sfu(SfuConfig(udpPort = 0, shards = 1, announcedAddress = "127.0.0.1"))
        server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { krtcSfu(sfu, "/ws") }
        }
        server.start(wait = false)
        url = "ws://127.0.0.1:" + runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        main.close()
        server.stop(100, 500)
        sfu.close()
    }

    private suspend fun waitUntil(what: String, check: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!check()) delay(50) }
        } catch (t: Throwable) {
            throw AssertionError("timed out waiting for $what; ${sfu.metrics()}; ${sfu.linkStats()}; ${debug()}; ${audioStats()}", t)
        }
    }

    private var debug: () -> String = { "" }
    private val sessions = mutableListOf<MeetSession>()

    private suspend fun audioStats(): String = sessions.map { s ->
        val pc = s.roomClient?.peerConnection ?: return@map "no pc"
        val report = runCatching { pc.getStats() }.getOrNull() ?: return@map "no stats"
        report.values.filter { (it.type == "inbound-rtp" || it.type == "outbound-rtp") && it.members["kind"] == "audio" }
            .joinToString { "${it.type} mid=${it.members["mid"]} sent=${it.members["packetsSent"]} recv=${it.members["packetsReceived"]} level=${it.members["audioLevel"]}" }
    }.joinToString(" | ")

    private fun frames(track: Any?) = (track as? NativeTrack)?.videoStats()?.frames ?: 0

    @Test
    fun callFeatures(): Unit = runBlocking(main) {
        val pa = TestPlatform()
        val pb = TestPlatform()
        val a = MeetSession(scope, pa)
        val b = MeetSession(scope, pb)
        sessions += a
        sessions += b
        try {
            // Both sides send the same test tone: echo cancellation would take Alice's tone for Bob's echo, and noise
            // suppression removes a steady tone. Off, so the levels below measure only what the test changes.
            val raw = AudioProcessing(echoCancellation = false, noiseSuppression = false, autoGainControl = false)
            a.setAudioProcessing(raw)
            b.setAudioProcessing(raw)
            a.join(JoinSettings(url, "r", "alice", CameraChoice.TestPattern, testAudio = true))
            b.join(JoinSettings(url, "r", "bob", CameraChoice.TestPattern, testAudio = true))
            val aliceAtBob: RemoteParticipant = withTimeout(15_000) { b.participants.first { it.isNotEmpty() }.single() }
            debug = { "audio=${aliceAtBob.audio.value} video=${aliceAtBob.video.value} frames=${frames(aliceAtBob.video.value)} stats=${(aliceAtBob.video.value as? NativeTrack)?.videoStats()}" }
            waitUntil("bob receiving alice's audio and video") { aliceAtBob.audio.value != null && frames(aliceAtBob.video.value) > 10 }

            // Mute state reaches the other side.
            a.toggleMic()
            waitUntil("alice shown muted") { aliceAtBob.audioMuted.value }
            a.toggleCamera()
            waitUntil("alice's camera shown off") { aliceAtBob.videoMuted.value }
            a.toggleMic()
            waitUntil("alice shown unmuted") { !aliceAtBob.audioMuted.value }

            // Chat both ways, over the data channel.
            waitUntil("chat open") { a.sendChat("привет") }
            waitUntil("bob got the line") { b.chat.value.any { it.text == "привет" && it.from == "alice" && !it.mine } }
            assertEquals(1, b.unread.value)
            b.setChatOpen(true)
            assertEquals(0, b.unread.value)
            assertTrue(b.sendChat("hi"))
            waitUntil("alice got the reply") { a.chat.value.any { it.text == "hi" && it.from == "bob" } }

            // Screen share: received, paused by unsubscribing, resumed by subscribing again.
            a.startScreen(a.screens().single(), withAudio = false)
            waitUntil("bob receiving alice's screen") { frames(aliceAtBob.screen.value) > 10 }
            b.setScreenSubscribed(aliceAtBob, false)
            delay(500)
            val paused = frames(aliceAtBob.screen.value)
            delay(1_500)
            assertTrue(frames(aliceAtBob.screen.value) - paused <= 1, "frames kept coming while unsubscribed")
            b.setScreenSubscribed(aliceAtBob, true)
            waitUntil("screen resumed") { frames(aliceAtBob.screen.value) > paused + 10 }
            a.stopScreen()
            waitUntil("screen share gone at bob") { aliceAtBob.screen.value == null }

            // Volume: alice's test tone is audible at bob, silent at volume 0, back at 1.
            val bobFactory = pb.factories.single()
            suspend fun level(): Double {
                var max = 0.0
                repeat(40) {
                    max = maxOf(max, bobFactory.outputLevel)
                    delay(25)
                }
                return max
            }
            waitUntil("bob hearing alice") { bobFactory.outputLevel > 0.01 }
            b.setVolume(aliceAtBob, 0f)
            delay(300)
            assertTrue(level() < 0.001, "still audible at volume 0")
            b.setVolume(aliceAtBob, 1f)
            waitUntil("bob hearing alice again") { bobFactory.outputLevel > 0.01 }

            // Changing the microphone processing recreates the microphone track; audio keeps flowing.
            a.setAudioProcessing(raw.copy(autoGainControl = true))
            delay(500)
            waitUntil("audio after processing change") { bobFactory.outputLevel > 0.01 }
        } finally {
            a.close()
            b.close()
        }
    }

    /** Leaving at any moment, also while an offer is being answered, must not touch released native objects. */
    @Test
    fun leavingDuringNegotiationIsSafe(): Unit = runBlocking(main) {
        repeat(8) { i ->
            val a = MeetSession(scope, TestPlatform())
            val b = MeetSession(scope, TestPlatform())
            a.join(JoinSettings(url, "leave$i", "a", CameraChoice.TestPattern, testAudio = true))
            b.join(JoinSettings(url, "leave$i", "b", CameraChoice.TestPattern, testAudio = true))
            delay(50L + i * 60)
            a.close()
            delay(20)
            b.close()
        }
        delay(500) // coroutines still running after close must fail cleanly, not crash the process
        assertEquals(0, sfu.metrics().participants, sfu.rooms().toString())
    }
}
