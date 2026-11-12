package krtc.meet

import platform.AudioToolbox.AudioServicesPlaySystemSound

/** System sounds: no audio session of our own to set up next to the call's. */
actual fun playCue(cue: Cue) {
    val id = when (cue) {
        Cue.JOIN, Cue.UNDEAFEN, Cue.UNMUTE -> 1113u // begin
        Cue.LEAVE, Cue.DEAFEN, Cue.MUTE -> 1114u // end
    }
    AudioServicesPlaySystemSound(id)
}
