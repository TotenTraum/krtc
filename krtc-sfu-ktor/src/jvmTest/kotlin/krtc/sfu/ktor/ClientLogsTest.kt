package krtc.sfu.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientLogsTest {
    @Test
    fun datesAndTimes() {
        assertEquals("1970-01-01", isoDate(0))
        assertEquals("2026-11-13", isoDate(1_794_533_433_510))
        assertEquals("2026-11-13T01:30:33.510Z", isoTime(1_794_533_433_510))
        assertEquals("2024-02-29", isoDate(1_709_164_800_000))
        assertEquals("1969-12-31T23:59:59.999Z", isoTime(-1))
    }

    @Test
    fun parsing() {
        val b = parseClientLogBatch("""{"client":"alice-1","app":"a","platform":"p","entries":[{"t":5,"l":"W","g":"call","m":"hi"},{"x":1}]}""")!!
        assertEquals("alice-1", b.client)
        assertEquals(1, b.entries.size)
        assertEquals("W", b.entries.single().level)
        assertNull(parseClientLogBatch("""{"client":"../etc","entries":[]}"""), "a path is not a client id")
        assertNull(parseClientLogBatch("not json"))
    }

    @Test
    fun storesBatchesInFilesAndLimitsRequests(): Unit = runBlocking {
        val dir = Files.createTempDirectory("client-logs").toFile()
        val server = embeddedServer(CIO, port = 0) {
            routing { krtcClientLogs(FileClientLogStore(dir.absolutePath), requestsPerMinute = 2, maxBodyBytes = 10_000) }
        }.also { it.start(wait = false) }
        try {
            val url = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}/logs"
            HttpClient(io.ktor.client.engine.cio.CIO).use { http ->
                val batch = """{"client":"bob","app":"krtc-meet","platform":"test","entries":[{"t":1794533433510,"l":"I","g":"call","m":"one\ntwo"}]}"""
                assertEquals(HttpStatusCode.NoContent, http.post(url) { setBody(batch) }.status)
                assertEquals(HttpStatusCode.BadRequest, http.post(url) { setBody("{}") }.status)
                // Too large: refused before it counts against the limit.
                assertEquals(HttpStatusCode.PayloadTooLarge, http.post(url) { setBody("x".repeat(20_000)) }.status)
                // Two requests a minute from this address: the third is refused.
                assertEquals(HttpStatusCode.TooManyRequests, http.post(url) { setBody(batch) }.status)
            }
            val file = dir.walk().single { it.isFile }
            assertEquals("bob.log", file.name)
            val text = file.readText()
            assertTrue(text.startsWith("# krtc-meet on test\n"), text)
            assertTrue("2026-11-13T01:30:33.510Z I [call] one\n    two\n" in text, text)
        } finally {
            server.stop(100, 500)
            dir.deleteRecursively()
        }
    }
}
