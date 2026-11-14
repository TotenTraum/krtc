package krtc.meet

import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory
import krtc.native.nativeScreenSources
import kotlin.test.Test

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
        if (System.getenv("KRTC_CAPTURE_PROBE") != "1") return
        val sources = nativeScreenSources()
        println("probe: ${sources.size} sources: ${sources.take(8)}")
        NativePeerConnectionFactory(AudioDevice.SYNTHETIC).use { f ->
            for (s in sources.filter { !it.isWindow }.take(1) + sources.filter { it.isWindow && it.title.isNotBlank() }.take(2)) {
                val source = f.createScreenSource(s, fps = 60, maxWidth = 1920, maxHeight = 1080) ?: continue
                val track = f.createVideoTrack(source, "probe")
                var frames = 0
                var size = ""
                track.setFrameListener { frame -> frames++; size = "${frame.width}x${frame.height}" }
                Thread.sleep(1_000)
                frames = 0
                Thread.sleep(3_000)
                println("probe: $s -> ${frames / 3} fps at $size")
                track.setFrameListener(null)
                track.release()
                source.close()
            }
        }
    }
}
