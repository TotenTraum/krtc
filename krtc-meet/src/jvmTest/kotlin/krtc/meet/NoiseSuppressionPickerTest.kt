package krtc.meet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class NoiseSuppressionPickerTest {
    @Test
    fun selectsOneModeOnDesktopAndPhone(): Unit = runBlocking(Dispatchers.Default) {
        val dir = File("build/ui-snapshots").also { it.mkdirs() }
        for (width in listOf(480, 300)) {
            var selected by mutableStateOf(NoiseSuppressionMode.STANDARD)
            val scene = ImageComposeScene(width, 330, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = MeetColors.scheme) {
                    Surface(Modifier.fillMaxSize(), color = MeetColors.Panel) {
                        Column(Modifier.padding(16.dp)) {
                            NoiseSuppressionPicker(selected) { selected = it }
                        }
                    }
                }
            }
            try {
                var tick = 0L
                suspend fun render(): org.jetbrains.skia.Image {
                    delay(30)
                    tick += 30_000_000
                    return scene.render(tick)
                }
                render()
                // Click the whole row, away from the radio itself: strong, off, then standard.
                for ((y, mode) in listOf(176f to NoiseSuppressionMode.STRONG, 70f to NoiseSuppressionMode.OFF, 130f to NoiseSuppressionMode.STANDARD)) {
                    val point = Offset(width - 32f, y)
                    scene.sendPointerEvent(PointerEventType.Move, point)
                    scene.sendPointerEvent(PointerEventType.Press, point)
                    scene.sendPointerEvent(PointerEventType.Release, point)
                    scene.sendPointerEvent(PointerEventType.Move, Offset(width - 1f, 329f))
                    repeat(15) { render() } // finish the radio/ripple animations before the snapshot
                    val image = render()
                    assertEquals(mode, selected, "whole-row selection at width $width")
                    File(dir, "noise-$width-${mode.name.lowercase()}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            } finally {
                scene.close()
            }
        }
    }
}
