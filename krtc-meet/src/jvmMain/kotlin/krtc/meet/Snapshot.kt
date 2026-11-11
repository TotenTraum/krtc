package krtc.meet

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.concurrent.thread

/**
 * Test aid: renders [content] (the same UI and session as the window) off screen into a PNG. The window itself is
 * drawn by Skia on the GPU, past Swing, and capturing the screen would need the Screen Recording permission.
 * Rendered over a second, so video tiles have taken their frames.
 */
internal object Snapshot {
    private var scheduled = false

    fun schedule(width: Int, height: Int, file: String, afterSeconds: Long, content: @Composable () -> Unit) {
        if (scheduled) return
        scheduled = true
        thread(isDaemon = true, name = "krtc-meet-snapshot") {
            Thread.sleep(afterSeconds * 1000)
            ImageComposeScene(width, height, Density(1f), content = content).use { scene ->
                var image = scene.render(0)
                for (i in 1..30) {
                    Thread.sleep(33)
                    image = scene.render(i * 33_000_000L)
                }
                File(file).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            println("snapshot written to $file")
        }
    }
}
