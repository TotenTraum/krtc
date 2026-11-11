package krtc.meet

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import krtc.native.VideoFrame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Skia bitmap in BGRA_8888, which is the memory layout of 0xAARRGGBB ints on little-endian machines: the frame's
 * ints are copied in as they are.
 */
actual class FrameBitmap actual constructor() {
    private var bitmap: Bitmap? = null
    private var bytes = ByteArray(0)
    private var info: ImageInfo? = null
    private var composeImage: ImageBitmap? = null
    actual val image: ImageBitmap? get() = composeImage

    actual fun write(frame: VideoFrame) {
        val n = frame.width * frame.height
        if (bitmap == null || info?.width != frame.width || info?.height != frame.height) {
            bitmap?.close()
            val i = ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val b = Bitmap()
            b.allocPixels(i)
            bitmap = b
            info = i
            bytes = ByteArray(n * 4)
            composeImage = b.asComposeImageBitmap()
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(frame.argb, 0, n)
        bitmap!!.installPixels(info!!, bytes, frame.width * 4)
    }
}
