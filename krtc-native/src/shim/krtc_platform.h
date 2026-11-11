// Platform pieces of the krtc shim (internal header).
#pragma once
#include <functional>
#include <memory>
#include <string>
#include <vector>
#include <cstdint>

#include <mutex>

#include "api/environment/environment.h"
#include "api/media_stream_interface.h"
#include "api/notifier.h"
#include "api/video/video_frame.h"
#include "api/video_codecs/video_decoder_factory.h"
#include "api/video_codecs/video_encoder_factory.h"
#include "media/base/adapted_video_track_source.h"
#include "rtc_base/thread.h"

namespace krtc {

/** A video source the application or a capturer pushes frames into. Thread-safe. */
class PushVideoSource : public webrtc::AdaptedVideoTrackSource {
public:
    /** [screencast]: screen content, so encoders keep resolution and sharpness over frame rate. */
    explicit PushVideoSource(bool screencast = false) : screencast_(screencast) {}
    void Push(const webrtc::VideoFrame &frame) { OnFrame(frame); }
    SourceState state() const override { return kLive; }
    bool remote() const override { return false; }
    bool is_screencast() const override { return screencast_; }
    std::optional<bool> needs_denoising() const override { return false; }

private:
    const bool screencast_;
};

/**
 * An audio source fed with PCM by the application or a capture (screen sound), independent of the factory's audio
 * device. Any rate and channel count; delivered to the sending tracks in 10 ms frames. Thread-safe.
 * LiveKit's libwebrtc keeps device audio out of a send stream with an external source only if the source is attached
 * before the stream starts; a source attached to a running stream gets device audio mixed in (ADR-0017).
 */
class PushAudioSource : public webrtc::Notifier<webrtc::AudioSourceInterface> {
public:
    SourceState state() const override { return kLive; }
    bool remote() const override { return false; }
    bool is_external_source() const override { return true; }
    void AddSink(webrtc::AudioTrackSinkInterface *sink) override;
    void RemoveSink(webrtc::AudioTrackSinkInterface *sink) override;
    /** 16-bit interleaved samples, [frames] per channel. */
    void Push(const int16_t *samples, size_t frames, int channels, int sampleRate);

private:
    std::mutex lock_;
    std::vector<webrtc::AudioTrackSinkInterface *> sinks_;
    std::vector<int16_t> pending_;
    int channels_ = 0;
    int rate_ = 0;
};

/**
 * Captures the system's sound (what plays on the default output) into [source], for screen sharing. macOS:
 * ScreenCaptureKit (needs Screen Recording permission, macOS 13+); Linux: the PulseAudio/PipeWire monitor of the
 * default sink; Windows: WASAPI loopback; iOS: the app's own sound while the ReplayKit screen capture runs.
 * Returns a stop function, or nullptr if unavailable (Android: the app captures with AudioPlaybackCapture).
 */
std::function<void()> StartSystemAudio(PushAudioSource *source);

/** Converts float samples (-1..1) to 16-bit and pushes them. */
void PushFloat(PushAudioSource *source, const float *samples, size_t frames, int channels, int sampleRate);

// Hardware-accelerated factories where the platform has them (Apple: VideoToolbox H.264/H.265 + libvpx/AV1);
// nullptr where none is built in, and the shim falls back to software VP8.
std::unique_ptr<webrtc::VideoEncoderFactory> PlatformVideoEncoderFactory();
std::unique_ptr<webrtc::VideoDecoderFactory> PlatformVideoDecoderFactory();

// Cameras: Apple (AVFoundation), Linux (V4L2), Windows (DirectShow). Elsewhere (Android: the app captures with Camera2) none.
int CameraCount();
std::string CameraName(int index);

/**
 * Starts capturing camera [index] at about width × height and fps into [source]. Returns a function that
 * stops the capture, or nullptr if the camera is unavailable.
 */
std::function<void()> StartCamera(int index, int width, int height, int fps, PushVideoSource *source);

/** Something that can be captured as a screen share: a display (window = false) or a window. */
struct ScreenSource {
    int64_t id;
    bool window;
    std::string title;
};

/**
 * Screen capture built into the shim. macOS: displays and windows (libwebrtc DesktopCapturer on ScreenCaptureKit;
 * asks for the Screen Recording permission). Linux: the X11 display (XGetImage; no Wayland). iOS: this app's own
 * screen (ReplayKit in-app capture, one source). Android: none (the app uses MediaProjection and pushes frames).
 */
std::vector<ScreenSource> ScreenSources();

/**
 * Starts capturing [id] (a display, or a window if [window]) at up to [fps] into [source], scaled down to at most
 * [maxWidth] × [maxHeight]. Returns a function that stops it, or nullptr if capture is unavailable or not permitted.
 */
std::function<void()> StartScreen(int64_t id, bool window, int fps, int maxWidth, int maxHeight, PushVideoSource *source);

/** Pushes a 32-bit frame with libyuv byte order [argb] = B,G,R,A in memory, scaled to fit the limits. */
void PushBgra(PushVideoSource *source, const uint8_t *bgra, int stride, int width, int height, int maxWidth, int maxHeight, int64_t timestampUs);

}  // namespace krtc
