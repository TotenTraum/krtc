package krtc.meet

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.routing.post
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import krtc.client.RemoteParticipant
import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory
import krtc.native.NativeTrack
import krtc.native.NativeVideoSource
import krtc.sfu.Sfu
import krtc.sfu.SfuConfig
import krtc.sfu.ktor.FileClientLogStore
import krtc.sfu.ktor.krtcClientLogs
import krtc.sfu.ktor.krtcSfu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private val logDir = java.nio.file.Files.createTempDirectory("krtc-client-logs").toFile()

    /** Synthetic audio, and a "screen" of pushed frames: no devices or permissions needed. [static]: always the same picture. */
    private class TestPlatform(private val static: Boolean = false, override val settings: SettingsStore = MemorySettings()) : MediaPlatform {
        val factories = mutableListOf<NativePeerConnectionFactory>()
        override val sharedSystemAudio = false
        override fun createFactory(testAudio: Boolean) = NativePeerConnectionFactory(AudioDevice.SYNTHETIC).also { factories += it }
        override fun cameras() = emptyList<CameraChoice.Device>()
        override fun openCamera(factory: NativePeerConnectionFactory, camera: CameraChoice.Device) = null
        override fun screens() = listOf(ScreenChoice("test"))
        override suspend fun openScreen(
            factory: NativePeerConnectionFactory, choice: ScreenChoice, withAudio: Boolean, fps: Int, maxWidth: Int, maxHeight: Int,
        ): OpenedScreen {
            val source = factory.createScreencastSource()
            return OpenedScreen(source, null, Pusher(source, fps, static))
        }
    }

    /** Pushes frames at [fps], like a capture would. */
    private class Pusher(source: NativeVideoSource, fps: Int, static: Boolean = false) : AutoCloseable {
        @Volatile private var running = true
        private val t = thread {
            val pixels = IntArray(640 * 360)
            var n = 0
            while (running) {
                pixels.fill(0xFF000000.toInt() or (if (static) 0x80 else n++ * 7 and 0xFF))
                source.pushArgb(640, 360, pixels)
                Thread.sleep(1000L / fps)
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
            routing {
                krtcSfu(sfu, "/ws")
                krtcClientLogs(FileClientLogStore(logDir.absolutePath))
            }
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
        logDir.deleteRecursively()
    }

    private suspend fun waitUntil(what: String, timeoutMillis: Long = 15_000, check: () -> Boolean) {
        try {
            withTimeout(timeoutMillis) { while (!check()) delay(50) }
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

            // The tile size picks the simulcast layer the SFU forwards (0 = lowest).
            fun layerAtBob() = sfu.linkStats().firstOrNull { it.participantId != aliceAtBob.id }?.videoLayers?.get(aliceAtBob.id)
            b.setTileHeight(aliceAtBob, 120)
            waitUntil("low layer for a small tile") { layerAtBob() == 0 }
            b.setTileHeight(aliceAtBob, 1080)
            // Going up also needs the bandwidth estimate to allow it, which can take a while after the low layer.
            waitUntil("a higher layer for a large tile", 40_000) { (layerAtBob() ?: 0) > 0 }

            // Connection quality from getStats: localhost is good, and alice is attributed by her mids.
            waitUntil("connection stats") { b.connection.value?.peers?.containsKey(aliceAtBob.id) == true }
            assertEquals(LinkQuality.GOOD, b.connection.value!!.quality)

            // Screen share at the chosen frame rate: received, paused by unsubscribing, resumed by subscribing again.
            a.setScreenFps(30)
            a.startScreen(a.screens().single(), withAudio = false)
            waitUntil("bob receiving alice's screen") { frames(aliceAtBob.screen.value) > 10 }
            // Screen content keeps its resolution and gives up frame rate first while the bandwidth estimate ramps up,
            // which takes a varying few seconds: wait for a second with more than 20 frames.
            var rate = 0L
            withTimeout(20_000) {
                while (rate <= 20) {
                    val f0 = frames(aliceAtBob.screen.value)
                    delay(1_000)
                    rate = frames(aliceAtBob.screen.value) - f0
                }
            }
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

            // Voice activity: the pulsed tone (level about 0.18) opens the gate at a low threshold and not at a high one,
            // while the level is still measured behind the closed gate.
            a.setInputSettings(InputSettings(InputMode.VOICE_ACTIVITY, threshold = 0.05f))
            val seen = java.util.TreeSet<Float>()
            debug = { "input levels seen: $seen" }
            waitUntil("voice gate opening") { seen += a.inputLevel.value; a.transmitting.value }
            waitUntil("bob hearing alice through the gate") { bobFactory.outputLevel > 0.01 }
            a.setInputSettings(InputSettings(InputMode.VOICE_ACTIVITY, threshold = 0.9f))
            delay(1_000) // the hold time passes
            var meter = 0f
            var heard = 0.0
            repeat(40) {
                meter = maxOf(meter, a.inputLevel.value)
                heard = maxOf(heard, bobFactory.outputLevel)
                delay(40)
            }
            assertTrue(!a.transmitting.value && heard < 0.001, "gate stayed open: heard $heard")
            assertTrue(meter > 0.05f, "the level is read while the gate is closed, got $meter")

            // Push-to-talk: silent until held, heard while held, silent again after release.
            a.setInputSettings(InputSettings(InputMode.PUSH_TO_TALK))
            delay(500)
            assertTrue(level() < 0.001, "heard without push-to-talk")
            a.setPushToTalk(true)
            waitUntil("bob hearing alice while she holds push-to-talk") { bobFactory.outputLevel > 0.01 }
            a.setPushToTalk(false)
            delay(700)
            assertTrue(level() < 0.001, "heard after releasing push-to-talk")
            a.setInputSettings(InputSettings(InputMode.OPEN))
            waitUntil("open microphone again") { bobFactory.outputLevel > 0.01 }

            // Deafen: bob hears nothing, his microphone closes, and alice sees both; undeafen restores it.
            val bobAtAlice = a.participants.value.single()
            b.toggleDeafen()
            waitUntil("alice sees bob deafened") { bobAtAlice.deafened.value && bobAtAlice.audioMuted.value }
            delay(300)
            assertTrue(level() < 0.001, "deafened but still hearing")
            assertTrue(!b.transmitting.value)
            b.toggleDeafen()
            waitUntil("alice sees bob back") { !bobAtAlice.deafened.value && !bobAtAlice.audioMuted.value }
            waitUntil("bob hearing again") { bobFactory.outputLevel > 0.01 }

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
        val events = java.util.concurrent.ConcurrentLinkedQueue<krtc.sfu.SfuEvent>()
        val collector = scope.launch(Dispatchers.Default) { sfu.events.collect { events += it } }
        kotlinx.coroutines.yield()
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
        collector.cancel()
        assertEquals(0, sfu.metrics().participants, "${sfu.rooms()}; events: ${events.filter { it !is krtc.sfu.SfuEvent.SpeakingChanged }}")
    }

    /** A TCP relay whose connections can be cut like a lost network (no WebSocket close frames). */
    private class Relay(private val target: Int) : AutoCloseable {
        private val server = java.net.ServerSocket(0)
        private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val port: Int = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    val upstream = java.net.Socket("127.0.0.1", target)
                    sockets += client
                    sockets += upstream
                    for ((from, to) in listOf(client to upstream, upstream to client)) thread(isDaemon = true) {
                        runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
                        runCatching { from.close() }
                        runCatching { to.close() }
                    }
                }
            }
        }

        fun cut() {
            for (s in sockets) runCatching { s.setSoLinger(true, 0); s.close() }
            sockets.clear()
        }

        override fun close() {
            server.close()
            cut()
        }
    }

    /** Signaling drops mid-call: both sides show it, resume the same session, and the media never stops. */
    @Test
    fun signalingDropIsResumedWithoutInterruptingMedia(): Unit = runBlocking(main) {
        val port = server.engine.resolvedConnectors().first().port
        Relay(port).use { relay ->
            val a = MeetSession(scope, TestPlatform())
            val b = MeetSession(scope, TestPlatform())
            try {
                a.join(JoinSettings("ws://127.0.0.1:${relay.port}", "drop", "alice", CameraChoice.TestPattern, testAudio = true))
                b.join(JoinSettings("ws://127.0.0.1:${relay.port}", "drop", "bob", CameraChoice.TestPattern, testAudio = true))
                val aliceAtBob = withTimeout(15_000) { b.participants.first { it.isNotEmpty() }.single() }
                waitUntil("video at bob") { frames(aliceAtBob.video.value) > 10 }

                // Resuming through a local relay takes milliseconds: count the SFU's resume events rather than catch the
                // short Reconnecting state.
                val resumed = java.util.concurrent.atomic.AtomicInteger()
                val events = scope.launch { sfu.events.collect { if (it is krtc.sfu.SfuEvent.ParticipantResumed) resumed.incrementAndGet() } }
                kotlinx.coroutines.yield()
                val before = frames(aliceAtBob.video.value)
                relay.cut()
                waitUntil("both sessions resumed") { resumed.get() == 2 }
                events.cancel()
                val during = before
                waitUntil("bob back in the call") { b.reconnecting.value == null && b.phase.value is CallPhase.InCall }
                assertTrue(b.participants.value.single() === aliceAtBob, "same session: same participant and tracks")
                waitUntil("video kept flowing") { frames(aliceAtBob.video.value) > during + 30 }
                assertEquals(2, sfu.metrics().participants)

                // Signaling works again after the resume.
                waitUntil("chat after resume") { a.sendChat("still here") }
                waitUntil("bob got it") { b.chat.value.any { it.text == "still here" } }
            } finally {
                a.close()
                b.close()
            }
        }
    }

    /**
     * Viewers who join while a static screen share is already running get the picture: the SFU keeps asking the
     * publisher for a key frame while a viewer waits, also when two join within the key frame request interval.
     */
    @Test
    fun lateViewersGetAStaticScreen(): Unit = runBlocking(main) {
        val a = MeetSession(scope, TestPlatform(static = true))
        val b = MeetSession(scope, TestPlatform())
        val c = MeetSession(scope, TestPlatform())
        try {
            a.join(JoinSettings(url, "late", "alice", CameraChoice.None, testAudio = true))
            waitUntil("alice in the call") { a.phase.value is CallPhase.InCall }
            a.startScreen(a.screens().single(), withAudio = false)
            waitUntil("alice sharing") { a.screen.value != null }
            delay(3_000) // the share's first key frame is long gone
            b.join(JoinSettings(url, "late", "bob", CameraChoice.None, testAudio = true))
            delay(100)
            c.join(JoinSettings(url, "late", "carol", CameraChoice.None, testAudio = true))
            for ((who, s) in listOf("bob" to b, "carol" to c)) {
                val alice = withTimeout(15_000) { s.participants.first { list -> list.any { it.name == "alice" } }.single { it.name == "alice" } }
                waitUntil("$who sees alice's screen") { frames(alice.screen.value) > 5 }
            }
        } finally {
            a.close()
            b.close()
            c.close()
        }
    }

    /** A server that accepts the socket but never answers: joining ends with an error, not "Подключение…" forever. */
    @Test
    fun joinThatNeverCompletesEndsWithAnError(): Unit = runBlocking(main) {
        val silent = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { webSocket("/ws") { for (frame in incoming) Unit } }
        }.also { it.start(wait = false) }
        try {
            val port = silent.engine.resolvedConnectors().first().port
            val a = MeetSession(scope, TestPlatform())
            a.join(JoinSettings("ws://127.0.0.1:$port", "r", "alice", CameraChoice.None, testAudio = true))
            waitUntil("join reported as failed", MeetSession.JOIN_TIMEOUT_MILLIS + 10_000) { a.phase.value is CallPhase.Ended }
            val reason = (a.phase.value as CallPhase.Ended).reason.orEmpty()
            assertTrue(reason.startsWith("сервер не ответил"), reason)
            assertTrue(java.io.File(logLocation()!!).readText().contains("join still not done"), "thread dump in the log")
            a.close()
        } finally {
            silent.stop(100, 500)
        }
    }

    /** The session's log, with @Traced calls (woven by kaop), reaches the server's log receiver; chat text does not. */
    @Test
    fun logReachesTheServer(): Unit = runBlocking(main) {
        val a = MeetSession(scope, TestPlatform())
        a.join(JoinSettings(url, "logs", "Alice Smith", CameraChoice.None, testAudio = true))
        waitUntil("alice in the call") { a.phase.value is CallPhase.InCall }
        a.toggleMic()
        waitUntil("chat open") { a.sendChat("my secret words") }
        a.leave()
        fun serverLog() = logDir.walk().filter { it.isFile }.joinToString("\n") { it.name + "\n" + it.readText() }
        waitUntil("the log on the server") { "call ended: left" in serverLog() }
        val log = serverLog()
        assertTrue(Regex("AliceSmith-[0-9a-f]{6}\\.log").containsMatchIn(log), log.take(300))
        assertTrue("# krtc-meet on " in log, "header line")
        assertTrue(Regex("D \\[MeetSession] toggleMic\\(\\) \\d+ ms").containsMatchIn(log), "traced call: " + log.take(2000))
        assertTrue(Regex("join\\(settings=JoinSettings\\(server=ws://").containsMatchIn(log), "traced arguments")
        assertTrue("sendChat()" in log && "my secret words" !in log, "chat text kept out")
        a.close()
    }

    /** A server that fails twice: the same entries are sent again, each arrives once and in order. */
    @Test
    fun uploaderRetriesFailedBatches(): Unit = runBlocking(main) {
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val failures = java.util.concurrent.atomic.AtomicInteger(2)
        val flaky = embeddedServer(CIO, port = 0) {
            routing {
                post("/logs") {
                    if (failures.getAndDecrement() > 0) return@post call.respond(io.ktor.http.HttpStatusCode.ServiceUnavailable)
                    bodies += call.receiveText()
                    call.respond(io.ktor.http.HttpStatusCode.NoContent)
                }
            }
        }.also { it.start(wait = false) }
        try {
            val port = flaky.engine.resolvedConnectors().first().port
            krtc.meet.log.ClientLog.take(Int.MAX_VALUE) // what other tests left
            repeat(5) { krtc.meet.log.ClientLog.i("retry-test", "entry $it") }
            val up = krtc.meet.log.LogUploader("http://127.0.0.1:$port/logs", "t-1", "test", "jvm", intervalMillis = 100, batchSize = 3)
            up.start(scope)
            waitUntil("all entries delivered") { bodies.joinToString().contains("entry 4") }
            up.stop()
            val sent = bodies.flatMap { Regex("entry (\\d)").findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }
            assertEquals(listOf(0, 1, 2, 3, 4), sent, "each once, in order")
        } finally {
            flaky.stop(100, 500)
        }
    }

    /** Settings survive a restart: a new session on the same store starts with them, and the join form too. */
    @Test
    fun settingsAreRemembered(): Unit = runBlocking(main) {
        val store = MemorySettings()
        val first = MeetSession(scope, TestPlatform(settings = store))
        first.setInputSettings(InputSettings(InputMode.PUSH_TO_TALK, threshold = 0.2f))
        first.setAudioProcessing(AudioProcessing(noiseSuppression = false))
        first.setScreenFps(60)
        first.setLayout(Layout.SPEAKER)
        first.join(JoinSettings(url, "remember", "carol", CameraChoice.TestPattern, testAudio = true))
        waitUntil("carol in the call") { first.phase.value is CallPhase.InCall }
        delay(200) // the flows' collectors save
        first.close()

        val second = MeetSession(scope, TestPlatform(settings = store))
        assertEquals(InputSettings(InputMode.PUSH_TO_TALK, 0.2f), second.inputSettings.value)
        assertEquals(false, second.audioProcessing.value.noiseSuppression)
        assertEquals(60, second.screenFps.value)
        assertEquals(Layout.SPEAKER, second.layout.value)
        val fallback = JoinSettings("ws://other", "room", "name", CameraChoice.None, testAudio = false)
        val remembered = second.rememberedJoin(fallback)
        assertEquals(JoinSettings(url, "remember", "carol", CameraChoice.TestPattern, testAudio = true), remembered)
        // What is given explicitly (automation) wins over what was saved.
        assertEquals("ws://other", second.rememberedJoin(fallback, given = setOf("server")).server)
        second.close()
    }

    @Test
    fun serverAddressesAreNormalized() {
        assertEquals("ws://155.212.175.60:8080", MeetSession.normalizeServer("155.212.175.60:8080"))
        assertEquals("ws://host:8080", MeetSession.normalizeServer(" http://host:8080/ "))
        assertEquals("wss://meet.example.com", MeetSession.normalizeServer("https://meet.example.com/ws"))
        assertEquals("ws://h:1", MeetSession.normalizeServer("ws://h:1"))
    }

    @Test
    fun updatesFollowTheBuildsChannel() {
        val releases = """[
            {"tag_name":"nightly","name":"nightly 1.0.57","prerelease":true,"html_url":"https://example/nightly"},
            {"tag_name":"v1.2.0","name":"krtc v1.2.0","prerelease":false,"html_url":"https://example/v1.2.0"},
            {"tag_name":"v1.3.0-rc1","name":"krtc v1.3.0","prerelease":true,"html_url":"https://example/rc"}
        ]"""
        assertEquals(Update("1.0.57", "https://example/nightly"), newerRelease("1.0.50", releases))
        assertEquals(null, newerRelease("1.0.57", releases), "the newest nightly already")
        assertEquals(Update("1.2.0", "https://example/v1.2.0"), newerRelease("1.1.0", releases), "release builds ignore nightly and pre-releases")
        assertEquals(null, newerRelease("1.2.0", releases))
    }
}
