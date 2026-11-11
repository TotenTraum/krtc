package krtc.native

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Android screen capture with MediaProjection into a screencast [NativeVideoSource]
 * ([NativePeerConnectionFactory.createScreencastSource]): the display is mirrored into an RGBA ImageReader, scaled to
 * fit [maxWidth] × [maxHeight], and pushed at up to [fps].
 *
 * The user's consent comes first: start [consentIntent] with `startActivityForResult` and pass the result here.
 * Android 10+ requires a running foreground service of type mediaProjection; [start] starts the library's own
 * [ScreenCaptureService] (declared in its manifest, with a notification) and stops it in [close].
 *
 *     startActivityForResult(AndroidScreenCapture.consentIntent(this), REQUEST)
 *     // onActivityResult:
 *     val source = factory.createScreencastSource()
 *     val capture = AndroidScreenCapture(this, resultCode, data!!, source).also { it.start() }
 *     client.setScreen(factory.createVideoTrack(source, "screen"))
 */
public class AndroidScreenCapture(
    context: Context,
    private val resultCode: Int,
    private val resultData: Intent,
    private val source: NativeVideoSource,
    private val maxWidth: Int = 1920,
    private val maxHeight: Int = 1080,
    private val fps: Int = 15,
) : AutoCloseable {
    private val context = context.applicationContext
    private val thread = HandlerThread("krtc-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lastFrameNanos = 0L

    /** Starts the foreground service (Android 10+) and the projection; frames arrive on a thread of its own. */
    public suspend fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val started = CompletableDeferred<Unit>()
            ScreenCaptureService.started = started
            context.startForegroundService(Intent(context, ScreenCaptureService::class.java))
            withTimeout(5_000) { started.await() } // the projection may only be created once the service is foreground
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = manager.getMediaProjection(resultCode, resultData) ?: error("no media projection (consent denied?)")
        projection = p
        // Android 14+: a callback must be registered before the virtual display is created.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = close()
        }, handler)

        val metrics = context.resources.displayMetrics
        val scale = minOf(1.0, maxWidth.toDouble() / metrics.widthPixels, maxHeight.toDouble() / metrics.heightPixels)
            .let { if (metrics.widthPixels < metrics.heightPixels) minOf(1.0, maxHeight.toDouble() / metrics.widthPixels, maxWidth.toDouble() / metrics.heightPixels) else it }
        val width = (metrics.widthPixels * scale).toInt() and 1.inv()
        val height = (metrics.heightPixels * scale).toInt() and 1.inv()
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        val periodNanos = 1_000_000_000L / fps
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                if (it.timestamp - lastFrameNanos < periodNanos) return@use
                lastFrameNanos = it.timestamp
                val plane = it.planes[0]
                source.pushRgba(it.width, it.height, plane.buffer, plane.rowStride, 0, it.timestamp / 1000)
            }
        }, handler)
        display = p.createVirtualDisplay("krtc-screen", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
    }

    override fun close() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread.quitSafely()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.stopService(Intent(context, ScreenCaptureService::class.java))
    }

    public companion object {
        /** The system consent dialog; its result goes to the constructor. */
        public fun consentIntent(activity: Activity): Intent =
            (activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()
    }
}

/** The foreground service screen capture needs on Android 10+ (started and stopped by [AndroidScreenCapture]). */
public class ScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setContentTitle("Sharing the screen")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        started?.complete(Unit)
        return START_NOT_STICKY
    }

    internal companion object {
        const val CHANNEL = "krtc-screen"
        const val NOTIFICATION_ID = 0x6b72
        @Volatile var started: CompletableDeferred<Unit>? = null
    }
}
