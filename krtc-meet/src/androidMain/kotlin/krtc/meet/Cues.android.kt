package krtc.meet

import android.media.AudioManager
import android.media.ToneGenerator

private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50) }.getOrNull() }

actual fun playCue(cue: Cue) {
    val tone = when (cue) {
        Cue.JOIN, Cue.UNDEAFEN -> ToneGenerator.TONE_PROP_ACK
        Cue.LEAVE, Cue.DEAFEN -> ToneGenerator.TONE_PROP_NACK
        Cue.UNMUTE -> ToneGenerator.TONE_PROP_BEEP
        Cue.MUTE -> ToneGenerator.TONE_PROP_BEEP2
    }
    tones?.startTone(tone, 120)
}
