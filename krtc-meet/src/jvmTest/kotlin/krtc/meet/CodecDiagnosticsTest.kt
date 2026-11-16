package krtc.meet

import krtc.core.RTCStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecDiagnosticsTest {
    private val offer = """
        v=0
        o=- 1 1 IN IP4 127.0.0.1
        s=-
        t=0 0
        m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103
        a=mid:s
        a=rtpmap:96 VP8/90000
        a=rtpmap:97 rtx/90000
        a=fmtp:97 apt=96
        a=rtpmap:102 H264/90000
        a=fmtp:102 profile-level-id=42e01f;packetization-mode=1
        a=rtpmap:103 rtx/90000
        a=fmtp:103 apt=102
    """.trimIndent()

    private fun report(implementation: String = "MediaFoundation H264", hardware: Boolean = true): Map<String, RTCStats> = mapOf(
        "codec" to RTCStats("codec", "codec", 0.0, mapOf("mimeType" to "video/H264", "payloadType" to 102,
            "sdpFmtpLine" to "profile-level-id=42e01f;packetization-mode=1")),
        "screen" to RTCStats("screen", "outbound-rtp", 0.0, mapOf("mid" to "s", "codecId" to "codec",
            "encoderImplementation" to implementation, "powerEfficientEncoder" to hardware)),
    )

    @Test
    fun actualSelectionComesFromStatsNotOfferOrder() {
        val report = report()
        val line = CodecDiagnostics().sample(report, offer, offer).single()
        assertTrue("selected=video/H264(pt=102" in line, line)
        assertTrue("offered=[video/VP8(pt=96), video/H264(pt=102" in line, line)
        assertTrue("packetization-mode=1" in line, line)
        assertTrue("implementation=MediaFoundation H264" in line && "powerEfficient=true" in line, line)
        assertTrue("rtx" !in line, "repair codecs must not be presented as encoder choices")
        assertEquals("video/H264(pt=102, fmtp=profile-level-id=42e01f;packetization-mode=1)", CodecDiagnostics.selected(report, report.getValue("screen")))
    }

    @Test
    fun softwareFallbackLoggedOnceEvenWhenCodecIsUnchanged() {
        val diagnostics = CodecDiagnostics()
        assertEquals(1, diagnostics.sample(report(), offer, offer).size)
        assertTrue(diagnostics.sample(report(), offer, offer).isEmpty())
        val line = diagnostics.sample(report("OpenH264", false), offer, offer).single()
        assertTrue(line.startsWith("codec changed:"), line)
        assertTrue("implementation=OpenH264 powerEfficient=false" in line, line)
        assertTrue("previous={" in line && "implementation=MediaFoundation H264" in line, line)
        assertTrue(diagnostics.sample(report("OpenH264", false), offer, offer).isEmpty())
    }

    @Test
    fun missingStatsDoNotGuessACodecAndReceiverIsIdentified() {
        val diagnostics = CodecDiagnostics()
        val missing = RTCStats("screen", "outbound-rtp", 0.0, mapOf("mid" to "s", "codecId" to "absent"))
        assertTrue(diagnostics.sample(mapOf("screen" to missing), offer, offer).isEmpty())
        assertEquals("unknown", CodecDiagnostics.selected(emptyMap(), missing))
        val incoming = report().toMutableMap()
        incoming["screen"] = RTCStats("screen", "inbound-rtp", 0.0,
            mapOf("mid" to "s", "codecId" to "codec", "decoderImplementation" to "FFmpeg"))
        val line = diagnostics.sample(incoming, null, null).single()
        assertTrue("direction=in" in line && "implementation=FFmpeg powerEfficient=unknown" in line, line)
        assertTrue("offered=unknown" in line, line)
    }
}
