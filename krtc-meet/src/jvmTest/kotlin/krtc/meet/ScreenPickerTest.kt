package krtc.meet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import krtc.native.ScreenSource
import krtc.native.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenPickerTest {
    @Test
    fun selectionDoesNotWaitForPreviewAndRefreshClearsIt(): Unit = runBlocking(Dispatchers.Default) {
        val allowPreview = CountDownLatch(1)
        val failedPreview = CountDownLatch(1)
        val listings = AtomicInteger()
        val closes = AtomicInteger()
        val platform = object : MediaPlatform by NativeMediaPlatform {
            override val settings = MemorySettings()
            override fun screens(): List<ScreenChoice> {
                val generation = listings.incrementAndGet()
                return listOf(
                    ScreenChoice("Moonlight", ScreenSource(generation * 10L, true, "Moonlight")),
                    ScreenChoice("Unavailable helper", ScreenSource(generation * 10L + 1, true, "Helper")),
                    ScreenChoice("Display", ScreenSource(generation * 10L + 2, false, "Display")),
                )
            }
            override fun screenThumbnail(choice: ScreenChoice, maxWidth: Int, maxHeight: Int): VideoFrame? {
                if (choice.label == "Unavailable helper") {
                    failedPreview.countDown()
                    return null
                }
                check(allowPreview.await(10, TimeUnit.SECONDS))
                return VideoFrame(64, 36, IntArray(64 * 36) { 0xff286d84.toInt() }, 0)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = MeetSession(scope, platform)
        val scene = ImageComposeScene(960, 800, density = Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme { ScreenPickerContent(session, live = false) { closes.incrementAndGet() } }
        }
        var tick = 0L
        suspend fun settle(snapshot: String? = null) {
            repeat(12) { delay(20); tick += 20_000_000; scene.render(tick) }
            if (snapshot != null) {
                val dir = File("build/ui-snapshots").also { it.mkdirs() }
                scene.render(tick).encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, "screen-picker-$snapshot.png").writeBytes(it) }
            }
        }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
        }
        try {
            settle()
            assertEquals(1, listings.get())
            assertEquals(0L, failedPreview.count, "a slow window must not block checking another window")
            click(100f, 180f) // a window is selectable while its preview is still loading
            settle("loading")
            click(780f, 664f)
            settle()
            assertEquals(1, closes.get(), "starting a share must not wait for a thumbnail")
            click(400f, 180f) // a missing thumbnail is not proof that the window cannot be shared
            settle("missing-preview")
            click(780f, 664f)
            settle()
            assertEquals(2, closes.get(), "a failed thumbnail must not disable the source")
            click(805f, 36f) // reload sources while the old thumbnail is still running
            settle("refreshed")
            assertEquals(2, listings.get())
            click(780f, 664f)
            settle()
            assertEquals(2, closes.get(), "refresh cannot start a stale selected window")
            allowPreview.countDown()
            settle("ready")
            click(100f, 180f)
            settle("selected")
            click(780f, 664f)
            settle()
            assertEquals(3, closes.get(), "the refreshed source remains selectable")
        } finally {
            allowPreview.countDown()
            scene.close()
            session.close()
            scope.cancel()
        }
    }
}
