#pragma once

#include <algorithm>
#include <cmath>
#include <memory>

#include "api/audio/audio_frame.h"
#include "common_audio/resampler/include/push_resampler.h"
#include "rnnoise.h"

namespace krtc {
using webrtc::AudioFrame;

/**
 * RNNoise needs 48 kHz, but AudioTransport chooses the lower of the capture and codec rates: even with Opus a
 * Bluetooth microphone can deliver 16 kHz. Resample each 10 ms frame to the model's rate and back, preserving the
 * original format. Each channel has its own model state; device format changes start a clean pipeline.
 */
class NeuralDenoiser {
public:
    ~NeuralDenoiser() { Reset(); }

    void Process(AudioFrame &frame) {
        const size_t samples = frame.samples_per_channel();
        const size_t channels = frame.num_channels();
        const int rate = frame.sample_rate_hz();
        if (rate < 8000 || rate > 48000 || samples * 100 != static_cast<size_t>(rate) || channels == 0 || channels > kMaxChannels) return;
        if (rate != rate_ || channels != channels_) {
            Reset();
            rate_ = rate;
            channels_ = channels;
            if (rate != 48000) {
                up_ = std::make_unique<webrtc::PushResampler<int16_t>>(samples, kFrame, channels);
                down_ = std::make_unique<webrtc::PushResampler<int16_t>>(kFrame, samples, channels);
            }
        }
        int16_t *data = frame.mutable_data();
        int16_t *processing = data;
        if (up_) {
            up_->Resample(webrtc::InterleavedView<const int16_t>(data, samples, channels),
                          webrtc::InterleavedView<int16_t>(resampled_, kFrame, channels));
            processing = resampled_;
        }
        for (size_t c = 0; c < channels; ++c) {
            if (!states_[c]) states_[c] = rnnoise_create(nullptr);
            if (!states_[c]) return;
            for (size_t i = 0; i < kFrame; ++i) in_[i] = processing[i * channels + c]; // RNNoise works on 16-bit scale floats
            rnnoise_process_frame(states_[c], out_, in_);
            for (size_t i = 0; i < kFrame; ++i) processing[i * channels + c] = static_cast<int16_t>(std::lrint(std::clamp(out_[i], -32768.0f, 32767.0f)));
        }
        if (down_) {
            down_->Resample(webrtc::InterleavedView<const int16_t>(resampled_, kFrame, channels),
                            webrtc::InterleavedView<int16_t>(data, samples, channels));
        }
    }

    /** Forgets the state (and frees it), so turning it on again starts clean. */
    void Reset() {
        for (DenoiseState *&st : states_) {
            if (st) rnnoise_destroy(st);
            st = nullptr;
        }
        up_.reset();
        down_.reset();
        rate_ = 0;
        channels_ = 0;
    }

private:
    static constexpr size_t kMaxChannels = 2;
    static constexpr size_t kFrame = 480;
    DenoiseState *states_[kMaxChannels] = {};
    int rate_ = 0;
    size_t channels_ = 0;
    std::unique_ptr<webrtc::PushResampler<int16_t>> up_, down_;
    int16_t resampled_[kFrame * kMaxChannels];
    float in_[kFrame];
    float out_[kFrame];
};

}  // namespace krtc
