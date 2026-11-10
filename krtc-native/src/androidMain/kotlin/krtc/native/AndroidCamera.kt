package krtc.native

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import kotlin.math.abs

/**
 * Android camera capture into a [NativeVideoSource] with Camera2: YUV_420_888 images are copied to I420 and
 * pushed with the sensor rotation (receivers rotate). Needs the CAMERA permission (granted at runtime).
 *
 *     val source = factory.createVideoSource()
 *     val camera = AndroidCamera(context, source).apply { start() }
 *     client.join(room, name, camera = factory.createVideoTrack(source))
 */
public class AndroidCamera(
    context: Context,
    private val source: NativeVideoSource,
    /** Camera2 id; null picks the first front-facing camera, else the first one. */
    cameraId: String? = null,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 30,
) : AutoCloseable {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val id: String = cameraId ?: manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    } ?: manager.cameraIdList.first()
    private val thread = HandlerThread("krtc-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val rotation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    // I420 planes, reused while the size stays the same.
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    /** Opens the camera and starts pushing frames (asynchronously, on the camera thread). */
    @SuppressLint("MissingPermission") // the caller is responsible for the CAMERA permission
    public fun start() {
        val size = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { abs(it.width - width) + abs(it.height - height) }
            ?: error("camera $id has no YUV output")
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use(::push) }, handler)
        reader = imageReader
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                @Suppress("DEPRECATION") // the SessionConfiguration variant needs API 28; minSdk is 24
                camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps / 2, fps))
                        }
                        s.setRepeatingRequest(request.build(), null, handler)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = close()
                }, handler)
            }

            override fun onDisconnected(camera: CameraDevice) = camera.close()
            override fun onError(camera: CameraDevice, error: Int) = camera.close()
        }, handler)
    }

    private fun push(image: Image) {
        val w = image.width
        val h = image.height
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        if (y.size != w * h) {
            y = ByteArray(w * h)
            u = ByteArray(cw * ch)
            v = ByteArray(cw * ch)
        }
        val planes = image.planes
        copyPlane(planes[0], w, h, y)
        copyPlane(planes[1], cw, ch, u)
        copyPlane(planes[2], cw, ch, v)
        source.pushI420(w, h, y, w, u, cw, v, cw, rotation, image.timestamp / 1000)
    }

    /** Copies one plane into a tightly packed array, whatever its row and pixel strides. */
    private fun copyPlane(plane: Image.Plane, w: Int, h: Int, out: ByteArray) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == w) {
            buf.position(0)
            buf.get(out, 0, minOf(out.size, buf.remaining()))
            return
        }
        val row = ByteArray(rowStride)
        for (r in 0 until h) {
            buf.position(r * rowStride)
            val n = minOf(rowStride, buf.remaining())
            buf.get(row, 0, n)
            if (pixelStride == 1) row.copyInto(out, r * w, 0, w) else for (c in 0 until w) out[r * w + c] = row[c * pixelStride]
        }
    }

    override fun close() {
        session?.close()
        device?.close()
        reader?.close()
        session = null
        device = null
        reader = null
        thread.quitSafely()
    }
}
