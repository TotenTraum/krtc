package krtc.meet

import krtc.core.RTCConfiguration
import krtc.native.AudioDevice
import krtc.native.NativePeerConnectionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in device routing probe: enumerates/configures devices without opening a microphone or playing audio. */
class AudioDeviceProbeTest {
    @Test
    fun microphoneChoiceSurvivesFirstConnection() {
        if (System.getenv("KRTC_DEVICE_PROBE") != "1") return
        NativePeerConnectionFactory(AudioDevice.PLATFORM, platformVideoCodecs = false).use { factory ->
            val names = factory.audioInputs
            assertTrue(names.isNotEmpty(), "at least one microphone is required for this probe")
            val index = names.indexOfFirst { !it.startsWith("default", ignoreCase = true) && it.contains("MacBook", ignoreCase = true) }
                .takeIf { it >= 0 } ?: names.lastIndex
            assertTrue(factory.setAudioInput(index))
            assertEquals(index, factory.audioInputIndex)
            val connection = factory.createPeerConnection(RTCConfiguration())
            try {
                val current = factory.audioInputIndex
                assertEquals(names[index], current?.let { factory.audioInputs.getOrNull(it) })
                assertTrue(factory.setAudioInput(current!!))
                assertEquals(current, factory.audioInputIndex)
                println("microphone before/after first connection: ${names[index]}")
            } finally {
                connection.close()
            }
        }
    }
}
