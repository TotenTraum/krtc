package krtc.meet

import krtc.core.RTCStats
import kotlinx.serialization.json.*
import kotlin.test.*

class ScreenDiagnosticsTest {
    private var time = 0L
    private val reports = mutableListOf<JsonObject>()
    private val monitor = ScreenDiagnostics({ time }) { _, report -> if (report["completion"]!!.jsonPrimitive.content != "collecting_post_context") reports += report }.apply {
        reset(mapOf("callId" to "test", "room" to "room", "selfId" to "publisher"))
    }
    private fun sample(fps: Double, capture: Double = 30.0) = monitor.sample("out", mapOf(
        "framesPerSecond" to fps,
        "qualityLimitationReason" to "bandwidth"), fps, true, capture)

    @Test fun sustainedDropIncludesBeforeAfterAndSuppressesDuplicates() {
        monitor.start("out", mapOf("attempt" to "1"), 30)
        repeat(6) { time += 2_000; sample(30.0); monitor.tick() }
        repeat(4) { time += 2_000; sample(2.0); monitor.tick() }
        assertTrue(reports.isEmpty(), "collect post-incident context before emitting")
        repeat(4) { time += 2_000; sample(30.0); monitor.tick() }
        val report = reports.single()
        assertEquals("bandwidth_limited_fps", report["reason"]!!.jsonPrimitive.content)
        assertTrue(report["before"]!!.jsonArray.size >= 6)
        assertEquals(4, report["after"]!!.jsonArray.size)
        assertEquals("publisher", report["context"]!!.jsonObject["selfId"]!!.jsonPrimitive.content)
        repeat(10) { time += 2_000; sample(2.0); monitor.tick() }
        assertEquals(1, reports.size, "cooldown avoids report storms")
    }

    @Test fun startupTimeoutWorksWithNoStatsAndFlushesOnExit() {
        monitor.start("out", mapOf("attempt" to "1"), 30)
        time = 15_000; monitor.tick()
        monitor.flush("call_ended")
        assertEquals("first_frame_timeout", reports.single()["reason"]!!.jsonPrimitive.content)
        assertEquals("call_ended", reports.single()["completion"]!!.jsonPrimitive.content)
    }

    @Test fun failedCaptureIsReportedOnceAndStoppedAttemptDoesNotTimeout() {
        monitor.start("out", emptyMap(), 30)
        monitor.failure("out", "capture_open_failed", "permission denied")
        time = 10_000; monitor.tick()
        time = 80_000; monitor.tick(); monitor.flush("exit")
        assertEquals(1, reports.size)
        assertEquals("capture_open_failed", reports.single()["reason"]!!.jsonPrimitive.content)
        monitor.start("next", emptyMap(), 30)
        monitor.stop("next")
        time = 100_000; monitor.tick(); monitor.flush("exit")
        assertEquals(1, reports.size)
    }

    @Test fun shortDipAndDetailModeDoNotTriggerFpsReport() {
        monitor.start("out", emptyMap(), 30)
        sample(30.0)
        time = 2_000; sample(2.0)
        time = 4_000; sample(30.0)
        monitor.stop("out")
        monitor.start("out", emptyMap(), 15)
        repeat(20) { time += 2_000; sample(1.0); monitor.tick() }
        monitor.flush("exit")
        assertTrue(reports.isEmpty())
    }

    @Test fun receiverLearnsBaselineAndReportsDecoderStall() {
        monitor.start("in", mapOf("publisherId" to "peer"))
        monitor.sample("in", mapOf("framesDecoded" to 100), 30.0, true)
        repeat(5) { time += 2_000; monitor.sample("in", mapOf("framesDecoded" to 100), 0.0, true); monitor.tick() }
        monitor.flush("exit")
        assertEquals("sustained_low_fps", reports.single()["reason"]!!.jsonPrimitive.content)
    }

    @Test fun staleReportedFpsCannotHideFrozenCounters() {
        monitor.start("out", emptyMap(), 30)
        repeat(6) {
            time += 2_000
            monitor.sample("out", mapOf("framesSent" to 100, "framesEncoded" to 100, "source.frames" to time), 30.0, true, 30.0)
            monitor.tick()
        }
        monitor.flush("exit")
        assertEquals("encoding_stall", reports.single()["reason"]!!.jsonPrimitive.content)
    }

    @Test fun provisionalReportExistsBeforePostContextCompletes() {
        val emitted = mutableListOf<JsonObject>()
        val recorder = ScreenDiagnostics({ time }) { _, report -> emitted += report }
        recorder.start("out", emptyMap(), 30)
        recorder.failure("out", "capture_open_failed")
        assertEquals("collecting_post_context", emitted.single()["completion"]!!.jsonPrimitive.content)
        recorder.flush("call_ended")
        assertEquals(2, emitted.size)
        assertEquals(emitted[0]["id"], emitted[1]["id"])
    }

    @Test fun reportStatsExcludeAddressesCertificatesAndUnrelatedFields() {
        val rtp = RTCStats("out", "outbound-rtp", 0.0, mapOf("ssrc" to 42, "framesEncoded" to 10, "transportId" to "transport", "secret" to "token"))
        val transport = RTCStats("transport", "transport", 0.0, mapOf("selectedCandidatePairId" to "pair"))
        val pair = RTCStats("pair", "candidate-pair", 0.0, mapOf("availableOutgoingBitrate" to 2_000_000, "localCandidateId" to "local"))
        val local = RTCStats("local", "local-candidate", 0.0, mapOf("address" to "192.0.2.1", "protocol" to "udp"))
        val selected = ScreenDiagnostics.stats(listOf(rtp, transport, pair, local).associateBy { it.id }, rtp)
        assertEquals(2_000_000, selected["network.availableOutgoingBitrate"])
        assertEquals("udp", selected["local.protocol"])
        assertFalse(selected.toString().contains("192.0.2.1"))
        assertFalse(selected.toString().contains("token"))
    }
}
