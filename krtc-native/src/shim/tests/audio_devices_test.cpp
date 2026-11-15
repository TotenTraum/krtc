#include "krtc_audio_devices.h"

#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <array>
#include <utility>

#define CHECK(condition) do { if (!(condition)) { std::fprintf(stderr, "failed at line %d: %s\n", __LINE__, #condition); std::exit(1); } } while (0)

struct Device {
    bool initialized = true, recording = true, playing = true, input_initialized = true, output_initialized = true;
    int inputs = 2, outputs = 2, input = 0, output = 0;
    int recording_stops = 0, playout_stops = 0, input_sets = 0, output_sets = 0;
    int select_result = 0, recording_start_result = 0;
    std::array<std::string, 2> input_names{"Redmi Buds 5", "MacBook microphone"};
    int RecordingDeviceName(int index, char *name, char *id) {
        if (index < 0 || index >= inputs) return -1;
        std::snprintf(name, 128, "%s", input_names[index].c_str());
        std::snprintf(id, 128, "id:%s", input_names[index].c_str());
        return 0;
    }
    int PlayoutDeviceName(int index, char *name, char *id) { return RecordingDeviceName(index, name, id); }
    bool Initialized() { return initialized; }
    int Init() { initialized = true; return 0; }
    int RecordingDevices() { return inputs; }
    int PlayoutDevices() { return outputs; }
    int GetRecordingDevice() { return input; }
    int GetPlayoutDevice() { return output; }
    bool RecordingIsInitialized() { return input_initialized; }
    bool PlayoutIsInitialized() { return output_initialized; }
    bool Recording() { return recording; }
    bool Playing() { return playing; }
    int StopRecording() { ++recording_stops; recording = false; input_initialized = false; return 0; }
    int StopPlayout() { ++playout_stops; playing = false; output_initialized = false; return 0; }
    int SetRecordingDevice(uint16_t index) { CHECK(!recording); ++input_sets; if (select_result == 0) input = index; return select_result; }
    int SetPlayoutDevice(uint16_t index) { CHECK(!playing); ++output_sets; if (select_result == 0) output = index; return select_result; }
    int InitRecording() { input_initialized = true; return 0; }
    int InitPlayout() { output_initialized = true; return 0; }
    int StartRecording() { recording = recording_start_result == 0; return recording_start_result; }
    int StartPlayout() { playing = true; return 0; }
};

int main() {
    Device same;
    for (int n = 0; n < 20; ++n) {
        CHECK(krtc::SelectAudioDevice(&same, false, 0) == 0);
        CHECK(krtc::SelectAudioDevice(&same, true, 0) == 0);
    }
    CHECK(same.recording_stops == 0 && same.playout_stops == 0 && same.input_sets == 0 && same.output_sets == 0);

    Device windows;
    windows.input = -1; // legacy Windows ADM has no current-index getter, even with multiple microphones
    CHECK(krtc::SelectAudioDevice(&windows, false, 1, 1) == 0); // last confirmed identity resolves to index 1
    CHECK(windows.recording_stops == 0 && windows.input_sets == 0);

    // A single active microphone/speaker, selected implicitly by the OS. No index is available from the ADM.
    Device single;
    single.inputs = single.outputs = 1;
    single.input = single.output = -1;
    CHECK(krtc::SelectAudioDevice(&single, false, 0) == 0);
    CHECK(krtc::SelectAudioDevice(&single, true, 0) == 0);
    CHECK(single.recording_stops == 0 && single.playout_stops == 0 && single.input_sets == 0 && single.output_sets == 0);

    // Before audio is initialized, the first explicit choice must still reach the backend.
    single.recording = single.playing = single.input_initialized = single.output_initialized = false;
    CHECK(krtc::SelectAudioDevice(&single, false, 0) == 0);
    CHECK(single.input_sets == 1 && !single.recording && !single.playing);
    single.input_initialized = false;
    CHECK(krtc::SelectAudioDevice(&single, false, 0) == 0);
    CHECK(single.input_sets == 2); // a cached index before stream initialization is not proof of active routing

    Device different;
    CHECK(krtc::SelectAudioDevice(&different, false, 1) == 0);
    CHECK(different.input == 1 && different.recording && different.playing);
    CHECK(different.recording_stops == 1 && different.playout_stops == 1);
    CHECK(krtc::SelectAudioDevice(&different, false, 1) == 0);
    CHECK(different.input_sets == 1 && different.recording_stops == 1);
    CHECK(krtc::SelectAudioDevice(&different, true, 1) == 0);
    CHECK(different.output == 1 && different.recording && different.playing && different.recording_stops == 1);

    Device invalid;
    for (int index : {-1, 2, 65536}) CHECK(krtc::SelectAudioDevice(&invalid, false, index) != 0);
    CHECK(invalid.recording_stops == 0 && invalid.playout_stops == 0 && invalid.input_sets == 0);

    Device failed;
    failed.select_result = -7;
    CHECK(krtc::SelectAudioDevice(&failed, false, 1) == -7);
    CHECK(failed.recording && failed.playing && failed.input == 0);
    failed.select_result = 0;
    failed.recording_start_result = -9;
    CHECK(krtc::SelectAudioDevice(&failed, false, 1) == -9);
    CHECK(failed.playing && !failed.recording);

    // Restoring the UI choice before a call is insufficient if voice-engine initialization selects the default.
    Device startup;
    const auto preferred = krtc::ReadAudioDevice(&startup, false, 1);
    CHECK(krtc::SelectAudioDevice(&startup, false, 1) == 0);
    startup.input = 0; // voice engine chose Redmi Buds as the default during its initialization
    CHECK(krtc::RestoreAudioDevice(&startup, false, preferred) == 0);
    CHECK(startup.input == 1 && startup.recording && startup.playing);
    // Bluetooth changes device order. Restore by identity, not the old numeric index.
    std::swap(startup.input_names[0], startup.input_names[1]);
    CHECK(krtc::RestoreAudioDevice(&startup, false, preferred) == 0);
    CHECK(startup.input == 0 && startup.input_names[startup.input] == "MacBook microphone");
    const int sets = startup.input_sets;
    CHECK(krtc::RestoreAudioDevice(&startup, false, preferred) == 0);
    CHECK(startup.input_sets == sets); // still avoid reopening the actual current device
    CHECK(krtc::RestoreAudioDevice(&startup, false, {"disconnected microphone", "missing-id"}) != 0);
    CHECK(startup.input_sets == sets);
    std::puts("audio device selection: all checks passed");
}
