package krtc.meet

import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory
import krtc.native.nativeScreenSources
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Measures how fast the real screen and windows are captured (skipped without Screen Recording permission). */
class CaptureProbe {
    /** What a viewer's frame path costs at 1080p60: frames delivered and the time to copy each into a bitmap. */
    @Test
    fun renderPath() {
        if (System.getenv("KRTC_CAPTURE_PROBE") != "1") return
        NativePeerConnectionFactory(AudioDevice.SYNTHETIC).use { f ->
            // Like a capture: 1080p ARGB frames pushed at 60 fps (converted to I420 like PushBgra).
            val source = f.createScreencastSource()
            val pixels = Array(4) { k -> IntArray(1920 * 1080) { i -> 0xFF000000.toInt() or ((i * 2654435761L + k * 977).toInt() and 0xFFFFFF) } }
            val pushing = java.util.concurrent.atomic.AtomicBoolean(true)
            var pushNanos = 0L
            var pushes = 0
            val pusher = kotlin.concurrent.thread {
                var n = 0
                var next = System.nanoTime()
                while (pushing.get()) {
                    val t0 = System.nanoTime()
                    source.pushArgb(1920, 1080, pixels[n++ % 4])
                    pushNanos += System.nanoTime() - t0
                    pushes++
                    next += 16_666_667
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
                }
            }
            val track = f.createVideoTrack(source, "probe")
            val exchange = FrameExchange()
            var frames = 0
            var nanos = 0L
            var worst = 0L
            track.setFrameListener { frame ->
                val t0 = System.nanoTime()
                exchange.onFrame(frame)
                val dt = System.nanoTime() - t0
                nanos += dt
                worst = maxOf(worst, dt)
                frames++
            }
            Thread.sleep(1_000)
            frames = 0; nanos = 0; worst = 0
            val counted0 = track.videoStats().frames
            Thread.sleep(4_000)
            val counted = (track.videoStats().frames - counted0) / 4
            println("probe: render path 1080p: source ${counted} fps, ${frames / 4} fps delivered, bitmap write avg ${nanos / maxOf(1, frames) / 1000} us, worst ${worst / 1000} us")
            var empty = 0
            track.setFrameListener { empty++ }
            Thread.sleep(1_000)
            empty = 0
            Thread.sleep(4_000)
            println("probe: with an empty listener: ${empty / 4} fps delivered")
            println("probe: capture-side conversion (1080p ARGB -> I420): avg ${pushNanos / maxOf(1, pushes) / 1000} us")
            pushing.set(false)
            pusher.join()
            track.setFrameListener(null)
            track.release()
            source.close()
        }
    }

    @Test
    fun captureRate() {
        org.junit.Assume.assumeTrue("set KRTC_CAPTURE_PROBE=1 for live capture checks", System.getenv("KRTC_CAPTURE_PROBE") == "1")
        val sources = nativeScreenSources()
        org.junit.Assume.assumeTrue("Screen Recording permission is required for the process running Gradle", sources.isNotEmpty())
        val displays = sources.filter { !it.isWindow }
        assertTrue(displays.isNotEmpty(), "source enumeration must include displays, not only windows")
        val requestedApps = System.getenv("KRTC_CAPTURE_APPS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val requestedWindows = requestedApps.map { app ->
            assertNotNull(sources.firstOrNull { it.isWindow && it.title.contains(app, ignoreCase = true) }, "$app must be listed; open its window before running this probe")
        }
        println("probe: ${displays.size} displays, ${sources.count { it.isWindow }} windows")
        NativePeerConnectionFactory(AudioDevice.SYNTHETIC).use { f ->
            // Null tests the main-display sentinel as well as every real display ID returned by enumeration.
            for (s in listOf(null) + displays + (requestedWindows.ifEmpty { sources.filter { it.isWindow }.take(2) })) {
                val source = assertNotNull(f.createScreenSource(s, fps = 60, maxWidth = 1920, maxHeight = 1080), "capture starts for ${s ?: "main display"}")
                val track = f.createVideoTrack(source, "probe")
                val frames = java.util.concurrent.atomic.AtomicInteger()
                val first = java.util.concurrent.CountDownLatch(1)
                try {
                    track.setFrameListener { frames.incrementAndGet(); first.countDown() }
                    assertTrue(first.await(5, java.util.concurrent.TimeUnit.SECONDS), "frames arrive from ${s ?: "main display"}")
                    frames.set(0)
                    Thread.sleep(3_000)
                    assertTrue(frames.get() > 0, "capture keeps delivering frames from ${s ?: "main display"}")
                    val size = track.videoStats()
                    println("probe: ${s?.title ?: "main display"} -> ${frames.get() / 3} fps at ${size.width}x${size.height}")
                } finally {
                    track.setFrameListener(null)
                    track.release()
                    source.close()
                }
            }
        }
    }
}
