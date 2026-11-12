package krtc.meet

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Two short notes per cue, synthesized: rising for "on" events, falling for "off" ones. */
private fun notes(cue: Cue): List<Int> = when (cue) {
    Cue.JOIN -> listOf(660, 880)
    Cue.LEAVE -> listOf(880, 660)
    Cue.UNMUTE -> listOf(784)
    Cue.MUTE -> listOf(523)
    Cue.UNDEAFEN -> listOf(523, 784)
    Cue.DEAFEN -> listOf(784, 523)
}

actual fun playCue(cue: Cue) {
    thread(isDaemon = true, name = "krtc-meet-cue") {
        runCatching {
            val rate = 44_100
            val noteSamples = rate * 90 / 1000
            val pcm = ByteArray(notes(cue).size * noteSamples * 2)
            notes(cue).forEachIndexed { n, hz ->
                for (i in 0 until noteSamples) {
                    // A quiet sine with a short fade in and out, so it does not click.
                    val envelope = minOf(1.0, i / 200.0, (noteSamples - i) / 600.0)
                    val v = (sin(2 * PI * hz * i / rate) * envelope * 5_000).toInt()
                    val at = (n * noteSamples + i) * 2
                    pcm[at] = v.toByte()
                    pcm[at + 1] = (v shr 8).toByte()
                }
            }
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            AudioSystem.getSourceDataLine(format).use { line ->
                line.open(format)
                line.start()
                line.write(pcm, 0, pcm.size)
                line.drain()
            }
        }
    }
}
