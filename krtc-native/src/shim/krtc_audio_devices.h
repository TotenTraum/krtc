#pragma once

#include <cstdint>
#include <string>

namespace krtc {

struct AudioDevicePreference {
    std::string name, id;
};

template <typename Device>
AudioDevicePreference ReadAudioDevice(Device *adm, bool output, int index) {
    // AudioDeviceModule's kAdmMaxDeviceNameSize and kAdmMaxGuidSize are both 128.
    char name[128] = {}, id[128] = {};
    const int result = output ? adm->PlayoutDeviceName(index, name, id) : adm->RecordingDeviceName(index, name, id);
    return result == 0 ? AudioDevicePreference{name, id} : AudioDevicePreference{};
}

// Runs on the ADM's worker thread. Kept generic so restart decisions can be tested without audio hardware.
template <typename Device>
int SelectAudioDevice(Device *adm, bool output, int index, int confirmed_index = -1) {
    if (index < 0) return -1;
    if (!adm->Initialized() && adm->Init() != 0) return -1;
    const int count = output ? adm->PlayoutDevices() : adm->RecordingDevices();
    if (index >= count) return -1;
    const int reported = output ? adm->GetPlayoutDevice() : adm->GetRecordingDevice();
    const int current = reported >= 0 ? reported : confirmed_index;
    const bool initialized = output ? adm->PlayoutIsInitialized() : adm->RecordingIsInitialized();
    // Some ADMs cannot report the OS default's index. An initialized stream with only one device is already
    // using that device. Stopping/reopening it merely to select it again can break the platform audio backend.
    if (initialized && (current == index || (current < 0 && count == 1))) return 0;

    const bool recording = !output && adm->Recording();
    const bool playing = adm->Playing();
    if (recording && adm->StopRecording() != 0) return -1;
    if (playing && adm->StopPlayout() != 0) {
        if (recording && adm->InitRecording() == 0) adm->StartRecording();
        return -1;
    }
    int result = output ? adm->SetPlayoutDevice(static_cast<uint16_t>(index))
                        : adm->SetRecordingDevice(static_cast<uint16_t>(index));
    // Restart both sides after a microphone change: Bluetooth can change the output format with its profile.
    // Restore the running streams even if selecting the new device failed, and report restart failures too.
    if (recording) {
        const int init = adm->InitRecording();
        const int start = init == 0 ? adm->StartRecording() : init;
        if (result == 0) result = start;
    }
    if (playing) {
        const int init = adm->InitPlayout();
        const int start = init == 0 ? adm->StartPlayout() : init;
        if (result == 0) result = start;
    }
    return result;
}

// Device indexes can change when Bluetooth connects or switches profiles. Re-resolve a saved identity after
// the voice engine is initialized; its initial default device must not replace the user's explicit choice.
template <typename Device>
int FindAudioDevice(Device *adm, bool output, const AudioDevicePreference &preferred) {
    if (preferred.name.empty() && preferred.id.empty()) return -1;
    const int count = output ? adm->PlayoutDevices() : adm->RecordingDevices();
    for (int i = 0; i < count; ++i) {
        const auto device = ReadAudioDevice(adm, output, i);
        if (!preferred.id.empty() ? preferred.id == device.id : preferred.name == device.name)
            return i;
    }
    return -1;
}

template <typename Device>
int RestoreAudioDevice(Device *adm, bool output, const AudioDevicePreference &preferred) {
    if (preferred.name.empty() && preferred.id.empty()) return 0;
    const int index = FindAudioDevice(adm, output, preferred);
    return index < 0 ? -1 : SelectAudioDevice(adm, output, index);
}

}  // namespace krtc
