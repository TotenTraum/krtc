package krtc.sfu

import krtc.signaling.ClientMessage
import krtc.signaling.PeerInfo
import krtc.signaling.ServerMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The host-facing API: admission policy, host ids, events, control, several instances, shutdown. */
class EmbeddingTest {
    private class Sink : SignalSink {
        val messages = mutableListOf<ServerMessage>()
        var closedWith: String? = null
        override fun send(message: ServerMessage) { messages += message }
        override fun close(reason: String) { closedWith = reason }
    }

    /** A host policy: token "t-<user>" admits <user> under that id; everyone else is denied. */
    private val tokenPolicy = JoinPolicy { request ->
        val token = request.credentials
        if (token != null && token.startsWith("t-")) JoinDecision.Allow(participantId = token.removePrefix("t-"))
        else JoinDecision.Deny("bad token")
    }

    @Test
    fun policyDeniesAndAssignsHostIds() = runTest {
        Sfu(SfuConfig(udpPort = 0, shards = 1, joinPolicy = tokenPolicy)).use { sfu ->
            assertFailsWith<JoinDeniedException> { sfu.join(JoinRequest("r", "x", credentials = "nope"), Sink()) }
            val alice = sfu.join(JoinRequest("r", "Alice", credentials = "t-alice"), Sink())
            assertEquals("alice", alice.id)
            assertEquals("r", alice.roomName)
            assertEquals(listOf("alice"), sfu.room("r")!!.participants.map { it.id })
        }
    }

    @Test
    fun mediaStateReachesTheRoom() = runTest {
        Sfu(SfuConfig(udpPort = 0, shards = 1)).use { sfu ->
            val aSink = Sink()
            val bSink = Sink()
            val a = sfu.join(JoinRequest("r", "a"), aSink)
            val b = sfu.join(JoinRequest("r", "b"), bSink)
            sfu.onMessage(a, ClientMessage.MediaState(audioMuted = true, videoMuted = false))
            assertEquals(ServerMessage.PeerState(a.id, audioMuted = true, videoMuted = false), bSink.messages.filterIsInstance<ServerMessage.PeerState>().last())
            assertEquals(ServerMessage.PeerState(a.id, audioMuted = true, videoMuted = false), aSink.messages.filterIsInstance<ServerMessage.PeerState>().last())
            assertTrue(sfu.participant(a.id)!!.audioMutedByClient)

            // The host's mute is announced too, and a newcomer learns the state in its welcome.
            sfu.setMuted(b.id, video = true)
            assertEquals(ServerMessage.PeerState(b.id, audioMuted = false, videoMuted = true), aSink.messages.filterIsInstance<ServerMessage.PeerState>().last())
            val cSink = Sink()
            sfu.join(JoinRequest("r", "c"), cSink)
            val welcome = cSink.messages.filterIsInstance<ServerMessage.Welcome>().single()
            assertEquals(setOf(PeerInfo(a.id, "a", audioMuted = true), PeerInfo(b.id, "b", videoMuted = true)), welcome.peers.toSet())
        }
    }

    @Test
    fun newerSessionWithSameIdReplacesOlder() = runTest {
        Sfu(SfuConfig(udpPort = 0, shards = 1, joinPolicy = tokenPolicy)).use { sfu ->
            val first = Sink()
            sfu.join(JoinRequest("r", "Alice", credentials = "t-alice"), first)
            val second = Sink()
            sfu.join(JoinRequest("r", "Alice", credentials = "t-alice"), second)
            assertEquals(ServerMessage.Bye("replaced by a newer session"), first.messages.last())
            assertEquals("replaced by a newer session", first.closedWith)
            assertNull(second.closedWith)
            assertEquals(1, sfu.room("r")!!.participants.size)
        }
    }

    @Test
    fun eventsKickAndMute() = runTest {
        Sfu(SfuConfig(udpPort = 0, shards = 1)).use { sfu ->
            val events = mutableListOf<SfuEvent>()
            val collector = launch { sfu.events.collect { events += it } }
            yield()
            val a = sfu.join(JoinRequest("room", "a"), Sink())
            val bSink = Sink()
            val b = sfu.join(JoinRequest("room", "b"), bSink)

            assertTrue(sfu.setMuted(a.id, audio = true, video = true))
            sfu.participant(a.id)!!.let { assertTrue(it.audioMuted && it.videoMuted) }
            assertFalse(sfu.setMuted("nobody", audio = true))

            assertTrue(sfu.kick(b.id, "rule violation"))
            assertEquals(ServerMessage.Bye("rule violation"), bSink.messages.last())
            assertEquals("rule violation", bSink.closedWith)
            sfu.leave(b) // the host's transport closes afterwards: harmless
            sfu.leave(a)
            yield()
            collector.cancel()

            assertEquals(
                listOf(
                    SfuEvent.RoomOpened("room"),
                    SfuEvent.ParticipantJoined("room", a.id, "a"),
                    SfuEvent.ParticipantJoined("room", b.id, "b"),
                    SfuEvent.MediaStateChanged("room", a.id, audioMuted = true, videoMuted = true),
                    SfuEvent.ParticipantLeft("room", b.id, "rule violation"),
                    SfuEvent.ParticipantLeft("room", a.id, "left"),
                    SfuEvent.RoomClosed("room"),
                ),
                events,
            )
            assertTrue(sfu.rooms().isEmpty())
        }
    }

    @Test
    fun severalInstancesInOneProcessAndShutdown() = runTest {
        val one = Sfu(SfuConfig(udpPort = 0, shards = 1))
        val two = Sfu(SfuConfig(udpPort = 0, shards = 1))
        assertNotEquals(one.udpPort, two.udpPort)
        val sink = Sink()
        one.join(JoinRequest("same-name", "x"), sink)
        two.join(JoinRequest("same-name", "y"), Sink())
        assertEquals(1, one.metrics().participants)
        assertEquals(1, two.metrics().participants)

        one.close()
        assertEquals("server shutdown", sink.closedWith)
        assertEquals(0, one.metrics().participants)
        assertEquals(1, two.metrics().participants)
        one.close() // idempotent
        assertFailsWith<IllegalStateException> { one.join(JoinRequest("r", "z"), Sink()) }
        two.close()
    }
}
