// Synthetic audio device for the krtc shim (internal header).
#pragma once
#include "api/audio/audio_device.h"
#include "api/scoped_refptr.h"
#include <cstdint>

namespace krtc {

class SyntheticAudioDevice;

// 48 kHz mono 16-bit, 10 ms frames. Recording sends PCM pushed by the application, else a test tone
// (if enabled) or silence. Playout pulls the decoded mix every 10 ms, so remote audio is decoded even
// without a speaker; the application can read it back.
webrtc::scoped_refptr<webrtc::AudioDeviceModule> CreateSyntheticAudioDevice();
void SetTestTone(webrtc::AudioDeviceModule *adm, bool enabled);
int PushAudio(webrtc::AudioDeviceModule *adm, const int16_t *samples, int count);
int PullAudio(webrtc::AudioDeviceModule *adm, int16_t *out, int count);
double OutputLevel(webrtc::AudioDeviceModule *adm);

}  // namespace krtc
