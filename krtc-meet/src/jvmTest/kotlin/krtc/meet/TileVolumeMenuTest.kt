package krtc.meet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TileVolumeMenuTest {
    @Test
    fun sliderWorksAfterPointerLeavesTile(): Unit = runBlocking(Dispatchers.Default) {
        val dir = File("build/ui-snapshots").also { it.mkdirs() }
        for (screen in listOf(true, false)) {
            var volume by mutableStateOf(1f)
            var open by mutableStateOf(false)
            val scene = ImageComposeScene(600, 400, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = MeetColors.scheme) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(16.dp)) {
                            // Short tile places the popup's slider below the tile, making hover loss unavoidable.
                            Tile(null, "alice", false, Modifier.width(320.dp).height(80.dp), controlsOpen = open,
                                actions = { TileVolumeMenu(volume, screen, true, open, { open = it }) { volume = it } })
                        }
                    }
                }
            }
            var tick = 0L
            suspend fun settle(name: String? = null) {
                repeat(12) {
                    delay(25)
                    tick += 25_000_000
                    val frame = scene.render(tick)
                    if (it == 11 && name != null) File(dir, "volume-${if (screen) "stream" else "voice"}-$name.png")
                        .writeBytes(frame.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
            fun move(x: Float, y: Float) = scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            fun click(x: Float, y: Float) {
                move(x, y)
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            }
            try {
                settle()
                move(310f, 40f)
                settle()
                click(310f, 40f)
                settle("open")
                assertTrue(open, "volume button opens the menu")
                move(500f, 114f) // outside the tile, inside the popup's slider
                settle("outside")
                click(500f, 114f)
                settle("changed")
                assertTrue(volume > 1.5f, "slider remains usable outside tile (screen=$screen), volume=$volume")
                scene.sendPointerEvent(PointerEventType.Press, Offset(450f, 120f))
                for (x in 440 downTo 330 step 10) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(x.toFloat(), 120f), buttons = PointerButtons(isPrimaryPressed = true))
                    delay(20)
                    tick += 20_000_000
                    scene.render(tick)
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(330f, 120f))
                settle("dragged")
                assertTrue(volume < 1f, "dragging the slider lowers the volume (screen=$screen), volume=$volume")
                click(550f, 300f)
                settle()
                assertTrue(!open, "clicking outside dismisses the volume menu")
            } finally {
                scene.close()
            }
        }
    }
}
