package krtc.native

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread

/**
 * Android microphone and speaker for a factory with [AudioDevice.SYNTHETIC] audio: AudioRecord (voice
 * communication source, with the platform echo canceller and noise suppressor where available) feeds
 * [NativePeerConnectionFactory.pushAudio], and AudioTrack plays [NativePeerConnectionFactory.pullAudio].
 * 48 kHz mono, 10 ms blocks. Needs the RECORD_AUDIO permission.
 */
public class AndroidAudio(private val factory: NativePeerConnectionFactory) : AutoCloseable {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val effects = ArrayList<android.media.audiofx.AudioEffect>()
    private val threads = ArrayList<Thread>()

    @SuppressLint("MissingPermission") // the caller is responsible for RECORD_AUDIO
    public fun start(microphone: Boolean = true, speaker: Boolean = true) {
        require(factory.audioDevice == AudioDevice.SYNTHETIC) { "AndroidAudio drives a factory with synthetic audio" }
        check(!running) { "already started" }
        running = true
        if (microphone) {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, BLOCK * 2 * 4))
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true; effects += it }
            record = r
            r.startRecording()
            threads += thread(name = "krtc-mic") {
                val block = ShortArray(BLOCK)
                while (running) {
                    val n = r.read(block, 0, BLOCK)
                    if (n > 0) factory.pushAudio(if (n == BLOCK) block else block.copyOf(n))
                }
            }
        }
        if (speaker) {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, BLOCK * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
            threads += thread(name = "krtc-speaker") {
                val block = ShortArray(BLOCK)
                while (running) {
                    val n = factory.pullAudio(block)
                    if (n < BLOCK) block.fill(0, n.coerceAtLeast(0), BLOCK) // underrun: play silence
                    t.write(block, 0, BLOCK) // blocks: paces the loop in real time
                }
            }
        }
    }

    override fun close() {
        running = false
        threads.forEach { it.join(500) }
        threads.clear()
        record?.run { stop(); release() }
        track?.run { stop(); release() }
        effects.forEach { it.release() }
        effects.clear()
        record = null
        track = null
    }

    private companion object {
        const val RATE = 48_000
        const val BLOCK = RATE / 100 // 10 ms
    }
}
