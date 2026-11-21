package krtc.meet

import java.io.File
import java.nio.file.Files
import krtc.meet.log.ClientLog
import kotlin.test.*

class ClientLogTest {
    @BeforeTest fun reset() {
        ClientLog.setMinimumLevel(ClientLog.Level.OFF)
        ClientLog.setMinimumLevel(ClientLog.Level.INFO)
    }

    @AfterTest fun restore() = reset()

    @Test fun minimumLevelFiltersBothFileAndQueueAndAvoidsLazyMessages() {
        val marker = "level-test-" + System.nanoTime()
        var built = false
        ClientLog.d(marker) { built = true; "debug payload" }
        assertFalse(built)
        ClientLog.i(marker, "ordinary entry")
        ClientLog.e(marker, "error entry")
        val entries = ClientLog.take(100)
        assertEquals(listOf(ClientLog.Level.INFO, ClientLog.Level.ERROR), entries.map { it.level })
        val text = File(logLocation()!!).readText()
        assertTrue("I [$marker] ordinary entry" in text)
        assertFalse("D [$marker]" in text)
    }

    @Test fun reducedVerbosityAlsoFiltersRetriesAndOffClearsTheQueue() {
        ClientLog.setMinimumLevel(ClientLog.Level.DEBUG)
        ClientLog.d("test", "debug")
        ClientLog.e("test", "error")
        val retry = ClientLog.take(100)
        ClientLog.i("test", "pending info")
        ClientLog.setMinimumLevel(ClientLog.Level.ERROR)
        ClientLog.putBack(retry)
        assertEquals(listOf("error"), ClientLog.take(100).map { it.message })
        ClientLog.setMinimumLevel(ClientLog.Level.OFF)
        ClientLog.e("test", "disabled")
        assertEquals(0, ClientLog.waiting.value)
        assertEquals(0, ClientLog.queuedStorageBytes)
    }

    @Test fun largeReportsAndRetriesRespectTheUploadMemoryBudget() {
        val payload = "Ж".repeat(ClientLog.MAX_MESSAGE_CHARS)
        repeat(40) { ClientLog.w("report", "$it $payload") }
        assertTrue(ClientLog.queuedStorageBytes <= ClientLog.MAX_PENDING_BYTES)
        val retry = ClientLog.take(Int.MAX_VALUE)
        assertTrue(retry.size < 40)
        assertEquals(0, ClientLog.queuedStorageBytes)
        repeat(30) { ClientLog.w("report", "new $it $payload") }
        ClientLog.putBack(retry)
        assertTrue(ClientLog.queuedStorageBytes <= ClientLog.MAX_PENDING_BYTES)
        assertTrue(ClientLog.take(Int.MAX_VALUE).last().message.startsWith("new 29 "))
        assertEquals(0, ClientLog.queuedStorageBytes)
    }

    @Test fun logRotationRunsDuringWritesAndReplacesExistingPreviousFile() {
        val dir = Files.createTempDirectory("krtc-rotation").toFile()
        try {
            val file = File(dir, "krtc-meet.log")
            val previous = File(dir, "krtc-meet.previous.log")
            val line = "Ж".repeat(20) + "\n" // 41 UTF-8 bytes
            previous.writeText("an older journal")
            repeat(8) {
                appendRotatingLog(file, line, 100)
                assertTrue(file.length() <= 100)
                assertTrue(previous.length() <= 100)
            }
            assertEquals(line.repeat(2), file.readText())
            assertEquals(line.repeat(2), previous.readText())
            assertEquals(2, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
