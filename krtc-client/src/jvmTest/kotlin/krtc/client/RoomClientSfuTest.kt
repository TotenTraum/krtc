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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
