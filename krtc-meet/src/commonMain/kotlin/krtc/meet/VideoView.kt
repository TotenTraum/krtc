package krtc.meet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import krtc.core.MediaStreamTrack
import krtc.native.NativeTrack
import krtc.native.VideoFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** A bitmap Compose can draw, refilled from ARGB frames (platform: Skia on desktop and iOS, android.graphics on Android). */
expect class FrameBitmap() {
    /** Copies one frame in; called on libwebrtc's thread, never while this bitmap is on screen. */
    fun write(frame: VideoFrame)
    val image: ImageBitmap?
}

/**
 * Triple buffering between libwebrtc's frame thread and Compose: the writer fills [back], publishes it as [ready];
 * the screen takes [ready] as [front]. The writer never touches the bitmap being drawn, and only the newest frame
 * is shown (older ones are dropped, so a slow screen does not queue up latency).
 */
internal class FrameExchange {
    private val lock = SynchronizedObject()
    private var back = FrameBitmap()
    private var ready = FrameBitmap()
    private var front = FrameBitmap()
    private var fresh = false

    fun onFrame(frame: VideoFrame) {
        back.write(frame)
        synchronized(lock) {
            val t = ready
            ready = back
            back = t
            fresh = true
        }
    }

    /** The newest frame not shown yet, or null. */
    fun take(): ImageBitmap? = synchronized(lock) {
        if (!fresh) return null
        val t = front
        front = ready
        ready = t
        fresh = false
        front.image
    }
}

/** Shows a video track (local or remote); black when there is none or no frame yet. */
@Composable
fun VideoView(track: MediaStreamTrack?, modifier: Modifier = Modifier, mirror: Boolean = false, contentScale: ContentScale = ContentScale.Crop) {
    val native = track as? NativeTrack
    val exchange = remember(native) { FrameExchange() }
    var image by remember(native) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(native) {
        native?.setFrameListener(exchange::onFrame)
        onDispose { native?.setFrameListener(null) }
    }
    LaunchedEffect(exchange) {
        while (true) withFrameNanos { exchange.take()?.let { image = it } }
    }
    Box(modifier.background(Color.Black)) {
        image?.let {
            Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize().graphicsLayer { if (mirror) scaleX = -1f })
        }
    }
}
