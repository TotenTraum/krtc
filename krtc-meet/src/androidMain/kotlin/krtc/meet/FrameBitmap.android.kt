package krtc.meet

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import krtc.native.VideoFrame

/** An ARGB_8888 bitmap: it takes the frame's 0xAARRGGBB ints as they are. */
actual class FrameBitmap actual constructor() {
    private var bitmap: Bitmap? = null
    private var composeImage: ImageBitmap? = null
    actual val image: ImageBitmap? get() = composeImage

    actual fun write(frame: VideoFrame) {
        var b = bitmap
        if (b == null || b.width != frame.width || b.height != frame.height) {
            b = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            bitmap = b
            composeImage = b.asImageBitmap()
        }
        b.setPixels(frame.argb, 0, frame.width, 0, 0, frame.width, frame.height)
    }
}
