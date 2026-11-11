package krtc.client

import io.ktor.server.engine.EmbeddedServer
import krtc.sfu.Sfu
import krtc.sfu.SfuConfig
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import krtc.sfu.ktor.krtcSfu
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * krtc-client against a real krtc-sfu (signaling and negotiation; media is a fake backend).
 * Verifies the SDK end to end: join, offers answered in order, tracks mapped to participants,
 * camera on/off reflected in other clients, simulcast preference accepted, leave.
 */
class RoomClientSfuTest {
    private lateinit var sfu: Sfu
    private lateinit var server: EmbeddedServer<*, *>
    private var url = ""

    @BeforeTest
    fun start() {
        sfu = Sfu(SfuConfig(udpPort = 0, shards = 2))
        // Embedded exactly as a host server would: its own Ktor app with the krtc route.
        server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { krtcSfu(sfu, "/ws") }
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        url = "ws://127.0.0.1:$port"
    }

    @AfterTest
    fun stop() {
        server.stop(100, 500)
        sfu.close()
    }

    /** A TCP relay in front of the signaling server whose connections can be cut like a lost network: no close frame. */
    private class Relay(private val target: Int) : AutoCloseable {
        private val server = java.net.ServerSocket(0)
        private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val port: Int = server.localPort

        /** While set, new connections are reset at once: the network is down. */
        @Volatile var down = false

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    if (down) {
                        runCatching { client.setSoLinger(true, 0); client.close() }
                        continue
                    }
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

        /** Drops every connection at once, without WebSocket close frames (RST). */
        fun cut() {
            for (s in sockets) runCatching { s.setSoLinger(true, 0); s.close() }
            sockets.clear()
        }

        override fun close() {
            server.close()
            cut()
        }
    }

    /** The network drops: clients reconnect and resume their sessions under the same ids, media untouched. */
    @Test
    fun reconnectsAndResumesAfterSignalingDrops(): Unit = runBlocking {
        val port = server.engine.resolvedConnectors().first().port
        Relay(port).use { relay ->
            withTimeout(30_000) {
                val alice = RoomClient("ws://127.0.0.1:${relay.port}", FakeFactory())
                val bob = RoomClient("ws://127.0.0.1:${relay.port}", FakeFactory())
                alice.join("resume", "Alice", microphone = FakeTrack("audio"))
                bob.join("resume", "Bob", microphone = FakeTrack("audio"))
                val aliceId = (alice.state.first { it is RoomState.Connected } as RoomState.Connected).selfId
                val aliceAtBob = bob.participants.first { it.size == 1 }.single()

                relay.cut()
                alice.state.first { it is RoomState.Reconnecting }
                assertEquals(aliceId, (alice.state.first { it is RoomState.Connected } as RoomState.Connected).selfId)
                bob.state.first { it is RoomState.Connected }
                assertTrue(bob.participants.value.single() === aliceAtBob, "the participant object (and its tracks) survive")
                assertEquals(2, sfu.room("resume")!!.participants.size)
                // Signaling works again: a mute reaches the other side.
                alice.setMediaState(audioMuted = true, videoMuted = false)
                aliceAtBob.audioMuted.first { it }

                // Leaving properly is final at once, no grace period.
                alice.leave()
                bob.participants.first { it.isEmpty() }
                bob.leave()
            }
        }
    }

    /** The server forgot the session (its grace period passed): the client joins again under a new id. */
    @Test
    fun rejoinsWhenTheSessionIsGone(): Unit = runBlocking {
        val shortSfu = Sfu(SfuConfig(udpPort = 0, shards = 1, resumeTimeoutMillis = 200))
        val s2 = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { krtcSfu(shortSfu, "/ws") }
        }.also { it.start(wait = false) }
        val relay = Relay(s2.engine.resolvedConnectors().first().port)
        try {
            withTimeout(30_000) {
                val alice = RoomClient("ws://127.0.0.1:${relay.port}", FakeFactory())
                val bob = RoomClient("ws://127.0.0.1:${relay.port}", FakeFactory())
                alice.join("gone", "Alice", microphone = FakeTrack("audio"))
                bob.join("gone", "Bob", microphone = FakeTrack("audio"))
                val firstId = (alice.state.first { it is RoomState.Connected } as RoomState.Connected).selfId
                bob.participants.first { it.size == 1 }

                // Keep the relay down until the server has dropped both, then let them back in.
                relay.down = true
                relay.cut()
                while (shortSfu.room("gone") != null) delay(50)
                relay.down = false
                val again = withTimeout(10_000) { alice.state.first { it is RoomState.Connected && it.selfId != firstId } }
                bob.state.first { it is RoomState.Connected }
                bob.participants.first { list -> list.any { it.id == (again as RoomState.Connected).selfId } }.single().audio.first { it != null }
                assertEquals(2, shortSfu.room("gone")!!.participants.size)
                alice.leave()
                bob.leave()
            }
        } finally {
            relay.close()
            s2.stop(0, 100)
            shortSfu.close()
        }
    }

    @Test
    fun mutedStateIsSeenByOthers(): Unit = runBlocking {
        withTimeout(15_000) {
            val alice = RoomClient(url, FakeFactory())
            val bob = RoomClient(url, FakeFactory())
            alice.setMediaState(audioMuted = true, videoMuted = false) // before joining: sent once connected
            alice.join("mute", "Alice", microphone = FakeTrack("audio"))
            alice.state.first { it is RoomState.Connected }
            alice.selfAudioMuted.first { it }
            bob.join("mute", "Bob", microphone = FakeTrack("audio"))
            val aliceAtBob = bob.participants.first { it.size == 1 }.single()
            aliceAtBob.audioMuted.first { it }
            alice.setMediaState(audioMuted = false, videoMuted = true)
            aliceAtBob.audioMuted.first { !it }
            aliceAtBob.videoMuted.first { it }

            // Unsubscribing from a screen share is accepted and reflected locally.
            val bobAtAlice = alice.participants.first { it.size == 1 }.single()
            alice.setSubscribed(bobAtAlice.id, enabled = false)
            assertEquals(false, bobAtAlice.screenSubscribed.value)
            alice.leave()
            bob.leave()
        }
    }

    @Test
    fun twoClientsSeeEachOthersMedia(): Unit = runBlocking {
        withTimeout(15_000) {
            val alice = RoomClient(url, FakeFactory())
            val bob = RoomClient(url, FakeFactory())
            val aliceCam = FakeTrack("video")
            alice.join("demo", "Alice", microphone = FakeTrack("audio"), camera = aliceCam)
            alice.state.first { it is RoomState.Connected }
            bob.join("demo", "Bob", microphone = FakeTrack("audio"))
            bob.state.first { it is RoomState.Connected }

            // Bob sees Alice with audio and video (she publishes a camera); Alice sees Bob with audio only.
            val aliceAtBob = bob.participants.first { it.size == 1 }.single()
            assertEquals("Alice", aliceAtBob.name)
            aliceAtBob.audio.first { it != null }
            val video = aliceAtBob.video.first { it != null }
            assertEquals("video", video!!.kind)

            val bobAtAlice = alice.participants.first { it.size == 1 }.single()
            assertEquals("Bob", bobAtAlice.name)
            bobAtAlice.audio.first { it != null }
            assertNull(bobAtAlice.video.value, "Bob has no camera, so Alice gets no video m-line for him")

            // Server-side view agrees: both answered, Bob receives Alice's video.
            assertEquals(2, sfu.metrics().participants)

            // Simulcast preference is accepted by the server (no disconnect).
            bob.setVideoQuality(aliceAtBob.id, VideoQuality.LOW)

            // Alice leaves: Bob's participant list empties.
            alice.leave()
            bob.participants.first { it.isEmpty() }
            assertTrue(bob.state.value is RoomState.Connected)
            bob.leave()
            bob.state.first { it is RoomState.Disconnected }
        }
    }

    @Test
    fun joinMarksConnectionAndUsesUplinkMids(): Unit = runBlocking {
        withTimeout(10_000) {
            val factory = FakeFactory()
            val client = RoomClient(url, factory)
            val mic = FakeTrack("audio")
            val cam = FakeTrack("video")
            client.join("solo", "Carol", mic, cam)
            val connected = client.state.first { it is RoomState.Connected } as RoomState.Connected
            assertEquals("solo", connected.room)
            // Wait until the first offer has been answered, then check the uplink transceivers.
            val pc = factory.created.single()
            while (pc.localDescription == null) kotlinx.coroutines.delay(20)
            val byMid = pc.getTransceivers().associateBy { it.mid }
            assertEquals(mic, byMid["0"]!!.sender.track)
            assertEquals(cam, byMid["1"]!!.sender.track)
            assertTrue(pc.localDescription!!.sdp.contains("a=mid:1\r\na=ice-ufrag:fake") && pc.localDescription!!.sdp.contains("a=sendonly"))
            client.leave()
            client.state.first { it is RoomState.Disconnected }
            assertTrue(pc.closed)
        }
    }

    @Test
    fun dataChannelsFollowTheOfferAndCarryTheEnvelope(): Unit = runBlocking {
        withTimeout(10_000) {
            val factory = FakeFactory()
            val client = RoomClient(url, factory)
            client.join("data", "Dana")
            val pc = factory.created.let { while (it.isEmpty()) kotlinx.coroutines.delay(10); it.single() }
            while (pc.localDescription == null) kotlinx.coroutines.delay(20)
            assertTrue(pc.localDescription!!.sdp.contains("m=application 9 UDP/DTLS/SCTP webrtc-datachannel"), "the answer accepts the SCTP m-line")
            val (reliable, lossy) = pc.dataChannels
            assertEquals(krtc.signaling.DataEnvelope.RELIABLE_LABEL, reliable.label)
            assertEquals(krtc.signaling.DataEnvelope.LOSSY_LABEL, lossy.label)
            assertEquals(false, lossy.ordered)
            assertEquals(0, lossy.maxRetransmits)

            assertEquals(false, client.sendData("early".encodeToByteArray()), "not open yet")
            reliable.state.value = krtc.core.RTCDataChannelState.OPEN
            lossy.state.value = krtc.core.RTCDataChannelState.OPEN
            client.dataOpen.first { it }
            assertTrue(client.sendData("hi".encodeToByteArray(), topic = "chat", to = listOf("7")))
            assertTrue(client.sendData(byteArrayOf(1), topic = "cursor", reliable = false))
            val sent = krtc.signaling.DataEnvelope.decode((reliable.sent.single() as krtc.core.RTCDataChannelMessage.Binary).data)!!
            assertEquals(listOf("7"), sent.ids)
            assertEquals("chat", sent.topic)
            assertEquals("hi", sent.payload.decodeToString())
            assertEquals(1, lossy.sent.size)

            val received = async { client.data.first() }
            kotlinx.coroutines.yield()
            while (reliable.incoming.subscriptionCount.value == 0) kotlinx.coroutines.delay(10)
            reliable.incoming.emit(krtc.core.RTCDataChannelMessage.Binary(krtc.signaling.DataEnvelope.encode(listOf("3"), "chat", "yo".encodeToByteArray())))
            val m = received.await()
            assertEquals("3", m.from)
            assertEquals("yo", m.payload.decodeToString())
            assertTrue(m.reliable)
            client.leave()
        }
    }

    @Test
    fun screenShareReachesTheOthersAndStops(): Unit = runBlocking {
        withTimeout(10_000) {
            val alice = RoomClient(url, FakeFactory())
            val bobFactory = FakeFactory()
            val bob = RoomClient(url, bobFactory)
            alice.join("screen", "Alice")
            alice.state.first { it is RoomState.Connected }
            bob.join("screen", "Bob")
            bob.state.first { it is RoomState.Connected }
            val bobOnAlice = alice.participants.first { it.isNotEmpty() }.single()

            val display = FakeTrack("video")
            val sound = FakeTrack("audio")
            bob.setScreen(display, sound)
            val shared = bobOnAlice.screen.first { it != null }!!
            assertEquals("video", shared.kind)
            val screenSound = bobOnAlice.screenAudio.first { it != null }!!
            assertEquals("audio", screenSound.kind)
            assertTrue(bobOnAlice.audio.value !== screenSound, "the screen's sound is not mistaken for the microphone")
            assertNull(bobOnAlice.video.value, "the screen is not mistaken for a camera")
            val bobPc = bobFactory.created.single()
            while (bobPc.getTransceivers().firstOrNull { it.mid == "s" }?.sender?.track !== display) kotlinx.coroutines.delay(20)
            assertEquals(krtc.core.RTCRtpTransceiverDirection.SENDONLY, bobPc.getTransceivers().single { it.mid == "s" }.direction)

            bob.setScreen(null)
            bobOnAlice.screen.first { it == null }
            bobOnAlice.screenAudio.first { it == null }
            alice.leave()
            bob.leave()
        }
    }

    @Test
    fun failedConnectionTriggersIceRestartWithFreshCredentials(): Unit = runBlocking {
        withTimeout(15_000) {
            val factory = FakeFactory()
            val client = RoomClient(url, factory)
            client.join("ice", "Carol", microphone = FakeTrack("audio"))
            client.state.first { it is RoomState.Connected }
            val pc = factory.created.single()
            val firstUfrag = waitForUfrag(pc) { true }

            pc.state.value = krtc.core.RTCPeerConnectionState.FAILED // e.g. the network changed
            val restartedUfrag = waitForUfrag(pc) { it != firstUfrag }
            assertTrue(restartedUfrag != firstUfrag, "the new offer carries fresh ICE credentials")
            pc.state.value = krtc.core.RTCPeerConnectionState.CONNECTED
            client.leave()
        }
    }

    private suspend fun waitForUfrag(pc: FakePeerConnection, accept: (String) -> Boolean): String {
        while (true) {
            val ufrag = pc.remoteDescription?.sdp?.lineSequence()?.firstOrNull { it.startsWith("a=ice-ufrag:") }?.removePrefix("a=ice-ufrag:")
            if (ufrag != null && accept(ufrag)) return ufrag
            kotlinx.coroutines.delay(20)
        }
    }
}
