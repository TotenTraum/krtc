// Synthetic AudioDeviceModule: a 10 ms clock driving libwebrtc's AudioTransport without sound hardware.
#include "krtc_audio.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <deque>
#include <mutex>
#include <thread>

#include "api/make_ref_counted.h"

using namespace webrtc;

namespace krtc {

namespace {
constexpr int kRate = 48000;
constexpr int kFrame = kRate / 100;  // 10 ms
constexpr size_t kMaxBuffered = kRate;  // 1 s
}  // namespace

class SyntheticAudioDevice : public AudioDeviceModule {
public:
    ~SyntheticAudioDevice() override { StopThread(); }

    void SetTone(bool on) { tone_ = on; }

    int Push(const int16_t *samples, int count) {
        std::lock_guard<std::mutex> lock(mu_);
        int accepted = 0;
        for (int i = 0; i < count && captured_.size() < kMaxBuffered; ++i, ++accepted) captured_.push_back(samples[i]);
        return accepted;
    }

    int Pull(int16_t *out, int count) {
        std::lock_guard<std::mutex> lock(mu_);
        int n = 0;
        for (; n < count && !played_.empty(); ++n) {
            out[n] = played_.front();
            played_.pop_front();
        }
        return n;
    }

    double Level() const { return level_.load(); }

    // --- AudioDeviceModule ---
    int32_t ActiveAudioLayer(AudioLayer *layer) const override { *layer = kDummyAudio; return 0; }
    int32_t RegisterAudioCallback(AudioTransport *cb) override { transport_ = cb; return 0; }
    int32_t Init() override { initialized_ = true; return 0; }
    int32_t Terminate() override { StopThread(); initialized_ = false; return 0; }
    bool Initialized() const override { return initialized_; }
    int16_t PlayoutDevices() override { return 1; }
    int16_t RecordingDevices() override { return 1; }
    int32_t PlayoutDeviceName(uint16_t, char name[kAdmMaxDeviceNameSize], char guid[kAdmMaxGuidSize]) override { return Name(name, guid); }
    int32_t RecordingDeviceName(uint16_t, char name[kAdmMaxDeviceNameSize], char guid[kAdmMaxGuidSize]) override { return Name(name, guid); }
    int32_t SetPlayoutDevice(uint16_t) override { return 0; }
    int32_t SetPlayoutDevice(WindowsDeviceType) override { return 0; }
    int32_t SetRecordingDevice(uint16_t) override { return 0; }
    int32_t SetRecordingDevice(WindowsDeviceType) override { return 0; }
    int32_t PlayoutIsAvailable(bool *a) override { *a = true; return 0; }
    int32_t InitPlayout() override { playout_init_ = true; return 0; }
    bool PlayoutIsInitialized() const override { return playout_init_; }
    int32_t RecordingIsAvailable(bool *a) override { *a = true; return 0; }
    int32_t InitRecording() override { recording_init_ = true; return 0; }
    bool RecordingIsInitialized() const override { return recording_init_; }
    int32_t StartPlayout() override { playing_ = true; StartThread(); return 0; }
    int32_t StopPlayout() override { playing_ = false; return 0; }
    bool Playing() const override { return playing_; }
    int32_t StartRecording() override { recording_ = true; StartThread(); return 0; }
    int32_t StopRecording() override { recording_ = false; return 0; }
    bool Recording() const override { return recording_; }
    int32_t InitSpeaker() override { return 0; }
    bool SpeakerIsInitialized() const override { return true; }
    int32_t InitMicrophone() override { return 0; }
    bool MicrophoneIsInitialized() const override { return true; }
    int32_t SpeakerVolumeIsAvailable(bool *a) override { *a = false; return 0; }
    int32_t SetSpeakerVolume(uint32_t) override { return -1; }
    int32_t SpeakerVolume(uint32_t *) const override { return -1; }
    int32_t MaxSpeakerVolume(uint32_t *) const override { return -1; }
    int32_t MinSpeakerVolume(uint32_t *) const override { return -1; }
    int32_t MicrophoneVolumeIsAvailable(bool *a) override { *a = false; return 0; }
    int32_t SetMicrophoneVolume(uint32_t) override { return -1; }
    int32_t MicrophoneVolume(uint32_t *) const override { return -1; }
    int32_t MaxMicrophoneVolume(uint32_t *) const override { return -1; }
    int32_t MinMicrophoneVolume(uint32_t *) const override { return -1; }
    int32_t SpeakerMuteIsAvailable(bool *a) override { *a = false; return 0; }
    int32_t SetSpeakerMute(bool) override { return -1; }
    int32_t SpeakerMute(bool *) const override { return -1; }
    int32_t MicrophoneMuteIsAvailable(bool *a) override { *a = false; return 0; }
    int32_t SetMicrophoneMute(bool) override { return -1; }
    int32_t MicrophoneMute(bool *) const override { return -1; }
#if defined(WEBRTC_IOS)
    // Pure virtual only on iOS; libwebrtc asks for them when it configures its audio session.
    int GetPlayoutAudioParameters(AudioParameters *params) const override { *params = AudioParameters(kRate, 1, kRate / 100); return 0; }
    int GetRecordAudioParameters(AudioParameters *params) const override { *params = AudioParameters(kRate, 1, kRate / 100); return 0; }
#endif
    int32_t StereoPlayoutIsAvailable(bool *a) const override { *a = false; return 0; }
    int32_t SetStereoPlayout(bool enable) override { return enable ? -1 : 0; }
    int32_t StereoPlayout(bool *e) const override { *e = false; return 0; }
    int32_t StereoRecordingIsAvailable(bool *a) const override { *a = false; return 0; }
    int32_t SetStereoRecording(bool enable) override { return enable ? -1 : 0; }
    int32_t StereoRecording(bool *e) const override { *e = false; return 0; }
    int32_t PlayoutDelay(uint16_t *d) const override { *d = 0; return 0; }
    bool BuiltInAECIsAvailable() const override { return false; }
    bool BuiltInAGCIsAvailable() const override { return false; }
    bool BuiltInNSIsAvailable() const override { return false; }
    int32_t EnableBuiltInAEC(bool) override { return -1; }
    int32_t EnableBuiltInAGC(bool) override { return -1; }
    int32_t EnableBuiltInNS(bool) override { return -1; }

private:
    static int32_t Name(char name[kAdmMaxDeviceNameSize], char guid[kAdmMaxGuidSize]) {
        std::snprintf(name, kAdmMaxDeviceNameSize, "krtc synthetic audio");
        if (guid) std::snprintf(guid, kAdmMaxGuidSize, "krtc-synthetic");
        return 0;
    }

    void StartThread() {
        bool expected = false;
        if (!running_.compare_exchange_strong(expected, true)) return;
        thread_ = std::thread([this] { Loop(); });
    }

    void StopThread() {
        running_ = false;
        if (thread_.joinable()) thread_.join();
    }

    void Loop() {
        int16_t frame[kFrame];
        int16_t out[kFrame];
        auto next = std::chrono::steady_clock::now();
        int64_t sample = 0;
        while (running_) {
            next += std::chrono::milliseconds(10);
            AudioTransport *t = transport_;
            if (t && recording_) {
                bool filled = false;
                {
                    std::lock_guard<std::mutex> lock(mu_);
                    if (captured_.size() >= static_cast<size_t>(kFrame)) {
                        for (int i = 0; i < kFrame; ++i) { frame[i] = captured_.front(); captured_.pop_front(); }
                        filled = true;
                    }
                }
                if (!filled) {
                    // Test tone: 440 Hz, half a second on, half off (so speaking detection toggles).
                    for (int i = 0; i < kFrame; ++i, ++sample) {
                        const bool on = tone_ && (sample / (kRate / 2)) % 2 == 0;
                        frame[i] = on ? static_cast<int16_t>(6000 * std::sin(2 * M_PI * 440 * sample / kRate)) : 0;
                    }
                }
                uint32_t mic_level = 0;
                t->RecordedDataIsAvailable(frame, kFrame, 2, 1, kRate, 0, 0, 0, false, mic_level);
            }
            if (t && playing_) {
                size_t got = 0;
                int64_t elapsed = 0, ntp = 0;
                if (t->NeedMorePlayData(kFrame, 2, 1, kRate, out, got, &elapsed, &ntp) == 0) {
                    double sum = 0;
                    for (size_t i = 0; i < got; ++i) sum += static_cast<double>(out[i]) * out[i];
                    level_ = got ? std::sqrt(sum / got) / 32768.0 : 0.0;
                    std::lock_guard<std::mutex> lock(mu_);
                    for (size_t i = 0; i < got; ++i) played_.push_back(out[i]);
                    while (played_.size() > kMaxBuffered) played_.pop_front();
                }
            }
            std::this_thread::sleep_until(next);
        }
    }

    AudioTransport *transport_ = nullptr;
    std::atomic<bool> initialized_{false}, playout_init_{false}, recording_init_{false};
    std::atomic<bool> playing_{false}, recording_{false}, running_{false}, tone_{false};
    std::atomic<double> level_{0};
    std::mutex mu_;
    std::deque<int16_t> captured_, played_;
    std::thread thread_;
};

scoped_refptr<AudioDeviceModule> CreateSyntheticAudioDevice() { return make_ref_counted<SyntheticAudioDevice>(); }
void SetTestTone(AudioDeviceModule *adm, bool enabled) { static_cast<SyntheticAudioDevice *>(adm)->SetTone(enabled); }
int PushAudio(AudioDeviceModule *adm, const int16_t *s, int n) { return static_cast<SyntheticAudioDevice *>(adm)->Push(s, n); }
int PullAudio(AudioDeviceModule *adm, int16_t *o, int n) { return static_cast<SyntheticAudioDevice *>(adm)->Pull(o, n); }
double OutputLevel(AudioDeviceModule *adm) { return static_cast<SyntheticAudioDevice *>(adm)->Level(); }

}  // namespace krtc
