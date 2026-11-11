package krtc.meet

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import krtc.native.VideoFrame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.posix.memcpy

/** Skia bitmap in BGRA_8888: the memory layout of 0xAARRGGBB ints on little-endian machines, copied in as is. */
@OptIn(ExperimentalForeignApi::class)
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
        bytes.usePinned { dst -> frame.argb.usePinned { src -> memcpy(dst.addressOf(0), src.addressOf(0), (n * 4).convert()) } }
        bitmap!!.installPixels(info!!, bytes, frame.width * 4)
    }
}
