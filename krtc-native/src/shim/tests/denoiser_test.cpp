#include "krtc_denoiser.h"

#include <cstdio>
#include <cstdlib>
#include <random>

// Exercises the actual processor at the native AudioTransport capture rates, including Bluetooth's 16 kHz.
int main() {
    krtc::NeuralDenoiser denoiser;
    // Reuse the same processor across format changes and toggles, as with a live device switch.
    for (bool reset : {false, true}) {
        if (reset) denoiser.Reset();
        for (int rate : {48000, 16000, 32000, 8000, 48000}) {
            for (size_t channels : {size_t{1}, size_t{2}}) {
                std::mt19937 random(1);
                std::normal_distribution<float> noise(0, 3000);
                double before = 0, after = 0;
                webrtc::AudioFrame frame;
                frame.SetSampleRateAndChannelSize(rate);
                frame.num_channels_ = channels;
                const size_t count = frame.samples_per_channel() * channels;
                for (int n = 0; n < 400; ++n) {
                    auto *data = frame.mutable_data();
                    for (size_t i = 0; i < count; ++i) {
                        // Noise in the first channel; the second must stay silent.
                        data[i] = i % channels == 0 ? static_cast<int16_t>(noise(random)) : 0;
                        if (n >= 200) before += double(data[i]) * data[i];
                    }
                    denoiser.Process(frame);
                    if (frame.sample_rate_hz() != rate || frame.samples_per_channel() != size_t(rate / 100) || frame.num_channels() != channels) {
                        std::fputs("processor changed the frame format\n", stderr);
                        return EXIT_FAILURE;
                    }
                    for (size_t i = 0; i < count; ++i) {
                        if (n >= 200) after += double(data[i]) * data[i];
                        if (i % channels != 0 && data[i] != 0) {
                            std::fputs("audio leaked between channels\n", stderr);
                            return EXIT_FAILURE;
                        }
                    }
                }
                const double ratio = std::sqrt(after / before);
                std::printf("%d Hz, %zu channels, reset=%d: noise RMS ratio %.6f\n", rate, channels, reset, ratio);
                if (ratio >= 0.2) return EXIT_FAILURE;
            }
        }
    }
}
